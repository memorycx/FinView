package com.finview.mapper;

import com.finview.entity.NavTrend;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 净值表数据访问。SQL 见 resources/mapper/NavTrendMapper.xml。
 *
 * 净值是全局数据（不按用户隔离）：同一只基金所有用户共用一份。
 * 写入只有 upsert，唯一键 (day, code)——外部接口偶尔会重发同一天的净值。
 */
public interface NavTrendMapper {

    /** 批量 upsert（靠 idx_day_code 唯一键命中冲突）；空集合直接返回 0 */
    int upsertBatch(@Param("rows") List<NavTrend> rows);

    /** 某 code 的全部净值，按日期升序（SeriesService 拿它做「最近一次净值」查找） */
    List<NavTrend> findByCode(@Param("code") String code);

    /** 某 code 最新净值日期，没有则返回 null（NavService 用它判断要不要去拉新数据） */
    LocalDate findMaxDayByCode(@Param("code") String code);
}
