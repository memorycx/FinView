package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「资金全景」页（3D 塔视图）的数据，对应前端 types.ts 的 Panorama。
 *
 * - buckets：按展示桶（与 /assets/summary 同一套 key/label/口径：未归档、最新市值）分组，
 *   **只返回有资产的桶**；桶按市值降序、桶内资产也按市值降序（3D 里最大的一座在前）；
 * - months：12 个自然月，**含本月剩余**（从今天起往后数 12 个自然月）；
 * - assets[].cumulative：从今天起到该月末的**累计变化**（不含未来涨跌）—— 「演变」动画就是把
 *   这些数加到当前的塔高上。基金 = 累计投入（周期扣款 amount + fee）；现金 = 累计净变化
 *   （每月固定新增 − 当月定投支出，可以为负，塔高由前端按 0 画）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PanoramaResponse {

    /** 数据起点（今天），形如 2026-10-14 */
    private String asOf;

    /** 12 个自然月，形如 ["2026-10", ..., "2027-09"] */
    private List<String> months;

    /** 按展示桶分组、按市值降序 */
    private List<BucketResponse> buckets;

    /** 一个桶 = 3D 里的一座塔 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BucketResponse {

        /** 展示桶 key：equityFund / bondFund / moneyFund / stock / bond / cash */
        private String key;

        /** 展示桶中文名：股票基金 / 债券基金 / … */
        private String label;

        /** 桶当前市值（元）= 下面各资产之和 */
        private BigDecimal value;

        /** 桶内资产，按市值降序（塔从下往上就是这个顺序） */
        private List<AssetItem> assets;
    }

    /** 塔里的一截（一只资产） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetItem {

        private String code;

        private String name;

        /** 当前市值（元） */
        private BigDecimal value;

        /** 3D 分段与右侧折线共用的颜色变量（桶内按 --chart 轮转） */
        private String colorVar;

        /**
         * 12 项：从今天起到第 i 个月末的累计变化（元）。
         * 基金 = 累计投入（单调不减，只算周期扣款 amount + fee）；
         * 现金 = 累计净变化（每月固定新增 − 当月定投支出，**可以为负**，见 PanoramaService.cashSeries）。
         */
        private List<BigDecimal> cumulative;
    }
}
