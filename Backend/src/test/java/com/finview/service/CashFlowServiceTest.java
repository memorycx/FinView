package com.finview.service;

import com.finview.entity.Adjustment;
import com.finview.service.CashFlowService.Forecast;
import com.finview.service.CashFlowService.MonthPoint;
import com.finview.service.CashFlowService.PlanDca;
import com.finview.service.SeriesService.PlanState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定投分析（CashFlowService.project）的核心算法测试。
 *
 * 全部走真引擎：手搓 adjustments → SeriesService.fold 推进到窗口末月 → project 汇总，
 * 所以这里同时钉住了「fold 吃未来日期」与「按实际扣款日归月」两件事。
 *
 * 固定 today = 2026-10-13，窗口 = 2026-11-01 ~ 2027-10-31（365 天，从周日到周日）。
 * 365 = 52 周 + 1 天（多出来的那天是周日），所以「周一 ~ 周五」日历下
 * 工作日数 = 52 × 5 = 260、周一数 = 52。
 */
class CashFlowServiceTest {

    private static final long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.parse("2026-10-13");

    /** 计划都从窗口之前就开着，避免开头几天的等待影响计数 */
    private static final String PLAN_START = "2026-01-01";

    /** 不关心交易日的用例用这个：每天都能扣 */
    private static final Predicate<LocalDate> EVERY_DAY = day -> true;

    /** 「周一 ~ 周五」退化规则（= 交易日历没加载时的判断） */
    private static final Predicate<LocalDate> WEEKDAYS = CashFlowServiceTest::isWeekday;

    /* ==================== 窗口与四个数 ==================== */

    @Test
    @DisplayName("窗口 = 下月起 12 个整月；四个数自洽：现金 + 12×新增 − 12×总额 = 12个月后")
    void windowAndReconciliation() {
        // 每日 10、每天都是交易日：365 天 × 10 = 3650
        Forecast forecast = project(1000, "3000", fold(monthlyEvents("daily", "10"), EVERY_DAY));

        assertEquals(YearMonth.of(2026, 11), forecast.fromMonth());
        assertEquals(YearMonth.of(2027, 10), forecast.toMonth());
        assertEquals(12, forecast.months().size());
        assertValue("3650", forecast.totalOutlay());
        // 3650 / 12 = 304.1666… → 304.17
        assertValue("304.17", forecast.monthlyOutlay());
        // 1000 + 12 × 3000 − 12 × 304.17 = 33349.96
        assertValue("33349.96", forecast.projectedCash());
        // 逐月真累加的末月是 1000 + 36000 − 3650 = 33350，与上面只差 4 分（≤ 6 分，收在末月）
        assertTrue(forecast.projectedCash().subtract(new BigDecimal("33350")).abs()
                        .compareTo(new BigDecimal("0.06")) <= 0,
                "误差应该收在 6 分以内，实际 " + forecast.projectedCash());

        // 卡片上的四则运算必须精确成立，而且「12 个月后」= 末月余额
        BigDecimal cardMath = forecast.currentCash()
                .add(forecast.monthlyInflow().multiply(new BigDecimal("12")))
                .subtract(forecast.monthlyOutlay().multiply(new BigDecimal("12")));
        assertEquals(0, cardMath.compareTo(forecast.projectedCash()));
        assertEquals(0, forecast.months().get(11).endBalance().compareTo(forecast.projectedCash()));
    }

    @Test
    @DisplayName("窗口端点就是下月起 12 个整月（跨年边界）")
    void windowHelpers() {
        assertEquals(LocalDate.parse("2026-11-01"), CashFlowService.windowStart(TODAY));
        assertEquals(LocalDate.parse("2027-10-31"), CashFlowService.windowEnd(TODAY));
        // 12 月中看窗口：首月是次年 1 月、末月是再下一年的 12 月
        assertEquals(LocalDate.parse("2027-01-01"), CashFlowService.windowStart(LocalDate.parse("2026-12-31")));
        assertEquals(LocalDate.parse("2027-12-31"), CashFlowService.windowEnd(LocalDate.parse("2026-12-31")));
    }

