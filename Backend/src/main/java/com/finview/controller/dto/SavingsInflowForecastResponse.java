package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 存钱计划详情的「未来资金流入」预测，对应前端 types.ts 的 SavingsInflowForecast。
 *
 * 口径与「定投分析」的现金流预测同源（都走 {@code SeriesService.fold}）：
 * - 窗口 = 下月起 12 个完整自然月（当月剩余扣款不计入）；
 * - 只统计**周期定投**（action=1/2 的事件），一笔买入/卖出不算；
 * - 金额 = asset_series 的 amount + fee，恰好等于配置的定投金额（申购费从金额里扣）；
 * - 按**实际扣款日**归月：每周一 / 每月 1 日遇非交易日顺延，顺延到次月的那笔记在次月；
 * - QDII 叠加标的市场休市日，交易日历口径与序列生成完全一致。
 *
 * assets 只下发窗口内有金额的资产（图例 + 配色）；months 恒 12 项，没有扣款的月份
 * total = 0、byAsset 为空对象，前端据此画出连续的横轴。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsInflowForecastResponse {

    /** 窗口首月，形如 2026-11 */
    private String fromMonth;

    /** 窗口末月，形如 2027-10 */
    private String toMonth;

    /** 窗口内有定投流入的资产（按计划资产列表顺序，配色与列表一致） */
    private List<AssetResponse> assets;

    /** 12 个月的明细，按月份升序，恒 12 项 */
    private List<MonthResponse> months;

    /** 图例里的一项资产 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetResponse {

        /** 资产编码 */
        private String code;

        /** 资产名称（没填过名字时用编码兜底，与列表同口径） */
        private String name;

        /** 图表主题色变量，如 --chart-1；前端直接转成 canvas 色值 */
        private String colorVar;
    }

    /** 单个自然月的流入：总额 + 按资产拆分的金额 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthResponse {

        /** 月份，形如 2026-11 */
        private String month;

        /** 当月定投流入合计（元） */
        private BigDecimal total;

        /** code → 当月金额（元），只放非零项；空对象表示这个月没有扣款 */
        private Map<String, BigDecimal> byAsset;
    }
}
