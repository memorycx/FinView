package com.finview.service;

import com.finview.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基金基础信息编辑的纯逻辑测试（不启 Spring、不碰 mapper）。
 *
 * 覆盖 FundService 里包级可见的 static 归一 / 校验函数：
 * 类型只认 equity/bond/money、市场只认 us/hk（空 = A 股）、费率非负且不超过 100%，
 * 以及「费率数值相等」的比较（0.12 与 0.1200 不应误触发序列重算）。
 * 真正的写库与重算流程不在这里覆盖（与 AssetServiceTest 同一套路）。
 */
class FundServiceTest {

    @Test
    @DisplayName("基金类型：大小写不敏感，只认 equity / bond / money")
    void normalizesAssetType() {
        assertEquals("equity", FundService.normalizeAssetType("equity"));
        assertEquals("bond", FundService.normalizeAssetType(" BOND "));
        assertEquals("money", FundService.normalizeAssetType("Money"));

        for (String bad : new String[]{null, "", "  ", "cash", "stock"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> FundService.normalizeAssetType(bad), String.valueOf(bad));
            assertEquals(400, ex.getCode());
            assertEquals("基金类型必须是 equity/bond/money", ex.getMessage());
        }
    }

    @Test
    @DisplayName("标的市场：空 = A 股（null），us / hk 大小写不敏感，其余 400")
    void normalizesMarket() {
        assertNull(FundService.normalizeMarket(null));
        assertNull(FundService.normalizeMarket(""));
        assertNull(FundService.normalizeMarket("   "));
        assertEquals("us", FundService.normalizeMarket("US"));
        assertEquals("hk", FundService.normalizeMarket(" hk "));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FundService.normalizeMarket("jp"));
        assertEquals(400, ex.getCode());
        assertEquals("标的市场必须是 us/hk", ex.getMessage());
    }

    @Test
    @DisplayName("申购费率：null 放行；保留 4 位小数；负数与超过 100 报 400")
    void normalizesRate() {
        assertNull(FundService.normalizeRate(null));
        assertEquals(new BigDecimal("0.1200"), FundService.normalizeRate(new BigDecimal("0.12")));
        assertEquals(new BigDecimal("0.1235"), FundService.normalizeRate(new BigDecimal("0.12345")));
        assertDoesNotThrow(() -> FundService.normalizeRate(new BigDecimal("100")));

        BusinessException negative = assertThrows(BusinessException.class,
                () -> FundService.normalizeRate(new BigDecimal("-0.01")));
        assertEquals("申购费率不能为负数", negative.getMessage());

        BusinessException tooBig = assertThrows(BusinessException.class,
                () -> FundService.normalizeRate(new BigDecimal("100.01")));
        assertEquals("申购费率不能超过 100%", tooBig.getMessage());
    }

    @Test
    @DisplayName("费率比较只看数值：0.12 与 0.1200 相等，null 与 0 不等")
    void comparesRateByValue() {
        assertTrue(FundService.sameRate(new BigDecimal("0.12"), new BigDecimal("0.1200")));
        assertFalse(FundService.sameRate(new BigDecimal("0.12"), new BigDecimal("0.13")));
        assertFalse(FundService.sameRate(null, BigDecimal.ZERO));
        assertTrue(FundService.sameRate(null, null));
    }
}
