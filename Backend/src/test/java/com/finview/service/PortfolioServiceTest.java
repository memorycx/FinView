package com.finview.service;

import com.finview.entity.AssetSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PortfolioService 组合年化新增的聚合逻辑测试。
 *
 * 只测包级可见的 static {@code aggregate}（不启 Spring）：这次新增的是一列 amount 的按天求和，
 * 风险都在「存量列相加、流量列相加」这一个点上；mapper 那几行组装与 FundService 一样不覆盖。
 */
class PortfolioServiceTest {

    private static AssetSeries row(String code, String day, String amount, String fee,
                                   String principal, String total) {
        return new AssetSeries(7L, code, LocalDate.parse(day),
                new BigDecimal(amount), new BigDecimal(fee),
                new BigDecimal(principal), new BigDecimal(total));
    }

    /** BigDecimal.equals 连 scale 一起比，这里只关心数值 */
    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual);
    }

    @Test
    @DisplayName("按天汇总：存量列相加、当日流量列相加，起点不同的 code 不互相污染")
    void aggregatesByDay() {
        // 数组下标：[0] 本金合计 [1] 市值合计 [2] 当日手续费合计 [3] 当日净投入合计
        Map<LocalDate, BigDecimal[]> byDay = PortfolioService.aggregate(List.of(
                row("A", "2025-01-01", "100", "1", "100", "100"),
                row("A", "2025-01-02", "0", "0", "100", "110"),
                row("B", "2025-01-02", "200", "0", "200", "200"),
                row("B", "2025-01-03", "0", "0", "200", "180")));

        assertEquals(3, byDay.size());

        BigDecimal[] day1 = byDay.get(LocalDate.parse("2025-01-01"));
        assertValue("100", day1[0]);   // B 还没开始，只有 A 的本金
        assertValue("100", day1[1]);
        assertValue("1", day1[2]);
        assertValue("100", day1[3]);

        BigDecimal[] day2 = byDay.get(LocalDate.parse("2025-01-02"));
        assertValue("300", day2[0]);   // 100 + 200
        assertValue("310", day2[1]);   // 110 + 200
        assertValue("0", day2[2]);     // 当天谁都没出手续费
        assertValue("200", day2[3]);   // 只有 B 当天有净投入

        BigDecimal[] day3 = byDay.get(LocalDate.parse("2025-01-03"));
        assertValue("300", day3[0]);   // 200 + A 结转过来的 100（A 的序列停在 1/2）
        assertValue("290", day3[1]);   // 180 + A 结转过来的 110
        assertValue("0", day3[2]);
        assertValue("0", day3[3]);
    }

    @Test
    @DisplayName("序列先结束的 code 要结转：组合末点不能只剩「今天有行」的那只")
    void carriesForwardEndedSeries() {
        // 2026-10-01 的真实故障：000051 被单独重算到当天，其它 code 还停在 09-30，
        // 不结转的话组合末点就只剩 000051 一行（本金 4.93 / 市值 20.01），曲线直接塌下来
        Map<LocalDate, BigDecimal[]> byDay = PortfolioService.aggregate(List.of(
                row("A", "2025-01-01", "100", "1", "100", "100"),
                row("A", "2025-01-02", "0", "0", "100", "110"),
                row("B", "2025-01-01", "200", "0", "200", "200"),
                row("B", "2025-01-02", "0", "0", "200", "205"),
                row("B", "2025-01-03", "0", "0", "200", "210")));

        BigDecimal[] last = byDay.get(LocalDate.parse("2025-01-03"));
        assertValue("300", last[0]);   // 100（A 结转）+ 200
        assertValue("320", last[1]);   // 110（A 结转）+ 210
        assertValue("0", last[2]);
        assertValue("0", last[3]);     // 结转日没有现金流，别把上一笔又记一遍
    }
}
