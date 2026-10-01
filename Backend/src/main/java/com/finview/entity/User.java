package com.finview.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 `user` 表。
 * 注意：password 存的是 BCrypt 密文，任何情况下都不要直接返回给前端，
 * 对外统一用 LoginResponse 包装。
 */
@Data
@NoArgsConstructor
public class User {

    private Long id;

    /** 用户名，唯一 */
    private String userName;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 昵称，注册时可选，缺省用用户名 */
    private String nickname;

    /** 邮箱，注册时可选；表上唯一索引，MySQL 下多个 NULL 不冲突 */
    private String email;

    /** 角色：user / admin */
    private String role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * asset_series 上次推进到的日期（《表设计.md》里叫 update_series_time）。
     * SeriesService.advance 拿它当水位：只补「这个时间之后到今天」的新行，当天重复登录直接跳过。
     */
    private LocalDateTime updateSeriesTime;
}
