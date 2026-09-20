package com.finview.entity;

import lombok.Data;

import java.util.List;

/** 定投计划（一只基金或一个股票组合） */
@Data
public class Fund {

    private String id;

    private String name;

    private String code;

    /** fund | stock | bond | cash */
    private String category;

    /** 定投是否进行中（false = 已归档） */
    private Boolean active;

    /** daily | weekly | monthly */
    private String frequency;

    /** 当前每期定投金额（元） */
    private Integer amount;

    /** 开始时间 YYYY-MM */
    private String startDate;

    /** 累计投入本金（元） */
    private Long principal;

    /** 当前市值（元） */
    private Long current;

    /** 本金/市值走势 */
    private List<DayPoint> series;

    /** 定投调整记录（按时间正序） */
    private List<PlanAdjustment> adjustments;
}
