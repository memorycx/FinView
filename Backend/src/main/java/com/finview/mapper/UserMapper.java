package com.finview.mapper;

import com.finview.entity.User;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 用户表数据访问。SQL 见 resources/mapper/UserMapper.xml。
 * 扫描由 FinViewApplication 上的 @MapperScan("com.finview.mapper") 完成。
 */
public interface UserMapper {

    /** 按用户名查用户（登录用），不存在返回 null */
    User findByUserName(@Param("userName") String userName);

    /** 按主键查用户（/auth/me 用），不存在返回 null */
    User findById(@Param("id") Long id);

    /** 用户名是否已存在 */
    int countByUserName(@Param("userName") String userName);

    /** 邮箱是否已被占用（email 为 null 时不调用） */
    int countByEmail(@Param("email") String email);

    /** 新增用户，回填自增主键到 user.id */
    int insert(User user);

    /**
     * 把 update_series_time 置为给定时间。
     * 单独一条语句而不是复用 update()：这个字段由 SeriesService 在每次推进序列后写，
     * 与用户资料更新是两条互不相干的路径。
     */
    int touchUpdateSeriesTime(@Param("id") Long id, @Param("updateSeriesTime") LocalDateTime updateSeriesTime);
}
