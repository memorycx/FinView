package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资产调整记录实体，对应 `adjustments` 表。
 *
 * 字段名与前端 types.ts 里的 Adjustment 接口一一对应（adjustment_id 靠
 * map-underscore-to-camel-case 映射成 adjustmentId），控制层直接返回本实体即可。
 *
 * action 取值见 {@link com.finview.service.AdjustmentService}：
 * 1 定投开始 / 2 结束定投 / 3 买入 / 4 卖出（现金的进账 / 出账也走 3 / 4）。
 */
@Data
@NoArgsConstructor
public class Adjustment {

    /** 调整记录ID，自增主键 */
    private Long adjustmentId;

    /** 所属用户id，来自 JWT，不接受前端传入 */
    private Long userId;

    /** 资产编码 */
    private String code;

    /** 记录日期 */
    private LocalDate date;

    /** 变动原因 */
    private String reason;

    /** 动作：1定投开始，2结束定投，3买入，4卖出（现金的进账 / 出账） */
    private Integer action;

    /**
     * 是否从下一交易日起生效：true = 记录日只写时间线，频率与金额都从严格晚于记录日的
     * 下一个交易日起生效（当天仍按原计划扣款）。只对 action=1 有意义，
     * 其余动作统一归一成 false；历史记录默认 false，保持原有「只改金额当天生效」的口径。
     */
    private Boolean deferred;

    /** 频率，仅 action=1 有意义，其余动作统一归一成 null */
    private String frequency;

    /** 金额，用 BigDecimal 承接 DECIMAL(18,4)，避免 double 的精度误差 */
    private BigDecimal amount;

    /** 备注 */
    private String note;
}
