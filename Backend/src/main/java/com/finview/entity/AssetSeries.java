package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资产每日序列实体，对应 `asset_series` 表。
 *
 * 这张表是《表设计.md》「核心更新流程」的产物：{@link com.finview.service.SeriesService}
 * 把 adjustments 事件流按天推进，每个 (user_id, code) 每天写一行，是前端走势图与
 * 资产总览的唯一数据来源——计划状态另外由 fold 现算，不在这里存。
 *
 * 四条不变式（对账时直接拿来用）：
 * - Σamount == 末行 principal（清仓之后可能是负数：卖出多于投入，「净投入」本来就是负的）；
 * - Σfee == 累计手续费（买入按 asset.rate 收、卖出为 0，逐笔记在当日 fee 上）；
 * - total == 累计份额 × 当日净值，没有净值数据时 total == principal；
 * - 同一天只会有一行，重复的 action=1（同一天多条「定投开始」）只扣一次款。
 */
@Data
@NoArgsConstructor
public class AssetSeries {

    /** 自增主键 */
    private Long id;

    /** 所属用户id */
    private Long userId;

    /** 资产编码 */
    private String code;

    /** 日期，每个 code 从首个事件当天起逐日连续 */
    private LocalDate day;

    /** 当日净投入 = 当日周期扣款 + 一笔收入 − 一笔支出，支出会写成负数 */
    private BigDecimal amount;

    /** 当日手续费 = 当日各笔买入手续费之和（卖出为 0）；累计手续费 = Σfee */
    private BigDecimal fee;

    /** 截至当日的累计投入本金 */
    private BigDecimal principal;

    /** 截至当日的总市值 */
    private BigDecimal total;

    public AssetSeries(Long userId, String code, LocalDate day,
                       BigDecimal amount, BigDecimal fee, BigDecimal principal, BigDecimal total) {
        this.userId = userId;
        this.code = code;
        this.day = day;
        this.amount = amount;
        this.fee = fee;
        this.principal = principal;
        this.total = total;
    }
}