    @Test
    @DisplayName("当月（10 月）的周期点与窗口前的扣款都不计入")
    void currentMonthExcluded() {
        // 10/1 起投每月 1 日：10/1 那笔在窗口外，窗口内 12 个月各扣一笔
        Forecast forecast = project(0, "0", fold(
                List.of(event(1, "M", "2026-10-01", 1, "monthly", "100")), EVERY_DAY));

        assertValue("1200", forecast.totalOutlay());
        assertEquals(YearMonth.of(2026, 11), forecast.months().get(0).month());
        assertValue("100", forecast.months().get(0).outlay());
    }

    /* ==================== 扣款节奏 ==================== */

    @Test
    @DisplayName("每日定投遇非交易日跳过（不像周月投那样补扣）：只数工作日")
    void dailySkipsNonTradingDays() {
        Forecast forecast = project(0, "0", fold(monthlyEvents("daily", "10"), WEEKDAYS));

        // 窗口 365 天 = 52 周 + 1 天（多出来的那天是周日），工作日 = 52 × 5 = 260
        assertValue("2600", forecast.totalOutlay());
    }

    @Test
    @DisplayName("每周一定投：周一遇节假日顺延到下一个交易日，仍记在顺延后那一天")
    void weeklyPostponesToNextTradingDay() {
        // 2026-11-09（周一）休市 → 顺延到 11-10（周二），钱还是 11 月走的
        LocalDate holiday = LocalDate.parse("2026-11-09");
        Predicate<LocalDate> calendar = day -> isWeekday(day) && !day.equals(holiday);

        Forecast forecast = project(0, "0", fold(monthlyEvents("weekly", "10"), calendar));

        // 窗口内 52 个周一，那一个顺延后照样扣；11 月有 5 个周一 = 50
        assertValue("520", forecast.totalOutlay());
        assertValue("50", forecast.months().get(0).outlay());
    }

    @Test
    @DisplayName("每月 1 日扣款：1 日非交易日顺延，仍落在当月")
    void monthlyDeductsOnFirstTradingDay() {
        // 2026-11-01 是周日 → 11-02 扣；2027-05-01 是周六 → 05-03 扣；都还在当月
        Forecast forecast = project(0, "0", fold(monthlyEvents("monthly", "100"), WEEKDAYS));

        assertValue("1200", forecast.totalOutlay());
        for (MonthPoint month : forecast.months()) {
            assertValue("100", month.outlay());
        }
    }

    @Test
    @DisplayName("顺延跨月：整月无交易日的极端日历下，11 月那笔与 12 月那笔合并记在 12 月")
    void postponementCanSpillIntoNextMonth() {
        // 11 月整月不交易、12/1 才恢复：11/1 的周期点顺延，与 12/1 自己那笔一起在 12/1 扣
        Predicate<LocalDate> calendar = day -> day.isAfter(LocalDate.parse("2026-11-30"));

        Forecast forecast = project(0, "0", fold(
                List.of(event(1, "M", "2026-11-01", 1, "monthly", "100")), calendar));

        assertValue("0", forecast.months().get(0).outlay());      // 11 月一分没扣
        assertValue("200", forecast.months().get(1).outlay());    // 两笔并记在 12 月
        assertValue("1200", forecast.totalOutlay());
    }

    @Test
    @DisplayName("结束定投后不再扣款：停止当天仍算一个周期点，顺延中的不补")
    void stopEndsDeductions() {
        List<Adjustment> events = List.of(
                event(1, "D", "2026-11-01", 1, "daily", "10"),
                event(2, "D", "2026-11-10", 2, null, "10"));

        Forecast forecast = project(0, "0", fold(events, EVERY_DAY));

        // 11/1 ~ 11/10 共 10 天（结束当天照扣）
        assertValue("100", forecast.totalOutlay());
        assertValue("100", forecast.months().get(0).outlay());
        assertValue("0", forecast.months().get(1).outlay());
    }

    /* ==================== 费率：手续费从扣款金额里扣 ==================== */

