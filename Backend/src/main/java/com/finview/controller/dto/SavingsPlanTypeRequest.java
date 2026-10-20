package com.finview.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新建 / 改名存钱计划类型的入参（两个接口共用）。单字段校验靠 @Valid，
 * 重名与系统类型的规则在 SavingsService。
 */
@Data
@NoArgsConstructor
public class SavingsPlanTypeRequest {

    /** 类型名称，如「旅行基金」 */
    @NotBlank(message = "类型名称不能为空")
    @Size(max = 32, message = "类型名称最多 32 个字")
    private String name;
}
