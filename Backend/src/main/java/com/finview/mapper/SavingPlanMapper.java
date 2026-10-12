package com.finview.mapper;

import com.finview.entity.SavingPlan;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存钱计划表数据访问。SQL 见 resources/mapper/SavingPlanMapper.xml。
 *
 * 写接口目前只有「新建」一条（POST /savings/plans）；
 * 所有语句都带 user_id 条件，与其它模块保持一致。
 */
public interface SavingPlanMapper {

    /** 某用户的全部计划，按 id 升序（前端列表按此顺序渲染） */
    List<SavingPlan> findByUser(@Param("userId") Long userId);

    /** 按主键 + 用户查计划（详情用），不存在或不属于该用户都返回 null */
    SavingPlan findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 新建计划，回填自增主键（entity.id） */
    int insert(SavingPlan plan);
}
