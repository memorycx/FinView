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
     * 图表主题色变量，如 --chart-1。
     * 前端直接写 `var(${colorVar})`，为 null 会渲染成透明，所以由服务端按顺序轮转补一个。
     */
    private String colorVar;
}
