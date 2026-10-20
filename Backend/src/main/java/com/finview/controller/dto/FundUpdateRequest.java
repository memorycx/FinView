package com.finview.controller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 编辑基金基础信息请求体（PUT /funds/{id}，定投详情页的「编辑」按钮）。
 *
 * 只覆盖 asset 表里**用户维护的元数据**：name / asset_type / rate / market。
 * 编码（asset.code）是 adjustments、asset_series、nav_trend、plan_asset 的关联键，不在这里开放修改；
 * active / frequency / startDate / principal / total 是 fold 出来的快照，也不能从这里改。
 *
 * 费率与市场会直接影响序列：rate 决定历史买入的申购手续费，market 决定 QDII 的扣款日，
 * 所以服务层在它们发生变化时会整段重算 asset_series；只改名称 / 类型不会触发重算。
 */
@Data
@NoArgsConstructor
public class FundUpdateRequest {

    /** 资产名称，必填（编辑表单不允许把名字清空） */
    @NotBlank(message = "基金名称不能为空")
    @Size(max = 100, message = "基金名称不能超过 100 个字符")
    private String name;

    /** 基金细分类型：equity 股基 / bond 债基 / money 货币基金（与 asset.asset_type 一致） */
    @NotBlank(message = "基金类型不能为空")
    private String assetType;

    /**
     * 申购费率，**百分数**（0.12 = 0.12%）；null = 不计费。
     * 上限 100 只是防手滑，实际费率不会到这个量级。
     */
    @DecimalMin(value = "0.0", message = "申购费率不能为负数")
    @DecimalMax(value = "100.0", message = "申购费率不能超过 100%")
    private BigDecimal rate;

    /**
     * 标的市场：us 美股 / hk 港股；null 或空串 = A 股（只按 A 股日历过滤扣款日）。
     * 取值校验在 FundService 里做（与 rate / assetType 同一处，错误信息统一）。
     */
    private String market;
}
