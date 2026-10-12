package com.finview.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新建存钱计划的入参，对应前端 types.ts 的 SavingsPlanPayload。
 *
 * 这里只做单字段的必填、长度、正数校验，靠 @Valid 触发、由 GlobalExceptionHandler
 * 统一转成中文提示；「type 取值合法」「不能建第二个总资产计划」「有目标才要日期与每月存入」
 * 这类跨字段规则放在 SavingsService.validateNewPlan。
 *
 * 注意没有 userId 字段：归属从 JWT 解析，不接受前端传入。
 *
 * **目标金额是可选的**（2026-10-05）：不设目标（targetAmount 为 null）的计划就是「只看这组资产
 * 的收益」，此时 targetDate / monthlyPlanAmount 一律落 NULL —— 它们是给目标服务的。
 * startDate 仍然必填（详情页直接渲染）。
 */
@Data
@NoArgsConstructor
public class SavingsPlanRequest {

    /** 计划名称 */
    @NotBlank(message = "计划名称不能为空")
    @Size(max = 100, message = "计划名称长度不能超过 100")
    private String name;

    /** 计划类型：asset 总资产 / life 生活保障 / wish 愿望 */
    @NotBlank(message = "计划类型不能为空")
    @Size(max = 64, message = "计划类型长度不能超过 64")
    private String type;

    /** 描述 */
    @Size(max = 512, message = "描述长度不能超过 512")
    private String description;

    /** 封面图地址，选填（留空时前端渲染占位块） */
    @Size(max = 255, message = "封面图地址长度不能超过 255")
    private String image;

    /**
     * 目标金额，选填：不传 = 这个计划不设目标（只看这组资产的收益），
     * 此时 targetDate / monthlyPlanAmount 会被忽略并落 NULL，且 type 只能是 wish。
     */
    @DecimalMin(value = "0.01", message = "目标金额必须大于 0")
    private BigDecimal targetAmount;

    /** 计划开始日期，JSON 里形如 "2026-10-05" */
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    /** 计划目标日期，仅在设了目标金额时必填（跨字段规则在 service 里判） */
    private LocalDate targetDate;

    /** 每月计划投入，仅在设了目标金额时必填（用于推算预计完成时间） */
    @DecimalMin(value = "0.01", message = "每月计划投入必须大于 0")
    private BigDecimal monthlyPlanAmount;

    /**
     * 新建时要一并关联进来的资产 id（选填，新建表单里直接勾的那些）。
     * 只有生活保障 / 愿望用得上：总资产计划本来就包含全部未归档资产，传了也会被忽略。
     */
    private List<String> assetIds;

    /** 备注引言，选填 */
    @Size(max = 255, message = "引言长度不能超过 255")
    private String quote;

    /** 详细备注，选填 */
    private String note;

    /** 备注图片地址，选填 */
    @Size(max = 255, message = "备注图片地址长度不能超过 255")
    private String noteImage;
}
