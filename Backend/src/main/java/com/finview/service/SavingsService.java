package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.common.CashAsset;
import com.finview.common.Xirr;
import com.finview.controller.dto.SavingsAssetResponse;
import com.finview.controller.dto.SavingsInflowForecastResponse;
import com.finview.controller.dto.SavingsMonthPointResponse;
import com.finview.controller.dto.SavingsPlanCandidateResponse;
import com.finview.controller.dto.SavingsPlanRequest;
import com.finview.controller.dto.SavingsPlanResponse;
import com.finview.controller.dto.SavingsPlanTypeResponse;
import com.finview.controller.dto.SavingsReturnResponse;
import com.finview.entity.Asset;
import com.finview.entity.AssetPlanLink;
import com.finview.entity.AssetSeries;
import com.finview.entity.SavingPlan;
import com.finview.entity.SavingPlanType;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import com.finview.mapper.PlanAssetMapper;
import com.finview.mapper.SavingPlanMapper;
import com.finview.mapper.SavingPlanTypeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 存钱计划业务：读计划 + 把关联资产组装成前端要的形状，以及新建计划、关联/解除资产。
 *
 * 「计划里有哪些资产」（{@link #assetsOf}）分两种口径：
 * - `type=asset`（总资产）：**该用户全部未归档资产**，不用手工关联（给非总资产的计划插
 *   plan_asset 关联会被 {@link #linkAsset} 拒掉）；
 * - `type=life`（生活保障）/ `type=wish`（愿望）：`plan_asset` 表里显式关联、且未归档的资产。
 * 两种口径都排除已归档 —— 归档是「不看了」的标记，看板/我的资产页/排行榜也都按未归档算。
 *
 * **计划的「当前已存金额」不落库**：它是上面那些资产的最新市值合计，读接口现算
 * （2026-10-05 起，saving_plans.current_amount 列已删）。资产金额取 asset_series 的最新市值，
 * 没有序列行时退回 asset.total。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsService {

    /** 计划内资产的配色轮转，取值与前端 --chart-N 变量一致（前端直接写 var()，缺了会渲染成透明） */
    private static final List<String> ASSET_COLORS =
            List.of("--chart-1", "--chart-3", "--chart-4", "--chart-5");

    /**
     * 内置类型（asset 总资产 / life 生活保障 / wish 愿望）：首次访问时按 (user_id, code) 幂等补种，
     * 响应里打 `builtin` 标记。**合法类型不再只有这三个** —— 2026-10-13 起用户可以自定义
     * （saving_plan_types 表，code = 'c' + 随机串），所以类型校验改成「必须在用户自己的类型表里」。
     * 行为差异只剩 asset：全账号唯一、必须带目标、默认全含资产、不接受手工关联。
     */
    private static final Set<String> BUILTIN_TYPES = Set.of("asset", "life", "wish");

    /** 总资产计划的类型值：它默认包含全部未归档资产，不接受手工关联 */
    private static final String TYPE_ASSET = "asset";

    private final SavingPlanMapper savingPlanMapper;
    private final AssetMapper assetMapper;
    private final AssetSeriesMapper assetSeriesMapper;
    private final PlanAssetMapper planAssetMapper;
    private final SavingPlanTypeMapper savingPlanTypeMapper;
    /** 计划详情的未来定投流入：复用定投分析那套 fold / 交易日历，别在这里重写一遍 */
    private final CashFlowService cashFlowService;

    /** 某用户的全部计划，按 id 升序 */
    public List<SavingsPlanResponse> list(Long userId) {
        // 类型名一次查好，别在 toResponse 里逐条查（N+1）
        Map<String, String> typeNames = typeNames(userId);
        return savingPlanMapper.findByUser(userId).stream()
                // 列表不填 dca / 预测：每个计划各 fold 一次没必要，列表也不展示这两块
                .map(plan -> toResponse(userId, plan, typeNames, false))
                .toList();
    }

    /* ==================== 计划类型（内置 + 自定义） ==================== */

    /** 某用户的类型列表：内置 asset/life/wish 幂等补种后按 sort_order 返回 */
    public List<SavingsPlanTypeResponse> listTypes(Long userId) {
        return ensureAndListTypes(userId).stream().map(this::toTypeResponse).toList();
    }

    /**
     * 新建自定义类型（用户在新建计划页里填的名字）。
     * code 服务端生成（'c' + 随机串），用户改的只是 name；重名直接 400。
     */
    @Transactional
    public SavingsPlanTypeResponse createType(Long userId, String name) {
        String trimmed = name == null ? "" : name.trim();
        requireTypeNameFree(userId, trimmed, null);

        SavingPlanType type = new SavingPlanType();
        type.setUserId(userId);
        type.setCode("c" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        type.setName(trimmed);
        // 自定义类型排在所有内置（0/1/2）之后，按创建顺序往后排
        type.setSortOrder(100 + (int) (savingPlanTypeMapper.findByUser(userId).stream()
                .filter(item -> !BUILTIN_TYPES.contains(item.getCode())).count()));
        savingPlanTypeMapper.insert(type);
        log.info("用户 {} 新建计划类型 [{}] {}", userId, type.getCode(), type.getName());
        return toTypeResponse(type);
    }

    /** 改类型名：只动 name —— 计划里存的是 code，所以改名不用碰任何计划 */
    @Transactional
    public SavingsPlanTypeResponse renameType(Long userId, String id, String name) {
        SavingPlanType type = requireType(userId, id);
        if (TYPE_ASSET.equals(type.getCode())) {
            throw BusinessException.badRequest("总资产类型不能改名");
        }
        String trimmed = name == null ? "" : name.trim();
        requireTypeNameFree(userId, trimmed, type.getId());
        savingPlanTypeMapper.updateName(type.getId(), userId, trimmed);
        type.setName(trimmed);
        log.info("用户 {} 把类型 [{}] 改名为 {}", userId, type.getCode(), trimmed);
        return toTypeResponse(type);
    }

    /** 删除类型：系统类型（asset）不能删；还有计划在用也不能删（提示先改掉那些计划） */
    @Transactional
    public void deleteType(Long userId, String id) {
        SavingPlanType type = requireType(userId, id);
        if (TYPE_ASSET.equals(type.getCode())) {
            throw BusinessException.badRequest("总资产类型不能删除");
        }
        long inUse = savingPlanMapper.findByUser(userId).stream()
                .filter(plan -> type.getCode().equals(plan.getType()))
                .count();
        if (inUse > 0) {
            throw BusinessException.badRequest("还有 " + inUse + " 个计划在用这个类型，先把它们改成别的类型再删");
        }
        savingPlanTypeMapper.deleteByIdAndUser(type.getId(), userId);
        log.info("用户 {} 删除计划类型 [{}]", userId, type.getCode());
    }

    /** 内置三种类型幂等补种后按顺序取全部 */
    private List<SavingPlanType> ensureAndListTypes(Long userId) {
        savingPlanTypeMapper.ensureBuiltins(userId);
        return savingPlanTypeMapper.findByUser(userId);
    }

    /** code → 类型名（计划响应里的 typeName 用它）；顺带补种内置类型 */
    private Map<String, String> typeNames(Long userId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (SavingPlanType type : ensureAndListTypes(userId)) {
            names.put(type.getCode(), type.getName());
        }
        return names;
    }

    /** 类型必须属于该用户，否则 404 */
    private SavingPlanType requireType(Long userId, String id) {
        SavingPlanType type = savingPlanTypeMapper.findByIdAndUser(parseId(id), userId);
        if (type == null) {
            throw BusinessException.notFound("计划类型不存在: " + id);
        }
        return type;
    }

    private void requireTypeNameFree(Long userId, String name, Long excludeId) {
        if (!StringUtils.hasText(name)) {
            throw BusinessException.badRequest("类型名称不能为空");
        }
        if (savingPlanTypeMapper.countByUserAndName(userId, name, excludeId) > 0) {
            throw BusinessException.badRequest("已有同名类型：" + name);
        }
    }

    private SavingsPlanTypeResponse toTypeResponse(SavingPlanType type) {
        return new SavingsPlanTypeResponse(
                String.valueOf(type.getId()), type.getCode(), type.getName(),
                BUILTIN_TYPES.contains(type.getCode()), TYPE_ASSET.equals(type.getCode()));
    }

    /** 计划详情，不存在或不属于当前用户抛 404 */
    public SavingsPlanResponse getById(Long userId, String id) {
        SavingPlan plan = savingPlanMapper.findByIdAndUser(parseId(id), userId);
        if (plan == null) {
            throw BusinessException.notFound("存钱计划不存在: " + id);
        }
        return toResponse(userId, plan);
    }

    /**
     * 新建计划。单字段校验（必填、长度、正数）已经在 SavingsPlanRequest 上用 @Valid 做过了，
     * 这里只做跨字段 / 跨行的规则，都用 400 + 中文提示返回给前端表单。
     *
     * 返回读接口同款的 SavingsPlanResponse（含刚回填的 id），前端拿它直接跳详情页。
     */
    @Transactional
    public SavingsPlanResponse create(Long userId, SavingsPlanRequest request) {
        // 类型必须是该用户自己的类型表里的 code（内置三种已幂等补种，自定义由用户先建）
        Map<String, String> typeNames = typeNames(userId);
        requireKnownType(typeNames.keySet(), request.getType());
        validateNewPlan(savingPlanMapper.findByUser(userId), request);

        // 没设目标时，目标日期与每月计划存入一律落 NULL（它们是给目标金额服务的，
        // 前端在「设定目标」关掉时本来也不会传，这里再兜一道）
        boolean hasTarget = request.getTargetAmount() != null;

        SavingPlan plan = new SavingPlan();
        plan.setUserId(userId);
        plan.setName(request.getName().trim());
        plan.setType(request.getType());
        plan.setDescription(trimToNull(request.getDescription()));
        plan.setImage(trimToNull(request.getImage()));
        plan.setTargetAmount(request.getTargetAmount());
        plan.setStartDate(request.getStartDate());
        // 目标日期选填：没填时按「每月存入」自适应推算，推出来的日子照常落库
        // （详情页 / 进度标签都读这一列，读接口不用再算一遍）
        plan.setTargetDate(hasTarget
                ? (request.getTargetDate() != null
                        ? request.getTargetDate()
                        : estimatedTargetDate(request.getStartDate(), request.getTargetAmount(),
                                request.getMonthlyPlanAmount()))
                : null);
        plan.setMonthlyPlanAmount(hasTarget ? request.getMonthlyPlanAmount() : null);
        plan.setQuote(trimToNull(request.getQuote()));
        plan.setNote(trimToNull(request.getNote()));
        plan.setNoteImage(trimToNull(request.getNoteImage()));

        savingPlanMapper.insert(plan);

        // 新建表单里直接勾的资产，同一个事务里写进关联表；总资产计划本来就全含，忽略该字段。
        // 资产校验沿用 requireAsset / 归档判断，与详情页那个「添加资产」完全一致。
        if (!TYPE_ASSET.equals(plan.getType()) && request.getAssetIds() != null) {
            for (String assetId : request.getAssetIds()) {
                Asset asset = requireAsset(userId, assetId);
                if (isArchived(asset)) {
                    throw BusinessException.badRequest("已归档的资产不计入计划：" + asset.getCode());
                }
                // 与详情页的「添加资产」同一条排他规则：一个资产只能属于一个分类
                requireAssetNotInOtherPlan(userId, plan, asset);
                planAssetMapper.insert(userId, plan.getId(), asset.getId());
            }
        }

        log.info("用户 {} 新建存钱计划 [{}] {}（目标 {}，关联资产 {}）", userId, plan.getId(), plan.getName(),
                hasTarget ? plan.getTargetAmount() : "无",
                request.getAssetIds() == null ? 0 : request.getAssetIds().size());
        return toResponse(userId, plan, typeNames, true);
    }

    /**
     * 给生活保障 / 愿望计划关联一个资产（总资产计划不用关联，直接 400）。
     *
     * 校验：计划属于该用户（否则 404，顺带挡住跨用户操作）、资产属于该用户（404）、
     * 资产未归档（400 —— 归档的资产不计入计划，关联了也不会变）。重复关联是幂等的
     * （plan_asset 的唯一键 + INSERT IGNORE）。
     */
    @Transactional
    public SavingsPlanResponse linkAsset(Long userId, String planId, String assetId) {
        SavingPlan plan = requirePlan(userId, planId);
        Asset asset = requireAsset(userId, assetId);
        if (TYPE_ASSET.equals(plan.getType())) {
            throw BusinessException.badRequest("总资产计划默认包含全部未归档资产，不需要手动关联");
        }
        if (isArchived(asset)) {
            throw BusinessException.badRequest("已归档的资产不计入计划，先取消归档再关联");
        }
        requireAssetNotInOtherPlan(userId, plan, asset);
        planAssetMapper.insert(userId, plan.getId(), asset.getId());
        log.info("用户 {} 把资产 {}（{}）关联到计划 [{}]", userId, asset.getCode(), asset.getName(),
                plan.getId());
        return toResponse(userId, plan);
    }

    /** 排他校验（见 requireAssetNotInOtherPlan）：查库后交给纯函数判 */
    private void requireAssetNotInOtherPlan(Long userId, SavingPlan plan, Asset asset) {
        requireAssetNotInOtherPlan(plan.getId(), assetName(asset),
                planAssetMapper.findLinkByAsset(userId, asset.getId()));
    }

    /**
     * 排他校验（2026-10-13 起）：一个资产只能进**一个**具体分类。
     * 「总资产」计划不走 plan_asset（默认全含），所以不受这条限制 —— 具体分类之间才互斥。
     * 做成包级静态是为了能单测（existing 由调用方查好传进来）。
     *
     * @param existing 该资产当前的归属（没有则 null，含"就是本计划"的情况）
     */
    static void requireAssetNotInOtherPlan(Long planId, String assetName, AssetPlanLink existing) {
        if (existing != null && !existing.getPlanId().equals(planId)) {
            throw BusinessException.badRequest(
                    "「" + assetName + "」已经在「" + existing.getPlanName() + "」里了，一个资产只能属于一个分类");
        }
    }

    /** 解除关联。本来就没关联也当成功（幂等），返回最新的计划详情。 */
    @Transactional
    public SavingsPlanResponse unlinkAsset(Long userId, String planId, String assetId) {
        SavingPlan plan = requirePlan(userId, planId);
        Asset asset = requireAsset(userId, assetId);
        planAssetMapper.delete(userId, plan.getId(), asset.getId());
        log.info("用户 {} 把资产 {}（{}）从计划 [{}] 解除关联", userId, asset.getCode(), asset.getName(),
                plan.getId());
        return toResponse(userId, plan);
    }

    /**
     * 「添加资产」面板的候选列表：该用户的全部资产（含现金、含已归档），
     * 每项带最新市值与是否已在本计划里。总资产计划下 linked 恒为 true（它本来就全含）。
     *
     * 不塞进详情响应：详情每次都要拉，候选只在点开面板时需要；
     * 也不能复用 /funds —— 现金被 foldForUser 过滤掉了，但它也是能挂进计划的资产。
     */
    public List<SavingsPlanCandidateResponse> candidates(Long userId, String planId) {
        return candidatesOf(userId, requirePlan(userId, planId));
    }

    /**
     * 新建表单用的候选列表（还没有计划，所以 linked 全是 false）。
     * 与 {@link #candidates} 同一份数据，只是没有「已关联」的概念。
     */
    public List<SavingsPlanCandidateResponse> candidatesForNew(Long userId) {
        return candidatesOf(userId, null);
    }

    /** plan 为 null = 新建表单的场景（不加 plan_asset 的关联标记） */
    private List<SavingsPlanCandidateResponse> candidatesOf(Long userId, SavingPlan plan) {
        List<Asset> assets = assetMapper.findByUser(userId);
        Map<String, BigDecimal> totals = latestTotals(userId, assets);
        boolean coversAll = plan != null && TYPE_ASSET.equals(plan.getType());
        // 资产 → 所属计划（一一对应）：既用来标「已在本计划」，也用来标「已在『别的计划』里」
        Map<Long, AssetPlanLink> links = new LinkedHashMap<>();
        for (AssetPlanLink link : planAssetMapper.findLinksByUser(userId)) {
            links.put(link.getAssetId(), link);
        }

        return assets.stream()
                .map(asset -> {
                    AssetPlanLink link = links.get(asset.getId());
                    boolean inThisPlan = plan != null && link != null && link.getPlanId().equals(plan.getId());
                    // 「已在别的分类里」：有归属、但不是本计划（新建表单没有本计划，任何归属都算别处）
                    String otherPlanName = link != null && !inThisPlan ? link.getPlanName() : null;
                    return new SavingsPlanCandidateResponse(
                            String.valueOf(asset.getId()),
                            asset.getCode(),
                            assetName(asset),
                            amountOf(asset, totals),
                            // 总资产计划默认全含；其余计划只有"恰好挂在本计划"才算已关联
                            coversAll || inThisPlan,
                            isArchived(asset),
                            otherPlanName);
                })
                .toList();
    }

    private SavingPlan requirePlan(Long userId, String planId) {
        SavingPlan plan = savingPlanMapper.findByIdAndUser(parseId(planId), userId);
        if (plan == null) {
            throw BusinessException.notFound("存钱计划不存在: " + planId);
        }
        return plan;
    }

    /** 资产要在该用户名下：资产表本来就按 (user_id, code) 隔离，按 id 取就在这份列表里找 */
    private Asset requireAsset(Long userId, String assetId) {
        long id;
        try {
            id = Long.parseLong(assetId.trim());
        } catch (RuntimeException ex) {
            throw BusinessException.notFound("资产不存在: " + assetId);
        }
        return assetMapper.findByUser(userId).stream()
                .filter(asset -> asset.getId() != null && asset.getId() == id)
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("资产不存在: " + assetId));
    }

    /**
     * 新建计划的跨字段校验。做成包级静态是为了能单测（本项目的测试都是纯 JUnit，
     * 没有 Mockito/MockMvc 的先例，见 AssetServiceTest）。
     *
     * 「type 必须是用户自己的类型表里的 code」这一条不在这里 —— 它要查库，
     * 由 {@link #create} 先查好类型表再调这里（用户能自定义类型，合法取值不再是固定三个）。
     *
     * 三条规则：
     * - 「总资产」计划**全用户只能有一条**：前端列表只把第一条 type=asset 渲染成横跨整行的大卡，
     *   多出来的在页面上根本看不见，与其让用户建一条隐形的计划不如直接拒掉；
     * - **设了目标金额就必须有目标日期与每月计划投入**（后者用来推算预计完成时间），
     *   且目标日期不能早于开始日期；没设目标则这三项一律忽略、落 NULL；
     * - **「总资产」计划必须设目标**：不设目标的话它就只是另一个「我的资产」页（用户 2026-10-05 定的）。
     */
    /**
     * 计划的类型必须是该用户类型表里的 code。抽成包级静态是为了单测（{@link #create} 查完类型表再调它）；
     * 用户能自定义类型，所以合法取值不再是固定的 asset/life/wish 三个。
     */
    static void requireKnownType(Set<String> knownCodes, String type) {
        if (type == null || !knownCodes.contains(type)) {
            throw BusinessException.badRequest("计划类型不存在，请先在类型里新建：" + type);
        }
    }

    static void validateNewPlan(List<SavingPlan> existing, SavingsPlanRequest request) {
        if (TYPE_ASSET.equals(request.getType())
                && existing.stream().anyMatch(plan -> TYPE_ASSET.equals(plan.getType()))) {
            throw BusinessException.badRequest("已有总资产计划，只能新建生活保障或愿望");
        }

        boolean hasTarget = request.getTargetAmount() != null;
        if (TYPE_ASSET.equals(request.getType()) && !hasTarget) {
            throw BusinessException.badRequest("总资产计划需要设定目标金额");
        }
        if (!hasTarget) {
            return;
        }
        if (request.getMonthlyPlanAmount() == null) {
            throw BusinessException.badRequest("设了目标金额就要填每月计划投入");
        }
        // 目标日期**选填**（2026-10-13 起）：没填时由 create() 按「每月存入」自适应推算
        // （见 estimatedTargetDate）；填了的话不能早于开始日期
        if (request.getTargetDate() != null && request.getTargetDate().isBefore(request.getStartDate())) {
            throw BusinessException.badRequest("目标日期不能早于开始日期");
        }
    }

    /**
     * 目标日期没填时的自适应推算：份数 = ⌈目标金额 ÷ 每月存入⌉，从开始日期往后推 N 个月。
     * 例：2026-10-05 起、目标 10000、每月 3000 → ⌈3.33⌉ = 4 个月 → 2027-02-05。
     * 目标金额一个月就能存完也至少推 1 个月（推 0 会得到"当天就该完成"）。
     * 上限 100 年：每月存入填得极小（比如 0.01）时别推出年份 9 万多的荒唐日期。
     */
    static LocalDate estimatedTargetDate(LocalDate start, BigDecimal targetAmount, BigDecimal monthlyPlanAmount) {
        BigDecimal months = targetAmount.divide(monthlyPlanAmount, 0, RoundingMode.CEILING);
        long clamped = Math.min(1200L, Math.max(1L, months.longValue()));
        return start.plusMonths(clamped);
    }

    /** 选填文本：空白串一律存 null，别把 "" 写进库（前端空输入框提交的就是 ""） */
    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * id 是路径参数：非数字一律当作不存在，别让它冒成 500。
     * （前端「新建计划」以前就是拿 id=new 打到这里的，现在走独立的 /savings/new 路由；
     * 这条兜底留着 —— 手敲 URL、老书签都可能再送一个非数字进来。）
     */
    private Long parseId(String id) {
        try {
            return Long.valueOf(id.trim());
        } catch (RuntimeException ex) {
            throw BusinessException.notFound("存钱计划不存在: " + id);
        }
    }

    /** 详情类响应（getById / 关联 / 解除）：带上定投标记与未来流入预测 */
    private SavingsPlanResponse toResponse(Long userId, SavingPlan plan) {
        return toResponse(userId, plan, typeNames(userId), true);
    }

    private SavingsPlanResponse toResponse(Long userId, SavingPlan plan, Map<String, String> typeNames,
                                           boolean withDca) {
        SavingsPlanResponse response = new SavingsPlanResponse();
        response.setId(String.valueOf(plan.getId()));
        response.setName(plan.getName());
        response.setDescription(plan.getDescription());
        response.setType(plan.getType());
        // 类型显示名：自定义类型没有内置映射，从前端拿到的类型列表里也对得上（同一个 code）
        response.setTypeName(typeNames.getOrDefault(plan.getType(), plan.getType()));
        response.setImage(plan.getImage());
        // 目标金额**可以为 null**（不设目标的计划），不能兜成 0：0 会被前端当成「有目标但目标是 0」
        response.setTargetAmount(plan.getTargetAmount());
        // 前端会直接 targetDate.slice(0, 7)，null 会让模板渲染抛错，所以缺日期给空串
        response.setStartDate(plan.getStartDate() == null ? "" : plan.getStartDate().toString());
        response.setTargetDate(plan.getTargetDate() == null ? "" : plan.getTargetDate().toString());
        response.setMonthlyPlanAmount(plan.getMonthlyPlanAmount());
        response.setQuote(plan.getQuote());
        response.setNote(plan.getNote());
        response.setNoteImage(plan.getNoteImage());

        List<Asset> assets = assetsOf(userId, plan);
        Map<String, BigDecimal> totals = latestTotals(userId, assets);
        // 「当前已存金额」是算出来的：计划内资产的最新市值合计（列已删，别再想着存回库里）
        BigDecimal currentAmount = assets.stream()
                .map(asset -> amountOf(asset, totals))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setCurrentAmount(currentAmount);

        // 逐只收益、整体收益、月度走势都吃同一份序列行（都在下面这次查询里），别各查一遍
        List<AssetSeries> rows = assets.isEmpty()
                ? List.of()
                : assetSeriesMapper.findByUserAndCodes(userId,
                        assets.stream().map(Asset::getCode).collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<String, SavingsReturnResponse> statsByCode = returnStatsByCode(assets, rows, totals);
        List<SavingsAssetResponse> assetResponses = toAssets(assets, totals, statsByCode);
        if (withDca && !assets.isEmpty()) {
            LocalDate today = LocalDate.now();
            // 与「定投分析」预测同一个窗口：下月起 12 个完整自然月
            LocalDate from = CashFlowService.windowStart(today);
            LocalDate to = CashFlowService.windowEnd(today);
            Set<String> codes = assets.stream()
                    .map(Asset::getCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, CashFlowService.PlanDca> dcaByCode =
                    cashFlowService.planDca(userId, assets, codes, today, from, to);
            applyDca(assetResponses, dcaByCode);
            response.setInflowForecast(buildForecast(assetResponses, dcaByCode, from, to));
        }
        response.setAssets(assetResponses);
        response.setSeries(monthlySeries(assets, rows));
        response.setReturnStats(sumStats(statsByCode));
        return response;
    }

    /**
     * 给资产列表填定投标记并稳定排序：定投中的排前面，组内保持原顺序（code 升序）。
     * 配色已在 {@link #toAssets} 里按原始顺序分配，排序不会让颜色跟着定投状态跳。
     */
    static void applyDca(List<SavingsAssetResponse> assets,
                         Map<String, CashFlowService.PlanDca> dcaByCode) {
        for (SavingsAssetResponse asset : assets) {
            CashFlowService.PlanDca dca = dcaByCode.get(asset.getCode());
            boolean active = dca != null && dca.active();
            asset.setDcaActive(active);
            asset.setDcaFrequency(active ? dca.frequency() : null);
            asset.setDcaAmount(active ? dca.amount() : null);
        }
        // Boolean.compare 稳定排序：true 排在 false 前面，组内保持传入顺序
        assets.sort((a, b) -> Boolean.compare(
                Boolean.TRUE.equals(b.getDcaActive()), Boolean.TRUE.equals(a.getDcaActive())));
    }

    /**
     * 计划详情的未来流入预测：只把窗口内有金额的资产放进图例，12 个月全部下发
     * （没有扣款的月份 total=0、byAsset 为空，横轴才不会断）。
     * 包级 static 是为了纯 JUnit 单测（不启 Spring，直接喂 DTO + PlanDca）。
     */
    static SavingsInflowForecastResponse buildForecast(List<SavingsAssetResponse> assets,
                                                       Map<String, CashFlowService.PlanDca> dcaByCode,
                                                       LocalDate from, LocalDate to) {
        List<SavingsInflowForecastResponse.AssetResponse> chartAssets = new ArrayList<>();
        for (SavingsAssetResponse asset : assets) {
            CashFlowService.PlanDca dca = dcaByCode.get(asset.getCode());
            if (dca == null || dca.outlayByMonth().isEmpty()) {
                continue;
            }
            chartAssets.add(new SavingsInflowForecastResponse.AssetResponse(
                    asset.getCode(), asset.getName(), asset.getColorVar()));
        }

        YearMonth fromMonth = YearMonth.from(from);
        YearMonth toMonth = YearMonth.from(to);
        List<SavingsInflowForecastResponse.MonthResponse> months = new ArrayList<>();
        for (YearMonth month = fromMonth; !month.isAfter(toMonth); month = month.plusMonths(1)) {
            Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            for (SavingsInflowForecastResponse.AssetResponse asset : chartAssets) {
                BigDecimal amount = dcaByCode.get(asset.getCode()).outlayByMonth().get(month);
                if (amount == null || amount.signum() == 0) {
                    continue;
                }
                byAsset.put(asset.getCode(), amount);
                total = total.add(amount);
            }
            months.add(new SavingsInflowForecastResponse.MonthResponse(
                    month.toString(), total.setScale(2, RoundingMode.HALF_UP), byAsset));
        }
        return new SavingsInflowForecastResponse(fromMonth.toString(), toMonth.toString(),
                chartAssets, months);
    }

    /**
     * 计划整体的收益四件套：市值 / 本金 / 手续费 / 累计投入，**四个数都只统计非现金资产**。
     *
     * 口径与 FundService 的单只基金完全一致，**逐只算完再相加**：
     * 累计投入 = Σ 每只资产 Σ 每日 max(0, amount + fee)，手续费 = Σ 每日 fee
     * （**是逐日的流水累加，不是末行那一格** —— 抄错这里会把收益算高），本金取每只末行的 principal。
     * 不能用 PortfolioService.aggregate：它是同日先把各基金的流水合并再 max(0)，
     * 同一天有人卖有人买时结果与「各基金页面上那些数之和」不一样。
     *
     * **现金必须从四个数里一起排除**（它没有 asset_series、本金恒等于市值）：只要市值那一项含了现金、
     * 其余三项不含，收益就会凭空多出一整笔现金 —— 含现金的总市值由调用方放进 currentAmount，
     * 两者口径不同是故意的（与 /assets/summary 计现金、/funds 不计现金一致）。
     *
     * 做成包级静态是为了单测（本项目的测试都是纯 JUnit，没有 Mockito）。
     * 一条序列行都没有的资产（新资产 / 抓取失败）退回 asset 快照的 principal / total，手续费按 0 ——
     * 与「最新市值退回 asset.total」同一条兜底。
     */
    static SavingsReturnResponse returnStats(List<Asset> assets, List<AssetSeries> rows,
                                             Map<String, BigDecimal> latestTotals) {
        return sumStats(returnStatsByCode(assets, rows, latestTotals));
    }

    /**
     * 逐只资产的收益四件套，key = 资产编码（现金不在内 —— 响应里就是缺省 null，前端显示「—」）。
     * 口径与 {@link #returnStats} 完全一致，只是不合并：计划详情的每只资产都要显示自己的累计收益/收益率。
     */
    static Map<String, SavingsReturnResponse> returnStatsByCode(List<Asset> assets, List<AssetSeries> rows,
                                                                Map<String, BigDecimal> latestTotals) {
        Map<String, List<AssetSeries>> byCode = new LinkedHashMap<>();
        for (AssetSeries row : rows) {
            byCode.computeIfAbsent(row.getCode(), code -> new ArrayList<>()).add(row);
        }

        Map<String, SavingsReturnResponse> statsByCode = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (CashAsset.isCash(asset.getCode())) {
                continue;
            }
            // findByUserAndCodes 是 ORDER BY code, day，所以每组内部按日升序
            statsByCode.put(asset.getCode(),
                    oneAssetStats(asset, byCode.getOrDefault(asset.getCode(), List.of()), latestTotals));
        }
        return statsByCode;
    }

    /** 单只资产的收益四件套；没有序列行的退回 asset 快照（本金 / 市值），手续费按 0 */
    private static SavingsReturnResponse oneAssetStats(Asset asset, List<AssetSeries> series,
                                                       Map<String, BigDecimal> latestTotals) {
        if (series.isEmpty()) {
            BigDecimal snapshot = zeroIfNull(asset.getPrincipal());
            return new SavingsReturnResponse(amountOf(asset, latestTotals), snapshot,
                    BigDecimal.ZERO, snapshot.max(BigDecimal.ZERO));
        }
        AssetSeries last = series.get(series.size() - 1);
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal invested = BigDecimal.ZERO;
        for (AssetSeries row : series) {
            invested = invested.add(Xirr.outlay(row.getAmount(), row.getFee()).max(BigDecimal.ZERO));
            fee = fee.add(zeroIfNull(row.getFee()));
        }
        return new SavingsReturnResponse(zeroIfNull(last.getTotal()), zeroIfNull(last.getPrincipal()),
                fee, invested);
    }

    /** 逐只的四件套合并成计划整体的那份（金额都是精确加法，顺序无关） */
    static SavingsReturnResponse sumStats(Map<String, SavingsReturnResponse> statsByCode) {
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal invested = BigDecimal.ZERO;
        for (SavingsReturnResponse one : statsByCode.values()) {
            current = current.add(zeroIfNull(one.getCurrent()));
            principal = principal.add(zeroIfNull(one.getPrincipal()));
            fee = fee.add(zeroIfNull(one.getFee()));
            invested = invested.add(zeroIfNull(one.getInvested()));
        }
        return new SavingsReturnResponse(current, principal, fee, invested);
    }

    /**
     * 计划里的资产（都排除已归档）：
     * - 总资产计划 = 该用户全部未归档资产；
     * - 生活保障 / 愿望 = plan_asset 里显式关联的那些。
     *
     * 返回值保持 assetMapper.findByUser 的 code 升序 —— toAssets 的配色按顺序轮转，
     * 顺序不稳的话列表颜色每次刷新都在跳。
     */
    private List<Asset> assetsOf(Long userId, SavingPlan plan) {
        List<Asset> all = assetMapper.findByUser(userId);
        Set<Long> linked = TYPE_ASSET.equals(plan.getType())
                ? Set.of()
                : new LinkedHashSet<>(planAssetMapper.findAssetIdsByPlan(userId, plan.getId()));
        return selectAssets(plan.getType(), all, linked);
    }

    /**
     * 从该用户的全部资产里挑出进计划的那些。做成包级静态是为了能单测
     * （本项目的测试都是纯 JUnit，没有 Mockito，见 AssetServiceTest）。
     *
     * 规则：所有类型都排除已归档；总资产计划全收；生活保障 / 愿望只收显式关联的。
     * 保持入参顺序（调用方传的是 findByUser 的 code 升序，配色轮转靠它稳定）。
     */
    static List<Asset> selectAssets(String planType, List<Asset> all, Set<Long> linkedIds) {
        boolean coversAll = TYPE_ASSET.equals(planType);
        return all.stream()
                .filter(asset -> !isArchived(asset))
                .filter(asset -> coversAll
                        || (asset.getId() != null && linkedIds.contains(asset.getId())))
                .toList();
    }

    /**
     * 计划内的资产：id / 编码 / 名称 + 最新市值 + 自身收益四件套 + 轮转配色
     * （id 是前端关联/解除用的；现金取不到四件套，就是 null）。
     */
    private List<SavingsAssetResponse> toAssets(List<Asset> assets, Map<String, BigDecimal> totals,
                                                Map<String, SavingsReturnResponse> statsByCode) {
        List<SavingsAssetResponse> result = new ArrayList<>();
        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            result.add(new SavingsAssetResponse(
                    String.valueOf(asset.getId()),
                    asset.getCode(),
                    assetName(asset),
                    amountOf(asset, totals),
                    statsByCode.get(asset.getCode()),
                    ASSET_COLORS.get(i % ASSET_COLORS.size()),
                    // dca 三件套只有详情响应才填（toResponse 里 applyDca），列表保持 null
                    null,
                    null,
                    null));
        }
        return result;
    }

    /** 资产名称：没填过名字的用编码兜底（与 fund 列表一个口径） */
    private static String assetName(Asset asset) {
        return StringUtils.hasText(asset.getName()) ? asset.getName() : asset.getCode();
    }

    /** 最新市值：asset_series 的最后一行；一条序列都没有（新资产/抓取失败）时退回 asset.total */
    private static BigDecimal amountOf(Asset asset, Map<String, BigDecimal> totals) {
        return totals.getOrDefault(asset.getCode(), zeroIfNull(asset.getTotal()));
    }

    /** 归档 = 不计入任何统计。archived 是 TINYINT，实体里是 Boolean，NULL 按未归档算 */
    private static boolean isArchived(Asset asset) {
        return Boolean.TRUE.equals(asset.getArchived());
    }

    /**
     * 月度走势：每个自然月取该月最后一天的市值合计，某个月没有任何序列行就沿用上个月的合计
     * （数据是按天生成的，但计划详情只画月度，没必要把上千个日度点丢给图表）。
     */
    private List<SavingsMonthPointResponse> monthlySeries(List<Asset> assets, List<AssetSeries> rows) {
        if (assets.isEmpty()) {
            return List.of();
        }

        // 月份 → (code → 该月末的市值)
        Map<YearMonth, Map<String, BigDecimal>> byMonth = new TreeMap<>();
        for (AssetSeries row : rows) {
            byMonth.computeIfAbsent(YearMonth.from(row.getDay()), month -> new HashMap<>())
                    .put(row.getCode(), row.getTotal());
        }

        Map<String, BigDecimal> carried = new HashMap<>();
        List<SavingsMonthPointResponse> series = new ArrayList<>();
        byMonth.forEach((month, totals) -> {
            carried.putAll(totals);
            BigDecimal sum = carried.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            series.add(new SavingsMonthPointResponse(month.toString(), sum));
        });
        return series;
    }

    /** 每个 code 最新一天的市值 */
    private Map<String, BigDecimal> latestTotals(Long userId, List<Asset> assets) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        assets.forEach(asset -> codes.add(asset.getCode()));

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AssetSeries row : assetSeriesMapper.findLatestPerCode(userId)) {
            if (codes.contains(row.getCode())) {
                totals.put(row.getCode(), row.getTotal());
            }
        }
        return totals;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
