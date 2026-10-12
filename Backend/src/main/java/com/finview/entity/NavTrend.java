package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单位净值实体，对应 `nav_trend` 表。
 *
 * 数据来自外部（见 resources/python/fund_info.py，由 {@link com.finview.service.NavService}
 * 调起并落库），按 (day, code) 唯一——同一天同一只基金只有一条净值。
 *
 * value 是单位净值，用来把「投入金额」换算成份额：
 * 份额 = Σ(当日净投入 / 当日净值)，市值 = 累计份额 × 当日净值。
 * rate 是外部接口给的日增长率（百分数，如 -1.57 表示 -1.57%），只作展示/留档，不参与计算。
 */
@Data
@NoArgsConstructor
public class NavTrend {

    /** 自增主键 */
    private Long id;

    /** 净值日期 */
    private LocalDate day;

    /** 单位净值 */
    private BigDecimal value;

    /** 日增长率(%) */
    private BigDecimal rate;

    /** 资产编码 */
    private String code;

    public NavTrend(LocalDate day, BigDecimal value, BigDecimal rate, String code) {
        this.day = day;
        this.value = value;
        this.rate = rate;
        this.code = code;
    }
}
