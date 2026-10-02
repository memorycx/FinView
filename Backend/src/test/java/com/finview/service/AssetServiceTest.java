package com.finview.service;

import com.finview.entity.Asset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AssetService 展示桶划分的测试。
 *
 * 只测包级可见的 static {@code bucketOf}（不启 Spring）：这次新增的风险全在
 * 「基金按 asset_type 细分成股票基金 / 债券基金、NULL 按股基算」这一个点上，
 * mapper 那几行组装与 FundService 一样不覆盖（与 PortfolioServiceTest 同一套路）。
 */
class AssetServiceTest {

    private static Asset asset(String category, String assetType) {
        Asset asset = new Asset();
        asset.setCategory(category);
        asset.setAssetType(assetType);
        return asset;
    }

    @Test
    @DisplayName("基金按 asset_type 细分：bond 进债券基金，equity / NULL 进股票基金")
    void splitsFundsByAssetType() {
        assertEquals("bondFund", AssetService.bucketOf(asset("fund", "bond")));
        assertEquals("equityFund", AssetService.bucketOf(asset("fund", "equity")));
        // 存量没标过的（NULL）按股基算，与 schema.sql 的回填、AssetService 的展示口径一致
        assertEquals("equityFund", AssetService.bucketOf(asset("fund", null)));
        // category 缺行时的兜底也是基金，同样按类型细分（与 summary 里的老行为一致）
        assertEquals("bondFund", AssetService.bucketOf(asset(null, "bond")));
        assertEquals("equityFund", AssetService.bucketOf(asset(null, null)));
    }

    @Test
    @DisplayName("非基金大类原样成桶：现金、股票、债券不参与细分")
    void keepsOtherCategoriesAsBuckets() {
        assertEquals("cash", AssetService.bucketOf(asset("cash", "cash")));
        assertEquals("stock", AssetService.bucketOf(asset("stock", null)));
        assertEquals("bond", AssetService.bucketOf(asset("bond", null)));
    }

    @Test
    @DisplayName("未知分类原样单列，不丢数据")
    void keepsUnknownCategories() {
        assertEquals("pension", AssetService.bucketOf(asset("pension", "equity")));
    }
}
