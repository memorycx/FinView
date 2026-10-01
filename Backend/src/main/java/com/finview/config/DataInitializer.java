package com.finview.config;

import com.finview.entity.User;
import com.finview.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐测试账号，让前端登录页提示的两个账号可直接登录：
 *   admin / admin123（管理员）
 *   demo  / demo123（普通用户）
 *
 * 密码必须由 PasswordEncoder 现算，不能写死在 data.sql 里（BCrypt 无法用 SQL 生成）。
 * 已存在则跳过，因此不会覆盖用户自己注册或改过密码的账号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_USER = "user";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seed("admin", "admin123", "管理员", "admin@finview.local", ROLE_ADMIN);
        seed("demo", "demo123", "普通用户", "demo@finview.local", ROLE_USER);
    }

    private void seed(String username, String rawPassword, String nickname, String email, String role) {
        if (userMapper.countByUserName(username) > 0) {
            return;
        }

        User user = new User();
        user.setUserName(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname);
        user.setEmail(email);
        user.setRole(role);

        try {
            userMapper.insert(user);
            log.info("已初始化测试账号：{}（角色 {}）", username, role);
        } catch (Exception ex) {
            // 种子数据只是便利，失败不该拖垮启动
            log.warn("初始化测试账号 {} 失败：{}", username, ex.getMessage());
        }
    }
}
