package com.finview.mapper;

import com.finview.entity.SavingPlan;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存钱计划表数据访问。SQL 见 resources/mapper/SavingPlanMapper.xml。
 *
 * 目前只有读接口（前端也没有新建/编辑计划的入口），所以只有查询语句；
 * 将来做写接口时注意所有语句都要带 user_id 条件，与其它模块保持一致。
 */
public interface SavingPlanMapper {

    /** 某用户的全部计划，按 id 升序（前端列表按此顺序渲染） */
    List<SavingPlan> findByUser(@Param("userId") Long userId);

    /** 按主键 + 用户查计划（详情用），不存在或不属于该用户都返回 null */
    SavingPlan findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
}
