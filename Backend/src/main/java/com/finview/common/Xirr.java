package com.finview.common;

import com.finview.entity.AssetSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 年化收益率（XIRR）：资金加权、逐笔现金流、ACT/365。看板卡片上「年化收益率」的唯一算法。
 *
 * <p>为什么不用「期末市值 / 成本 的 12/月数 次方 − 1」：定投是持续加仓的，那个式子隐含
 * 「所有本金从第一天就投入并复利」，越往后投入的钱权重越大、偏差越大。XIRR 按每笔钱
 * 实际在场的天数折现，是资金加权口径，也是基金平台给「年化收益率」时用的口径。
 *
 * <p><b>现金流口径</b>（与 asset_series 一一对应，改这里等于改口径）：
 * <pre>
 *   CF(day)  = −( amount(day) + fee(day) )   // 出资为负、收回为正
 *   CF(末日) += total(末日)                   // 期末按市值「虚拟赎回」
 * </pre>
 * fee 必须加回去：当天的 amount 是**扣费后**的净投入，真金白银掏出去的是「净投入 + 手续费」。
 * 漏掉 fee 等于在等式两边同时把它抹掉——买 1000 元、费率 1%、净值一年不动，真实年化是
 * −1%（990/1000 − 1），只用 amount 会算成 0%。卖出日 amount 为负、fee 为 0，式子自然
 * 得到正的流入，不需要分支。于是 Σ|流出| = 本金 + 累计手续费，与「收益率」口径的成本一致。
 *
 * <p>无解一律返回 null，前端显示「—」：持有不足 {@link #MIN_HOLDING_DAYS} 天、现金流同号、
 * 非零现金流不足两笔、二分找不到变号区间。
 *
 * <p>本类不落库、不缓存：年化是「当时的现金流 + 当时的市值」的函数，只能读时现算。
 */
public final class Xirr {

    /** 持有不足 1 个月不给年化：样本太短，年化会把几天的波动放大成没有意义的数字 */
    public static final int MIN_HOLDING_DAYS = 30;

    /** 结果精度：与 FundService.returnRate 的 scale 对齐（前端只显示到 0.1%，绰绰有余） */
    private static final int RATE_SCALE = 8;

    /**
     * 折现率定义域 r &gt; −1。下界取 −0.9999 而不是贴着 −1：再低 Math.pow(1+r, −t) 会溢出，
     * 而且 −100% 与 −99.99% 在展示上没区别。
     */
    private static final double LOWER = -0.9999;
    private static final double UPPER = 1e6;

    /** 牛顿迭代：初值 0.1（Excel 同款）、上限 100 次。收敛判据见 {@link #solve} */
    private static final int NEWTON_ITER = 100;
    private static final double NEWTON_START = 0.1;
    private static final double TOLERANCE = 1e-10;

    /** 二分兜底：200 次足够把区间收到 double 的精度 */
    private static final int BISECT_ITER = 200;

    /**
     * 二分用的粗网格。标准现金流（先流出、最后一笔流入）的 NPV 对 r 单调，最多一个根，
     * 网格只负责找出变号区间，不需要密。
     */
    private static final double[] GRID = {
            LOWER, -0.999, -0.99, -0.95, -0.9, -0.8, -0.6, -0.4, -0.2, -0.1, 0,
            0.05, 0.1, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 5, 10, 20, 50,
            100, 1_000, 10_000, 100_000, UPPER,
    };

    private Xirr() {
    }

    /** 一笔外部现金流：出资为负，收回（含期末市值）为正 */
    public record CashFlow(LocalDate day, BigDecimal amount) {
    }

    /**
     * 当天的出资额 = 当日净投入 + 当日手续费：买入为正、卖出为负。
     * 现金流（取负）与「累计投入」（只取正的部分）用的是同一个口径，别各写一份。
     */
    public static BigDecimal outlay(BigDecimal amount, BigDecimal fee) {
        return zeroIfNull(amount).add(zeroIfNull(fee));
    }

    /**
     * 单个资产（一个 code）的年化收益率：由 asset_series 的行直接算，算全程。
     * 行序不限（内部按日期排序）；序列为空、持有不足 30 天或数学上无解时返回 null。
     */
    public static BigDecimal ofSeries(List<AssetSeries> rows) {
        return ofSeries(rows, null);
    }

    /**
     * 只算 from（最近一次清仓日）之后这一轮；from 为 null 表示没清过仓，算全程。
     *
     * <p>清仓是轮次分界：上一轮的钱已经全部收回，再和这一轮的现金流混在一起算年化，
     * 会把「24 天赚 4.9%」这种短期收益按整段持有期摊掉，得出一个没有意义的数字。
     * 清仓那天本身的现金流也不算（那天仓位已经归零）。
     */
    public static BigDecimal ofSeries(List<AssetSeries> rows, LocalDate from) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        List<AssetSeries> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(AssetSeries::getDay));
        AssetSeries last = sorted.get(sorted.size() - 1);

        // 清仓之后还没重新建仓：这一轮是空的，没有年化可言
        if (from != null && !last.getDay().isAfter(from)) {
            return null;
        }

        List<CashFlow> flows = new ArrayList<>(sorted.size() + 1);
        for (AssetSeries row : sorted) {
            if (from != null && !row.getDay().isAfter(from)) {
                continue;   // 上一轮的现金流（含清仓那天）
            }
            // 出资 = 当日净投入 + 当日手续费（见类注释：fee 必须加回来）
            flows.add(new CashFlow(row.getDay(), outlay(row.getAmount(), row.getFee()).negate()));
        }
        // 期末虚拟赎回：还在持有时就是最后一天的市值，已清仓时是 0（会被按零值剔掉）
        flows.add(new CashFlow(last.getDay(), zeroIfNull(last.getTotal())));

        return annualizedRate(flows);
    }

    /**
     * 核心求解：现金流需按日期升序，返回小数（0.08 = 8%）。
     * 无法求解返回 null（见类注释列的条件）。
     */
    public static BigDecimal annualizedRate(List<CashFlow> flows) {
        if (flows == null) {
            return null;
        }

        // 零现金流不影响 NPV，但会把「首笔/末笔」的日期带偏，先剔掉
        List<CashFlow> nonZero = flows.stream()
                .filter(flow -> flow.amount() != null && flow.amount().signum() != 0)
                .toList();
        if (nonZero.size() < 2) {
            return null;
        }

        // 持有期 = 首笔现金流 → 末笔现金流；首笔记录在未来（跨度 0 或负）也会在这里被挡掉
        LocalDate first = nonZero.get(0).day();
        LocalDate last = nonZero.get(nonZero.size() - 1).day();
        if (ChronoUnit.DAYS.between(first, last) < MIN_HOLDING_DAYS) {
            return null;
        }

        double[] t = new double[nonZero.size()];
        double[] cf = new double[nonZero.size()];
        boolean inflow = false;
        boolean outflow = false;
        for (int i = 0; i < nonZero.size(); i++) {
            CashFlow flow = nonZero.get(i);
            t[i] = ChronoUnit.DAYS.between(first, flow.day()) / 365.0;   // ACT/365，首笔当 t = 0
            cf[i] = flow.amount().doubleValue();                          // 只在这里出入 double
            inflow |= cf[i] > 0;
            outflow |= cf[i] < 0;
        }
        if (!inflow || !outflow) {
            return null;   // 同号：钱只出不进（或只进不出），没有收益率可言
        }

        Double rate = solve(t, cf);
        return rate == null ? null : BigDecimal.valueOf(rate).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 解 Σ cfᵢ · (1+r)^(−tᵢ) = 0：先牛顿，失败再网格找变号区间 + 二分。无根返回 null。
     *
     * <p>牛顿的收敛判据用**残差**（相对总现金流）而不是步长：极端亏损时迭代会被压到
     * r = −1 附近，步长越来越小但根本不是根，看步长会误判成收敛。
     */
    static Double solve(double[] t, double[] cf) {
        double scale = 0;
        for (double value : cf) {
            scale += Math.abs(value);
        }
        if (scale == 0) {
            return null;
        }

        double rate = NEWTON_START;
        for (int i = 0; i < NEWTON_ITER; i++) {
            double npv = npv(rate, t, cf);
            if (Math.abs(npv) <= TOLERANCE * scale) {
                return rate;
            }
            double slope = dNpv(rate, t, cf);
            if (slope == 0 || !Double.isFinite(slope)) {
                break;
            }

            double next = rate - npv / slope;
            if (!Double.isFinite(next) || next > UPPER) {
                break;
            }
            if (next <= LOWER) {
                next = (rate + LOWER) / 2;   // 越出定义域就拉回来，别让 1+r 变成 0 或负数
            }
            rate = next;
        }

        for (int i = 0; i + 1 < GRID.length; i++) {
            double low = GRID[i];
            double high = GRID[i + 1];
            double fLow = npv(low, t, cf);
            double fHigh = npv(high, t, cf);
            if (!Double.isFinite(fLow) || !Double.isFinite(fHigh)) {
                continue;
            }
            if (fLow == 0) {
                return low;
            }
            if (fHigh == 0) {
                return high;
            }
            if (Math.signum(fLow) == Math.signum(fHigh)) {
                continue;
            }

            for (int k = 0; k < BISECT_ITER; k++) {
                double mid = (low + high) / 2;
                double fMid = npv(mid, t, cf);
                if (fMid == 0) {
                    return mid;
                }
                if (Math.signum(fMid) == Math.signum(fLow)) {
                    low = mid;
                    fLow = fMid;
                } else {
                    high = mid;
                }
            }
            return (low + high) / 2;
        }
        return null;
    }

    /** 净现值：Σ cfᵢ · (1+r)^(−tᵢ) */
    private static double npv(double rate, double[] t, double[] cf) {
        double base = 1 + rate;
        double sum = 0;
        for (int i = 0; i < cf.length; i++) {
            sum += cf[i] * Math.pow(base, -t[i]);
        }
        return sum;
    }

    /** NPV 对 r 的导数：Σ −tᵢ · cfᵢ · (1+r)^(−tᵢ−1) */
    private static double dNpv(double rate, double[] t, double[] cf) {
        double base = 1 + rate;
        double sum = 0;
        for (int i = 0; i < cf.length; i++) {
            sum += -t[i] * cf[i] * Math.pow(base, -t[i] - 1);
        }
        return sum;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
