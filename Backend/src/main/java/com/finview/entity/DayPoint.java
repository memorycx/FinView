package com.finview.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 时间序列数据点（按日/月采样） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DayPoint {

    /** YYYY-MM-DD */
    private LocalDate day;

    /** 累计投入本金（元） */
    private long principal;

    /** 当前总市值（元） */
    private long total;
}
