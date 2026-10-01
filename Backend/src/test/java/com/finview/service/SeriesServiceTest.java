package com.finview.service;

import com.finview.common.Xirr;
import com.finview.entity.Adjustment;
import com.finview.entity.AssetSeries;
import com.finview.service.SeriesService.PlanState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SeriesService 的核心算法测试。
 *
 * fixture 是 2026-09-29 从线上库（user_id=7 秋水.cx）导出的一份**快照**（35 条 adjustments，
 * 含当时还存在的重复行），today 固定为 2026-09-29，所以断言的是确定的数字而不是
 * 「今天跑一遍看看」。用户后来改过库里的记录（删了重复行、动了日期），
 * 这份 fixture 不必跟着变 —— 它是给算法钉行为的回归基准。
 *
 * 覆盖表设计.md 的四个场景（只有开始定投 / 开始+结束 / 只有离散收支 / 混合），以及几处口径选择：
 * <ul>
 *   <li>**扣款日**：daily 每个交易日、weekly 每周一、monthly 每月 1 日；</li>
 *   <li>**调整生效时机**：只改金额当天生效，改频率当天仍按原计划、次日生效；</li>
 *   <li>**结束定投当天仍算一个周期点**（013402 的 6/29、008887 的 8/6）；</li>
 *   <li>**同一天重复的 action=1 只扣一次款**；</li>
 *   <li>**非交易日不扣款**：每日定投跳过、每周/每月顺延到下一个交易日。</li>
 * </ul>
 */
class SeriesServiceTest {

    private static final long USER_ID = 7L;

