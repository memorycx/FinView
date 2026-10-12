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
 * allocation 恒返回五个展示桶（股票基金/债券基金/股票/债券/现金），没有持仓的为 0，
 * 前端按 category 取色并直接渲染五行图例，缺行会导致图例缺项。
 * 基金不再单独成桶：按 asset.asset_type 细分成 equityFund / bondFund（未标注按股票基金算）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssetSummaryResponse {

    /** 总资产（元）= allocation 各项之和 */
    private BigDecimal totalAssets;

    /**
     * 安全资金（元）= asset_type 为 bond（债基）或 cash（现金）的资产市值合计。
     * 口径与 allocation 一致（只算未归档）；未标注类型（NULL）按股票基金算，同样是非安全。
     * 「我的资产」页的安全资金占比 = safeAssets / totalAssets，由前端算（占比展示口径在前端）。
     */
    private BigDecimal safeAssets;

    /** 按资产大类的市值分布 */
    private List<AllocationResponse> allocation;

    /** 单个资产大类 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationResponse {

        /**
         * 展示桶 key：equityFund / bondFund / stock / bond / cash，
         * **不是 asset.category 原值**——基金按 asset_type 细分后派生出来的（见 AssetService.bucketOf）。
         */
        private String category;

        /** 展示桶中文名：股票基金 / 债券基金 / 股票 / 债券 / 现金 */
        private String label;

        /** 当前市值（元） */
        private BigDecimal value;
    }
}
