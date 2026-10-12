package com.finview.controller;

import com.finview.common.Result;
import com.finview.controller.dto.LoginRequest;
import com.finview.controller.dto.LoginResponse;
import com.finview.controller.dto.RegisterRequest;
import com.finview.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。context-path 为 /api，故实际路径是 /api/auth/**。
 *
 * POST /auth/login    登录，放行
 * POST /auth/register 注册，放行
 * GET  /auth/me       取当前用户资料，需携带 Authorization: Bearer <token>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    /**
     * 当前登录用户。
     * JwtAuthenticationFilter 把 userId 放进了 principal，这里直接取出来用。
     */
    @GetMapping("/me")
    public Result<LoginResponse> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.success(authService.currentUser(userId));
    }
}
