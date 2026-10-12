package com.finview.common;

/**
 * 现金资产的约定：保留 code、名称、分类的唯一出处（2026-10-01 加）。
 *
 * 现金不是一个新表，而是一条**保留 code 的普通资产**：
 * - 手工记录复用 adjustments（正数 = action=3 一笔收入、负数 = action=4 一笔支出），
 *   方向由 action 表达，所以全库「amount 恒为正」的约定不用破；
 * - 余额由 {@link com.finview.service.CashLedger} 现算（手工记录 + 基金买卖联动），
 *   只写 asset 表的一条快照，**不写 asset_series**；
 * - 不进定投看板：{@code SeriesService.foldForUser} 会把它滤掉，
 *   {@code PortfolioService} 也不把现金当持仓（否则组合现金流被对冲、XIRR 会算歪）。
 */
public final class CashAsset {

    /** 保留 code。用户的基金代码都是数字，不会撞 */
    public static final String CODE = "CASH";

    /** 资产名，只在首次插入 asset 行时写进去 */
    public static final String NAME = "现金";

    /** asset.category，AssetService 的「现金」桶按它分组 */
    public static final String CATEGORY = "cash";

    /**
     * asset.asset_type：现金是系统语义（安全资金统计按 bond + cash 计），
     * 每次刷新余额快照都会照写；基金的类型是用户维护的元数据，fold 不碰。
     */
    public static final String ASSET_TYPE = "cash";

    private CashAsset() {
    }

    /**
     * 是不是现金。
     * 必须忽略大小写：库里 code 列是 utf8mb4_unicode_ci（唯一键 idx_user_code 大小写不敏感），
     * 手写 SQL 落进一行 {@code cash} 也不会和 {@code CASH} 共存 —— 但 Java 的 equals 会认不出它，
     * 于是这行现金会被当成基金：出现在 /funds、进组合曲线、每次登录还被抓一次净值。
     */
    public static boolean isCash(String code) {
        return code != null && CODE.equalsIgnoreCase(code.trim());
    }
}
