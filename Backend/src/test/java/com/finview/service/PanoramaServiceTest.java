package com.finview.service;

import com.finview.entity.Adjustment;
import com.finview.service.SeriesService.PlanState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「资金全景」未来累计投入的测试（{@code cumulativeByCode}）。
 *
 * 固定 asOf = 2026-10-14（周三），窗口 = 2026-10 起 12 个自然月；
 * 窗口从**明天**开始算（今天的扣款已经在当前市值里），逐日 rows 按月归口再累加。
 * 走真 fold（只喂 action 1/2），假日历用「每天都是交易日」把日历因素排除掉。
 */
class PanoramaServiceTest {

    private static final long USER_ID = 7L;
    private static final LocalDate AS_OF = LocalDate.parse("2026-10-14");

    private static List<YearMonth> months() {
        List<YearMonth> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(YearMonth.from(AS_OF).plusMonths(i));
        }
        return months;
    }

    private static Map<String, List<BigDecimal>> cumulative(List<Adjustment> events) {
        LocalDate horizonEnd = months().get(11).atEndOfMonth();
        // 先落到声明好类型的变量上：fold 有两个 6 参重载，内联 lambda 会报「引用不明确」
        Function<String, Predicate<LocalDate>> calendar = code -> day -> true;
        Map<String, PlanState> states = SeriesService.fold(
                USER_ID, events, null, Map.of(), calendar, horizonEnd);
        return PanoramaService.cumulativeByCode(months(), PanoramaService.outlayByMonth(AS_OF, months(), states));
    }

    @Test
    @DisplayName("每日定投：从明天起算，本月只算剩余 17 天，之后逐月累加")
    void dailyFromTomorrow() {
        Map<String, List<BigDecimal>> result = cumulative(
                List.of(event(1, "D", "2026-01-01", 1, "daily", "10")));

        List<BigDecimal> d = result.get("D");
        assertEquals(12, d.size());
        // 10-15 ~ 10-31 = 17 天 × 10
        assertValue("170", d.get(0));
        // + 11 月 30 天 → 470
        assertValue("470", d.get(1));
        // 整个人窗口 10-15 ~ 2027-09-30 共 351 天 → 3510
        assertValue("3510", d.get(11));
        // 单调不减
        for (int i = 1; i < d.size(); i++) {
            assertTrue(d.get(i).compareTo(d.get(i - 1)) >= 0, "第 " + i + " 个月比上个月小了");
        }
    }

    @Test
    @DisplayName("每周定投：只数周一；本月只剩 10-19 / 10-26 两次")
    void weeklyMondays() {
        Map<String, List<BigDecimal>> result = cumulative(
                List.of(event(1, "W", "2026-01-01", 1, "weekly", "10")));

        List<BigDecimal> w = result.get("W");
        assertValue("20", w.get(0));    // 10-19、10-26
        assertValue("70", w.get(1));    // 11 月 5 个周一 → +50
    }

    @Test
    @DisplayName("没有定投的 code（现金那种）不在结果里；调用方给全 0 兜底")
    void emptyForNoPlans() {
        Map<String, List<BigDecimal>> result = cumulative(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("今天及以前的扣款不算（已经在当前市值里）；停投之后不再增长")
    void skipsTodayAndAfterStop() {
        // 10-01 起每日 10，10-20 停投：10 月只剩 10-15 ~ 10-20（含停止当天）6 天 = 60
        Map<String, List<BigDecimal>> result = cumulative(List.of(
                event(1, "S", "2026-10-01", 1, "daily", "10"),
                event(2, "S", "2026-10-20", 2, null, "10")));

        List<BigDecimal> s = result.get("S");
        assertValue("60", s.get(0));
        assertValue("60", s.get(11));   // 之后一分钱都不再投
    }

    @Test
    @DisplayName("现金：本月只扣支出不加新增，之后每月 = 上月 + 固定新增 − 当月定投")
    void cashSeriesFollowsInflowMinusOutlay() {
        List<YearMonth> months = months();
        Map<YearMonth, Map<String, BigDecimal>> outlays = emptyOutlays(months);
        // 10 月剩下的 17 天 × 10（与 dailyFromTomorrow 同一个数）、11 月 300
        outlays.get(months.get(0)).put("D", new BigDecimal("170"));
        outlays.get(months.get(1)).put("D", new BigDecimal("300"));

        List<BigDecimal> cash = PanoramaService.cashSeries(months, outlays, fixedInflow(months, "1500"));

        assertValue("-170", cash.get(0));     // 本月（第 0 个月）不加新增
        assertValue("1030", cash.get(1));     // +1500 − 300
        assertValue("2530", cash.get(2));     // 之后每月 +1500
        assertValue("16030", cash.get(11));   // 11 个整月 +11×1500，扣掉 170 + 300
    }

    @Test
    @DisplayName("现金：支出大于新增时逐月变负（不截断到 0 —— 塔高由前端按 0 画）")
    void cashSeriesCanGoNegative() {
        List<YearMonth> months = months();
        Map<YearMonth, Map<String, BigDecimal>> outlays = emptyOutlays(months);
        outlays.get(months.get(0)).put("D", new BigDecimal("2000"));

        List<BigDecimal> cash = PanoramaService.cashSeries(months, outlays, fixedInflow(months, "1500"));

        assertValue("-2000", cash.get(0));
        assertValue("-500", cash.get(1));
        assertValue("14500", cash.get(11));   // 之后 10 个整月每月净 +1500
    }

    private static Map<YearMonth, Map<String, BigDecimal>> emptyOutlays(List<YearMonth> months) {
        Map<YearMonth, Map<String, BigDecimal>> outlays = new LinkedHashMap<>();
        for (YearMonth month : months) {
            outlays.put(month, new LinkedHashMap<>());
        }
        return outlays;
    }

    private static Map<YearMonth, BigDecimal> fixedInflow(List<YearMonth> months, String amount) {
        Map<YearMonth, BigDecimal> inflows = new LinkedHashMap<>();
        for (YearMonth month : months) {
            inflows.put(month, new BigDecimal(amount));
        }
        return inflows;
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

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual);
    }
}
