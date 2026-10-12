package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.config.JwtTokenProvider;
import com.finview.controller.dto.LoginRequest;
import com.finview.controller.dto.LoginResponse;
import com.finview.controller.dto.RegisterRequest;
import com.finview.entity.User;
import com.finview.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证业务：登录、注册、取当前用户。
 *
 * 约定：
 * - 密码只以 BCrypt 密文落库，明文不落库、不打日志；
 * - 对外一律返回 LoginResponse，绝不把 User 实体（含 password）抛给控制层；
 * - 失败抛 BusinessException（前端拿到 { code, message } 后直接展示 message）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SeriesService seriesService;

    /**
     * 登录：校验用户名密码，签发 JWT，然后推进 asset_series。
     *
     * 「用户登录 → 取 update_series_time 作水位 → 逐日推进到今天」是《表设计.md》的核心更新流程，
     * 入口就在这里（注册同理）。推进是纯 DB 计算，几十毫秒；拉净值另走异步线程，不挡登录。
     */
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        User user = userMapper.findByUserName(username);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.info("登录失败，用户名={}", username);
            throw new BusinessException(400, "用户名或密码错误");
        }

        log.info("登录成功，userId={}, 用户名={}", user.getId(), user.getUserName());
        advanceSeries(user.getId());
        return buildResponse(user);
    }

    /**
     * 注册：用户名唯一，邮箱可选但填了就必须唯一。
     * 注册成功直接签发 token，前端无需再登录一次（见 stores/auth.ts）。
     */
    public LoginResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = trimToNull(request.getEmail());
        String nickname = trimToNull(request.getNickname());

        if (userMapper.countByUserName(username) > 0) {
            throw new BusinessException(400, "用户名已被占用");
        }
        if (email != null && userMapper.countByEmail(email) > 0) {
            throw new BusinessException(400, "邮箱已被注册");
        }

        User user = new User();
        user.setUserName(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(nickname != null ? nickname : username);
        user.setEmail(email);
        // 角色由服务端写死，不接受前端传入，避免注册出管理员
        user.setRole("user");

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 并发注册时上面的 count 可能双双通过，兜底靠库里的唯一索引
            log.warn("注册撞唯一索引，用户名={}, 邮箱={}", username, email);
            throw new BusinessException(400, email != null ? "用户名或邮箱已被占用" : "用户名已被占用");
        }

        log.info("注册成功，userId={}, 用户名={}", user.getId(), user.getUserName());
        advanceSeries(user.getId());
        return buildResponse(user);
    }

    /**
     * 推进当前用户的 asset_series。
     * 序列是派生数据，推进失败不该让用户登不进来（比如某个 code 的数据异常），
     * 所以这里兜住异常只记日志，下一次登录或改记录时会重算。
     */
    private void advanceSeries(Long userId) {
        try {
            seriesService.advance(userId);
        } catch (Exception ex) {
            log.error("推进 asset_series 失败，userId={}", userId, ex);
        }
    }

    /**
     * 查询当前登录用户资料（token 由请求头带入，此处不重新签发）。
     * 返回的 token 字段为 null，前端保留本地已有的 token 即可。
     */
    public LoginResponse currentUser(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            // token 合法但用户已被删除
            throw BusinessException.notFound("用户不存在");
        }
        LoginResponse response = toResponse(user);
        response.setToken(null);
        return response;
    }

    /** 签发 token 并组装响应 */
    private LoginResponse buildResponse(User user) {
        LoginResponse response = toResponse(user);
        response.setToken(jwtTokenProvider.generateToken(user.getId(), user.getUserName(), user.getRole()));
        return response;
    }

    /** User -> LoginResponse，注意这里不含 password */
    private LoginResponse toResponse(User user) {
        return new LoginResponse(
                null,
                user.getId(),
                user.getUserName(),
                user.getNickname(),
                user.getEmail(),
                user.getRole()
        );
    }

    /** 去空白，空串归一成 null */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
