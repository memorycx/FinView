package com.finview.mapper;

import com.finview.entity.Adjustment;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 资产调整记录表数据访问。SQL 见 resources/mapper/AdjustmentMapper.xml。
 *
 * 所有查询/修改都带 user_id 条件：记录按用户隔离，越权访问由 SQL 层挡住，
 * 而不是先查出来再在 Service 里判断。
 */
public interface AdjustmentMapper {

    /** 新增记录，回填自增主键到 adjustment.adjustmentId */
    int insert(Adjustment adjustment);

    /**
     * 按主键 + 用户查记录（详情 / 改 / 删前的归属校验），不存在或不属于该用户都返回 null。
     */
    Adjustment findByIdAndUser(@Param("adjustmentId") Long adjustmentId,
                               @Param("userId") Long userId);

    /**
     * 查某用户的记录列表。
     * 过滤条件为 null 时该项不参与 where（见 XML 里的 if 标签）。
     *
     * @param code      资产编码，精确匹配
     * @param action    动作 1/2/3/4
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     */
    List<Adjustment> findByUser(@Param("userId") Long userId,
                                @Param("code") String code,
                                @Param("action") Integer action,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);

    /** 按主键 + 用户整条更新，返回受影响行数（0 表示记录不存在或不属于该用户） */
    int updateByIdAndUser(Adjustment adjustment);

    /** 按主键 + 用户删除，返回受影响行数（0 表示记录不存在或不属于该用户） */
    int deleteByIdAndUser(@Param("adjustmentId") Long adjustmentId,
                          @Param("userId") Long userId);

    /**
     * 统计同一 (user, code, date) 上指定动作的记录数，用于拦截「同一天既开始又结束定投」。
     *
     * @param excludeId 排除掉的记录 id（改记录时排除自己），新增时传 null
     */
    int countByUserCodeDayAction(@Param("userId") Long userId,
                                 @Param("code") String code,
                                 @Param("date") LocalDate date,
                                 @Param("action") Integer action,
                                 @Param("excludeId") Long excludeId);
}
