package com.finview.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 定投走势图上的数据点，对应前端 types.ts 的 DayPoint，由 asset_series 的一行转换而来。
 *
 * principal = 累计投入本金，total = 当前市值（份额 × 当日净值），fee = 当日手续费。
 * 该 code 还没有净值数据时 total 等于 principal，画出来是一条零收益的本金曲线。
 * 累计手续费由前端按日累加（收益图按「市值 − 本金 − 累计手续费」算，把申购费当成本），
 * 组合走势（PortfolioService）里 fee 同样是「当天各 code 手续费之和」，口径一致。
 */
@Data
@NoArgsConstructor
public class DayPointResponse {

    /** 日期，形如 2026-06-15 */
    private String day;

    /** 截至当日的累计投入本金 */
    private BigDecimal principal;

    /** 截至当日的总市值 */
    private BigDecimal total;

    /** 当日手续费（卖出为 0）；累计手续费由调用方按日累加 */
    private BigDecimal fee;

    /**
     * 截至当日的**累计投入** = Σ 正的当日出资（当日净投入 + 当日手续费），卖出日不计入。
     * 它是「收益率」的分母：没清过仓时恰好等于「本金 + 累计手续费」，清过仓的基金本金会变成
     * 负数或很小，只有它能兜住分母（见 CLAUDE.md 的收益率口径）。
     */
    private BigDecimal invested;

    public DayPointResponse(String day, BigDecimal principal, BigDecimal total,
                            BigDecimal fee, BigDecimal invested) {
        this.day = day;
        this.principal = principal;
        this.total = total;
        this.fee = fee;
        this.invested = invested;
    }
}
