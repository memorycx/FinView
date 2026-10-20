package com.finview.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新建 / 修改调整记录的入参，对应前端 types.ts 的 AdjustmentPayload。
 *
 * 这里只做单字段的必填、长度、正数校验，靠 @Valid 触发、由 GlobalExceptionHandler
 * 统一转成中文提示；「action 取值合法」「action=1 必须带频率」这类跨字段规则
 * 放在 AdjustmentService 里。
 *
 * 注意没有 userId 字段：记录归属由后端从 JWT 解析，不接受前端传入。
 */
@Data
@NoArgsConstructor
public class AdjustmentRequest {

    /** 资产编码 */
    @NotBlank(message = "资产编码不能为空")
    @Size(max = 64, message = "资产编码长度不能超过 64")
    private String code;

    /**
     * 资产名称，选填。
     * 注意它**不落 adjustments 表**（表设计.md 里这张表没有名字列）：填了就会写进
     * asset.name（auto 生成的资产行默认用 code 当名字），留空则不动已有名字。
     */
    @Size(max = 100, message = "资产名称长度不能超过 100")
    private String name;

    /**
     * 基金细分类型：equity 股基 / bond 债基 / money 货币基金，选填。
     * 同样不落 adjustments 表：填了才写 asset.asset_type（新建的基金行默认 equity）。
     * **不填 = 不修改已有分类**（给一只债基录常规买入不会把它翻回股基）；
     * 现金（code=CASH）带它会像 frequency 一样被归一掉 —— 现金的类型是系统语义。
     */
    @Size(max = 16, message = "资产类型长度不能超过 16")
    private String assetType;

    /** 记录日期，JSON 里形如 "2024-01-15" */
    @NotNull(message = "记录日期不能为空")
    private LocalDate date;

    /** 变动原因 */
    @Size(max = 255, message = "变动原因长度不能超过 255")
    private String reason;

    /** 动作：1定投开始，2结束定投，3买入，4卖出（现金的进账 / 出账也走 3 / 4） */
    @NotNull(message = "动作不能为空")
    private Integer action;

    /**
     * 是否从下一交易日起生效（仅 action=1 有意义）：
     * true = 记录日只写时间线，频率与金额从严格晚于记录日的下一个交易日起生效（当天仍按原计划扣）；
     * 不传 / false = 原有口径（只改金额当天生效、改频率次日生效）。
     * 定投详情页的「调整定投」按钮固定传 true。
     */
    private Boolean deferred;

    /** 定投频率，仅 action=1 需要，其余动作传了也会被忽略 */
    @Size(max = 64, message = "频率长度不能超过 64")
    private String frequency;

    /** 金额 */
    @NotNull(message = "金额不能为空")
    @Positive(message = "金额必须大于 0")
    private BigDecimal amount;

    /** 备注 */
    @Size(max = 512, message = "备注长度不能超过 512")
    private String note;
}