    @Test
    @DisplayName("支出 = amount + fee 恒等于配置金额：费率从 0 改成 10 总支出不变")
    void feeIsCarvedOutOfAmount() {
        Forecast noFee = project(0, "0", fold(monthlyEvents("daily", "10"), EVERY_DAY));
        Forecast withFee = project(0, "0", fold(monthlyEvents("daily", "10"),
                Map.of("D", new BigDecimal("10")), EVERY_DAY));

        assertValue("3650", noFee.totalOutlay());
        assertValue("3650", withFee.totalOutlay());
    }

    @Test
    @DisplayName("窗口内的买入 / 卖出记录不算定投支出（只保留 action=1/2 再 fold）")
    void discreteFlowsAreNotOutlay() {
        List<Adjustment> events = List.of(
                event(1, "D", "2026-11-01", 1, "daily", "10"),
                event(2, "D", "2026-11-05", 3, null, "500"),     // 买入
                event(3, "D", "2026-11-20", 4, null, "200"));    // 卖出

        Forecast forecast = project(0, "0",
                fold(CashFlowService.dcaEvents(events), Map.of("D", new BigDecimal("0.12")), EVERY_DAY));

        // 365 天 × 10 = 3650；买入的 500 / 卖出的 200 都不进柱子
        assertValue("3650", forecast.totalOutlay());
    }

    /* ==================== 未设置 / 无计划 / QDII ==================== */

    @Test
    @DisplayName("每月固定新增为 null：按 0 参与计算（响应里保持 null），余额只降不升")
    void nullInflowFallsBackToZero() {
        Forecast forecast = project(1000, null, fold(monthlyEvents("daily", "10"), EVERY_DAY));

        assertNull(forecast.monthlyInflow());
        assertValue("0", forecast.months().get(0).inflow());
        assertValue("304.17", forecast.monthlyOutlay());
        assertValue("-2650.04", forecast.projectedCash());   // 1000 − 12 × 304.17
        assertEquals(0, forecast.months().get(11).endBalance().compareTo(forecast.projectedCash()));
    }

    @Test
    @DisplayName("没有定投计划：每月支出 0，余额 = 现金 + 每月新增逐月累加")
    void noPlansMeansFlatGrowth() {
        Forecast forecast = project(1000, "1500", Map.of());

        assertValue("0", forecast.monthlyOutlay());
        assertValue("0", forecast.totalOutlay());
        assertValue("19000", forecast.projectedCash());      // 1000 + 12 × 1500
        assertValue("2500", forecast.months().get(0).endBalance());
        assertValue("19000", forecast.months().get(11).endBalance());
    }

    @Test
    @DisplayName("QDII：交易日判断按 code 区分，标的市场休市日不扣款")
    void marketCalendarIsPerCode() {
        List<Adjustment> events = List.of(
                event(1, "A", "2026-11-01", 1, "daily", "10"),
                event(2, "B", "2026-11-01", 1, "daily", "10"));

        // A 按工作日扣 260 天；B 的标的市场窗口内一天都不开（极端，只为验证日历是按 code 取的）
        Function<String, Predicate<LocalDate>> calendar = code -> {
            if ("A".equals(code)) {
                return WEEKDAYS;
            }
            return day -> false;
        };

        Forecast forecast = project(0, "0", fold(events, Map.of(), calendar));

        assertValue("2600", forecast.totalOutlay());
    }

    /* ==================== 单月覆盖（点柱状图的柱子） ==================== */

    @Test
    @DisplayName("单月覆盖：只改那一个月，其余月份仍跟默认值走，四个数按新增合计算")
    void overrideChangesOneMonthOnly() {
        // 默认 3000，把 2026-12 单独改成 8000；每日 10、每天都是交易日
        Forecast forecast = project(1000, "3000", Map.of("2026-12", new BigDecimal("8000")),
                fold(monthlyEvents("daily", "10"), EVERY_DAY));

        assertValue("8000", forecast.months().get(1).inflow());
        assertTrue(forecast.months().get(1).overridden());
        assertValue("3000", forecast.months().get(0).inflow());
        assertFalse(forecast.months().get(0).overridden());
        assertValue("41000", forecast.totalInflow());          // 11 × 3000 + 8000

        // 逐月真累加：11 月 = 1000 + 3000 − 300（30 天 × 10）；12 月 = 3700 + 8000 − 310
        assertValue("3700", forecast.months().get(0).endBalance());
        assertValue("11390", forecast.months().get(1).endBalance());

        // 卡片算式（通用形式）：现金 + 新增合计 − 12 × 每月定投总额 = 12 个月后
        assertValue("38349.96", forecast.projectedCash());      // 1000 + 41000 − 12 × 304.17
        assertEquals(0, forecast.currentCash().add(forecast.totalInflow())
                .subtract(forecast.monthlyOutlay().multiply(new BigDecimal("12")))
                .compareTo(forecast.projectedCash()));
        assertEquals(0, forecast.months().get(11).endBalance().compareTo(forecast.projectedCash()));
    }

