package com.finview.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统用户实体（与 user 表映射）。
 * password 使用 @JsonProperty(access = WRITE_ONLY) 确保只接收不输出，
 * 避免任何序列化路径泄露密码哈希。
 */
@Data
@NoArgsConstructor
public class User {

    private Long id;

    private String username;

    /** 只允许写入（请求体接收），禁止序列化输出 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String email;

    private String nickname;

    private String role;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
