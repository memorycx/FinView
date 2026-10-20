package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「定投日历」响应体（定投分析页），对应前端 types.ts 的 CashFlowCalendar。
 *
 * 口径（见 CashFlowService.calendar）：
 * - 金额 = 当天**周期扣款**划走的钱（按 amount + fee 汇总，等于配置的定投金额；申购费从里面扣收），
 *   买入 / 卖出不算定投；整月（含过去的日子）都是按当前事件流**重算**的，
 *   口径改动还没重算历史时可能和走势图的已存序列差一笔，跑一次重算即一致；
 * - days 恒返回该月每一天（金额 0 的天也在），前端直接铺日历格子；
 * - weekdays（按周）/ monthsOfYear（按年）是同一份数据的两种分布视图，前端切着看；
 * - minMonth / maxMonth 是导航上下界：下界 = 最早有定投记录的月份，上界 = 预测窗口末月（本月 + 12）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowCalendarResponse {

    /** 该月的月份，形如 2026-10 */
    private String month;

    /** 导航下界（最早有定投记录的月份）；没有任何记录时 = 当前月 */
    private String minMonth;

    /** 导航上界（预测窗口末月，本月 + 12） */
    private String maxMonth;

    /** 本月定投总金额（元）= days 里各天金额之和 */
    private BigDecimal total;

    /** 定投执行天数：当月金额大于 0 的天数 */
    private int executedDays;

    /** 涉及基金数量：当月有扣款的 code 数 */
    private int fundCount;

    /** 日均定投金额（元）= total ÷ executedDays；没有执行日时是 0 */
    private BigDecimal dailyAverage;

    /** 该月每一天，按日期升序，恒 lengthOfMonth 项 */
    private List<DayResponse> days;

    /** 每只基金的本月金额与占比，按金额降序；当月没有扣款的基金不返回 */
    private List<FundResponse> funds;

    /** 周一 ~ 周日的金额分布（7 项，下标 0 = 周一） */
    private List<BigDecimal> weekdays;

    /**
     * 年度定投金额分布：所选月份那一年的 1~12 月合计（12 项，下标 0 = 1 月）。
     * 次年年尾几个月可能超出预测窗口——后端会把 fold 推进到那一年年尾，所以这里是完整的 12 个月。
     */
    private List<BigDecimal> monthsOfYear;

    /** 年度定投总金额（元）= monthsOfYear 之和 */
    private BigDecimal yearTotal;

    /** 年度定投执行天数：当年有扣款的天数 */
    private int yearExecutedDays;

    /** 年度涉及基金数量：当年有扣款的 code 数 */
    private int yearFundCount;

    /** 年度日均定投金额（元）= yearTotal ÷ yearExecutedDays；当年没有执行日时是 0 */
    private BigDecimal yearDailyAverage;

    /** 单日：日期 / 当日定投金额 / 当日执行的定投计划数 / 各基金明细 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayResponse {

        /** 形如 2026-10-01 */
        private String day;

        /** 当日定投金额（元）；非扣款日是 0 */
        private BigDecimal amount;

        /** 当日执行的定投计划数量（同一只基金一天最多算一笔） */
        private int plans;

        /**
         * 当天扣款的各基金明细（前端点某一天时右侧列表用它），按金额降序；
         * 没有扣款的日子是空数组。名称与配色不在这里重复，按 code 去 funds[] 里取。
         */
        private List<DayFundResponse> funds;
    }

    /** 单日里的单只基金 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayFundResponse {

        private String code;

        /** 当天这只基金划走的金额（元） */
        private BigDecimal amount;
    }

    /** 单只基金在某月的汇总 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundResponse {

        private String code;

        /** 资产名称（asset.name；没有 asset 行时退回 code） */
        private String name;

        /** 本月金额（元） */
        private BigDecimal amount;

        /** 占比（0~1），= amount ÷ total */
        private BigDecimal share;

        /**
         * 配色变量名（--chart-1 / --chart-3 / --chart-4 / --chart-5 轮转），
         * 与存钱计划的 SavingsAssetResponse.colorVar 同一套；前端直接写 `var(${colorVar})`。
         */
        private String colorVar;
    }
}