    @Test
    @DisplayName("覆盖成 0 与没覆盖是两回事：0 = 这个月收入为零，没覆盖 = 跟默认值走")
    void overrideZeroIsNotDefault() {
        Forecast forecast = project(0, "3000", Map.of("2026-12", BigDecimal.ZERO),
                fold(monthlyEvents("daily", "10"), EVERY_DAY));

        assertValue("0", forecast.months().get(1).inflow());
        assertTrue(forecast.months().get(1).overridden());
        assertValue("3000", forecast.months().get(0).inflow());
        assertFalse(forecast.months().get(0).overridden());
        assertValue("33000", forecast.totalInflow());
    }

    @Test
    @DisplayName("没有默认值时单月覆盖照样生效：只有被覆盖的那个月有新增")
    void overrideWorksWithoutBase() {
        Forecast forecast = project(500, null, Map.of("2027-01", new BigDecimal("1200")),
                fold(monthlyEvents("daily", "10"), EVERY_DAY));

        assertNull(forecast.monthlyInflow());
        assertValue("1200", forecast.months().get(2).inflow());
        assertValue("0", forecast.months().get(0).inflow());
        assertValue("1200", forecast.totalInflow());
        assertValue("-1950.04", forecast.projectedCash());      // 500 + 1200 − 12 × 304.17
    }

    /* ==================== 计划详情：按 code 投影 ==================== */

    @Test
    @DisplayName("按 code 投影：逐只按月拆分，金额 = amount + fee（费率不改变柱子高度）")
    void projectByCodeSplitsAssets() {
        // D 每日 10、费率 10%（net 9 + fee 1）；W 每周一 100；X 只是一笔买入（action=3），不算定投
        List<Adjustment> events = List.of(
                event(1, "D", PLAN_START, 1, "daily", "10"),
                event(2, "W", PLAN_START, 1, "weekly", "100"),
                event(3, "X", "2026-11-02", 3, null, "500"));

        Map<String, PlanDca> dca = CashFlowService.projectByCode(USER_ID, events,
                Map.of("D", new BigDecimal("10")), code -> EVERY_DAY,
                TODAY, CashFlowService.windowStart(TODAY), CashFlowService.windowEnd(TODAY));

        assertEquals(Set.of("D", "W"), dca.keySet());
        PlanDca daily = dca.get("D");
        assertTrue(daily.active());
        assertEquals("daily", daily.frequency());
        assertValue("10", daily.amount());
        // 11 月 30 天 × 10；费率只是把 10 拆成 net 9 + fee 1，柱子仍是 10
        assertValue("300", daily.outlayByMonth().get(YearMonth.of(2026, 11)));
        assertValue("3650", total(daily.outlayByMonth()));

        PlanDca weekly = dca.get("W");
        assertTrue(weekly.active());
        // 2026-11 有 5 个周一（2/9/16/23/30）
        assertValue("500", weekly.outlayByMonth().get(YearMonth.of(2026, 11)));
        // 窗口 365 天：52 个周一
        assertValue("5200", total(weekly.outlayByMonth()));
    }

