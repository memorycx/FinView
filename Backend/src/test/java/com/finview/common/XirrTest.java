package com.finview.common;

import com.finview.entity.AssetSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Xirr 的算法测试：现金流口径、求解器（牛顿 + 二分兜底）、以及「什么时候不给值」的边界。
 *
 * 日期从 2025-01-01 起算（非闰年）：D0 → D1 正好 365 天（t = 1），D0 → D2 正好 730 天（t = 2），
 * 所以期望值都能手算，而不是「跑一遍看看是多少」。
 */
class XirrTest {

    private static final LocalDate D0 = LocalDate.parse("2025-01-01");
    private static final LocalDate D1 = LocalDate.parse("2026-01-01");
    private static final LocalDate D2 = LocalDate.parse("2027-01-01");

    private static Xirr.CashFlow flow(LocalDate day, String amount) {
        return new Xirr.CashFlow(day, new BigDecimal(amount));
    }

    /** asset_series 的一行：amount 是当日净投入，fee 是当日手续费 */
    private static AssetSeries row(String code, String day, String amount, String fee, String total) {
        return new AssetSeries(7L, code, LocalDate.parse(day),
                new BigDecimal(amount), new BigDecimal(fee), null, new BigDecimal(total));
    }

    private static void assertRate(double expected, Xirr.CashFlow... flows) {
        BigDecimal rate = Xirr.annualizedRate(List.of(flows));
        assertNotNull(rate, "应该算得出年化");
        assertEquals(expected, rate.doubleValue(), 1e-6);
    }

    /* ==================== 求解器 ==================== */

    @Test
    @DisplayName("一年翻倍 → 100%；两年翻倍 → √2 − 1")
    void doubling() {
        assertRate(1.0, flow(D0, "-1000"), flow(D1, "2000"));
        assertRate(Math.sqrt(2) - 1, flow(D0, "-1000"), flow(D2, "2000"));
    }

    @Test
    @DisplayName("赚 10% / 亏 10%：亏损给负数，不再归零")
    void gainAndLoss() {
        assertRate(0.1, flow(D0, "-1000"), flow(D1, "1100"));
        assertRate(-0.1, flow(D0, "-1000"), flow(D1, "900"));
    }

    @Test
    @DisplayName("多笔投入：解 5x² − 2x − 2 = 0（x = 1/(1+r)），年化 15.83%")
    void multipleContributions() {
        assertRate(0.1583124, flow(D0, "-1000"), flow(D1, "-1000"), flow(D2, "2500"));
    }

    @Test
    @DisplayName("近全损（−1000 → +1）：牛顿会被压到定义域边界，靠二分兜底给出 −99.9%")
    void nearTotalLoss() {
        assertRate(-0.999, flow(D0, "-1000"), flow(D1, "1"));
    }

    /* ==================== 口径与边界 ==================== */

    @Test
    @DisplayName("改口径的意义：分 12 个月投入 1200、期末 1260，年化 ≈ 9.4%（期末简单收益率只有 5%）")
    void contributionTimingMatters() {
        // 钱平均只在场半年，资金加权年化必须明显高于「收益 / 总投入」的 5%。
        // 旧的前端口径（total/cost 开 12/月数 次方）在这里会给出 5%，那才是被修掉的偏差
        List<Xirr.CashFlow> flows = new ArrayList<>();
        for (int month = 0; month < 12; month++) {
            flows.add(flow(D0.plusMonths(month), "-100"));
        }
        flows.add(flow(D1, "1260"));

        BigDecimal rate = Xirr.annualizedRate(flows);
        assertNotNull(rate);
        assertEquals(0.0936, rate.doubleValue(), 0.005);
    }

    @Test
    @DisplayName("持有不足 30 天不给年化；恰好 30 天给")
    void minimumHoldingDays() {
        assertNull(Xirr.annualizedRate(List.of(flow(D0, "-1000"), flow(D0.plusDays(29), "1100"))));
        assertNotNull(Xirr.annualizedRate(List.of(flow(D0, "-1000"), flow(D0.plusDays(30), "1100"))));
    }

