package com.finview.entity;

import lombok.Data;

import java.time.LocalDate;

/** 定投调整记录 */
@Data
public class PlanAdjustment {

    /** 记录 id（如 a1） */
    private String id;

    /** YYYY-MM-DD */
    private LocalDate date;

    /** 更改原因 */
    private String reason;

    /** 调整动作简述，如「开始定投」「加大定投」「停止定投」 */
    private String action;

    /** 调整后的频率，null 表示停止定投 */
    private String frequency;

    /** 调整后的每期金额（元），null 表示停止定投 */
    private Integer amount;

    /** 备注（可选） */
    private String note;
}