    @Test
    @DisplayName("按 code 投影：今天状态只吃 <= today 的事件；未来开始/结束仍计入窗口")
    void projectByCodeSplitsNowAndFuture() {
        List<Adjustment> events = List.of(
                // A：每月 1 日 100，12/31 结束 —— 今天在投，窗口内 11/12 月各一笔
                event(1, "A", "2026-09-01", 1, "monthly", "100"),
                event(2, "A", "2026-12-31", 2, null, "100"),
                // B：12/1 才开始（未来）—— 今天不在投，但窗口内从 12 月起有扣款
                event(3, "B", "2026-12-01", 1, "daily", "10"),
                // C：9 月就结束了 —— 今天不在投，窗口内没有扣款
                event(4, "C", "2026-09-01", 1, "daily", "10"),
                event(5, "C", "2026-09-30", 2, null, "10"),
                // D：同一天开始又结束 —— fold 的停投在后面，今天不在投
                event(6, "D", "2026-09-01", 1, "daily", "10"),
                event(7, "D", "2026-09-01", 2, null, "10"),
                // E：CASH 的历史 action=1 也不能当定投
                event(8, "CASH", PLAN_START, 1, "daily", "10"));

        Map<String, PlanDca> dca = CashFlowService.projectByCode(USER_ID, events, Map.of(),
                code -> EVERY_DAY, TODAY, CashFlowService.windowStart(TODAY),
                CashFlowService.windowEnd(TODAY));

        PlanDca a = dca.get("A");
        assertTrue(a.active());
        assertValue("100", a.outlayByMonth().get(YearMonth.of(2026, 11)));
        assertValue("100", a.outlayByMonth().get(YearMonth.of(2026, 12)));
        assertNull(a.outlayByMonth().get(YearMonth.of(2027, 1)));

        PlanDca b = dca.get("B");
        assertFalse(b.active());
        assertNull(b.outlayByMonth().get(YearMonth.of(2026, 11)));
        assertValue("310", b.outlayByMonth().get(YearMonth.of(2026, 12)));
        // 12/1 起共 335 天（窗口 365 天 − 11 月 30 天）
        assertValue("3350", total(b.outlayByMonth()));

        PlanDca c = dca.get("C");
        assertFalse(c.active());
        assertTrue(c.outlayByMonth().isEmpty());

        PlanDca d = dca.get("D");
        assertFalse(d.active());
        assertTrue(d.outlayByMonth().isEmpty());

        assertFalse(dca.containsKey("CASH"));
    }

    @Test
    @DisplayName("按 code 投影：没有 1/2 事件返回空 map（fold 不能吃空列表）")
    void projectByCodeEmpty() {
        assertTrue(CashFlowService.projectByCode(USER_ID, List.of(), Map.of(), code -> EVERY_DAY,
                TODAY, CashFlowService.windowStart(TODAY),
                CashFlowService.windowEnd(TODAY)).isEmpty());
    }

    /* ==================== 定投日历 ==================== */

    @Test
    @DisplayName("日历：按天汇总金额与执行计划数，总览四数与每周分布都对得上")
    void calendarAggregatesDaysAndFunds() {
        // 每日 ¥10 的 D + 每周一 ¥10 的 W，2026-11（30 天，11/1 是周日）
        List<Adjustment> events = List.of(
                event(1, "D", PLAN_START, 1, "daily", "10"),
                event(2, "W", PLAN_START, 1, "weekly", "10"));

        CashFlowService.Calendar calendar = calendar("2026-11", events, Map.of("D", "电池", "W", "白酒"), EVERY_DAY);

        assertEquals(30, calendar.days().size());
        // 周一（11/2）两笔 = 20，其余天一笔 = 10；11/1 是周日，只有 D 扣
        assertValue("20", dayAmount(calendar, "2026-11-02"));
        assertEquals(2, dayPlans(calendar, "2026-11-02"));
        assertValue("10", dayAmount(calendar, "2026-11-01"));
        assertEquals(1, dayPlans(calendar, "2026-11-01"));

        // 总览：30 天 × 10 + 5 个周一 × 10 = 350；执行 30 天、2 只基金、日均 11.67
        assertValue("350", calendar.total());
        assertEquals(30, calendar.executedDays());
        assertEquals(2, calendar.fundCount());
        assertValue("11.67", calendar.dailyAverage());

        // 每周分布（下标 0 = 周一）：周一 5 天 × 20 = 100；周日 5 天 × 10 = 50；其余 4 天 × 10 = 40
        assertValue("100", calendar.weekdays().get(0));
        assertValue("50", calendar.weekdays().get(6));
        assertValue("40", calendar.weekdays().get(3));

        // 基金列表按金额降序，配色轮转与存钱计划同一套
        assertEquals(2, calendar.funds().size());
        assertEquals("D", calendar.funds().get(0).code());
        assertEquals("电池", calendar.funds().get(0).name());
        assertValue("300", calendar.funds().get(0).amount());
        assertValue("0.8571", calendar.funds().get(0).share());
        assertEquals("--chart-1", calendar.funds().get(0).colorVar());
        assertEquals("--chart-3", calendar.funds().get(1).colorVar());

        // 年度统计：D 每天扣（365 天）+ W 每 52 个周一 → 执行天数 = 365（每天至少有 D）、2 只基金
        assertValue("4170", calendar.yearTotal());          // 3650 + 520
        assertEquals(365, calendar.yearExecutedDays());
        assertEquals(2, calendar.yearFundCount());
        assertValue("11.42", calendar.yearDailyAverage());  // 4170 ÷ 365
    }

