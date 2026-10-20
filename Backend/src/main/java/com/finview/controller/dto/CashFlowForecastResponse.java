package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 定投分析（现金流规划 + 现金积累预测）响应体，对应前端 types.ts 的 CashFlowForecast。
 *
 * 口径（见 CashFlowService 类注释）：
 * - 窗口 = 下月起 12 个完整自然月（当月的剩余扣款刻意不计入）；
 * - 支出按**实际扣款日**归月：每周一 / 每月 1 日遇非交易日顺延到下一个交易日，
 *   顺延到次月的那笔记在次月；单笔支出 = 配置的定投金额（申购费从里面扣，不额外增加流出）；
 * - 卡片四个数的自洽算式：projectedCash = currentCash + 12 × monthlyInflow − 12 × monthlyOutlay，
 *   monthlyOutlay = totalOutlay ÷ 12（四舍五入到分），末月余额直接钉到 projectedCash，
 *   把四舍五入误差（≤ 6 分）收在最后一个月里。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowForecastResponse {

    /** 当前可用现金（元）= asset 表 code=CASH 那一行的 total，与 /assets/summary 的现金桶同源 */
    private BigDecimal currentCash;

    /** 默认的每月固定新增（元）；null = 用户还没设置（按 0 参与预测，页面显示「未设置」）。单月可以覆盖 */
    private BigDecimal monthlyInflow;

    /** 每月定投总额（元）= 12 个月扣款合计 ÷ 12（已四舍五入到分） */
    private BigDecimal monthlyOutlay;

    /** 12 个月后预计现金（元）= currentCash + 12 个月新增合计 − 12 × monthlyOutlay（无单月覆盖时 = currentCash + 12 × monthlyInflow − 12 × monthlyOutlay） */
    private BigDecimal projectedCash;

    /** 窗口内 12 个月的扣款合计（元），对账用 */
    private BigDecimal totalOutlay;

    /** 窗口首月，形如 2026-11 */
    private String fromMonth;

    /** 窗口末月，形如 2027-10 */
    private String toMonth;

    /**
     * 交易日历覆盖到的最后一天（形如 2026-12-31）；null = 日历没加载，窗口内按「周一~周五」估算。
     * 日历只覆盖到当年年底，次年的预测里节假日会被当成交易日（偏多），前端拿它写脚注说明。
     */
    private String calendarCoverageEnd;

    /** 12 个月的明细，按月份升序，恒 12 项 */
    private List<MonthResponse> months;

    /** 单个自然月：新增 / 定投支出 / 月末余额 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthResponse {

        /** 月份，形如 2026-11 */
        private String month;

        /** 当月现金新增：有单月覆盖用覆盖值，否则用默认的 monthlyInflow（都为 0 时是 0） */
        private BigDecimal inflow;

        /** 当月定投支出（元）＝当月实际划走的定投金额合计（申购费从里面扣） */
        private BigDecimal outlay;

        /** 当月末现金余额（元）= 上月末余额 + 当月新增 − 当月支出 */
        private BigDecimal endBalance;

        /** 这个月的新增是否被单独调整过（true = 有覆盖行；页面上标出来并允许「恢复默认」） */
        private boolean overridden;
    }
}
