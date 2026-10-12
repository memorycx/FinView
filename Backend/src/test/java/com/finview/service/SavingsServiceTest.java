package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.controller.dto.SavingsPlanRequest;
import com.finview.entity.SavingPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 存钱计划的纯逻辑测试（不启 Spring、不碰 mapper）。
 *
 * 覆盖两块包级可见的 static：
 * - {@code validateNewPlan}：类型合法 / 总资产计划唯一 / 目标相关的必填组合 / 日期不倒挂；
 * - {@code groupTotals}：计划内资产的收益率口径（逐只累加、现金排除、没序列行的不贡献）。
 * 组装与 insert 那几行不覆盖（与 AssetServiceTest 同一套路）。
 */
class SavingsServiceTest {

    private static SavingsPlanRequest request(String type, String start, String target) {
        SavingsPlanRequest request = new SavingsPlanRequest();
        request.setName("日本旅行");
        request.setType(type);
        request.setTargetAmount(new BigDecimal("10000"));
        request.setStartDate(LocalDate.parse(start));
        request.setTargetDate(LocalDate.parse(target));
        request.setMonthlyPlanAmount(new BigDecimal("1000"));
        return request;
    }

    /** 不设目标的计划：目标金额 / 目标日期 / 每月存入 全为 null */
    private static SavingsPlanRequest targetless(String type, String start) {
        SavingsPlanRequest request = new SavingsPlanRequest();
        request.setName("看这组资产的收益");
        request.setType(type);
        request.setStartDate(LocalDate.parse(start));
        return request;
    }

    private static SavingPlan existing(String type) {
        SavingPlan plan = new SavingPlan();
        plan.setType(type);
        return plan;
    }

    private static com.finview.entity.AssetSeries row(String code, String day, String amount, String fee) {
        com.finview.entity.AssetSeries row = new com.finview.entity.AssetSeries();
        row.setCode(code);
        row.setDay(LocalDate.parse(day));
        row.setAmount(new BigDecimal(amount));
        row.setFee(new BigDecimal(fee));
        return row;   // principal / total 只有末行会被读到，测试里按需 set
    }