    @Test
    @DisplayName("日历：每天带各基金明细（点某一天右侧列表用），按金额降序；不扣款的日子是空数组")
    void calendarDayDetailListsFunds() {
        // D 每日 ¥10、W 每周一 ¥10、X 每日 ¥30
        List<Adjustment> events = List.of(
                event(1, "D", PLAN_START, 1, "daily", "10"),
                event(2, "W", PLAN_START, 1, "weekly", "10"),
                event(3, "X", PLAN_START, 1, "daily", "30"));

        CashFlowService.Calendar calendar = calendar("2026-11", events, Map.of(), EVERY_DAY);

        // 周一（11/2）三只：X 30 > D 10 / W 10（并列按 code 升序）
        List<CashFlowService.FundAmount> monday = dayFunds(calendar, "2026-11-02");
        assertEquals(3, monday.size());
        assertEquals("X", monday.get(0).code());
        assertValue("30", monday.get(0).amount());
        assertEquals("D", monday.get(1).code());
        assertEquals("W", monday.get(2).code());
        assertEquals(3, dayPlans(calendar, "2026-11-02"));
        assertValue("50", dayAmount(calendar, "2026-11-02"));

        // 周二（11/3）只有两只日投，明细要跟着缩
        List<CashFlowService.FundAmount> tuesday = dayFunds(calendar, "2026-11-03");
        assertEquals(2, tuesday.size());
        assertEquals("X", tuesday.get(0).code());
        assertEquals("D", tuesday.get(1).code());
        assertValue("40", dayAmount(calendar, "2026-11-03"));

        // 用「周一 ~ 周五」日历时周末不扣款：明细为空
        CashFlowService.Calendar weekdays = calendar("2026-11", events, Map.of(), WEEKDAYS);
        assertTrue(dayFunds(weekdays, "2026-11-01").isEmpty());
        assertValue("0", dayAmount(weekdays, "2026-11-01"));
    }

