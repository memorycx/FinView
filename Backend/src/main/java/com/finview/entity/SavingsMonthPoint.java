package com.finview.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 月度存入数据点 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsMonthPoint {

    /** YYYY-MM */
    private String month;

    /** 当月金额（元） */
    private long amount;
}
