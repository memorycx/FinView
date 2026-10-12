package com.finview.service;

import com.finview.common.CashAsset;
import com.finview.entity.Adjustment;
import com.finview.entity.AssetSeries;
import com.finview.service.SeriesService.PlanState;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 现金余额的纯计算：手工记录的方向金额，减去基金侧从联动起点起的净流出（2026-10-01 加）。
 *
 * <pre>
 * 余额(today) = Σ(手工记录的方向金额, day ≤ today)
 *             − Σ_{code ≠ CASH}(当日 amount + fee, linkFrom ≤ day ≤ today)
 * </pre>
 *
 * 为什么是 {@code amount + fee}：asset_series 的 amount 是**扣掉申购费之后的净投入**
 * （买入为正、卖出一笔支出为负），手续费单列在 fee 里 —— 两者相加才是平台当天从卡里划走的钱。
 * 卖出时 amount 为负、fee 为 0，式子自然得到正的流入，不需要分支。
 *
 * **联动起点之前的历史一律不补**：用户的历史买入发生在起点之前，那部分钱早就出去了、
 * 也从来没记过现金，扣进来会让余额凭空少掉六千多。起点之后（含当天）的每一笔扣款/买入/卖出
 * 才动现金。
 *
 * 余额**不设下限**：钱花超了就是负数，这比悄悄 clamp 成 0 更接近事实，也是「该记一笔现金了」的信号。
 */
final class CashLedger {

    /** 一笔收入：现金增加（与 AdjustmentService 的 VALID_ACTIONS 一致） */
    private static final int ACTION_INCOME = 3;

    /** 一笔支出：现金减少 */
    private static final int ACTION_EXPENSE = 4;

    private CashLedger() {
    }

    /**
     * 现金余额。
     *
     * @param cashEvents 该用户的现金记录（code=CASH），方向由 action 决定
     * @param fundStates fold 出来的基金状态，按 code；现金 code 若混进来也会被跳过
     * @param linkFrom   联动起点（含当天），之前的历史不计入
     * @param today      只算到这一天，之后的记录（未来日期的录入）不计入
     */
    static BigDecimal balance(List<Adjustment> cashEvents,
                              Map<String, PlanState> fundStates,
                              LocalDate linkFrom, LocalDate today) {
        BigDecimal balance = BigDecimal.ZERO;

        for (Adjustment event : cashEvents) {
            if (event.getDate() == null || event.getDate().isAfter(today)) {
                continue;
            }
            BigDecimal amount = zeroIfNull(event.getAmount());
            if (Integer.valueOf(ACTION_INCOME).equals(event.getAction())) {
                balance = balance.add(amount);
            } else if (Integer.valueOf(ACTION_EXPENSE).equals(event.getAction())) {
                balance = balance.subtract(amount);
            }
            // 其它 action 忽略：现金上只允许 3/4（AdjustmentService 会拦），真进了库也不该污染余额
        }

        if (fundStates != null) {
            for (PlanState state : fundStates.values()) {
                if (CashAsset.isCash(state.getCode())) {
                    continue;
                }
                for (AssetSeries row : state.getRows()) {
                    if (row.getDay().isBefore(linkFrom) || row.getDay().isAfter(today)) {
                        continue;
                    }
                    balance = balance.subtract(zeroIfNull(row.getAmount()))
                            .subtract(zeroIfNull(row.getFee()));
                }
            }
        }
        return balance;
    }

    /** 现金资产的 startDate：最早一条手工记录；没有记录返回 null（asset.startDate 只用于展示） */
    static LocalDate startDate(List<Adjustment> cashEvents) {
        LocalDate earliest = null;
        for (Adjustment event : cashEvents) {
            LocalDate date = event.getDate();
            if (date == null) {
                continue;
            }
            if (earliest == null || date.isBefore(earliest)) {
                earliest = date;
            }
        }
        return earliest;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
