package com.finview.mapper;

import com.finview.entity.MonthlyInflowOverride;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 「每月固定新增」单月覆盖表数据访问。SQL 见 resources/mapper/MonthlyInflowOverrideMapper.xml。
 *
 * 与其它 mapper 一样按 user_id 隔离：读写都带 user_id 条件。
 */
public interface MonthlyInflowOverrideMapper {

    /** 某用户的全部单月覆盖（预测时按 month 取用），没有覆盖时返回空列表 */
    List<MonthlyInflowOverride> findByUser(@Param("userId") Long userId);

    /**
     * 写入 / 更新某个月的单月覆盖（靠 (user_id, month) 唯一键命中冲突后更新），返回受影响行数。
     * 想「恢复默认」要走 {@link #deleteByUserAndMonth}，不要写 0 顶替 —— 0 是「这个月收入为零」。
     */
    int upsert(MonthlyInflowOverride override);

    /** 删除某个月的单月覆盖（= 恢复默认值），返回受影响行数（0 表示本来就没有覆盖） */
    int deleteByUserAndMonth(@Param("userId") Long userId, @Param("month") String month);
}
