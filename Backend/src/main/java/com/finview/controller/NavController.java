package com.finview.controller;

import com.finview.common.Result;
import com.finview.service.SeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 净值刷新接口。context-path 为 /api，故实际路径是 /api/nav/refresh。
 *
 * 净值平时在登录后异步补（见 SeriesService.advance），这个接口是给「想马上看到最新市值」用的：
 * 同步抓完该用户所有 code 的净值再重算序列，所以会比其它接口慢（每个 code 要起一次 python）。
 * 需要认证，只刷当前用户自己的 code。
 */
@RestController
@RequestMapping("/nav")
@RequiredArgsConstructor
public class NavController {

    private final SeriesService seriesService;

    /** 手动刷新净值与序列，返回实际拿到新数据的 code 数 */
    @PostMapping("/refresh")
    public Result<Integer> refresh(Authentication authentication) {
        return Result.success(seriesService.refreshNav(currentUserId(authentication)));
    }

    /** JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用 */
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }
}
