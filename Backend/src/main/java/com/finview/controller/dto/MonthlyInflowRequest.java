package com.finview.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 保存「每月固定新增」（定投分析页的现金流假设）的请求体，对应前端 updateMonthlyInflow。
 *
 * 单字段校验靠 @Valid（见 GlobalExceptionHandler），跨字段规则没有：
 * 金额只要求 ≥ 0、最多两位小数；null 合法且有意义——表示清空，页面回到「未设置」。
 */
@Data
@NoArgsConstructor
public class MonthlyInflowRequest {

    /** 每月固定新增（元），对应 user.monthly_inflow；null = 清空（预测按 0 计） */
    @DecimalMin(value = "0.00", message = "每月固定新增不能为负数")
    @Digits(integer = 13, fraction = 2, message = "每月固定新增最多两位小数")
    private BigDecimal monthlyInflow;
}
