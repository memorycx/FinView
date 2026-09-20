package com.finview.entity;

import lombok.Data;

import java.util.List;

/** 资产总览 */
@Data
public class AssetSummary {

    /** 总资产（元） */
    private long totalAssets;

    /** 按资产大类的市值分布 */
    private List<Allocation> allocation;
}
