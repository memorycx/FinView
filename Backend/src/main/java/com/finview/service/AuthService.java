package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.config.JwtTokenProvider;
import com.finview.controller.dto.LoginRequest;
import com.finview.controller.dto.LoginResponse;
import com.finview.controller.dto.RegisterRequest;
import com.finview.entity.User;
import com.finview.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证业务：登录 / 注册 / 获取当前用户信息。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /** 用户登录：校验密码 → 生成 JWT */
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getId(), user.getUsername(),
                user.getNickname(), user.getEmail(), user.getRole());
    }

    /** 用户注册：用户名唯一 → BCrypt 加密 → 入库 */
    public LoginResponse register(RegisterRequest request) {
        User existing = userMapper.findByUsername(request.getUsername());
        if (existing != null) {
            throw new BusinessException(409, "用户名已被注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setRole("USER");
        user.setEnabled(true);
        userMapper.insert(user);

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getId(), user.getUsername(),
                user.getNickname(), user.getEmail(), user.getRole());
    }

    /** 根据 id 获取用户（不返回密码） */
    public LoginResponse getUserById(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return new LoginResponse(null, user.getId(), user.getUsername(),
                user.getNickname(), user.getEmail(), user.getRole());
    }
}
