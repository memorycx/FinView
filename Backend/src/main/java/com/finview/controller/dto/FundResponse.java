package com.finview.controller.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定投计划响应体，对应前端 types.ts 的 Fund。
 *
 * 注意这是**推导出来的视图**、不是某张表的行：计划状态由 FundService 把 adjustments
 * 事件流 fold 得到，name / category 取自 asset 表。字段名与前端契约一一对应，别改名。
 *
 * 几条前端依赖的约定（改动前先看组件）：
 * - series / adjustments 必须是数组且非 null：Dashboard 判 series.length，
 *   PlanDetail 做 [...fund.adjustments]，缺了会直接抛错；
 * - active=true 时 frequency / amount 必须非空：PlanPanel 会 formatCNY(amount)；
 * - id 是前端的选中态 key（还进 ?fund= 路由参数），所以取 code 这种稳定值。
 */
@Data
@NoArgsConstructor
public class FundResponse {

    /** 计划标识，取 code */
    private String id;

    /** 资产名称，asset 表缺行时兜底为 code */
    private String name;

    /** 资产编码 */
    private String code;

    /** 资产分类：fund / stock / bond / cash */
    private String category;

    /**
     * 基金细分类型：asset.asset_type（equity 股基 / bond 债基）。
     * null = 未标注（asset 行缺失或存量没标过），前端按股票基金显示；
     * 现金不进 /funds（foldForUser 已滤掉），所以这里不会出现 cash。
     */
    private String assetType;

    /** 定投是否进行中 */
    private Boolean active;

    /** 是否已归档：来自 asset.archived（用户手工标记），与定投是否进行中无关 */
    private Boolean archived;

    /** 当前定投频率；已结束或从未定投时为 null */
    private String frequency;

    /** 当前每期定投金额；已结束或从未定投时为 null */
    private BigDecimal amount;

    /** 计划开始时间，形如 2026-06（前端 PerformanceChart 按长度 7 识别为月份） */
    private String startDate;

    /** 累计投入本金，取自 asset_series 末行 */
    private BigDecimal principal;

    /** 当前市值 = 累计份额 × 最新净值，取自 asset_series 末行；没有净值时等于 principal */
    private BigDecimal current;

    /** 累计申购手续费 = 序列各日 fee 之和；收益与收益率把它算进成本（口径见 FundService.returnRate） */
    private BigDecimal fee;

    /** 累计投入 = Σ 正的当日出资（净投入 + 手续费），卖出日不计入；收益率的分母 */
    private BigDecimal invested;

    /**
     * 年化收益率（XIRR，资金加权、ACT/365），小数：0.08 = 8%。
     * null = 持有不足 30 天或数学上无解，前端显示「—」；口径见 {@link com.finview.common.Xirr}。
     * 与上面的「收益率」不是一回事：那个是期末的简单收益率，这个是按每笔钱在场天数折现的资金加权年化。
     */
    private BigDecimal annualizedRate;

    /**
     * 最后更新时间（asset.last_update_time）：该计划最后一次生成 / 重算序列的时刻，ISO 8601；
     * asset 行缺失时为 null。看板折线图页脚的「最后更新」显示它（组合总览另有 PortfolioSeriesResponse）。
     */
    private LocalDateTime lastUpdateTime;

    /** 本金 / 市值走势，按日期升序逐日连续，最后一个点与 principal / current 对齐 */
    private List<DayPointResponse> series;

    /** 定投调整记录，按时间正序（PlanDetail 自己 reverse 成新→旧展示） */
    private List<PlanAdjustmentResponse> adjustments;
}
