package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 计划整体的收益四件套，字段名与含义**与单只基金的那四个完全一致**（FundResponse 的
 * current / principal / fee / invested），所以前端可以直接把它喂给 lib/finance.ts 的
 * `returnRate()`，不需要在别处再写一遍公式：
 *
 *   收益 = current − principal − fee；收益率 = 收益 / invested。
 *
 * **四个数都只统计非现金资产**（现金没有 asset_series、本金恒等于市值，算进去只会稀释收益率）。
 * 这一点必须四个字段同进同出 —— 若 current 用含现金的计划总市值、其余三个不含现金，
 * 收益会凭空多出一整笔现金（例：基金本金 1 万、市值 1.2 万，另有现金 5 千，
 * 混着算会得到 +70% 而不是 +20%）。
 * 含现金的总市值在 {@link SavingsPlanResponse#getCurrentAmount()} 里，两者口径不同是故意的。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsReturnResponse {

    /** 计划内非现金资产的最新市值合计（元） */
    private BigDecimal current;

    /** 累计投入本金（净投入，清过仓可为负） */
    private BigDecimal principal;

    /** 累计手续费（逐日 fee 之和，不是末行那一格） */
    private BigDecimal fee;

    /** 累计投入 = Σ 每只资产 Σ 每日 max(0, amount + fee)，收益率的分母 */
    private BigDecimal invested;
}
