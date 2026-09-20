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
 * 认证相关端点：/api/auth/login, /api/auth/register, /api/auth/me
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /auth/login  用户登录 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    /** POST /auth/register  用户注册 */
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    /** GET /auth/me  获取当前登录用户信息 */
    @GetMapping("/me")
    public Result<LoginResponse> me(Authentication authentication) {
        // JwtAuthenticationFilter 把 userId 放进了 Authentication.principal
        Long userId = (Long) authentication.getPrincipal();
        return Result.success(authService.getUserById(userId));
    }
}
