package com.finview.mapper;

import com.finview.entity.Asset;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资产表数据访问。SQL 见 resources/mapper/AssetMapper.xml。
 *
 * 与 AdjustmentMapper 一样，所有语句都带 user_id 条件，按用户隔离。
 */
public interface AssetMapper {

    /** 查某用户的全部资产行，用于给 fold 出来的计划补 name / category 等元数据 */
    List<Asset> findByUser(@Param("userId") Long userId);

    /**
     * 写入 fold 出来的计划快照，靠 (user_id, code) 唯一键命中冲突后更新。
     * 只更新 active / frequency / startDate / principal / total / last_update_time 六列，
     * name / category / asset_type 只在首次插入时落默认值（资产名、大类、股基/债基都是用户维护的），
     * 其余列（industry / region / currency / archived / market）
     * 同理不参与 set —— 被 fold 覆盖就白改了。
     *
     * 「资产属于哪个计划」已经不在这张表上：2026-10-05 起走 plan_asset 关联表（PlanAssetMapper），
     * 所以快照语句天生不会碰到它。
     */
    int upsertSnapshot(Asset asset);

    /**
     * 现金资产（code=CASH）的快照 upsert，与 {@link #upsertSnapshot} 的唯一区别是
     * **category / asset_type 参与冲突更新**。
     *
     * 其余资产的 category 是用户维护的元数据、快照不覆盖；但现金的分类与类型是系统语义
     * （AssetService 靠 category 把余额分进「现金」桶、靠 asset_type 算安全资金），
     * 一行被写成 fund 就再也回不去了 —— 要么界面里凭空多一只叫「现金」的基金，要么现金桶永远是 0。
     * active / frequency / startDate / principal / total / last_update_time 照常更新，name / archived 等仍不碰。
     */
    int upsertCashSnapshot(Asset asset);

    /**
     * 改资产名称（用户在录入界面填了名字时调用）。
     *
     * 单独一条语句、不并进 {@link #upsertSnapshot}：快照每推进一次序列就会刷一遍，
     * 而名字只在用户明确填写时才该被改写，否则登录时按 code 生成的名字会把用户改过的覆盖掉。
     *
     * @return 受影响行数，0 表示该 code 还没有 asset 行（或不属于该用户）
     */
    int updateName(@Param("userId") Long userId,
                   @Param("code") String code,
                   @Param("name") String name);

    /**
     * 改资产类型（录入 / 修改调整记录时带了 assetType 才调用，取值 equity / bond / money）。
     *
     * 与 {@link #updateName} 同款：单独一条语句、不并进 {@link #upsertSnapshot}——
     * 快照每次推进序列都会刷，而类型只在用户明确选择时才该被改写。
     * 调用方必须保证它晚于序列重建（新行是重建时插入的）。
     *
     * @return 受影响行数，0 表示该 code 还没有 asset 行（或不属于该用户）
     */
    int updateAssetType(@Param("userId") Long userId,
                        @Param("code") String code,
                        @Param("assetType") String assetType);

    /**
     * 一次性改掉基金基础信息（定投详情页「编辑」按钮）：name / asset_type / rate / market。
     *
     * 与 {@link #updateName} / {@link #updateAssetType} 一样只碰用户维护的元数据，快照列与
     * category / industry / region / currency / archived 全都不动。
     * rate 与 market 会参与序列生成（手续费、QDII 扣款日），调用方改完要重算 asset_series。
     *
     * @return 受影响行数，0 表示该 code 还没有 asset 行（或不属于该用户）
     */
    int updateMetadata(@Param("userId") Long userId,
                       @Param("code") String code,
                       @Param("name") String name,
                       @Param("assetType") String assetType,
                       @Param("rate") BigDecimal rate,
                       @Param("market") String market);
}
