package com.finview.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 定投计划时间线上的一条调整记录，对应前端 types.ts 的 PlanAdjustment。
 *
 * 与实体 Adjustment 的区别有两点，都是前端渲染要求的：
 * - action 是**展示文案**（「开始定投」「下调金额」这类），不是 1/2/3/4 的动作码，
 *   由 FundService 对比上一条状态生成；
 * - 「结束定投」的记录把 frequency / amount 归一成 null：
 *   PlanDetail / PlanTimeline 都用 `adj.amount === null` 判断要不要显示「停止定投」徽标，
 *   非 null 的话会走进 formatCNY 分支，把停止显示成一条正常的定投。
 */
@Data
@NoArgsConstructor
public class PlanAdjustmentResponse {

    /** 调整记录ID，前端类型是 string，故这里转成字符串 */
    private String id;

    /** 记录日期，形如 2026-06-15 */
    private String date;

    /** 变动原因，用户录入时填的原文 */
    private String reason;

    /** 动作文案，如「开始定投」「上调金额」「结束定投」 */
    private String action;

    /** 调整后的定投频率，null 表示停止定投 */
    private String frequency;

    /** 调整后的每期定投金额，null 表示停止定投 */
    private BigDecimal amount;

    /** 备注 */
    private String note;
}
