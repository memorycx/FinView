package com.finview.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 分布统计项 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistributionItem {

    private String name;

    private long value;
}
