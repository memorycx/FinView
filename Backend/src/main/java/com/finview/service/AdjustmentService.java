package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.common.CashAsset;
import com.finview.controller.dto.AdjustmentRequest;
import com.finview.entity.Adjustment;
import com.finview.mapper.AdjustmentMapper;
import com.finview.mapper.AssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 资产调整记录业务：录入、查询、修改、删除。
 *
 * 约定：
 * - 所有方法第一个参数都是当前登录用户的 userId（控制层从 JWT 的 principal 取出），
 *   查询和修改都在 SQL 的 where 里带上它，越权访问直接命中 0 行；
 * - 记录不存在与记录不属于当前用户返回同一句提示，避免被用来探测别人的记录 id；
 * - 每次写操作之后都要让 {@link SeriesService} 重算该 code 的每日序列——事件流改了，
 *   序列必须跟着变，而且只能整段重算（历史被改写，增量补不回来）；
 * - 请求里可以带资产名与基金类型（录入界面填的），它们都不落 adjustments 表，
 *   只用来更新 asset.name / asset.asset_type（类型见 {@link #applyAssetType}）；
 * - 校验失败抛 BusinessException（前端拿到 { code, message } 直接展示 message）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    /** 定投开始：需要额外校验频率，且与「结束定投」在同一天互斥 */
    private static final int ACTION_START_INVEST = 1;

    /** 结束定投：与「定投开始」在同一天互斥 */
    private static final int ACTION_STOP_INVEST = 2;

    /** 一笔收入：现金的「进账」也用这个动作（见 CashAsset） */
    private static final int ACTION_INCOME = 3;

    /** 一笔支出：现金的「出账」 */
    private static final int ACTION_EXPENSE = 4;

    /** 合法动作：1定投开始，2结束定投，3一笔收入，4一笔支出（与表注释、前端 AdjustmentAction 一致） */
    private static final Set<Integer> VALID_ACTIONS = Set.of(1, 2, 3, 4);

    /** 合法频率，与前端 InvestFrequency 一致 */
    private static final Set<String> VALID_FREQUENCIES = Set.of("daily", "weekly", "monthly");

    /**
     * 合法的基金细分类型：equity 股基 / bond 债基（cash 只属于保留 code CASH，不接受前端指定）。
     */
    private static final Set<String> VALID_ASSET_TYPES = Set.of("equity", "bond");

    private final AdjustmentMapper adjustmentMapper;
    private final AssetMapper assetMapper;
    private final SeriesService seriesService;

    /**
     * 新增一条调整记录，返回落库后的完整记录（含回填的自增 adjustmentId）。
     * 写完重算该 code 的每日序列，让 asset_series 与 asset 快照跟上事件流；
     * 请求里带了资产名 / 基金类型的话，顺手写进 asset.name / asset.asset_type。
     */
    @Transactional
    public Adjustment create(Long userId, AdjustmentRequest request) {
        Adjustment adjustment = new Adjustment();
        adjustment.setUserId(userId);
        applyRequest(adjustment, request, null);
        // 类型先校验再落库：非法值要在写任何一行之前 400（applyRequest 已把 code 归一成 CASH）
        String assetType = normalizeAssetType(request.getAssetType(), adjustment.getCode());

        adjustmentMapper.insert(adjustment);
        log.info("新增调整记录，userId={}, adjustmentId={}, code={}, action={}",
                userId, adjustment.getAdjustmentId(), adjustment.getCode(), adjustment.getAction());

        seriesService.rebuildAndSyncNav(userId, adjustment.getCode());
        applyAssetName(userId, adjustment.getCode(), request.getName());
        applyAssetType(userId, adjustment.getCode(), assetType);
        return adjustment;
    }

    /**
     * 查当前用户的调整记录，按日期倒序（最近的在前）。
     * code / action / startDate / endDate 都是可选过滤项，传 null 即不过滤。
     */
    public List<Adjustment> list(Long userId, String code, Integer action,
                                 LocalDate startDate, LocalDate endDate) {
        if (action != null && !VALID_ACTIONS.contains(action)) {
            throw BusinessException.badRequest("动作必须是 1/2/3/4");
        }
        return adjustmentMapper.findByUser(userId, trimToNull(code), action, startDate, endDate);
    }

    /** 查单条记录，不存在或不属于当前用户抛 404 */
    public Adjustment getById(Long userId, Long adjustmentId) {
        return requireOwned(userId, adjustmentId);
    }

    /** 整条覆盖更新，返回更新后的记录；不存在或不属于当前用户抛 404 */
    @Transactional
    public Adjustment update(Long userId, Long adjustmentId, AdjustmentRequest request) {
        // 先按 adjustmentId + userId 查出来，既做了归属校验，也拿到了不能改的 userId
        Adjustment adjustment = requireOwned(userId, adjustmentId);
        // 记录可能被改到另一个 code，旧 code 的序列要跟着回退，所以先把原 code 记下来
        String previousCode = adjustment.getCode();
        applyRequest(adjustment, request, adjustmentId);
        String assetType = normalizeAssetType(request.getAssetType(), adjustment.getCode());

        adjustmentMapper.updateByIdAndUser(adjustment);
        log.info("更新调整记录，userId={}, adjustmentId={}", userId, adjustmentId);

        seriesService.rebuildAndSyncNav(userId, previousCode);
        if (!previousCode.equals(adjustment.getCode())) {
            seriesService.rebuildAndSyncNav(userId, adjustment.getCode());
        }
        applyAssetName(userId, adjustment.getCode(), request.getName());
        applyAssetType(userId, adjustment.getCode(), assetType);
        return adjustment;
    }

    /**
     * 删除记录；不存在或不属于当前用户抛 404。
     * 先查一遍是为了拿到 code——删完就没法知道该重算哪个计划了。
     */
    @Transactional
    public void delete(Long userId, Long adjustmentId) {
        Adjustment adjustment = requireOwned(userId, adjustmentId);
        adjustmentMapper.deleteByIdAndUser(adjustmentId, userId);
        log.info("删除调整记录，userId={}, adjustmentId={}", userId, adjustmentId);

        seriesService.rebuild(userId, adjustment.getCode());
    }

    /**
     * 把请求体写进实体，并做跨字段校验（单字段的必填/长度/正数已由 DTO 上的注解挡掉）。
     * user_id 与 adjustment_id 不在此处赋值：新增时 userId 由调用方给，
     * 修改时两者都沿用库里查出来的值。
     *
     * @param excludeId 修改时传自己的 adjustmentId，用于「同一天 1+2」检查时排除自己；新增传 null
     */
    private void applyRequest(Adjustment adjustment, AdjustmentRequest request, Long excludeId) {
        Integer action = request.getAction();
        // Set.of 的 contains(null) 会抛 NPE，所以先判空
        if (action == null || !VALID_ACTIONS.contains(action)) {
            throw BusinessException.badRequest("动作必须是 1/2/3/4");
        }

        String frequency = trimToNull(request.getFrequency());
        if (action == ACTION_START_INVEST) {
            if (frequency == null) {
                throw BusinessException.badRequest("定投开始需指定频率");
            }
            if (!VALID_FREQUENCIES.contains(frequency)) {
                throw BusinessException.badRequest("频率必须是 daily/weekly/monthly");
            }
        } else {
            // 结束定投和收支记录都不该带频率，前端可能残留上次选的值，这里统一归一成 null
            frequency = null;
        }

        String code = request.getCode().trim();
        // 现金是保留 code（见 CashAsset）：只接受离散收支，「定投开始 / 结束」在现金上没有意义 ——
        // 让它落库的话现金账本会忽略它、基金 fold 又看不到它，等于一条永远不起作用的记录。
        // 顺带把大小写统一成 CASH：唯一键是 utf8mb4_unicode_ci，库里不许混出 cash/CASH 两种写法
        if (CashAsset.isCash(code)) {
            code = CashAsset.CODE;
            if (action != ACTION_INCOME && action != ACTION_EXPENSE) {
                throw BusinessException.badRequest("现金只支持「一笔收入 / 一笔支出」");
            }
        }
        LocalDate date = request.getDate();
        // 同一天既开始又结束定投没法定义该扣几笔款（表设计.md：这种属于离散收支，业务层拦截）
        if (action == ACTION_START_INVEST || action == ACTION_STOP_INVEST) {
            int opposite = action == ACTION_START_INVEST ? ACTION_STOP_INVEST : ACTION_START_INVEST;
            if (adjustmentMapper.countByUserCodeDayAction(
                    adjustment.getUserId(), code, date, opposite, excludeId) > 0) {
                throw BusinessException.badRequest("同一天不能既有定投开始又有结束定投");
            }
        }

        adjustment.setCode(code);
        adjustment.setDate(date);
        adjustment.setReason(trimToNull(request.getReason()));
        adjustment.setAction(action);
        adjustment.setFrequency(frequency);
        adjustment.setAmount(request.getAmount());
        adjustment.setNote(trimToNull(request.getNote()));
    }

    /**
     * 把录入时填的资产名写进 asset 表（表设计.md 里名字属于 asset，adjustments 没有这一列）。
     *
     * 必须在序列重建**之后**调用：新 code 的 asset 行是重建时由快照 upsert 插进去的，
     * 先写名字会命中 0 行、白写。
     * 名字留空则不动 —— 编辑一条旧记录时不该把之前起好的名字抹掉。
     */
    private void applyAssetName(Long userId, String code, String name) {
        String assetName = trimToNull(name);
        if (assetName == null) {
            return;
        }
        int updated = assetMapper.updateName(userId, code, assetName);
        log.info("更新资产名称，userId={}, code={}, name={}, 影响行数={}", userId, code, assetName, updated);
    }

    /**
     * 归一 + 校验基金细分类型，返回 null 表示「不改动已有分类」。
     *
     * 现金行直接忽略（返回 null）：asset_type 对现金是系统语义（CashAsset.ASSET_TYPE，
     * 每次余额快照都会照写），与 frequency 对非「定投开始」的归一化同一套处理——传了不报错，但不生效。
     */
    private String normalizeAssetType(String assetType, String code) {
        String value = trimToNull(assetType);
        if (value == null || CashAsset.isCash(code)) {
            return null;
        }
        if (!VALID_ASSET_TYPES.contains(value)) {
            throw BusinessException.badRequest("资产类型必须是 equity/bond");
        }
        return value;
    }

    /**
     * 把录入时选的基金类型写进 asset.asset_type（表设计.md 里它属于 asset，adjustments 没有这一列）。
     *
     * 与 {@link #applyAssetName} 一样必须在序列重建**之后**调用（新行是重建时插进去的），
     * 且 null（没填 / 现金）时不动 —— 给一只债基录常规买入时不该把它翻回默认的股基。
     */
    private void applyAssetType(Long userId, String code, String assetType) {
        if (assetType == null) {
            return;
        }
        int updated = assetMapper.updateAssetType(userId, code, assetType);
        log.info("更新资产类型，userId={}, code={}, assetType={}, 影响行数={}",
                userId, code, assetType, updated);
    }

    /** 查记录并校验归属，返回单条记录；查不到一律当作「不存在」 */
    private Adjustment requireOwned(Long userId, Long adjustmentId) {
        Adjustment adjustment = adjustmentMapper.findByIdAndUser(adjustmentId, userId);
        if (adjustment == null) {
            throw BusinessException.notFound("调整记录不存在");
        }
        return adjustment;
    }

    /** 去空白，空串归一成 null，避免库里混进 "" 这种空值 */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
