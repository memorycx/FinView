package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.controller.dto.SavingsAssetResponse;
import com.finview.controller.dto.SavingsInflowForecastResponse;
import com.finview.controller.dto.SavingsPlanRequest;
import com.finview.entity.AssetPlanLink;
import com.finview.entity.SavingPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("类型必须在该用户的类型表里：内置与自定义都能过，表外的（含大小写不同/空值）报 400")
    void requireKnownType() {
        Set<String> known = Set.of("asset", "life", "wish", "c8f3a1b2");
        // 内置三种与自定义 code 都合法
        assertDoesNotThrow(() -> SavingsService.requireKnownType(known, "asset"));
        assertDoesNotThrow(() -> SavingsService.requireKnownType(known, "c8f3a1b2"));

        for (String bad : List.of("saving", "ASSET", "wishes", "")) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> SavingsService.requireKnownType(known, bad), bad);
            assertEquals(400, ex.getCode());
        }
        // null 也拒绝（请求没带 type）
        assertThrows(BusinessException.class, () -> SavingsService.requireKnownType(known, null));
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
        // 类型必须是用户类型表里的 code，这一步在 create() 里（要查库），所以单独测：
        // 类型不在表里时先报类型，不会继续往下走到唯一性 / 日期
        BusinessException badType = assertThrows(BusinessException.class,
                () -> SavingsService.requireKnownType(Set.of("asset", "life", "wish"), "bogus"));
        assertEquals("计划类型不存在，请先在类型里新建：bogus", badType.getMessage());

        // 类型合法时，唯一性先于日期：已有总资产计划 + 日期倒挂 → 报唯一性
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SavingsService.validateNewPlan(List.of(existing("asset")), request("asset", "2026-10-05", "2020-01-01")));
        assertEquals("已有总资产计划，只能新建生活保障或愿望", ex.getMessage());
    }

    /* ==================== 资产只能属于一个具体分类（排他） ==================== */

    @Test
    @DisplayName("排他：资产已经在别的计划里 → 400；没有归属、或就是本计划 → 放行")
    void requiresAssetNotInOtherPlan() {
        AssetPlanLink inOther = new AssetPlanLink();
        inOther.setPlanId(9L);
        inOther.setPlanName("养老金");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SavingsService.requireAssetNotInOtherPlan(14L, "华夏沪深300", inOther));
        assertEquals(400, ex.getCode());
        assertEquals("「华夏沪深300」已经在「养老金」里了，一个资产只能属于一个分类", ex.getMessage());

        // 没有归属（null）→ 放行
        assertDoesNotThrow(() -> SavingsService.requireAssetNotInOtherPlan(14L, "华夏沪深300", null));

        // 就是本计划（重复关联，幂等）→ 也放行
        AssetPlanLink inSelf = new AssetPlanLink();
        inSelf.setPlanId(14L);
        inSelf.setPlanName("养老金");
        assertDoesNotThrow(() -> SavingsService.requireAssetNotInOtherPlan(14L, "华夏沪深300", inSelf));
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
    @DisplayName("设了目标金额就必须有每月计划投入；目标日期选填")
    void requiresMonthlyWhenTargetPresent() {
        // 目标日期不填是合法的：create() 会按每月存入自适应推算
        SavingsPlanRequest noDate = request("wish", "2026-10-05", "2027-10-05");
        noDate.setTargetDate(null);
        assertDoesNotThrow(() -> SavingsService.validateNewPlan(List.of(), noDate));

        SavingsPlanRequest noMonthly = request("wish", "2026-10-05", "2027-10-05");
        noMonthly.setMonthlyPlanAmount(null);
        assertEquals("设了目标金额就要填每月计划投入",
                assertThrows(BusinessException.class, () -> SavingsService.validateNewPlan(List.of(), noMonthly)).getMessage());
    }

    @Test
    @DisplayName("目标日期自适应推算：按每月存入向上取整，从开始日期往后推；一个月内能存完也推 1 个月")
    void estimatedTargetDate() {
        // 10000 ÷ 3000 = 3.33 → 4 个月
        assertEquals(LocalDate.parse("2027-02-05"), SavingsService.estimatedTargetDate(
                LocalDate.parse("2026-10-05"), new BigDecimal("10000"), new BigDecimal("3000")));
        // 8000 ÷ 800 = 10 个月，正好整除
        assertEquals(LocalDate.parse("2027-08-05"), SavingsService.estimatedTargetDate(
                LocalDate.parse("2026-10-05"), new BigDecimal("8000"), new BigDecimal("800")));
        // 目标比每月存入还小 → 至少推 1 个月（推 0 会得到"当天就该完成"）
        assertEquals(LocalDate.parse("2026-11-05"), SavingsService.estimatedTargetDate(
                LocalDate.parse("2026-10-05"), new BigDecimal("500"), new BigDecimal("3000")));
        // 跨年 + 每月 31 号这类边界由 plusMonths 自己处理（10-31 + 4 个月 → 2 月只有 28 天，取 02-28）
        assertEquals(LocalDate.parse("2027-02-28"), SavingsService.estimatedTargetDate(
                LocalDate.parse("2026-10-31"), new BigDecimal("4000"), new BigDecimal("1000")));
        // 每月存入填得极小也不会推出天文数字年份（上限 100 年）
        assertEquals(LocalDate.parse("2126-10-05"), SavingsService.estimatedTargetDate(
                LocalDate.parse("2026-10-05"), new BigDecimal("100000"), new BigDecimal("0.01")));
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

    @Test
    @DisplayName("returnStatsByCode：逐只分开给（现金不在内），合并回来与 returnStats 完全一致")
    void perAssetStatsMatchesAggregate() {
        var assets = List.of(asset(1, "000051", false), asset(2, "161725", false), asset(3, "CASH", false));
        var rows = List.of(
                last("000051", "2026-02-01", "500", "0.6", "1500", "1600"),
                last("161725", "2026-01-15", "2000", "0", "3000", "3300"));
        var totals = java.util.Map.of("CASH", new BigDecimal("5000"));

        var byCode = SavingsService.returnStatsByCode(assets, rows, totals);
        // 现金没有四件套（详情接口里它就是 null，前端显示「—」）
        assertEquals(Set.of("000051", "161725"), byCode.keySet());

        var fund = byCode.get("000051");
        assertEquals(0, new BigDecimal("1600").compareTo(fund.getCurrent()));
        assertEquals(0, new BigDecimal("1500").compareTo(fund.getPrincipal()));
        assertEquals(0, new BigDecimal("0.6").compareTo(fund.getFee()));
        assertEquals(0, new BigDecimal("500.6").compareTo(fund.getInvested()));

        // 详情接口整体那份走的就是「逐只算完再相加」，两边必须一字不差
        var sum = SavingsService.sumStats(byCode);
        var aggregate = SavingsService.returnStats(assets, rows, totals);
        assertEquals(0, aggregate.getCurrent().compareTo(sum.getCurrent()));
        assertEquals(0, aggregate.getPrincipal().compareTo(sum.getPrincipal()));
        assertEquals(0, aggregate.getFee().compareTo(sum.getFee()));
        assertEquals(0, aggregate.getInvested().compareTo(sum.getInvested()));
    }

    /* ==================== 计划详情：定投标记 + 未来流入 ==================== */

    @Test
    @DisplayName("applyDca：定投中的排前面，组内保持原顺序，颜色跟着资产走")
    void applyDcaSortsActiveFirst() {
        var a = assetResponse("A", "--chart-1");
        var b = assetResponse("B", "--chart-3");
        var c = assetResponse("C", "--chart-4");
        var d = assetResponse("D", "--chart-5");
        var assets = new java.util.ArrayList<>(List.of(a, b, c, d));

        var active = new CashFlowService.PlanDca(true, "weekly", new BigDecimal("100"),
                Map.of(YearMonth.of(2026, 11), new BigDecimal("100")));
        var inactive = new CashFlowService.PlanDca(false, null, null, Map.of());
        SavingsService.applyDca(assets, Map.of("B", active, "D", active, "A", inactive, "C", inactive));

        assertEquals(List.of("B", "D", "A", "C"),
                assets.stream().map(SavingsAssetResponse::getCode).toList());
        // 颜色在 toAssets 里按原顺序分配，排序后仍跟着资产走
        assertEquals("--chart-3", assets.get(0).getColorVar());
        assertEquals("--chart-5", assets.get(1).getColorVar());
        assertTrue(assets.get(0).getDcaActive());
        assertNull(assets.get(2).getDcaFrequency());
    }

    @Test
    @DisplayName("buildForecast：只把有金额的资产放进图例，12 个月全下发，byAsset 不放 0")
    void buildsInflowForecast() {
        var a = assetResponse("A", "--chart-1");
        var b = assetResponse("B", "--chart-3");
        var c = assetResponse("C", "--chart-4");
        var assets = List.of(a, b, c);

        Map<YearMonth, BigDecimal> aOutlay = new LinkedHashMap<>();
        aOutlay.put(YearMonth.of(2026, 11), new BigDecimal("100"));
        aOutlay.put(YearMonth.of(2026, 12), new BigDecimal("200"));
        var aDca = new CashFlowService.PlanDca(true, "weekly", new BigDecimal("100"), aOutlay);
        var bDca = new CashFlowService.PlanDca(false, null, null, Map.of());
        // C 今天还没开始定投，但窗口内有未来扣款 —— 也要进图例
        var cDca = new CashFlowService.PlanDca(false, null, null,
                Map.of(YearMonth.of(2027, 1), new BigDecimal("300")));

        var forecast = SavingsService.buildForecast(assets,
                Map.of("A", aDca, "B", bDca, "C", cDca),
                LocalDate.parse("2026-11-01"), LocalDate.parse("2027-10-31"));

        assertEquals("2026-11", forecast.getFromMonth());
        assertEquals("2027-10", forecast.getToMonth());
        assertEquals(12, forecast.getMonths().size());
        // B 没有金额，不进图例；C 未来才开始，要进去
        assertEquals(List.of("A", "C"), forecast.getAssets().stream()
                .map(SavingsInflowForecastResponse.AssetResponse::getCode).toList());

        var november = forecast.getMonths().get(0);
        assertEquals("2026-11", november.getMonth());
        assertEquals(0, new BigDecimal("100").compareTo(november.getTotal()));
        assertEquals(0, new BigDecimal("100").compareTo(november.getByAsset().get("A")));
        assertNull(november.getByAsset().get("C"));

        var january = forecast.getMonths().get(2);
        assertEquals(0, new BigDecimal("300").compareTo(january.getByAsset().get("C")));
        assertEquals(0, new BigDecimal("300").compareTo(january.getTotal()));

        var february = forecast.getMonths().get(3);
        assertEquals(0, BigDecimal.ZERO.compareTo(february.getTotal()));
        assertTrue(february.getByAsset().isEmpty());
    }

    private static SavingsAssetResponse assetResponse(String code, String colorVar) {
        return new SavingsAssetResponse(code, code, code, BigDecimal.ZERO, null, colorVar,
                null, null, null);
    }
}
