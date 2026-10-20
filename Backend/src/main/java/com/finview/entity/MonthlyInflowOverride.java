package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 「每月固定新增」的单月覆盖值，对应 `monthly_inflow_override` 表（2026-10-13 加）。
 *
 * 「定投分析」页的每月新增有两层：`user.monthly_inflow` 是默认值（卡片 1 编辑），
 * 本表按 (user_id, month) 存单月覆盖（点柱状图的柱子设置）。预测时某月有覆盖就用覆盖值、
 * 否则用默认值；删掉覆盖行 = 恢复默认（见 CashFlowService.project）。
 */
@Data
@NoArgsConstructor
public class MonthlyInflowOverride {

    /** 自增主键 */
    private Long id;

    /** 所属用户id */
    private Long userId;

    /** 目标月份，YYYY-MM */
    private String month;

    /** 该月的每月固定新增（元） */
    private BigDecimal amount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
