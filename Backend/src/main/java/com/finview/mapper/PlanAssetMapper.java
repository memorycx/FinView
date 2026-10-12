package com.finview.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存钱计划 ↔ 资产的多对多关联（`plan_asset` 表）。SQL 见 resources/mapper/PlanAssetMapper.xml。
 *
 * 2026-10-05 起「资产属于哪个计划」由这张表表达，`asset.plan_id` 已删。
 * 「总资产」计划（type=asset）不查这张表：它默认包含该用户全部未归档资产（见 SavingsService.assetsOf），
 * 所以服务端会拒绝给这种计划手动挂资产。
 *
 * 与其它 mapper 一样，每条语句都带 user_id 条件，按用户隔离。
 */
public interface PlanAssetMapper {

    /** 某计划关联的资产 id，按资产 code 排序（调用方据此让配色轮转稳定） */
    List<Long> findAssetIdsByPlan(@Param("userId") Long userId, @Param("planId") Long planId);

    /**
     * 关联一个资产。唯一键 (plan_id, asset_id) 挡重复，用 INSERT IGNORE，
     * 所以重复调用是幂等的（返回 0 行表示本来就关联着）。
     */
    int insert(@Param("userId") Long userId, @Param("planId") Long planId, @Param("assetId") Long assetId);

    /** 解除关联，返回受影响行数（0 = 本来就没关联） */
    int delete(@Param("userId") Long userId, @Param("planId") Long planId, @Param("assetId") Long assetId);
}
