package com.finview.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 存钱计划响应体，对应前端 types.ts 的 SavingsPlan。
 *
 * 计划本身存 saving_plans 表，assets 与 series 是算出来的：
 * - assets：asset 表里 plan_id 指向本计划的资产（表设计.md 用 asset.plan_id 取代了 plan_asset 关联表）；
 * - series：这些资产的市值按月采样的走势。
 *
 * 几条前端渲染要求的约定（SavingsDetail / SavingsView 直接 .slice(0, 7)）：
 * - id 是 string（前端拿它做路由参数）；
 * - startDate / targetDate 形如 2026-12-31，且**不能为 null**（null 会让模板渲染直接抛错）；
 * - assets / series 必须是数组，不能为 null。
 */
@Data
@NoArgsConstructor
public class SavingsPlanResponse {

    /** 计划id，字符串形式（前端类型是 string，且进路由参数） */
    private String id;

    /** 计划名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 计划类型：asset 总资产目标 / wish 小愿望 */
    private String type;

    /** 详情页头图 URL，没有则前端用占位 */
    private String image;

    /** 当前已存金额 */
    private BigDecimal currentAmount;

    /** 目标金额 */
    private BigDecimal targetAmount;

    /** 计划开始日期，形如 2026-01-01 */
    private String startDate;

    /** 计划目标日期，形如 2030-12-31 */
    private String targetDate;

    /** 每月计划投入 */
    private BigDecimal monthlyPlanAmount;

    /** 备注引言 */
    private String quote;

    /** 详细备注 */
    private String note;

    /** 备注图片 */
    private String noteImage;

    /** 计划内持有的资产 */
    private List<SavingsAssetResponse> assets;

    /** 月度市值走势 */
    private List<SavingsMonthPointResponse> series;
}
