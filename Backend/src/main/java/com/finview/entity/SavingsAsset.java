package com.finview.entity;

import lombok.Data;

/** 计划内持有的资产项 */
@Data
public class SavingsAsset {

    private String name;

    private Long amount;

    /** 图表主题色 CSS 变量，如 '--chart-1' */
    private String colorVar;
}
