package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.PortfolioSeriesResponse;
import com.finview.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组合总览接口。context-path 为 /api，故实际路径是 /api/portfolio/**。
 *
 * GET /portfolio/series  按天汇总全部进行中持仓的本金与市值 + 组合年化收益率（前端看板的组合总览）
 *
 * 与 /funds 一样是只读视图：数据来自 asset_series，没有对应的写入接口，
 * 增删改都走 AdjustmentController。需要认证，user_id 从 token 解析。
 */
@RestController
@RequestMapping("/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;

    /** 组合走势（按日期升序）与组合年化；没有任何进行中的持仓时是空曲线 + null 年化 */
    @GetMapping("/series")
    public Result<PortfolioSeriesResponse> series(Authentication authentication) {
        return Result.success(portfolioService.series(currentUserId(authentication)));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