    @Test
    @DisplayName("年度分布：所选月份的整年 1~12 月合计，恒 12 项")
    void calendarMonthsOfYear() {
        // 每日 10、每天都是交易日，从 2026-01-01 一直投 → 各月 = 当月天数 × 10
        CashFlowService.Calendar calendar = calendar("2026-11", monthlyEvents("daily", "10"), Map.of(), EVERY_DAY);

        List<BigDecimal> months = calendar.monthsOfYear();
        assertEquals(12, months.size());
        assertValue("310", months.get(0));    // 1 月 31 天
        assertValue("280", months.get(1));    // 2 月 28 天
        assertValue("310", months.get(9));    // 10 月 31 天
        assertValue("300", months.get(10));   // 11 月 30 天
        assertValue("310", months.get(11));   // 12 月 31 天
        assertEquals(0, months.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(new BigDecimal("3650")));   // 2026 年 365 天 × 10

        // 年度统计（与月度同口径）：执行 365 天、1 只基金、日均 = 3650 ÷ 365 = 10
        assertValue("3650", calendar.yearTotal());
        assertEquals(365, calendar.yearExecutedDays());
        assertEquals(1, calendar.yearFundCount());
        assertValue("10.00", calendar.yearDailyAverage());

        // 换一年：所选月份属于 2027 → 给的是 2027 年的 1~12 月。
        // 这条只推进到窗口末月（2027-10-31），所以 11/12 月没有行、是 0 —— aggregate 只汇总 fold 出来的行，
        // 年尾的完整性由生产路径负责（见下一条断言）
        CashFlowService.Calendar next = calendar("2027-03", monthlyEvents("daily", "10"), Map.of(), EVERY_DAY);
        assertValue("310", next.monthsOfYear().get(0));    // 1 月 31 天
        assertValue("0", next.monthsOfYear().get(11));     // 12 月：超出这条的 fold 范围

        // 生产路径会把 fold 推到「窗口末月与该年年尾里更晚的那个」：整年 12 个月都要有数，
        // 不能因为窗口只到 2027-10 就让 11/12 月假报 0
        Map<String, PlanState> fullYear = fold(monthlyEvents("daily", "10"), Map.of(), EVERY_DAY,
                LocalDate.parse("2027-12-31"));
        CashFlowService.Calendar full = CashFlowService.aggregate(YearMonth.parse("2027-03"), fullYear, Map.of());
        assertValue("300", full.monthsOfYear().get(10));   // 11 月 30 天
        assertValue("310", full.monthsOfYear().get(11));   // 12 月 31 天
    }

    @Test
    @DisplayName("日历：非交易日不扣款（每日跳过），当天金额与计划数都是 0")
    void calendarSkipsNonTradingDays() {
        List<Adjustment> events = List.of(event(1, "D", PLAN_START, 1, "daily", "10"));

        CashFlowService.Calendar calendar = calendar("2026-11", events, Map.of(), WEEKDAYS);

        // 11/1 周日不扣、11/2 周一扣
        assertValue("0", dayAmount(calendar, "2026-11-01"));
        assertEquals(0, dayPlans(calendar, "2026-11-01"));
        assertValue("10", dayAmount(calendar, "2026-11-02"));
        // 30 天里工作日 21 天（11 月无节假日）
        assertEquals(21, calendar.executedDays());
        assertValue("210", calendar.total());
    }

    @Test
    @DisplayName("日历：每月 1 日遇非交易日顺延，那笔记在顺延后的那天")
    void calendarPostponementShowsOnActualDay() {
        List<Adjustment> events = List.of(event(1, "M", "2026-11-01", 1, "monthly", "100"));

        CashFlowService.Calendar calendar = calendar("2026-11", events, Map.of(), WEEKDAYS);

        assertValue("0", dayAmount(calendar, "2026-11-01"));      // 11/1 是周日
        assertValue("100", dayAmount(calendar, "2026-11-02"));    // 顺延到周一
        assertEquals(1, calendar.executedDays());
    }

    @Test
    @DisplayName("日历：没有记录之前 / 没有扣款的月份返回空日历，earliest 指向最早有记录的月份")
    void calendarEmptyMonth() {
        List<Adjustment> events = List.of(event(1, "D", "2026-11-05", 1, "daily", "10"));

        // 2026-10 在第一条记录之前
        CashFlowService.Calendar empty = calendar("2026-10", events, Map.of(), EVERY_DAY);
        assertValue("0", empty.total());
        assertEquals(0, empty.executedDays());
        assertEquals(0, empty.fundCount());
        assertValue("0", empty.dailyAverage());
        assertEquals(31, empty.days().size());
        assertTrue(empty.funds().isEmpty());
        assertEquals(YearMonth.of(2026, 11), empty.earliest());

        // 2026-12 有扣款（从 11/5 起每日投），earliest 仍是 11 月（窗外的月份也要看见）
        CashFlowService.Calendar december = calendar("2026-12", events, Map.of(), EVERY_DAY);
        assertValue("310", december.total());
        assertEquals(YearMonth.of(2026, 11), december.earliest());
    }

    /* ==================== helpers ==================== */

    /** 从 2026-01-01 一直开着的单只定投计划 */
    private static List<Adjustment> monthlyEvents(String frequency, String amount) {
        return List.of(event(1, "D", PLAN_START, 1, frequency, amount));
    }

