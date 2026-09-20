package com.finview.entity;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 存钱计划 */
@Data
public class SavingsPlan {

    private String id;

    private String name;

    private String description;

    /** asset | wish */
    private String type;

    /** 详情页头图 URL，留空则占位 */
    private String image;

    /** 已存金额（元） */
    private Long currentAmount;

    /** 目标金额（元） */
    private Long targetAmount;

    /** YYYY-MM-DD */
    private LocalDate startDate;

    /** YYYY-MM-DD */
    private LocalDate targetDate;

    /** 每月计划存入（元） */
    private Integer monthlyPlanAmount;

    private String quote;

    private String note;

    private String noteImage;

    /** 计划内持有的资产 */
    private List<SavingsAsset> assets;

    /** 月度存入走势 */
    private List<SavingsMonthPoint> series;
}
