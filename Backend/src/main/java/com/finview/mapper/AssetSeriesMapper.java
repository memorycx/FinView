package com.finview.mapper;

import com.finview.entity.AssetSeries;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 资产每日序列表数据访问。SQL 见 resources/mapper/AssetSeriesMapper.xml。
 *
 * 与其它 mapper 一样按 user_id 隔离；写入只有 upsert —— 序列是幂等重算出来的，
 * 不存在「改一行」的语义，重算后整段覆盖即可（唯一键 (user_id, code, day)）。
 */
public interface AssetSeriesMapper {

    /**
     * 批量 upsert（靠 idx_user_code_day 唯一键命中冲突）。
     * 空集合直接返回 0，不要在 XML 里拼出 `VALUES` 后面啥都没有的非法 SQL。
     */
    int upsertBatch(@Param("rows") List<AssetSeries> rows);

    /** 删掉某 code 的全部序列行，重建前先清空 */
    int deleteByUserAndCode(@Param("userId") Long userId, @Param("code") String code);

    /** 某 code 的完整序列，按日期升序 */
    List<AssetSeries> findByUserAndCode(@Param("userId") Long userId, @Param("code") String code);

    /** 某批 code 的序列，按 code、日期升序（组合走势 / 存钱计划按月聚合用） */
    List<AssetSeries> findByUserAndCodes(@Param("userId") Long userId,
                                         @Param("codes") Collection<String> codes);

    /** 某 code 最新一天的序列行，没有则返回 null */
    AssetSeries findLatestByUserAndCode(@Param("userId") Long userId, @Param("code") String code);

    /**
     * 每个 code 各取最新一天的行（资产总览用）。
     * 返回行数 = 该用户有序列的 code 数，调用方自己按 category 汇总。
     */
    List<AssetSeries> findLatestPerCode(@Param("userId") Long userId);
}
