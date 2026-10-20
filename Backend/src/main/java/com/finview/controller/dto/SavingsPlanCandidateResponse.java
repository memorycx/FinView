package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 「添加资产」面板里的一条候选资产，对应前端 types.ts 的 SavingsPlanCandidate。
 *
 * 一个计划的候选 = 该用户的**全部资产**（含现金、含已归档），
 * linked / archived / linkedPlanName 三个字段给前端决定这一行怎么渲染：
 * - linked：已关联到**本计划**（总资产计划下恒 true，它默认全含）；
 * - archived：已归档，不允许新关联（服务端也会拒），但已关联的仍要显示出来以便解除；
 * - linkedPlanName：**已在另一个计划里**（一一对应规则，2026-10-13 起）时给那个计划的名字，
 *   前端据此把这一行禁用并提示「已在『养老金』里」；没有归属、或就是本计划时是 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsPlanCandidateResponse {

    /** 资产 id（关联 / 解除关联的入参） */
    private String id;

    /** 资产编码 */
    private String code;

    /** 资产名称（没填过名字的用编码兜底） */
    private String name;

    /** 最新市值（元），口径与计划详情里的资产金额一致 */
    private BigDecimal amount;

    /** 是否已关联到本计划 */
    private boolean linked;

    /** 是否已归档 */
    private boolean archived;

    /** 已在另一个计划里时是那个计划的名字（本计划 / 无归属时为 null） */
    private String linkedPlanName;
}
