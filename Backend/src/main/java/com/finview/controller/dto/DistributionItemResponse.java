package com.finview.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 资产分布的一项，对应前端 types.ts 的 DistributionItem。
 *
 * /assets/distribution 不带 dim 时返回三个维度的全部数据（前端一次拿全，
 * 切换行业/区域/币种不用重新请求），带 dim 时只返回该维度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistributionItemResponse {

    /** 分组名，如「宽基指数」「A股」「人民币 CNY」 */
    private String name;

    /** 该组当前市值（元） */
    private BigDecimal value;
}
