package com.finview.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 * CORS 统一交由 SecurityConfig 管理（securityFilterChain 中的 corsConfigurationSource），
 * 本类不再重复配置，避免两处 CORS 规则不一致导致预检请求异常。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // CORS 已迁移到 SecurityConfig
}
