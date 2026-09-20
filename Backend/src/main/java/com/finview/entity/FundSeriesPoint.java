package com.finview.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 带基金 id 的走势点，用于组合总览按基金聚合 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FundSeriesPoint extends DayPoint {

    private String fundId;

    public FundSeriesPoint() {
        super();
    }

    public FundSeriesPoint(String fundId, LocalDate day, long principal, long total) {
        super(day, principal, total);
        this.fundId = fundId;
    }
}
