package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.common.Xirr;
import com.finview.controller.dto.DayPointResponse;
import com.finview.controller.dto.FundResponse;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import com.finview.service.SeriesService.PlanState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 定投计划业务：把 fold 出来的计划状态翻译成前端要的「计划」。
 *
 * 计划本身不落表：状态由 {@link SeriesService} 把 adjustments 事件流 fold 得到
 * （在定投中 / 已结束 / 从未定投），走势与本金市值直接读它写出的 asset_series。
 * 本类只做「读 + 组装」，不再自己算钱——两套算法迟早会对不上。
 *
 * 几条前端依赖的约定（改动前先看组件）：
 * - series 必须是数组且非 null，且按日期升序：Dashboard 判 series.length，PerformanceChart 拿
 *   末元素当「最新值」；库里还没有序列时退化成单点，不能让页面卡在「数据加载中…」；
 * - adjustments 同样必须非 null（PlanDetail 直接 [...fund.adjustments]）；
 * - id 取 code：前端拿它做选中态 key、还会带进 ?fund= 路由参数，code 比自增主键稳定。
 *
 * 定投状态有三种不是两种：进行中 / 已结束 / 从未定投（只有零星买卖，一条 1/2 都没有），
 * 后两种 active 都是 false，列表里显示「已停止」，但**不等于归档**：
 * 是否归档只由 asset.archived（用户手工标记，fold 不覆盖）决定，与定投状态无关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundService {

    /** asset 表缺行时的兜底分类 */
    private static final String DEFAULT_CATEGORY = "fund";

    private final AssetMapper assetMapper;
    private final AssetSeriesMapper assetSeriesMapper;
    private final SeriesService seriesService;

    /**
     * 当前用户的定投计划列表。
     *
     * @param status active / archived / all，缺省为 all（前端不带参数时走这条）。
     *               active 表示**未归档**（持有中），判定看 asset.archived，不看定投状态。
     */
    public List<FundResponse> list(Long userId, String status) {
        StatusFilter filter = parseStatus(status);

        // 一次取全量事件（数据量是个人看板级别，几十行），fold 出来的状态不会过期
        Map<String, PlanState> states = seriesService.foldForUser(userId, LocalDate.now());

        // name / category / assetType / archived 只有 asset 表有，缺行时用默认值兜底，别让少一行元数据把接口搞挂
        Map<String, Asset> assets = assetMapper.findByUser(userId).stream()
                .collect(Collectors.toMap(Asset::getCode, Function.identity(), (a, b) -> a));
        Map<String, List<AssetSeries>> series = loadSeries(userId, states);

        return states.values().stream()
                .filter(state -> filter.matches(assets.get(state.getCode())))
                .map(state -> toResponse(state, assets.get(state.getCode()), series.get(state.getCode())))
                .toList();
    }

    /** 查单个计划，id 就是 code；不存在抛 404（与「越权访问别人的计划」返回同一句提示） */
    public FundResponse getById(Long userId, String id) {
        return list(userId, "all").stream()
                .filter(fund -> fund.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("定投计划不存在"));
    }

    /**
     * 收益排行榜：按收益率降序，含已归档计划（与前端 mock 的 fundLeaderboard 行为一致）。
     * 排序必须在这里做完——Leaderboard.vue 拿到什么顺序就画什么顺序，自己不排。
     *
     * **累计投入为 0 的计划直接剔除**：前端是用（市值 − 本金 − 手续费）/ 累计投入 自己重算收益率的，
     * 0 会算出 Infinity/NaN，而 `Math.max(0, ...list.map(Math.abs(returnRate)))` 一旦吃进 NaN
     * 就把 maxShare 污染成 NaN，页面上**所有**柱子的宽度都会变成 NaN%。
     * 后端这次防除零只保护排序，救不了前端那次重算，所以只能不给它这种行。
     * 收益率全是 0（没有净值数据源）时用本金降序兜底，保证顺序稳定、可预期。
     */
    public List<FundResponse> leaderboard(Long userId) {
        var byRateDesc = java.util.Comparator.comparing(FundService::returnRate).reversed();
        var byPrincipalDesc = java.util.Comparator.comparing(FundResponse::getPrincipal).reversed();

        return list(userId, "all").stream()
                .filter(fund -> fund.getInvested() != null && fund.getInvested().signum() > 0)
                .sorted(byRateDesc.thenComparing(byPrincipalDesc).thenComparing(FundResponse::getCode))
                .toList();
    }

    /**
     * 为单个 code 重新生成 asset_series：整段重算 + 异步补一次净值，返回生成后的序列行数。
     *
     * 为什么要有这个入口：读接口一律不写库（见 CLAUDE.md），序列被清空 / 缺行时，
     * 靠页面自己恢复不了——登录走的 {@link SeriesService#advance} 被 user.update_series_time
     * 水位挡着（当天推进过就直接返回），只有 rebuild 这条路能把行补回来。
     *
     * 返回 0 表示该 code 一条调整记录都没有，regenerate 会顺手清掉它的残留行。
     */
    public int generateSeries(Long userId, String id) {
        seriesService.rebuildAndSyncNav(userId, id);
        return assetSeriesMapper.findByUserAndCode(userId, id).size();
    }

    /**
     * 一键更新**全部**资产的每日序列（组合总览页的「更新全部资产序列」按钮），返回更新的资产数。
     *
     * 逐个 code 走 {@link #generateSeries} 同一条路（整段重算 + 各自异步补一次净值），
     * 范围 = fold 出来的全部 code，**含已归档**、不含现金（现金没有序列，登录推进也一样跳过）。
     * 每只各自一个事务：中途某只失败不影响已经更新好的，异常直接抛给控制层（前端展示 message）。
     */
    public int generateAllSeries(Long userId) {
        Map<String, PlanState> states = seriesService.foldForUser(userId, LocalDate.now());
        states.keySet().forEach(code -> seriesService.rebuildAndSyncNav(userId, code));
        log.info("一键更新全部序列完成，userId={}, 资产数={}", userId, states.size());
        return states.size();
    }

    /** fold 结果 + asset 元数据 + 序列 → 前端契约的 Fund */
    private FundResponse toResponse(PlanState state, Asset asset, List<AssetSeries> rows) {
        List<AssetSeries> series = rows == null ? List.of() : rows;

        FundResponse response = new FundResponse();
        response.setId(state.getCode());
        response.setCode(state.getCode());
        response.setName(asset != null && StringUtils.hasText(asset.getName())
                ? asset.getName() : state.getCode());
        response.setCategory(asset != null && StringUtils.hasText(asset.getCategory())
                ? asset.getCategory() : DEFAULT_CATEGORY);
        // 股基/债基原样透出，不在读侧归一：null 就是「未标注」，前端按股票基金显示（与 AssetService.bucketOf 同口径）
        response.setAssetType(asset == null ? null : asset.getAssetType());
        response.setActive(state.isActive());
        response.setArchived(isArchived(asset));
        response.setFrequency(state.getFrequency());
        response.setAmount(state.getAmount());
        response.setStartDate(state.startMonth());
        // 最后更新时间来自 asset 行（SeriesService 每次生成/重算时刷新）；asset 行缺失时为 null
        response.setLastUpdateTime(asset == null ? null : asset.getLastUpdateTime());

        // 本金 / 市值取序列末行：与 asset_series 是同一份数字，不再另算一遍
        AssetSeries last = series.isEmpty() ? state.lastRow() : series.get(series.size() - 1);
        response.setPrincipal(last.getPrincipal());
        response.setCurrent(last.getTotal());
        // 累计手续费 = 序列各日 fee 之和；成本口径（本金 + 手续费）与前端 lib/finance.returnRate 一致
        response.setFee(series.stream()
                .map(AssetSeries::getFee)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        // 累计投入（收益率的分母）= Σ 正的当日出资，卖出日不计入；顺手把序列点一起产出，不额外过一遍。
        // 没清过仓的基金它恰好等于「本金 + 累计手续费」，清过仓的基金本金会变负、只能靠它兜住分母
        List<DayPointResponse> points = new ArrayList<>(series.size());
        BigDecimal invested = BigDecimal.ZERO;
        for (AssetSeries row : series) {
            invested = invested.add(Xirr.outlay(row.getAmount(), row.getFee()).max(BigDecimal.ZERO));
            points.add(new DayPointResponse(row.getDay().toString(), row.getPrincipal(),
                    row.getTotal(), row.getFee(), invested));
        }
        response.setSeries(points);
        response.setInvested(invested);
        // 年化直接吃上面那份 rows：DB 行与内存 fold 兜底两条路径带的 amount/fee 口径一致，
        // 不用再查一次库。清过仓的只算最近这一轮（上一轮已经结束，现金流不该混进来），
        // 持有不足 30 天或数学上无解时是 null（前端显示「—」）
        response.setAnnualizedRate(Xirr.ofSeries(series, state.getLastClearance()));
        response.setAdjustments(state.getTimeline());
        return response;
    }

    /**
     * 每个 code 的每日序列，按日期升序。
     * 优先用库里的行（走势图与资产总览/组合走势读的是同一份数据），
     * 库里还没有（刚注册、还没走过 advance）时回退到内存 fold 的结果，
     * 避免 /funds 返回空 series 让前端一直停在「数据加载中…」。
     */
    private Map<String, List<AssetSeries>> loadSeries(Long userId, Map<String, PlanState> states) {
        Map<String, List<AssetSeries>> byCode = new LinkedHashMap<>();
        Set<String> codes = states.keySet();
        if (!codes.isEmpty()) {
            for (AssetSeries row : assetSeriesMapper.findByUserAndCodes(userId, codes)) {
                byCode.computeIfAbsent(row.getCode(), code -> new ArrayList<>()).add(row);
            }
        }
        states.forEach((code, state) -> byCode.putIfAbsent(code, state.getRows()));
        return byCode;
    }

    /**
     * 收益率 = 累计收益 / 累计投入（口径见 CLAUDE.md）。
     * 累计收益 = 市值 − 本金 − 累计手续费：本金是**净投入**（卖出已冲减），所以这个差值跨清仓连续。
     * 分母用累计投入而不是「本金 + 手续费」：清过仓的基金本金会变成负数或很小，
     * 拿它当分母会算出 +297% 这种假数字；没清过仓时两者恰好相等。
     * 分母为 0（从没买过）返回 0，避免除零算出 Infinity 把排序搞乱。
     */
    private static BigDecimal returnRate(FundResponse fund) {
        BigDecimal invested = fund.getInvested() == null ? BigDecimal.ZERO : fund.getInvested();
        if (invested.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return profit(fund).divide(invested, 8, RoundingMode.HALF_UP);
    }

    /** 累计收益 = 市值 − 本金 − 累计手续费 */
    private static BigDecimal profit(FundResponse fund) {
        BigDecimal principal = fund.getPrincipal() == null ? BigDecimal.ZERO : fund.getPrincipal();
        BigDecimal fee = fund.getFee() == null ? BigDecimal.ZERO : fund.getFee();
        return fund.getCurrent().subtract(principal).subtract(fee);
    }

    /** asset 行缺失时视为未归档 */
    private static boolean isArchived(Asset asset) {
        return asset != null && Boolean.TRUE.equals(asset.getArchived());
    }

    /** 状态参数只认 active / archived / all，其余值直接 400，别把拼错的参数当默认值静默放过 */
    private StatusFilter parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return StatusFilter.ALL;
        }
        return switch (status.trim().toLowerCase()) {
            case "active" -> StatusFilter.ACTIVE;
            case "archived" -> StatusFilter.ARCHIVED;
            case "all" -> StatusFilter.ALL;
            default -> throw BusinessException.badRequest("状态必须是 active/archived/all");
        };
    }

    /**
     * 列表状态过滤，取值与前端 types.ts 的 FundStatus 一致。
     * active = 未归档（持有中），archived = 已归档；只取决于 asset.archived，
     * 与定投是否进行中（PlanState.isActive）无关——定投结束不等于归档。
     */
    private enum StatusFilter {
        ACTIVE, ARCHIVED, ALL;

        boolean matches(Asset asset) {
            return switch (this) {
                case ACTIVE -> !isArchived(asset);
                case ARCHIVED -> isArchived(asset);
                case ALL -> true;
            };
        }
    }
}
