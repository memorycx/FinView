package com.finview.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/**
 * 标的市场休市日：QDII 基金的扣款日 = A 股交易日 ∩ 标的市场开市日。
 *
 * 起因：017641（标普500）在 2026-09-07（美股劳动节，A 股开市）被多扣了一笔定投 ——
 * 场外基金只在**两个市场都开市**的日子确认申购，只按 A 股日历会多扣。
 * 市场取值来自 asset.market（用户维护的元数据）：us / hk；NULL 或其它值不额外过滤。
 *
 * - **美股**按 NYSE 规则生成，任何年份都能算：元旦 / 马丁·路德·金日 / 总统日 / 耶稣受难日 /
 *   阵亡将士纪念日 / 六月节 / 独立日 / 劳动节 / 感恩节 / 圣诞；周六→前一个周五（元旦除外，
 *   周六的元旦不补休）、周日→后一个周一，与交易所的实际安排一致。
 * - **港股**用 2026 年的官方休市表（港交所参与者通告，已与恒指行情逐日核对过）。
 *   港股节日多按农历，无法像美股那样按规则生成，**跨年要补新表**：表里没有的年份直接按
 *   「开市」放行（宁可多扣、事后重算能纠正；漏扣则不会自己补回来）。
 */
final class MarketCalendar {

    private MarketCalendar() {
    }

    /** 香港交易所 2026 年休市日（周末除外，来源：港交所参与者通告） */
    private static final Set<LocalDate> HK_HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 1, 1),   // 一月一日
            LocalDate.of(2026, 2, 17),  // 农历年初一
            LocalDate.of(2026, 2, 18),  // 农历年初二
            LocalDate.of(2026, 2, 19),  // 农历年初三
            LocalDate.of(2026, 4, 3),   // 耶稣受难节
            LocalDate.of(2026, 4, 6),   // 清明节翌日（清明逢周日）
            LocalDate.of(2026, 4, 7),   // 复活节星期一翌日（与清明补假相撞顺延）
            LocalDate.of(2026, 5, 1),   // 劳动节
            LocalDate.of(2026, 5, 25),  // 佛诞翌日
            LocalDate.of(2026, 6, 19),  // 端午节
            LocalDate.of(2026, 7, 1),   // 香港特别行政区成立纪念日
            LocalDate.of(2026, 10, 1),  // 国庆日
            LocalDate.of(2026, 10, 19), // 重阳节翌日
            LocalDate.of(2026, 12, 25)  // 圣诞节
    );

    /** 标的市场在当天是不是开市（null / 未知市场一律放行，不额外过滤） */
    static boolean isOpen(String market, LocalDate day) {
        if (market == null || market.isBlank()) {
            return true;
        }
        return switch (market.trim().toLowerCase()) {
            case "us" -> !isUsHoliday(day);
            case "hk" -> !(day.getYear() == 2026 && HK_HOLIDAYS_2026.contains(day));
            default -> true;
        };
    }

    /** 美股（NYSE）休市日，规则生成 */
    private static boolean isUsHoliday(LocalDate day) {
        int year = day.getYear();
        return day.equals(observed(LocalDate.of(year, 1, 1), true))
                || day.equals(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3))
                || day.equals(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3))
                || day.equals(easter(year).minusDays(2)) // 耶稣受难日
                || day.equals(LocalDate.of(year, Month.MAY, 1)
                        .with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)))
                || day.equals(observed(LocalDate.of(year, 6, 19), false))
                || day.equals(observed(LocalDate.of(year, 7, 4), false))
                || day.equals(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1))
                || day.equals(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4))
                || day.equals(observed(LocalDate.of(year, 12, 25), false));
    }

    /**
     * 节日的实际休市日：周六→前一个周五、周日→后一个周一。
     *
     * @param skipSaturday 元旦特殊：落在周六时**不**提前到周五（跨年的周五照常开市），返回 null
     */
    private static LocalDate observed(LocalDate day, boolean skipSaturday) {
        return switch (day.getDayOfWeek()) {
            case SATURDAY -> skipSaturday ? null : day.minusDays(1);
            case SUNDAY -> day.plusDays(1);
            default -> day;
        };
    }

    /** 某月的第 n 个星期几（nth=3 → 第三个周一） */
    private static LocalDate nthWeekday(int year, Month month, DayOfWeek weekday, int nth) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(nth, weekday));
    }

    /** 复活节（格里高利历 Anonymous computus），只用来推耶稣受难日 */
    private static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
