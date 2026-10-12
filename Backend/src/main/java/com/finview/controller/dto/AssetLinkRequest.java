package com.finview.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「把某个资产关联到存钱计划」的入参（POST /savings/plans/{id}/assets），
 * 对应前端 types.ts 里 linkPlanAsset 的第二个参数。
 *
 * assetId 用字符串：资产 id 在前端一路是 string（和计划的 id 一样，避免 JS 大整数精度问题）。
 * 归属、是否已归档、计划类型这些规则都在 SavingsService.linkAsset 里校验。
 */
@Data
@NoArgsConstructor
public class AssetLinkRequest {

    /** 资产 id */
    @NotBlank(message = "资产 id 不能为空")
    private String assetId;
}
