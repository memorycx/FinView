package com.finview.mapper;

import com.finview.entity.SavingPlanType;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存钱计划类型表数据访问。SQL 见 resources/mapper/SavingPlanTypeMapper.xml。
 * 与其它 mapper 一样按 user_id 隔离。
 */
public interface SavingPlanTypeMapper {

    /**
     * 幂等补种内置三种类型（asset / life / wish），靠 (user_id, code) 唯一键 + INSERT IGNORE，
     * 重复调用无副作用。新用户第一次打开存钱计划 / 新建表单时由 SavingsService 调。
     */
    int ensureBuiltins(@Param("userId") Long userId);

    /** 某用户的全部类型：内置按 sort_order 0/1/2 在前，自定义按创建顺序在后 */
    List<SavingPlanType> findByUser(@Param("userId") Long userId);

    /** 按主键 + 用户查（改 / 删前的归属校验），不存在或不属于该用户返回 null */
    SavingPlanType findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 新增自定义类型，回填自增主键到 type.id */
    int insert(SavingPlanType type);

    /** 改名（只动 name），返回受影响行数 */
    int updateName(@Param("id") Long id, @Param("userId") Long userId, @Param("name") String name);

    /** 删除，返回受影响行数 */
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 同名类型计数（新建 / 改名时挡重名；excludeId 传 null = 不排除自己） */
    int countByUserAndName(@Param("userId") Long userId, @Param("name") String name,
                           @Param("excludeId") Long excludeId);
}
