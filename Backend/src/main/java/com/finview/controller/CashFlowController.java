package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.CashFlowCalendarResponse;
import com.finview.controller.dto.CashFlowForecastResponse;
import com.finview.controller.dto.MonthlyInflowRequest;
import com.finview.service.CashFlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定投分析（现金流规划 / 现金积累预测）接口。context-path 为 /api，故实际路径是 /api/cashflow/**。
 *
 * GET /cashflow/forecast                 当前可用现金 + 未来 12 个整月的定投支出与月末余额预测
 * GET /cashflow/calendar?month=YYYY-MM   定投日历：某月每天的扣款与执行计划数 + 总览/基金/每周分布
 * PUT /cashflow/monthly-inflow           保存「每月固定新增」默认值（用户假设），返回重算后的整份预测
 * PUT /cashflow/monthly-inflow/{month}   保存某个月的覆盖值（金额 null = 恢复默认），同样返回整份预测
 *
 * GET 只读业务数据；两个 PUT 只写 user.monthly_inflow / monthly_inflow_override，绝不碰 asset / asset_series。
 * 全部需要认证（SecurityConfig 里 anyRequest().authenticated()），user_id 从 token 解析。
 */
@RestController
@RequestMapping("/cashflow")
@RequiredArgsConstructor
public class CashFlowController {

    private final CashFlowService cashFlowService;

    /** 现金流预测：四个卡片数字 + 12 个月明细，口径见 {@link CashFlowService} */
    @GetMapping("/forecast")
    public Result<CashFlowForecastResponse> forecast(Authentication authentication) {
        return Result.success(cashFlowService.forecast(currentUserId(authentication)));
    }

    /**
     * 定投日历：某个月每天的扣款金额与执行计划数 + 本月总览 / 每只基金 / 每周分布。
     * month 参形如 2026-10，缺省 = 当前月；格式不对返回 400。
     */
    @GetMapping("/calendar")
    public Result<CashFlowCalendarResponse> calendar(Authentication authentication,
                                                     @RequestParam(required = false) String month) {
        return Result.success(cashFlowService.calendar(currentUserId(authentication), month));
    }

    /** 保存默认的每月固定新增；body 里的金额为 null 表示清空（页面回到「未设置」） */
    @PutMapping("/monthly-inflow")
    public Result<CashFlowForecastResponse> updateMonthlyInflow(
            Authentication authentication, @Valid @RequestBody MonthlyInflowRequest request) {
        return Result.success(cashFlowService.updateMonthlyInflow(
                currentUserId(authentication), request.getMonthlyInflow()));
    }

    /**
     * 保存某个月的每月固定新增（点柱状图的柱子）；月份是 YYYY-MM。
     * body 里的金额为 null 表示删除该月的覆盖、恢复默认值（传 0 是「这个月收入为零」，不是恢复默认）。
     */
    @PutMapping("/monthly-inflow/{month}")
    public Result<CashFlowForecastResponse> updateMonthlyInflowOfMonth(
            Authentication authentication, @PathVariable String month,
            @Valid @RequestBody MonthlyInflowRequest request) {
        return Result.success(cashFlowService.updateMonthlyInflow(
                currentUserId(authentication), month, request.getMonthlyInflow()));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
