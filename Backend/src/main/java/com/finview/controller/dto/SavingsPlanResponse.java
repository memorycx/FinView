package com.finview.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 存钱计划响应体，对应前端 types.ts 的 SavingsPlan。
 *
 * 计划本身存 saving_plans 表，currentAmount / assets / series 都是算出来的：
 * - assets：总资产计划 = 该用户全部未归档资产；小愿望 = plan_asset 关联表里显式关联的（SavingsService.assetsOf）；
 * - currentAmount：上面这些资产的最新市值合计（**不落库**，saving_plans.current_amount 列已删）；
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

    /**
     * 计划类型 code：内置 asset 总资产 / life 生活保障 / wish 愿望，或用户自定义类型的 code（c+随机串）。
     * 取值一定在该用户的类型表（/savings/plan-types）里；只有 asset 有额外规则（全账号唯一、必须带目标）。
     */
    private String type;

    /** 类型显示名（type 对应的 name，用户可改）；前端的分类标签直接用它，不要自己再映射一套 */
    private String typeName;

    /** 详情页头图 URL，没有则前端用占位 */
    private String image;

    /** 当前市值：计划内资产的最新市值合计（算出来的，不落库） */
    private BigDecimal currentAmount;

    /** 目标金额；null = 这个计划不设目标（只看这组资产的收益），此时 targetDate / monthlyPlanAmount 也是 null */
    private BigDecimal targetAmount;

    /**
     * 计划整体的收益四件套（收益率口径，与 /funds 的单只基金一致，前端直接用 returnRate() 算）。
     * **只统计非现金资产**，而 currentAmount 是含现金的总市值 —— 这是故意的，别把两者混着算。
     */
    private SavingsReturnResponse returnStats;

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

    /**
     * 未来 12 个完整自然月的定投流入预测（按资产拆分）。
     * **只有计划详情类响应填充**（GET /savings/plans/{id}、新建、关联/解除）；列表响应为 null。
     * 口径见 {@link com.finview.service.CashFlowService#planDca}：
     * 只算周期定投（action=1/2），金额 = actual amount + fee，按实际扣款日归月。
     */
    private SavingsInflowForecastResponse inflowForecast;
}
