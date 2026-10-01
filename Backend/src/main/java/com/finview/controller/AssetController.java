package com.finview.controller;

import com.finview.common.BusinessException;
import com.finview.common.Result;
import com.finview.controller.dto.AssetSummaryResponse;
import com.finview.controller.dto.DistributionItemResponse;
import com.finview.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资产分析接口。context-path 为 /api，故实际路径是 /api/assets/**。
 *
 * GET /assets/summary                 总资产 + 按大类的市值配置
 * GET /assets/distribution            资产分布；不带 dim 返回行业/区域/币种三个维度的全部数据，
 *                                     带 dim=industry|region|currency 只返回该维度
 *
 * 两个接口都只统计定投进行中的资产，口径见 {@link AssetService}。
 * 全部需要认证（SecurityConfig 里 anyRequest().authenticated()），user_id 从 token 解析。
 */
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /** 资产总览：总资产与四个大类的市值分布 */
    @GetMapping("/summary")
    public Result<AssetSummaryResponse> summary(Authentication authentication) {
        return Result.success(assetService.summary(currentUserId(authentication)));
    }

    /**
     * 资产分布。
     * 带 dim 时响应体是数组、不带时是「维度 → 数组」的对象——两种形状对应前端
     * getAssetDistribution 与 getAssetDistributionByDim 两个函数，返回类型只能是 Object。
     */
    @GetMapping("/distribution")
    public Result<Object> distribution(Authentication authentication,
                                       @RequestParam(required = false) String dim) {
        Map<String, List<DistributionItemResponse>> all =
                assetService.distribution(currentUserId(authentication));

        Object payload = all;
        if (StringUtils.hasText(dim)) {
            String key = dim.trim().toLowerCase();
            if (!all.containsKey(key)) {
                throw BusinessException.badRequest("未知的分布维度: " + dim);
            }
            payload = all.get(key);
        }
        return Result.success(payload);
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
