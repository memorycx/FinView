package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 存钱计划实体，对应 `saving_plans` 表。
 *
 * 计划与资产是多对多，关联存在 `plan_asset` 表（PlanAssetMapper）；「总资产」计划不用关联，
 * 它默认包含该用户全部未归档资产。
 *
 * **没有「当前已存金额」字段**：完成度不落库，读接口里按关联资产的最新市值算
 * （SavingsService.toResponse），原来的 current_amount 列已删。
 */
@Data
@NoArgsConstructor
public class SavingPlan {

    /** 自增主键 */
    private Long id;

    /** 所属用户id */
    private Long userId;

    /** 计划名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 计划类型：asset 总资产目标 / wish 小愿望 */
    private String type;

    /** 头图地址 */
    private String image;

    /** 目标金额 */
    private BigDecimal targetAmount;

    /** 计划开始日期 */
    private LocalDate startDate;

    /** 计划目标日期 */
    private LocalDate targetDate;

    /** 每月计划投入 */
    private BigDecimal monthlyPlanAmount;

    /** 备注引言 */
    private String quote;

    /** 详细备注 */
    private String note;

    /** 备注图片 */
    private String noteImage;
}
