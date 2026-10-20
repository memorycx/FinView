package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「资产 → 所属计划」的一条关联（plan_asset 表 JOIN saving_plans 取名字）。
 *
 * 2026-10-13 起资产与具体计划**一对一**（见 SavingsService.linkAsset 的排他校验）：
 * 一个资产至多出现在一条这样的记录里；「总资产」计划不走这张表（它默认全含）。
 * 候选接口用它把「已在『养老金』里」这种提示带给前端。
 */
@Data
@NoArgsConstructor
public class AssetPlanLink {

    private Long assetId;

    private Long planId;

    /** 所属计划的名字（给提示文案用） */
    private String planName;
}
