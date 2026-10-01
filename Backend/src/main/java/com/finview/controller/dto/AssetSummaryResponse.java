package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资产总览响应体，对应前端 types.ts 的 AssetSummary。
 *
 * 口径是「当前持仓」：只统计未归档的 code（archived=0）的最新市值，定投结束但未归档的仍算持有。
 * 与前端 AssetOverview / Leaderboard 的算法保持一致——它们拿这份 allocation 算占比与集中度，
 * 分母里塞进已归档计划的市值会让所有比例失真。
 *
 * allocation 恒返回四个大类（基金/股票/债券/现金），没有持仓的大类值为 0，
 * 前端按 category 取色（--chart-1/3/4/5）并直接渲染四行图例，缺行会导致图例缺项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssetSummaryResponse {

    /** 总资产（元）= allocation 各项之和 */
    private BigDecimal totalAssets;

    /** 按资产大类的市值分布 */
    private List<AllocationResponse> allocation;

    /** 单个资产大类 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationResponse {

        /** 资产大类：fund / stock / bond / cash */
        private String category;

        /** 大类中文名：基金 / 股票 / 债券 / 现金 */
        private String label;

        /** 当前市值（元） */
        private BigDecimal value;
    }
}