    /**
     * 数据窗口（2026-04-22 ~ 2026-09-30）内的 A 股工作日节假日，取自真实交易日历
     * （akshare tool_trade_date_hist_sina）：劳动节 5/1、5/4、5/5，端午 6/19，中秋 9/25。
     * A 股从不在周末交易（调休上班的周末也不开盘），所以「交易日 = 工作日 − 这些节假日」。
     */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.parse("2026-05-01"),
            LocalDate.parse("2026-05-04"),
            LocalDate.parse("2026-05-05"),
            LocalDate.parse("2026-06-19"),
            LocalDate.parse("2026-09-25"));

    /** 真实 A 股交易日历的等价规则 */
    private static final Predicate<LocalDate> A_SHARE_CALENDAR = day -> isWeekday(day) && !HOLIDAYS.contains(day);

    /** 不关心交易日的用例用这个：每天都能定投 */
    private static final Predicate<LocalDate> EVERY_DAY = day -> true;

    /* ==================== 场景 3：只有离散收支 ==================== */

    @Test
    @DisplayName("只有一笔收入：本金就是买入金额，序列逐日连续，从未定投")
    void buyOnly() {
        Map<String, PlanState> states = foldRealData();

        PlanState state = states.get("011803");
        assertEquals(new BigDecimal("1000"), state.getPrincipal());
        assertFalse(state.isActive());
        assertFalse(state.isEverInvested());
        // 2026-04-22 → 2026-09-29 逐日一行
        assertEquals(daysBetween("2026-04-22", "2026-09-29"), state.getRows().size());
        assertEquals(new BigDecimal("1000"), state.getRows().get(0).getAmount());
        // 之后的每一天都是 0 投入，本金不变
        assertEquals(BigDecimal.ZERO, state.getRows().get(1).getAmount());
    }

    /* ==================== 场景 1 + 2：daily 开始、下调、结束 ==================== */

    @Test
    @DisplayName("daily 定投：只在交易日扣，下调当天按新金额，结束当天仍扣一笔")
    void dailyWithDownsizeAndStop() {
        PlanState state = foldRealData().get("270042");

        // 4/27–7/20 共 57 个交易日 × 10（日历 85 天里 28 天是周末+劳动节）
        // 7/21 下调后 7/21–7/29 共 7 个交易日 × 5（含 7/29 结束当天）= 570 + 35
        assertEquals(new BigDecimal("605"), state.getPrincipal());
        assertFalse(state.isActive());
        assertTrue(state.isEverInvested());
        assertEquals(new BigDecimal("10"), state.getRows().get(0).getAmount());
        assertEquals(new BigDecimal("5"), amountOn(state, "2026-07-29"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-07-30"));
        // 周末不扣款
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-05-02"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-05-04"));
    }

    /* ==================== 场景 2：weekly，含「停止当天是周期点」 ==================== */

    @Test
    @DisplayName("weekly 定投：每 7 天一笔，结束当天(周一)也算周期点")
    void weeklyWithStopOnPeriodPoint() {
        PlanState state = foldRealData().get("017641");

        // 6/1 起每周一：6/1 扣 100；6/8 下调为 10 → 6/8、6/15、6/22、6/29 各 10（6/29 是停止当天，仍扣）
        // 7/31 起 daily 10 → 7/31–9/29 共 42 个交易日
        assertEquals(new BigDecimal("100"), amountOn(state, "2026-06-01"));
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-06-08"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-06-07"));
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-06-29"));
        assertEquals(new BigDecimal("560"), state.getPrincipal());
        assertTrue(state.isActive());
    }

    @Test
    @DisplayName("daily 结束后重启、再切 weekly：改频率当天按原计划扣，新频率次日生效")
    void restartAndFrequencyChange() {
        PlanState state = foldRealData().get("008887");

        // 8/3 daily 10 → 8/6 停止（含当天），4 个交易日 = 40
        // 8/11 daily 10 重启 → 8/11–9/1 共 16 个交易日 = 160
        // 9/2 由每日改成每周：当天仍扣每日那一笔 10（adjustment_id=22 的语义）
        // 次日起走周投 → 9/7、9/14、9/21、9/28 四个周一各 10 = 40
        assertEquals(new BigDecimal("40"), sumAmounts(state, "2026-08-03", "2026-08-10"));
        assertEquals(new BigDecimal("160"), sumAmounts(state, "2026-08-11", "2026-09-01"));
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-02"), "改频率当天按原计划（daily）扣");
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-03"), "9/3 是周三，周投不扣");
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-07"), "从下一个周一开始周投");
        assertEquals(new BigDecimal("40"), sumAmounts(state, "2026-09-03", "2026-09-29"));
        assertEquals(new BigDecimal("250"), state.getPrincipal());
    }

    @Test
    @DisplayName("定投下调当天就按新金额扣：扣款日仍落在周一")
    void downsizeTakesEffectSameDay() {
        PlanState state = foldRealData().get("161725");

        // 7/7 买入 103.75；7/8 daily 10 → 7/10 停止（含当天）3 个交易日 = 30
        // 7/13 起 weekly 100：7/13、7/20、7/27、8/3、8/10、8/17、8/24、8/31 共 8 次 = 800
        // 9/7 下调为 10（只改金额，当天生效）：9/7、9/14、9/21、9/28 各 10 = 40
        assertEquals(new BigDecimal("973.75"), state.getPrincipal());
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-07"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-08"));
    }

    /* ==================== 场景 4：混合 + 同日多事件 + 重复录入 ==================== */

    @Test
    @DisplayName("同日「定投开始 + 买入」：先算买入再扣款，时间线仍按录入顺序展示")
    void mixedSameDay() {
        PlanState state = foldRealData().get("013402");

        // 6/15：买入 103.73 + 每周定投 100（两条重复的 action=1 只扣一次）= 203.73
        assertEquals(new BigDecimal("203.73"), amountOn(state, "2026-06-15"));
        // 时间线按 adjustment_id 升序：两条「开始定投」在前，买入在后
        assertEquals("开始定投", state.getTimeline().get(2).getAction());
        assertEquals("一笔收入", state.getTimeline().get(4).getAction());
    }

    @Test
    @DisplayName("混合场景总账：买入 − 卖出 + 周期扣款，同一天重复的 action=1 不重复扣款")
    void mixedPortfolio() {
        PlanState state = foldRealData().get("013402");

        // 买入 70+30+103.73+10+10 = 223.73；卖出 −625.82
        // 每周：6/15、6/22、6/29 = 300；7/13–8/31 共 8 次 = 800
        // 9/10 起 daily 10 → 9/10–9/29 共 13 个交易日 = 130（9/25 中秋不扣）
        assertEquals(new BigDecimal("827.91"), state.getPrincipal());
        assertTrue(state.isActive());
        assertEquals(new BigDecimal("-625.82"), amountOn(state, "2026-09-09"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-25"));
    }

    /* ==================== 不变式 ==================== */

    @Test
    @DisplayName("每个 code 的对账不变式：Σamount == 末行 principal，非交易日一律不扣款")
    void invariantsHold() {
        Map<String, PlanState> states = foldRealData();

        assertEquals(9, states.size(), "9 个 code 一个都不能少");
        states.forEach((code, state) -> {
            BigDecimal sum = state.getRows().stream()
                    .map(AssetSeries::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, sum.compareTo(state.getPrincipal()),
                    code + " 的 Σamount 应该等于累计本金");
            assertEquals(0, sum.compareTo(state.lastRow().getPrincipal()),
                    code + " 的 Σamount 应该等于末行 principal");
            assertTrue(state.lastRow().getTotal().signum() >= 0, code + " 的市值不应该为负");
            assertEquals(state.getStartDate(), state.getRows().get(0).getDay(),
                    code + " 的首行应该落在首个事件当天");

            // 周末与节假日不允许有任何一笔投入
            state.getRows().stream()
                    .filter(row -> !A_SHARE_CALENDAR.test(row.getDay()))
                    .forEach(row -> assertEquals(0, row.getAmount().signum(),
                            code + " 在非交易日 " + row.getDay() + " 不该扣款"));
        });
    }

    @Test
    @DisplayName("事件顺序不影响结果：mapper 返回日期倒序，fold 与整段重建必须算得一样")
    void eventOrderDoesNotMatter() {
        List<Adjustment> events = List.of(
                event(2, "Z", "2026-09-28", 2, null, "10"),
                event(1, "Z", "2026-09-24", 1, "daily", "10"));

        PlanState asc = SeriesService.buildSeries(USER_ID, "Z", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-29"));
        List<Adjustment> reversed = new ArrayList<>(events);
        Collections.reverse(reversed);
        PlanState desc = SeriesService.buildSeries(USER_ID, "Z", reversed, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-29"));

        assertEquals(LocalDate.parse("2026-09-24"), asc.getStartDate());
        assertEquals(asc.getPrincipal(), desc.getPrincipal());
        // 9/24(周四) 与 9/28(周一，停止当天) 各扣 10；中间的 9/25 中秋与周末不扣
        assertEquals(new BigDecimal("20"), asc.getPrincipal());
        assertEquals(asc.getRows(), desc.getRows());
    }

    /* ==================== 非交易日规则 ==================== */

    @Test
    @DisplayName("daily 遇非交易日跳过：不会顺延到下一个交易日连扣两笔")
    void dailySkipsNonTradingDays() {
        // 9/25(周五，节假日) 起投，每天 10
        List<Adjustment> events = List.of(event(1, "D", "2026-09-25", 1, "daily", "10"));

        PlanState state = SeriesService.buildSeries(USER_ID, "D", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-29"));

        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-25"), "节假日不扣");
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-26"), "周六不扣");
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-27"), "周日不扣");
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-28"), "下一个交易日只扣当天这一笔");
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-29"));
        assertEquals(new BigDecimal("20"), state.getPrincipal());
    }

    @Test
    @DisplayName("weekly 的周一遇非交易日：顺延到下一个交易日补扣")
    void weeklyPostponesToNextTradingDay() {
        // 4/27(周一) 起投每周 100；5/4 是周一但劳动节休市，5/5 也休市，顺延到 5/6(周三)
        List<Adjustment> events = List.of(event(1, "W", "2026-04-27", 1, "weekly", "100"));

        PlanState state = SeriesService.buildSeries(USER_ID, "W", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-05-08"));

        assertEquals(new BigDecimal("100"), amountOn(state, "2026-04-27"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-05-04"), "周一休市，当期不顺延当天扣");
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-05-05"), "还是休市");
        assertEquals(new BigDecimal("100"), amountOn(state, "2026-05-06"), "顺延到下一个交易日补扣");
        assertEquals(new BigDecimal("200"), state.getPrincipal());
    }

    @Test
    @DisplayName("顺延中的那笔在定投结束之后就作废")
    void postponeCancelledByStop() {
        // 4/27 起投每周 100；5/4(周一) 休市记进顺延，5/5 结束定投 → 5/6 的补扣不该发生
        List<Adjustment> events = List.of(
                event(1, "W", "2026-04-27", 1, "weekly", "100"),
                event(2, "W", "2026-05-05", 2, null, "100"));

        PlanState state = SeriesService.buildSeries(USER_ID, "W", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-05-08"));

        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-05-06"));
        assertEquals(new BigDecimal("100"), state.getPrincipal(), "只有 4/27 那一笔");
        assertFalse(state.isActive());
    }

    @Test
    @DisplayName("起点不是周一的周投：当天不扣，等下周一才开始")
    void weeklyStartOnNonMondayWaitsForMonday() {
        // 9/26(周六) 起投每周 100 → 首个扣款日是 9/28(周一)
        List<Adjustment> events = List.of(event(1, "W", "2026-09-26", 1, "weekly", "100"));

        PlanState state = SeriesService.buildSeries(USER_ID, "W", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-29"));

        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-26"));
        assertEquals(new BigDecimal("100"), amountOn(state, "2026-09-28"));
        assertEquals(new BigDecimal("100"), state.getPrincipal());
    }

    @Test
    @DisplayName("改频率当天按原计划：每日改每周的那天仍扣每日那笔")
    void frequencyChangeTakesEffectNextDay() {
        // 9/1(周二) 起每日 10，9/2(周三) 改成每周 10
        List<Adjustment> events = List.of(
                event(1, "F", "2026-09-01", 1, "daily", "10"),
                event(2, "F", "2026-09-02", 1, "weekly", "10"));

        PlanState state = SeriesService.buildSeries(USER_ID, "F", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-08"));

        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-01"));
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-02"), "改频率当天仍按每日扣");
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-03"), "9/3 起才走周投，周三不扣");
        assertEquals(new BigDecimal("10"), amountOn(state, "2026-09-07"), "下一个周一");
        assertEquals(new BigDecimal("30"), state.getPrincipal());
    }

    @Test
    @DisplayName("只改金额：当天就按新金额扣")
    void amountChangeTakesEffectSameDay() {
        // 9/1 起每日 10，9/2 下调为 30（同频率）
        List<Adjustment> events = List.of(
                event(1, "A", "2026-09-01", 1, "daily", "10"),
                event(2, "A", "2026-09-02", 1, "daily", "30"));

        PlanState state = SeriesService.buildSeries(USER_ID, "A", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-02"));

        assertEquals(new BigDecimal("30"), amountOn(state, "2026-09-02"));
        assertEquals(new BigDecimal("40"), state.getPrincipal());
    }

    @Test
    @DisplayName("monthly 每月 1 日扣：月中开的定投等到下个月 1 号")
    void monthlyDeductsOnFirstDay() {
        // 8/15(周五) 起投每月 100 → 首个扣款日是 9/1
        List<Adjustment> events = List.of(event(1, "M", "2026-08-15", 1, "monthly", "100"));

        PlanState state = SeriesService.buildSeries(USER_ID, "M", events, new TreeMap<>(), null,
                A_SHARE_CALENDAR, LocalDate.parse("2026-09-03"));

        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-08-15"));
        assertEquals(new BigDecimal("100"), amountOn(state, "2026-09-01"));
        assertEquals(BigDecimal.ZERO, amountOn(state, "2026-09-02"));
        assertEquals(new BigDecimal("100"), state.getPrincipal());
    }

    /* ==================== 净值 / 份额换算 ==================== */

    @Test
    @DisplayName("有净值时按份额算市值：市值 = 累计份额 × 当日净值")
    void marketValueUsesNav() {
        List<Adjustment> events = List.of(event(1, "X", "2026-03-01", 1, "daily", "100"));

        NavigableMap<LocalDate, BigDecimal> navs = new TreeMap<>();
        navs.put(LocalDate.parse("2026-03-01"), new BigDecimal("2.0000"));
        navs.put(LocalDate.parse("2026-03-02"), new BigDecimal("2.5000"));
        navs.put(LocalDate.parse("2026-03-05"), new BigDecimal("1.0000"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of("X", navs), Map.of(), EVERY_DAY,
                LocalDate.parse("2026-03-05")).get("X");

        // 3/1 投入 100，净值 2.0 → 50 份，市值 100（市值统一保留 4 位小数）
        assertEquals(new BigDecimal("100").setScale(4), state.getRows().get(0).getTotal());
        // 3/2 再投 100，净值 2.5 → +40 份 = 90 份 → 市值 225
        assertEquals(new BigDecimal("225").setScale(4), state.getRows().get(1).getTotal());
        // 3/3 每天继续投 100，净值没更新（沿用 3/2 的 2.5）→ 130 份 → 325
        assertEquals(new BigDecimal("325").setScale(4), state.getRows().get(2).getTotal());
        // 3/5 净值跌到 1.0：本金 500，累计 270 份 → 市值 270
        assertEquals(new BigDecimal("500"), state.getPrincipal());
        assertEquals(new BigDecimal("270").setScale(4), state.getRows().get(4).getTotal());
        assertTrue(state.getRows().get(4).getTotal().compareTo(state.getPrincipal()) < 0);
    }

    @Test
    @DisplayName("没有净值数据时市值回退为本金（收益率 0），不会算成 0")
    void marketValueFallsBackToPrincipal() {
        List<Adjustment> events = List.of(event(1, "Y", "2026-08-01", 1, "daily", "10"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of(), EVERY_DAY,
                LocalDate.parse("2026-08-03")).get("Y");

        assertEquals(new BigDecimal("30"), state.getPrincipal());
        assertEquals(new BigDecimal("30").setScale(4), state.lastRow().getTotal());
    }

    /* ==================== 周期点规则 ==================== */

    @Test
    @DisplayName("周期点：daily 每天、weekly 每周一、monthly 每月 1 日")
    void periodPointRules() {
        assertTrue(SeriesService.isPeriodPoint("daily", LocalDate.parse("2026-09-02")));
        // 2026-09-07 是周一
        assertTrue(SeriesService.isPeriodPoint("weekly", LocalDate.parse("2026-09-07")));
        assertFalse(SeriesService.isPeriodPoint("weekly", LocalDate.parse("2026-09-08")));
        assertTrue(SeriesService.isPeriodPoint("monthly", LocalDate.parse("2026-09-01")));
        assertFalse(SeriesService.isPeriodPoint("monthly", LocalDate.parse("2026-09-02")));
        // 频率不认识 / 缺失时不当成周期点，免得脏数据天天扣款
        assertFalse(SeriesService.isPeriodPoint("yearly", LocalDate.parse("2026-09-07")));
        assertFalse(SeriesService.isPeriodPoint(null, LocalDate.parse("2026-09-07")));
    }

    /* ==================== 申购手续费 ==================== */

    @Test
    @DisplayName("买入扣手续费：一笔收入按 金额×费率/100 扣，净额进本金")
    void buyFeeReducesPrincipal() {
        // 一笔收入 100，费率 0.12% → 手续费 0.12，净额 99.88
        List<Adjustment> events = List.of(event(1, "B", "2026-09-01", 3, null, "100"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of("B", new BigDecimal("0.12")),
                EVERY_DAY, LocalDate.parse("2026-09-01")).get("B");

        assertEquals(new BigDecimal("99.88").setScale(4), state.getPrincipal());
        assertEquals(new BigDecimal("99.88").setScale(4), amountOn(state, "2026-09-01"));
    }

    @Test
    @DisplayName("周期扣款同样扣手续费，逐日累计")
    void deductionFeeReducesPrincipal() {
        // 每日 10，费率 0.12% → 每期手续费 0.012，净额 9.988
        List<Adjustment> events = List.of(event(1, "D", "2026-09-01", 1, "daily", "10"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of("D", new BigDecimal("0.12")),
                EVERY_DAY, LocalDate.parse("2026-09-02")).get("D");

        assertEquals(new BigDecimal("9.99").setScale(4), amountOn(state, "2026-09-01"));
        assertEquals(new BigDecimal("19.98").setScale(4), state.getPrincipal());
    }

    @Test
    @DisplayName("手续费同时压缩份额与市值：净额才是真正买进去的钱")
    void feeReducesSharesAndMarketValue() {
        // 一笔收入 100，费率 1%（好算），当日净值 1.0 → 只买到 99 份
        List<Adjustment> events = List.of(event(1, "F", "2026-09-01", 3, null, "100"));

        NavigableMap<LocalDate, BigDecimal> navs = new TreeMap<>();
        navs.put(LocalDate.parse("2026-09-01"), new BigDecimal("1.0000"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of("F", navs),
                Map.of("F", new BigDecimal("1")), EVERY_DAY, LocalDate.parse("2026-09-01")).get("F");

        assertEquals(new BigDecimal("99").setScale(4), state.getPrincipal());
        assertEquals(new BigDecimal("99").setScale(4), state.lastRow().getTotal());
    }

    @Test
    @DisplayName("卖出不扣费率：一笔支出全额冲减本金")
    void sellIsNotChargedFee() {
        List<Adjustment> events = List.of(
                event(1, "S", "2026-09-01", 3, null, "100"),
                event(2, "S", "2026-09-02", 4, null, "40"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of("S", new BigDecimal("1")),
                EVERY_DAY, LocalDate.parse("2026-09-02")).get("S");

        // 买 100 扣 1 元手续费 = 99，卖 40 不扣 = 59
        assertEquals(new BigDecimal("59").setScale(4), state.getPrincipal());
        assertEquals(new BigDecimal("-40"), amountOn(state, "2026-09-02"));
    }

    @Test
    @DisplayName("手续费不足 1 分按 1 分扣（小额定投的实际情况）")
    void feeIsAtLeastOneCent() {
        // 10 元 × 0.01% = 0.001 元，四舍五入到分是 0 → 按下限收 1 分，净额 9.99
        List<Adjustment> events = List.of(event(1, "C", "2026-09-01", 3, null, "10"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of("C", new BigDecimal("0.01")),
                EVERY_DAY, LocalDate.parse("2026-09-01")).get("C");

        assertEquals(new BigDecimal("9.99").setScale(4), state.getPrincipal());
    }

    @Test
    @DisplayName("手续费正好 1 分时不触发下限，也不凑整到分")
    void feeAroundOneCent() {
        // 10 元 × 0.10% = 0.010 元，正好 1 分
        PlanState exact = SeriesService.fold(USER_ID, List.of(event(1, "E", "2026-09-01", 3, null, "10")),
                Map.of(), Map.of("E", new BigDecimal("0.1")), EVERY_DAY, LocalDate.parse("2026-09-01")).get("E");
        assertEquals(new BigDecimal("9.99").setScale(4), exact.getPrincipal());

        // 10 元 × 0.12% = 0.012 元 → 四舍五入到分还是 1 分
        PlanState above = SeriesService.fold(USER_ID, List.of(event(1, "E2", "2026-09-01", 3, null, "10")),
                Map.of(), Map.of("E2", new BigDecimal("0.12")), EVERY_DAY, LocalDate.parse("2026-09-01")).get("E2");
        assertEquals(new BigDecimal("9.99").setScale(4), above.getPrincipal());

        // 1000 元 × 0.126% = 1.26 元，按分收就是 1.26（保留到分的两位）
        PlanState big = SeriesService.fold(USER_ID, List.of(event(1, "E3", "2026-09-01", 3, null, "1000")),
                Map.of(), Map.of("E3", new BigDecimal("0.126")), EVERY_DAY, LocalDate.parse("2026-09-01")).get("E3");
        assertEquals(new BigDecimal("998.74").setScale(4), big.getPrincipal());
    }

    @Test
    @DisplayName("手续费不会超过买入金额本身（净额不会变成负数）")
    void feeNeverExceedsAmount() {
        // 买入 0.005 元（半分的量级）：1 分的下限被金额本身压住，净额是 0 而不是负数
        List<Adjustment> events = List.of(event(1, "T", "2026-09-01", 3, null, "0.005"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of("T", new BigDecimal("1")),
                EVERY_DAY, LocalDate.parse("2026-09-01")).get("T");

        assertEquals(0, state.getPrincipal().signum());
    }

    @Test
    @DisplayName("费率为空或 0 时不扣手续费（默认行为不变）")
    void zeroRateMeansNoFee() {
        List<Adjustment> events = List.of(event(1, "Z0", "2026-09-01", 3, null, "100"));

        PlanState nullRate = SeriesService.fold(USER_ID, events, Map.of(), Map.of(),
                EVERY_DAY, LocalDate.parse("2026-09-01")).get("Z0");
        PlanState zeroRate = SeriesService.fold(USER_ID, events, Map.of(), Map.of("Z0", BigDecimal.ZERO),
                EVERY_DAY, LocalDate.parse("2026-09-01")).get("Z0");

        assertEquals(new BigDecimal("100"), nullRate.getPrincipal());
        assertEquals(new BigDecimal("100"), zeroRate.getPrincipal());
    }

    @Test
    @DisplayName("带手续费时对账不变式依然成立：Σamount == 末行 principal")
    void feeKeepsInvariant() {
        List<Adjustment> events = List.of(
                event(1, "I", "2026-09-01", 1, "daily", "10"),
                event(2, "I", "2026-09-02", 3, null, "100"),
                event(3, "I", "2026-09-03", 4, null, "30"));

        PlanState state = SeriesService.fold(USER_ID, events, Map.of(), Map.of("I", new BigDecimal("0.12")),
                EVERY_DAY, LocalDate.parse("2026-09-03")).get("I");

        BigDecimal sum = state.getRows().stream()
                .map(AssetSeries::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(state.lastRow().getPrincipal()));
    }

    /* ==================== 年化（XIRR）端到端 ==================== */

    @Test
    @DisplayName("fold → XIRR：逐日的零投入行不产生假现金流，末日按市值虚拟赎回")
    void annualizedFromFoldedSeries() {
        // 2025-01-01 一次性买入 100，净值 1 → 2，持有一整年（2025 非闰年，正好 365 天）
        List<Adjustment> events = List.of(event(1, "T", "2025-01-01", 3, null, "100"));
        Map<String, NavigableMap<LocalDate, BigDecimal>> navs = Map.of("T", new TreeMap<>(Map.of(
                LocalDate.parse("2025-01-01"), BigDecimal.ONE,
                LocalDate.parse("2026-01-01"), new BigDecimal("2"))));

        PlanState state = SeriesService.fold(USER_ID, events, navs, Map.of(), EVERY_DAY,
                LocalDate.parse("2026-01-01")).get("T");

        // 序列有 365 行，但只有首行（买入）与末行（末日市值）进现金流 → 年化正好 100%
        assertEquals(1.0, Xirr.ofSeries(state.getRows()).doubleValue(), 1e-6);
    }

    /* ==================== 清仓（000051 的真实流水） ==================== */

    @Test
    @DisplayName("清仓：份额与市值归零（不留空头），但本金与手续费照净额累计，累计收益跨轮连续")
    void clearanceResetsEpisode() {
        // 000051 的真实记录：4/15 买 300（平台收 0.12% 申购费，实际份额 170.04）、
        // 5/9（周六）卖 314.69（顺延到 5/11 的净值 1.8600 成交，到账已扣 1.58 赎回费）、
        // 9/29 起重新定投 daily 10
        List<Adjustment> events = List.of(
                event(1, "000051", "2026-04-15", 3, null, "300"),
                event(2, "000051", "2026-05-09", 4, null, "314.69"),
                event(3, "000051", "2026-09-29", 1, "daily", "10"));
        Map<String, NavigableMap<LocalDate, BigDecimal>> navs = Map.of("000051", new TreeMap<>(Map.of(
                LocalDate.parse("2026-04-15"), new BigDecimal("1.7622"),   // 买入日净值
                LocalDate.parse("2026-05-08"), new BigDecimal("1.8313"),   // 5/9 是周六，只能取到这一天
                LocalDate.parse("2026-05-11"), new BigDecimal("1.8600"),   // 平台的实际成交净值
                LocalDate.parse("2026-09-29"), new BigDecimal("1.6689"),
                LocalDate.parse("2026-09-30"), new BigDecimal("1.6737"))));

        PlanState state = SeriesService.fold(USER_ID, events, navs, Map.of("000051", new BigDecimal("0.12")),
                EVERY_DAY, LocalDate.parse("2026-09-30")).get("000051");

        // 买入日：300 里扣 0.36 申购费，净额 299.64 才进本金（份额 299.64 / 1.7622 = 170.0376）
        assertValue("299.64", rowOn(state, "2026-04-15").getAmount());
        assertValue("299.64", rowOn(state, "2026-04-15").getPrincipal());
        assertValue("0.36", rowOn(state, "2026-04-15").getFee());

        // 清仓日：到账的 314.69 照记进 amount，份额与市值归零（不能留下负份额的空头），
        // 本金照减 → −15.05（卖出多于投入，「净投入」本来就是负的）
        assertValue("-314.69", rowOn(state, "2026-05-09").getAmount());
        assertValue("-15.05", rowOn(state, "2026-05-09").getPrincipal());
        assertValue("0", rowOn(state, "2026-05-09").getTotal());
        assertEquals(LocalDate.parse("2026-05-09"), state.getLastClearance());

        // 9/29 起是新一轮：份额按新一轮的净投入重建，本金继续接着上一轮累计（不清零）
        assertValue("-5.06", rowOn(state, "2026-09-29").getPrincipal());
        assertValue("9.99", rowOn(state, "2026-09-29").getTotal());
        assertValue("4.93", rowOn(state, "2026-09-30").getPrincipal());
        BigDecimal total = rowOn(state, "2026-09-30").getTotal();
        assertTrue(total.compareTo(new BigDecimal("19.98")) > 0, "市值应该略高于本轮投入，实际 " + total);
        assertTrue(total.compareTo(new BigDecimal("20.05")) < 0, "市值不该大得离谱，实际 " + total);

        // 累计收益跨轮连续 = 市值 + 已收回 − 累计投入 = 20.01 + 314.69 − 320 ≈ 14.70，
        // 而不是「新一轮只有 +0.01」；累计投入（收益率分母）= 300 + 10 + 10 = 320
        BigDecimal feeSum = state.getRows().stream()
                .map(AssetSeries::getFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertValue("0.38", feeSum);
        BigDecimal profit = total.subtract(state.getPrincipal()).subtract(feeSum);
        assertTrue(profit.subtract(new BigDecimal("14.70")).abs().compareTo(new BigDecimal("0.02")) < 0,
                "累计收益应该接着上一轮 ≈ 14.70，实际 " + profit);
    }

    /* ==================== 真实数据 fixture ==================== */

    /** 把库里 user_id=7 的 35 条记录 fold 一遍，today = 2026-09-29，交易日按真实 A 股日历 */
    private Map<String, PlanState> foldRealData() {
        return SeriesService.fold(USER_ID, realEvents(), Map.of(), Map.of(), A_SHARE_CALENDAR,
                LocalDate.parse("2026-09-29"));
    }

    /**
     * 线上库的 35 条 adjustments。
     * 013402 的 adjustment_id 用的是库里的真实值，因为同一天多事件的顺序由它决定。
     */
    private List<Adjustment> realEvents() {
        List<Adjustment> events = new ArrayList<>();
        events.add(event(1, "011803", "2026-04-22", 3, null, "1000"));
        events.add(event(2, "270042", "2026-04-27", 1, "daily", "10"));
        events.add(event(3, "270042", "2026-07-21", 1, "daily", "5"));
        events.add(event(4, "270042", "2026-07-29", 2, null, "5"));
        events.add(event(5, "019851", "2026-05-13", 3, null, "1000"));
        events.add(event(6, "019851", "2026-08-17", 3, null, "1000"));
        events.add(event(7, "016452", "2026-07-30", 1, "daily", "10"));
        events.add(event(8, "017641", "2026-06-01", 1, "weekly", "100"));
        events.add(event(9, "017641", "2026-06-08", 1, "weekly", "10"));
        events.add(event(10, "017641", "2026-06-29", 2, null, "10"));
        events.add(event(11, "017641", "2026-07-31", 1, "daily", "10"));
        events.add(event(12, "007721", "2026-05-12", 3, null, "100"));
        events.add(event(13, "008887", "2026-08-03", 1, "daily", "10"));
        events.add(event(14, "008887", "2026-08-06", 2, null, "10"));
        events.add(event(15, "008887", "2026-08-11", 1, "daily", "10"));
        events.add(event(16, "008887", "2026-09-02", 1, "weekly", "10"));
        events.add(event(17, "161725", "2026-07-07", 3, null, "103.75"));
        events.add(event(18, "161725", "2026-07-08", 1, "daily", "10"));
        events.add(event(19, "161725", "2026-07-10", 2, null, "10"));
        events.add(event(20, "161725", "2026-07-13", 1, "weekly", "100"));
        events.add(event(21, "161725", "2026-09-07", 1, "weekly", "10"));
        events.add(event(28, "013402", "2026-06-08", 3, null, "70"));
        events.add(event(29, "013402", "2026-06-09", 3, null, "30"));
        events.add(event(30, "013402", "2026-06-15", 1, "weekly", "100"));
        events.add(event(31, "013402", "2026-06-15", 1, "weekly", "100"));
        events.add(event(33, "013402", "2026-06-15", 3, null, "103.73"));
        events.add(event(32, "013402", "2026-06-29", 2, null, "100"));
        events.add(event(34, "013402", "2026-07-13", 1, "weekly", "100"));
        events.add(event(35, "013402", "2026-07-13", 1, "weekly", "100"));
        events.add(event(36, "013402", "2026-08-31", 2, null, "100"));
        events.add(event(37, "013402", "2026-08-31", 2, null, "100"));
        events.add(event(38, "013402", "2026-09-07", 3, null, "10"));
        events.add(event(39, "013402", "2026-09-07", 3, null, "10"));
        events.add(event(40, "013402", "2026-09-09", 4, null, "625.82"));
        events.add(event(41, "013402", "2026-09-10", 1, "daily", "10"));
        // 库里返回的是日期倒序，这里刻意打乱一下，验证 fold 自己排序
        List<Adjustment> shuffled = new ArrayList<>(events);
        java.util.Collections.reverse(shuffled);
        return shuffled;
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

    private static long daysBetween(String from, String to) {
        return ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1;
    }

    /** 某一天的当日净投入 */
    private static BigDecimal amountOn(PlanState state, String day) {
        return rowOn(state, day).getAmount();
    }

    /** 某一天的整行 */
    private static AssetSeries rowOn(PlanState state, String day) {
        return state.getRows().stream()
                .filter(row -> row.getDay().toString().equals(day))
                .findFirst()
                .orElseThrow(() -> new AssertionError("序列里没有 " + day + " 这一天"));
    }

    /** BigDecimal.equals 连 scale 一起比，这里只关心数值 */
    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual);
    }

    /** 某个区间（含两端）内当日净投入的合计 */
    private static BigDecimal sumAmounts(PlanState state, String from, String to) {
        LocalDate start = LocalDate.parse(from);
        LocalDate end = LocalDate.parse(to);
        return state.getRows().stream()
                .filter(row -> !row.getDay().isBefore(start) && !row.getDay().isAfter(end))
                .map(AssetSeries::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
