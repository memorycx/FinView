package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 存钱计划实体，对应 `saving_plans` 表。
 *
 * 计划与资产的关联走 {@link Asset#getPlanId()}（表设计.md 用 asset.plan_id 取代了 plan_asset 关联表）。
 * currentAmount 是独立字段、不会自动等于关联资产的市值——目前还没有计划的写接口，
 * 需要时直接在库里插（见 resources/db/data.sql 里的示例）。
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

    /** 当前已存金额 */
    private BigDecimal currentAmount;
}
