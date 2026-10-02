package com.finview.service;

import com.finview.entity.Adjustment;
import com.finview.entity.AssetSeries;
import com.finview.service.SeriesService.PlanState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 现金余额口径的纯计算测试（不启 Spring）。
 *
 * 风险集中在一个点上：**哪些天的基金流水要算进现金**。用户的历史买入（≈6741.97）发生在
 * 联动起点之前、从来没记过现金，一旦被算进来余额就会凭空少掉一大截 —— 所以这里逐个方向都钉死。
 */
class CashLedgerTest {

    /** 2026-10-08 = 国庆后第一个交易日，与 application.yml 的 finview.cash.link-from 一致 */
    private static final LocalDate LINK_FROM = LocalDate.parse("2026-10-08");

    private static final LocalDate TODAY = LocalDate.parse("2026-10-20");

    /** 现金记录：action 3 = 进账、4 = 出账（金额恒正，方向看 action） */
    private static Adjustment cashEvent(String date, int action, String amount) {
        Adjustment event = new Adjustment();
        event.setCode("CASH");
        event.setDate(LocalDate.parse(date));
        event.setAction(action);
        event.setAmount(new BigDecimal(amount));
        return event;
    }

    /** 基金序列行：amount 是扣费后净额（买入为正、卖出一笔支出为负），fee 单列 */
    private static AssetSeries row(String code, String day, String amount, String fee) {
        return new AssetSeries(7L, code, LocalDate.parse(day),
                new BigDecimal(amount), new BigDecimal(fee), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static PlanState fundState(String code, AssetSeries... rows) {
        PlanState state = new PlanState(code, LocalDate.parse("2026-01-01"));
        state.getRows().addAll(List.of(rows));
        return state;
    }

    /** BigDecimal.equals 连 scale 一起比，这里只关心数值 */
    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual);
    }

    @Test
    @DisplayName("手工记录按 action 方向累加")
    void sumsManualEntries() {
        BigDecimal balance = CashLedger.balance(List.of(
                        cashEvent("2026-10-01", 3, "5000"),
                        cashEvent("2026-10-02", 4, "1200"),
                        cashEvent("2026-10-03", 3, "200")),
                Map.of(), LINK_FROM, TODAY);

        assertValue("4000", balance);
    }

    @Test
    @DisplayName("未来日期的记录不计入")
    void ignoresFutureEntries() {
        BigDecimal balance = CashLedger.balance(List.of(
                        cashEvent("2026-10-01", 3, "5000"),
                        cashEvent("2026-11-01", 3, "9999")),
                Map.of(), LINK_FROM, TODAY);

        assertValue("5000", balance);
    }

    @Test
    @DisplayName("联动起点之前的历史买入一律不补")
    void ignoresHistoryBeforeLinkFrom() {
        // 起点之前四个多月、合计 6741.97 的净流出：一分都不该从现金里扣
        BigDecimal balance = CashLedger.balance(
                List.of(cashEvent("2026-10-01", 3, "10000")),
                Map.of("000051", fundState("000051",
                        row("000051", "2026-04-22", "1000", "1.2"),
                        row("000051", "2026-09-30", "5741.97", "7.25"))),
                LINK_FROM, TODAY);

        assertValue("10000", balance);
    }

    @Test
    @DisplayName("起点当天起的买入：扣「净额 + 手续费」")
    void deductsPurchasesFromLinkFrom() {
        BigDecimal balance = CashLedger.balance(
                List.of(cashEvent("2026-10-01", 3, "10000")),
                Map.of("000051", fundState("000051",
                        row("000051", "2026-10-08", "9.99", "0.01"),
                        row("000051", "2026-10-09", "9.99", "0.01"))),
                LINK_FROM, TODAY);

        assertValue("9980.00", balance);   // 10000 − 20
    }

    @Test
    @DisplayName("卖出一笔支出是流入：amount 为负、fee 为 0")
    void sellAddsCashBack() {
        BigDecimal balance = CashLedger.balance(
                List.of(cashEvent("2026-10-01", 3, "1000")),
                Map.of("000051", fundState("000051",
                        row("000051", "2026-10-08", "-314.69", "0"))),
                LINK_FROM, TODAY);

        assertValue("1314.69", balance);
    }

    @Test
    @DisplayName("多个 code 一起联动，未来日期的基金行不计")
    void sumsAcrossCodesAndIgnoresFutureRows() {
        BigDecimal balance = CashLedger.balance(
                List.of(cashEvent("2026-10-01", 3, "10000")),
                Map.of(
                        "000051", fundState("000051",
                                row("000051", "2026-10-08", "10", "0.02"),
                                row("000051", "2026-11-02", "500", "0.5")),   // 未来，不计
                        "013402", fundState("013402",
                                row("013402", "2026-10-08", "20", "0.03"))),
                LINK_FROM, TODAY);

        assertValue("9969.95", balance);   // 10000 − 10.02 − 20.03
    }

    @Test
    @DisplayName("余额不设下限：扣成负数也照实返回")
    void allowsNegativeBalance() {
        BigDecimal balance = CashLedger.balance(
                List.of(),
                Map.of("000051", fundState("000051",
                        row("000051", "2026-10-08", "40", "0.04"))),
                LINK_FROM, TODAY);

        assertValue("-40.04", balance);
    }

    @Test
    @DisplayName("现金 code 混进 fundStates 也不会把自己算两遍")
    void skipsCashState() {
        BigDecimal balance = CashLedger.balance(
                List.of(cashEvent("2026-10-01", 3, "1000")),
                Map.of("CASH", fundState("CASH", row("CASH", "2026-10-08", "1000", "0"))),
                LINK_FROM, TODAY);

        assertValue("1000", balance);
    }

    @Test
    @DisplayName("startDate 取最早一条记录，没有记录返回 null")
    void startDateIsEarliestEntry() {
        assertEquals(LocalDate.parse("2026-10-01"), CashLedger.startDate(List.of(
                cashEvent("2026-10-05", 3, "1"),
                cashEvent("2026-10-01", 4, "1"))));
        assertNull(CashLedger.startDate(List.of()));
    }
}
