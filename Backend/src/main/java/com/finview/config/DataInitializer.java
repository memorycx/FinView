package com.finview.config;

import com.finview.entity.User;
import com.finview.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 应用启动时初始化默认管理员账号（若不存在）。
 * 避免在 SQL 中硬编码 BCrypt 哈希值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // 初始管理员: admin / admin123
        if (userMapper.findByUsername("admin") == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("系统管理员");
            admin.setRole("ADMIN");
            admin.setEnabled(true);
            userMapper.insert(admin);
            log.info("初始化默认管理员账号: admin / admin123");
        }

        // 初始普通用户: demo / demo123
        if (userMapper.findByUsername("demo") == null) {
            User demo = new User();
            demo.setUsername("demo");
            demo.setPassword(passwordEncoder.encode("demo123"));
            demo.setNickname("演示用户");
            demo.setRole("USER");
            demo.setEnabled(true);
            userMapper.insert(demo);
            log.info("初始化演示账号: demo / demo123");
        }
    }
}
