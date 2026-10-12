package com.finview.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合总览响应体，对应前端 types.ts 的 PortfolioSeries。
 *
 * series 是原有的逐日汇总曲线（口径见 PortfolioService），annualizedRate 是组合的
 * XIRR 年化收益率——两者由同一次聚合算出，保证曲线与年化严格同源。
 *
 * annualizedRate 可以为 null（持有不足 30 天 / 无法求解），前端显示「—」；
 * series 必须是数组且非 null（Dashboard 判 series.length，缺了会一直停在「数据加载中…」）。
 */
@Data
@NoArgsConstructor
public class PortfolioSeriesResponse {

    /** 按日期升序的组合走势：principal = 累计本金合计，total = 市值合计，fee = 当日手续费合计 */
    private List<DayPointResponse> series;

    /** 组合年化收益率（XIRR），小数：0.08 = 8%；null = 无法计算 */
    private BigDecimal annualizedRate;

    /**
     * 组合的「最后更新时间」= 该用户**全部 asset 行**（含已归档与现金）last_update_time 的 MAX，
     * 口径见 PortfolioService；一条 asset 行都没有时为 null。看板折线图页脚显示它。
     */
    private LocalDateTime lastUpdateTime;

    public PortfolioSeriesResponse(List<DayPointResponse> series, BigDecimal annualizedRate,
                                   LocalDateTime lastUpdateTime) {
        this.series = series;
        this.annualizedRate = annualizedRate;
        this.lastUpdateTime = lastUpdateTime;
    }
}
