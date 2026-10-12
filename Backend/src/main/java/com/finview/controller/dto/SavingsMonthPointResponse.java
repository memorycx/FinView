package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 存钱计划的月度数据点，对应前端 types.ts 的 SavingsMonthPoint。
 * month 形如 2026-08，amount 是该月月底该计划持有资产的市值合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsMonthPointResponse {

    /** 月份，形如 2026-08 */
    private String month;

    /** 该月市值（元） */
    private BigDecimal amount;
}
