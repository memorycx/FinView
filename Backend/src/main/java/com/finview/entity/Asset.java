package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产实体，对应 `asset` 表。
 *
 * 这张表是**定投计划状态的持久化快照**：active / frequency / startDate / principal / total /
 * last_update_time 六列由 {@link com.finview.service.SeriesService} 从 adjustments 事件流
 * fold 出来后写入，每次增删改调整记录、每次推进序列都会重算，不要手工维护它们。
 *
 * 其余列反过来是用户维护的元数据，快照刷新时刻意不覆盖，见 AssetMapper.upsertSnapshot：
 * - name / category / asset_type：adjustments 表里没有这几列，录入界面填了才随请求写进 asset
 *   （AdjustmentService.applyAssetName / applyAssetType）；
 * - plan_id：所属存钱计划（表设计.md 用它取代了 plan_asset 关联表）；
 * - industry / region / currency：/assets/distribution 的三个维度；
 * - archived：是否归档，用户手工标记（定投结束 ≠ 归档，两者互相独立）；
 * - market：标的市场（us / hk），QDII 的扣款日要叠加标的市场休市日，见 MarketCalendar。
 *
 * principal = 累计投入本金，total = 当前市值（= 份额 × 最新净值，无净值时等于 principal）。
 */
@Data
@NoArgsConstructor
public class Asset {

    /** 自增主键 */
    private Long id;

    /** 所属用户id，来自 JWT */
    private Long userId;

    /** 资产名称，缺省等于 code，由用户改成真实名称 */
    private String name;

    /** 资产编码，与 adjustments.code 对应 */
    private String code;

    /** 资产分类：fund / stock / bond / cash；fund 的股基 / 债基细分看 assetType */
    private String category;

    /**
     * 资产类型：equity 股基 / bond 债基（基金行）/ cash 现金（保留 code CASH，系统维护）。
     * 用户维护的元数据：录入 / 修改调整记录时随请求带上（AdjustmentRequest.assetType，传了才改），
     * fold 不覆盖；null = 未标注，按**股基**算（读侧兜底，schema.sql 也会把 NULL 回填成 equity）。
     * 「我的资产」页的安全资金占比 = asset_type ∈ {bond, cash} 的市值 / 总资产，见 AssetService.summary。
     */
    private String assetType;

    /** 定投是否进行中（fold 结果：最后一个状态事件是不是「定投开始」） */
    private Boolean active;

    /**
     * 是否归档：用户手工标记的元数据（定投结束 ≠ 归档，两者互相独立），
     * upsertSnapshot 不覆盖；目前没有接口读写它，靠 SQL 维护。
     */
    private Boolean archived;

    /** 当前定投频率，计划结束或从未定投时为 null */
    private String frequency;

    /**
     * 申购费率（**百分数**，0.12 表示 0.12%），用户维护的元数据，fold 不覆盖。
     * 买入（周期扣款与一笔收入）时按 金额 × rate / 100 扣手续费，不足 1 分按 1 分扣，
     * 扣完的净额才计入本金与份额；null 或 0 表示不计费。赎回（一笔支出）不收费率。
     */
    private BigDecimal rate;

    /** 计划开始日期，取最早一条调整记录的日期 */
    private LocalDate startDate;

    /** 累计投入本金 = Σ(周期扣款) + Σ(action=3) − Σ(action=4)，与 asset_series 末行一致 */
    private BigDecimal principal;

    /** 当前市值 = 累计份额 × 最新净值；没有净值数据时等于 principal */
    private BigDecimal total;

    /** 所属存钱计划id，null 表示不计入任何计划 */
    private Long planId;

    /** 行业标签，供 /assets/distribution 的 industry 维度使用 */
    private String industry;

    /** 区域标签，供 /assets/distribution 的 region 维度使用 */
    private String region;

    /** 币种标签，供 /assets/distribution 的 currency 维度使用 */
    private String currency;

    /** 标的市场：us / hk；null 表示只看 A 股日历（A 股基金不用标） */
    private String market;

    /**
     * 最后更新时间：该资产最后一次生成 / 重算 asset_series 的时刻，由 SeriesService 写快照时刷成当时时刻
     * （现金没有序列，取最后一次余额快照刷新的时刻）。
     * 存量行由 schema.sql 迁移回填成所属用户的 user.create_time，之后每次生成都会覆盖；
     * 折线图页脚的「最后更新时间」用它，组合总览取该用户全部 asset 行的 MAX（含已归档与现金）。
     */
    private LocalDateTime lastUpdateTime;
}
