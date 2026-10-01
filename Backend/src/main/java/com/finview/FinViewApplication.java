package com.finview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 认证完全由 JWT 过滤器承担（见 config/JwtAuthenticationFilter），
 * 用不到 Spring Security 默认的内存用户，排除掉它免得每次启动都打印一个随机的
 * “Using generated security password” 干扰排查。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.finview.mapper")
public class FinViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinViewApplication.class, args);
    }
}
