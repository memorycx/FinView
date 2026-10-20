package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 存钱计划内的一项资产，对应前端 types.ts 的 SavingsAsset。
 *
 * 来源：总资产计划 = 该用户全部未归档资产；小愿望 = plan_asset 关联表里显式关联的那些
 * （见 SavingsService.assetsOf）。amount 取该资产的最新市值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsAssetResponse {

    /** 资产 id（前端「解除关联」要用它） */
    private String id;

    /** 资产编码 */
    private String code;

    /** 资产名称 */
    private String name;

    /** 当前市值（元） */
    private BigDecimal amount;

    /**
     * 这只资产自己的收益四件套（口径与计划整体那份完全一致，就是逐只的那一份：
     * 收益 = current − principal − fee，收益率 = 收益 / invested）。
     * **现金为 null** —— 它没有 asset_series、本金恒等于市值，谈不上收益（与整体口径排除现金一致），
     * 前端对 null 显示「—」。
     */
    private SavingsReturnResponse returnStats;

    /**
     * 图表主题色变量，如 --chart-1。
     * 前端直接写 `var(${colorVar})`，为 null 会渲染成透明，所以由服务端按顺序轮转补一个。
     */
    private String colorVar;

    /**
     * 当前是否正在执行定投（最近一条 action=1/2 记录是「定投开始」且日期不晚于今天）。
     * **只有计划详情类响应填充**（GET /savings/plans/{id}、新建、关联/解除）；列表响应为 null，
     * 避免列表为每个计划各 fold 一次。前端把 null 当「未计算」处理。
     */
    private Boolean dcaActive;

    /** 当前定投频率（daily / weekly / monthly）；未在定投时为 null */
    private String dcaFrequency;

    /** 当前每期定投金额（元）；未在定投时为 null */
    private BigDecimal dcaAmount;
}
