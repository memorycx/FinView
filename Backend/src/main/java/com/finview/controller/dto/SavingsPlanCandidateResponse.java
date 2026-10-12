package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 「添加资产」面板里的一条候选资产，对应前端 types.ts 的 SavingsPlanCandidate。
 *
 * 一个计划的候选 = 该用户的**全部资产**（含现金、含已归档），
 * linked / archived 两个布尔给前端决定这一行怎么渲染：
 * - linked：已关联（总资产计划下恒 true，它默认全含）；
 * - archived：已归档，不允许新关联（服务端也会拒），但已关联的仍要显示出来以便解除。
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
}