    @Test
    @DisplayName("零现金流不参与：既不影响求解，也不能把「首笔/末笔」的日期带偏")
    void zeroFlowsAreSkipped() {
        assertRate(1.0, flow(D0, "-1000"), flow(D0.plusDays(10), "0"), flow(D1, "2000"));
        // 只有一笔非零现金流（另一笔是 0）→ 无解
        assertNull(Xirr.annualizedRate(List.of(flow(D0, "-1000"), flow(D1, "0"))));
    }

    @Test
    @DisplayName("现金流同号 / 空列表 / 只有一笔 → null")
    void unsolvable() {
        assertNull(Xirr.annualizedRate(List.of()));
        assertNull(Xirr.annualizedRate(List.of(flow(D0, "-1000"))));
        assertNull(Xirr.annualizedRate(List.of(flow(D0, "-1000"), flow(D1, "-1000"))));
        assertNull(Xirr.annualizedRate(List.of(flow(D0, "1000"), flow(D1, "1000"))));
    }

    @Test
    @DisplayName("ofSeries：手续费吃平收益 → −1%（用裸 amount 会算成 0%）")
    void feeIsPartOfTheCashFlow() {
        List<AssetSeries> rows = List.of(
                row("T", "2025-01-01", "990", "10", "990"),    // 掏 1000，扣掉 10 手续费，990 买入
                row("T", "2026-01-01", "0", "0", "990"));      // 净值一年不动，市值还是 990

        assertEquals(-0.01, Xirr.ofSeries(rows).doubleValue(), 1e-6);
    }

    @Test
    @DisplayName("ofSeries：同日既买又卖按净额算；末日市值为 0（已清仓）时末笔取卖出日")
    void sameDayBuyAndSellWithZeroTerminal() {
        List<AssetSeries> rows = List.of(
                row("T", "2025-01-01", "99", "1", "99"),
                row("T", "2026-01-01", "-150", "0", "0"));

        // 现金流 = −(99+1) = −100，+150；末日的 0 被剔掉，持有期仍是 D0 → D1
        assertEquals(0.5, Xirr.ofSeries(rows).doubleValue(), 1e-6);
    }

    @Test
    @DisplayName("ofSeries(rows, from)：清过仓的只算 from 之后这一轮")
    void episodeAfterClearance() {
        List<AssetSeries> rows = List.of(
                row("T", "2025-01-01", "1000", "0", "1000"),   // 第一轮：买入
                row("T", "2025-03-01", "-1100", "0", "0"),     // 清仓，本金归零
                row("T", "2025-06-01", "100", "0", "100"),     // 第二轮：重新开始
                row("T", "2026-01-01", "0", "0", "200"));

        BigDecimal secondEpisode = Xirr.ofSeries(List.of(rows.get(2), rows.get(3)));
        BigDecimal afterClearance = Xirr.ofSeries(rows, LocalDate.parse("2025-03-01"));

        assertNotNull(afterClearance);
        assertEquals(secondEpisode.doubleValue(), afterClearance.doubleValue(), 1e-9);
        // 混进第一轮（24 天赚 10%）会把年化摊成另一个数
        assertNotEquals(Xirr.ofSeries(rows).doubleValue(), afterClearance.doubleValue());

        // 清仓之后还没重新建仓：这一轮没有现金流，不给年化
        assertNull(Xirr.ofSeries(rows.subList(0, 2), LocalDate.parse("2025-03-01")));
    }

    @Test
    @DisplayName("ofSeries：行序打乱不影响结果（内部按日期排），空序列与单行返回 null")
    void rowOrderDoesNotMatter() {
        List<AssetSeries> sorted = List.of(
                row("T", "2025-01-01", "1000", "0", "1000"),
                row("T", "2026-01-01", "0", "0", "2000"));
        List<AssetSeries> shuffled = List.of(sorted.get(1), sorted.get(0));

        assertEquals(Xirr.ofSeries(sorted).doubleValue(), Xirr.ofSeries(shuffled).doubleValue(), 1e-9);
        assertNull(Xirr.ofSeries(List.of()));
        assertNull(Xirr.ofSeries(List.of(sorted.get(0))));
    }
}