    @Test
    @DisplayName("合法的生活保障 / 愿望：任何已有计划下都放行")
    void acceptsWishPlan() {
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), request("wish", "2026-10-05", "2027-10-05")));
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), request("life", "2026-10-05", "2027-10-05")));
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(
                List.of(existing("asset"), existing("life")), request("wish", "2026-10-05", "2027-10-05")));
    }

    @Test
    @DisplayName("总资产计划只能有一条：没有时可建，已有时报 400")
    void allowsOnlyOneAssetPlan() {
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), request("asset", "2026-10-05", "2030-12-31")));

        BusinessException ex = assertThrows(BusinessException.class, () -> SavingsService.validateNewPlan(
                List.of(existing("asset")), request("asset", "2026-10-05", "2030-12-31")));
        assertEquals(400, ex.getCode());
        assertEquals("已有总资产计划，只能新建生活保障或愿望", ex.getMessage());
    }

    @Test
    @DisplayName("未知类型报 400（不是 asset / wish 一律拒绝）")
    void rejectsUnknownType() {
        for (String bad : List.of("saving", "ASSET", "wishes", "", " ")) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> SavingsService.validateNewPlan(List.of(), request(bad, "2026-10-05", "2027-10-05")));
            assertEquals(400, ex.getCode());
        }
    }

    @Test
    @DisplayName("目标日期早于开始日期报 400；同一天放行")
    void rejectsTargetDateBeforeStartDate() {
        BusinessException ex = assertThrows(BusinessException.class, () -> SavingsService.validateNewPlan(
                List.of(), request("wish", "2026-10-05", "2026-10-04")));
        assertEquals("目标日期不能早于开始日期", ex.getMessage());

        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), request("wish", "2026-10-05", "2026-10-05")));
    }

    @Test
    @DisplayName("校验按「类型 → 唯一性 → 日期」的顺序，先撞上哪条报哪条")
    void reportsFirstViolation() {
        // 类型非法 + 已有总资产计划 + 日期倒挂：应报类型（说明短路在前，不会去 stream existing）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SavingsService.validateNewPlan(List.of(existing("asset")), request("bogus", "2026-10-05", "2020-01-01")));
        assertEquals("计划类型只能是 asset（总资产）、life（生活保障）或 wish（愿望）", ex.getMessage());
    }

    /* ==================== 计划里包含哪些资产（selectAssets） ==================== */

    private static com.finview.entity.Asset asset(long id, String code, boolean archived) {
        com.finview.entity.Asset asset = new com.finview.entity.Asset();
        asset.setId(id);
        asset.setCode(code);
        asset.setArchived(archived);
        return asset;
    }

    @Test
    @DisplayName("总资产计划：全部未归档资产都进，已归档的排除，关联集合不起作用")
    void assetPlanCoversEveryActiveAsset() {
        var all = List.of(asset(1, "000051", false), asset(2, "CASH", false), asset(3, "161725", true));
        var picked = SavingsService.selectAssets("asset", all, Set.of(1L));
        assertEquals(List.of("000051", "CASH"), picked.stream().map(a -> a.getCode()).toList());
    }

    @Test
    @DisplayName("生活保障 / 愿望：只收显式关联且未归档的，顺序跟入参（code 升序）一致")
    void wishPlanKeepsOnlyLinkedActiveAssets() {
        var all = List.of(asset(1, "000051", false), asset(2, "CASH", false), asset(3, "161725", true));
        // 关联了 3（已归档）和 2 → 只应留下 2
        var picked = SavingsService.selectAssets("wish", all, Set.of(2L, 3L));
        assertEquals(List.of("CASH"), picked.stream().map(a -> a.getCode()).toList());

        // 没有关联 → 空
        assertEquals(0, SavingsService.selectAssets("wish", all, Set.of()).size());
    }

    @Test
    @DisplayName("已归档资产无论哪种计划都不计入（归档 = 不看了）")
    void archivedAssetsNeverCount() {
        var all = List.of(asset(1, "000051", true));
        assertEquals(0, SavingsService.selectAssets("asset", all, Set.of()).size());
        assertEquals(0, SavingsService.selectAssets("wish", all, Set.of(1L)).size());
    }

    /* ==================== 目标金额可选（validateNewPlan 的新规则） ==================== */

    @Test
    @DisplayName("生活保障 / 愿望可以不设目标：目标金额/日期/每月存入都为空也放行")
    void allowsTargetlessWish() {
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), targetless("wish", "2026-10-05")));
    }

    @Test
    @DisplayName("总资产计划必须设目标（不设它就只是另一个「我的资产」页）")
    void rejectsTargetlessAssetPlan() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SavingsService.validateNewPlan(List.of(), targetless("asset", "2026-10-05")));
        assertEquals(400, ex.getCode());
        assertEquals("总资产计划需要设定目标金额", ex.getMessage());
    }

    @Test
    @DisplayName("设了目标金额就必须有目标日期和每月计划投入")
    void requiresDateAndMonthlyWhenTargetPresent() {
        SavingsPlanRequest noDate = request("wish", "2026-10-05", "2027-10-05");
        noDate.setTargetDate(null);
        assertEquals("设了目标金额就要填目标日期",
                assertThrows(BusinessException.class, () -> SavingsService.validateNewPlan(List.of(), noDate)).getMessage());

        SavingsPlanRequest noMonthly = request("wish", "2026-10-05", "2027-10-05");
        noMonthly.setMonthlyPlanAmount(null);
        assertEquals("设了目标金额就要填每月计划投入",
                assertThrows(BusinessException.class, () -> SavingsService.validateNewPlan(List.of(), noMonthly)).getMessage());
    }

    @Test
    @DisplayName("不设目标时，传了目标日期也不校验顺序（会被清成 null，不参与判断）")
    void ignoresTargetDateWhenNoTarget() {
        SavingsPlanRequest weird = targetless("wish", "2027-01-01");
        weird.setTargetDate(LocalDate.parse("2020-01-01"));   // 早于开始日期，但没有目标金额 → 忽略
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), weird));
    }

    /* ==================== 组内收益率口径（returnStats） ==================== */

    private static com.finview.entity.AssetSeries last(String code, String day, String amount, String fee,
                                                       String principal, String total) {
        com.finview.entity.AssetSeries row = row(code, day, amount, fee);
        row.setPrincipal(new BigDecimal(principal));
        row.setTotal(new BigDecimal(total));
        return row;
    }

    @Test
    @DisplayName("returnStats：逐只累加累计投入与手续费，本金/市值取每只末行")
    void sumsPerAsset() {
        var assets = List.of(asset(1, "000051", false), asset(2, "161725", false));
        var rows = List.of(
                row("000051", "2026-01-01", "1000", "1.2"),
                last("000051", "2026-02-01", "500", "0.6", "1500", "1600"),
                last("161725", "2026-01-15", "2000", "0", "3000", "3300"));

        var stats = SavingsService.returnStats(assets, rows, java.util.Map.of());
        // 累计投入 = (1000+1.2) + (500+0.6) + 2000 = 3501.8（手续费也算出资）
        assertEquals(0, new BigDecimal("3501.8").compareTo(stats.getInvested()));
        // 手续费 = 1.2 + 0.6 + 0 = 1.8（逐日累加，不是只取末行）
        assertEquals(0, new BigDecimal("1.8").compareTo(stats.getFee()));
        // 本金 = 两只各自的末行 principal 之和；市值 = 两只末行 total 之和
        assertEquals(0, new BigDecimal("4500").compareTo(stats.getPrincipal()));
        assertEquals(0, new BigDecimal("4900").compareTo(stats.getCurrent()));
    }

    @Test
    @DisplayName("returnStats：卖出日不增加累计投入（max(0)），本金可为负（清过仓）")
    void sellDaysDoNotAddInvested() {
        var assets = List.of(asset(1, "000051", false));
        var rows = List.of(
                row("000051", "2026-01-01", "1000", "1"),
                last("000051", "2026-03-01", "-1200", "0", "-200", "0"));   // 清仓卖出

        var stats = SavingsService.returnStats(assets, rows, java.util.Map.of());
        assertEquals(0, new BigDecimal("1001").compareTo(stats.getInvested()));
        assertEquals(0, new BigDecimal("-200").compareTo(stats.getPrincipal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(stats.getCurrent()));
    }

    @Test
    @DisplayName("returnStats：现金四个数一起排除（否则收益会凭空多出一整笔现金）")
    void skipsCashInAllFourFields() {
        var assets = List.of(asset(1, "000051", false), asset(2, "CASH", false));
        var rows = List.of(last("000051", "2026-01-01", "1000", "0", "1000", "1200"));
        // 现金的市值在 latestTotals 里是 5000，但收益率这一组不能碰它
        var stats = SavingsService.returnStats(assets, rows, java.util.Map.of("CASH", new BigDecimal("5000")));

        assertEquals(0, new BigDecimal("1200").compareTo(stats.getCurrent()));
        assertEquals(0, new BigDecimal("1000").compareTo(stats.getPrincipal()));
        assertEquals(0, new BigDecimal("1000").compareTo(stats.getInvested()));

        // 只有现金 → 四个数都是 0（前端 returnRate 对 0 分母返回 0，UI 显示「—」）
        var cashOnly = SavingsService.returnStats(List.of(asset(2, "CASH", false)), List.of(),
                java.util.Map.of("CASH", new BigDecimal("5000")));
        assertEquals(0, BigDecimal.ZERO.compareTo(cashOnly.getInvested()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cashOnly.getFee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cashOnly.getPrincipal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cashOnly.getCurrent()));
    }

    @Test
    @DisplayName("returnStats：没有序列行的资产退回 asset 快照（本金 / 市值），手续费按 0")
    void assetsWithoutSeriesFallBackToSnapshot() {
        var bare = asset(1, "000051", false);
        bare.setPrincipal(new BigDecimal("800"));
        bare.setTotal(new BigDecimal("900"));

        var stats = SavingsService.returnStats(List.of(bare), List.of(),
                java.util.Map.of("000051", new BigDecimal("900")));
        assertEquals(0, new BigDecimal("900").compareTo(stats.getCurrent()));
        assertEquals(0, new BigDecimal("800").compareTo(stats.getPrincipal()));
        assertEquals(0, new BigDecimal("800").compareTo(stats.getInvested()));
        assertEquals(0, BigDecimal.ZERO.compareTo(stats.getFee()));
    }
}