    /** 日历的纯计算：同样走真 fold（推进到窗口末月）再按月汇总 */
    private static CashFlowService.Calendar calendar(String month, List<Adjustment> events,
                                                     Map<String, String> names, Predicate<LocalDate> calendar) {
        return CashFlowService.aggregate(YearMonth.parse(month), fold(events, calendar), names);
    }

    /** 日历里某一天（YYYY-MM-DD）的金额 */
    private static BigDecimal dayAmount(CashFlowService.Calendar calendar, String day) {
        return calendar.days().stream()
                .filter(d -> d.day().toString().equals(day))
                .findFirst()
                .orElseThrow(() -> new AssertionError("日历里没有 " + day))
                .amount();
    }

    /** 日历里某一天（YYYY-MM-DD）的各基金明细 */
    private static List<CashFlowService.FundAmount> dayFunds(CashFlowService.Calendar calendar, String day) {
        return calendar.days().stream()
                .filter(d -> d.day().toString().equals(day))
                .findFirst()
                .orElseThrow(() -> new AssertionError("日历里没有 " + day))
                .funds();
    }

    /** 日历里某一天（YYYY-MM-DD）执行的计划数 */
    private static int dayPlans(CashFlowService.Calendar calendar, String day) {
        return calendar.days().stream()
                .filter(d -> d.day().toString().equals(day))
                .findFirst()
                .orElseThrow(() -> new AssertionError("日历里没有 " + day))
                .plans();
    }

    /** 生产同款路径：只保留 action=1/2 → fold 推进到窗口末月（窗口内的逐日扣款才有 rows 可汇总） */
    private static Map<String, PlanState> fold(List<Adjustment> events, Predicate<LocalDate> calendar) {
        return fold(events, Map.of(), calendar);
    }

    private static Map<String, PlanState> fold(List<Adjustment> events, Map<String, BigDecimal> rates,
                                               Predicate<LocalDate> calendar) {
        // 先落到声明好类型的变量上再传：隐式 lambda 对两个重载都「潜在兼容」，直接写会报引用不明确
        Function<String, Predicate<LocalDate>> perCode = code -> calendar;
        return fold(events, rates, perCode);
    }

    private static Map<String, PlanState> fold(List<Adjustment> events, Map<String, BigDecimal> rates,
                                               Function<String, Predicate<LocalDate>> calendar) {
        return SeriesService.fold(USER_ID, events, null, rates, calendar, CashFlowService.windowEnd(TODAY));
    }

    /** 显式指定推进到哪一天（年度分布要 fold 到年尾，见 calendarMonthsOfYear） */
    private static Map<String, PlanState> fold(List<Adjustment> events, Map<String, BigDecimal> rates,
                                               Predicate<LocalDate> calendar, LocalDate until) {
        Function<String, Predicate<LocalDate>> perCode = code -> calendar;
        return SeriesService.fold(USER_ID, events, null, rates, perCode, until);
    }

    private static Forecast project(int cash, String inflow, Map<String, PlanState> states) {
        return project(cash, inflow, Map.of(), states);
    }

    private static Forecast project(int cash, String inflow, Map<String, BigDecimal> overrides,
                                    Map<String, PlanState> states) {
        return CashFlowService.project(TODAY, new BigDecimal(cash),
                inflow == null ? null : new BigDecimal(inflow), overrides, states);
    }

    private static Adjustment event(long id, String code, String date, int action,
                                    String frequency, String amount) {
        Adjustment event = new Adjustment();
        event.setAdjustmentId(id);
        event.setUserId(USER_ID);
        event.setCode(code);
        event.setDate(LocalDate.parse(date));
        event.setAction(action);
        event.setFrequency(frequency);
        event.setAmount(new BigDecimal(amount));
        return event;
    }

    private static boolean isWeekday(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** 逐月 map 的合计（测试断言用） */
    private static BigDecimal total(Map<YearMonth, BigDecimal> byMonth) {
        return byMonth.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** BigDecimal.equals 连 scale 一起比，这里只关心数值 */
    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual);
    }
}
