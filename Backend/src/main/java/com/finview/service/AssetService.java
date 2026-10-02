package com.finview.service;

import com.finview.controller.dto.AssetSummaryResponse;
import com.finview.controller.dto.DistributionItemResponse;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 资产分析业务：总览（按展示桶汇总市值，基金按 asset_type 细分成股票基金 / 债券基金）与分布（行业 / 区域 / 币种）。
 *
 * 两个接口都只统计**未归档**的资产（asset.archived = 0）：归档是用户手工标记的「移出当前视图」，
 * 定投结束但没归档的仍算持有、仍计入。前端的 AssetOverview 与 Leaderboard 拿总览里的总资产
 * 当分母算占比、集中度，口径必须与它自己的持仓列表（/funds?status=active，同样按未归档过滤）一致。
 *
 * 市值取 asset_series 每个 code 的最新一行（与走势图同一份数据），
 * 一行序列都没有时才回退 asset 表的快照列。
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    /**
     * 展示桶的中文名，与前端 AssetOverview 的图例一致；顺序固定，前端按桶 key 取色。
     * 基金不单独成桶：按 asset.asset_type 细分成股票基金（equityFund）/ 债券基金（bondFund）。
     */
    private static final Map<String, String> BUCKET_LABELS = new LinkedHashMap<>();

    /** 分布维度：category 之外支持的三列，取值与前端 DistributionDim 一致 */
    private static final List<String> DISTRIBUTION_DIMS = List.of("industry", "region", "currency");

    /** 安全资金的 asset_type 取值：债基 + 现金（2026-10-02 约定，见 AssetSummaryResponse.safeAssets） */
    private static final Set<String> SAFE_ASSET_TYPES = Set.of("bond", "cash");

    static {
        BUCKET_LABELS.put("equityFund", "股票基金");
        BUCKET_LABELS.put("bondFund", "债券基金");
        BUCKET_LABELS.put("stock", "股票");
        BUCKET_LABELS.put("bond", "债券");
        BUCKET_LABELS.put("cash", "现金");
    }

    private final AssetMapper assetMapper;
    private final AssetSeriesMapper assetSeriesMapper;

    /** 资产总览：总资产 + 安全资金 + 按展示桶的市值分布（五个桶恒返回，没有持仓的为 0） */
    public AssetSummaryResponse summary(Long userId) {
        Map<String, BigDecimal> totals = latestTotals(userId);

        // 五个展示桶先占位为 0，保证前端图例不缺项；未知分类不丢数据，按原样单列一项。
        // 顺手累加安全资金：asset_type ∈ {bond, cash} 的市值（未标注的按「非安全」计）
        Map<String, BigDecimal> byBucket = new LinkedHashMap<>();
        BUCKET_LABELS.keySet().forEach(bucket -> byBucket.put(bucket, BigDecimal.ZERO));
        BigDecimal safeAssets = BigDecimal.ZERO;
        for (Asset asset : unarchivedAssets(userId)) {
            BigDecimal value = valueOf(asset, totals);
            byBucket.merge(bucketOf(asset), value, BigDecimal::add);
            if (SAFE_ASSET_TYPES.contains(asset.getAssetType())) {
                safeAssets = safeAssets.add(value);
            }
        }

        List<AssetSummaryResponse.AllocationResponse> allocation = byBucket.entrySet().stream()
                .map(entry -> new AssetSummaryResponse.AllocationResponse(
                        entry.getKey(),
                        BUCKET_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                        entry.getValue()))
                .toList();
        BigDecimal totalAssets = allocation.stream()
                .map(AssetSummaryResponse.AllocationResponse::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AssetSummaryResponse(totalAssets, safeAssets, allocation);
    }

    /**
     * 三个维度的市值分布，固定按 industry / region / currency 的顺序返回。
     * 一次算完而不是按 dim 单独查：个人看板的数据量下没有成本，前端切换维度也不用再请求。
     */
    public Map<String, List<DistributionItemResponse>> distribution(Long userId) {
        Map<String, BigDecimal> totals = latestTotals(userId);
        List<Asset> unarchived = unarchivedAssets(userId);

        Map<String, List<DistributionItemResponse>> result = new LinkedHashMap<>();
        for (String dim : DISTRIBUTION_DIMS) {
            result.put(dim, groupBy(unarchived, totals, dimensionGetter(dim)));
        }
        return result;
    }

    /** 维度取值器：三列都是资产上的字符串标签 */
    private Function<Asset, String> dimensionGetter(String dim) {
        return switch (dim) {
            case "industry" -> Asset::getIndustry;
            case "region" -> Asset::getRegion;
            default -> Asset::getCurrency;
        };
    }

    /** 按某一维度分组汇总市值，未填标签的资产不计入（前端空态就是「还没标」），按金额降序 */
    private List<DistributionItemResponse> groupBy(List<Asset> assets,
                                                   Map<String, BigDecimal> totals,
                                                   Function<Asset, String> getter) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Asset asset : assets) {
            String name = getter.apply(asset);
            if (!StringUtils.hasText(name)) {
                continue;
            }
            sums.merge(name.trim(), valueOf(asset, totals), BigDecimal::add);
        }
        List<DistributionItemResponse> items = new ArrayList<>();
        sums.forEach((name, value) -> items.add(new DistributionItemResponse(name, value)));
        items.sort(Comparator.comparing(DistributionItemResponse::getValue).reversed());
        return items;
    }

    /**
     * 资产落在哪个展示桶：基金（含 category 缺行时的兜底）按 asset_type 细分，
     * 未标注（null，存量没标过的）按股票基金算；其余大类原样返回，未知分类也单列一项、不丢数据。
     * 包级可见是为了单测（见 AssetServiceTest），与 PortfolioService.aggregate 同一套路。
     */
    static String bucketOf(Asset asset) {
        String category = StringUtils.hasText(asset.getCategory()) ? asset.getCategory() : "fund";
        if (!"fund".equals(category)) {
            return category;
        }
        return "bond".equals(asset.getAssetType()) ? "bondFund" : "equityFund";
    }

    /** 某资产当前市值：优先取序列末行，没有序列则用 asset 表的快照 */
    private BigDecimal valueOf(Asset asset, Map<String, BigDecimal> totals) {
        BigDecimal value = totals.get(asset.getCode());
        if (value != null) {
            return value;
        }
        return asset.getTotal() == null ? BigDecimal.ZERO : asset.getTotal();
    }

    /** 每个 code 最新一天的市值 */
    private Map<String, BigDecimal> latestTotals(Long userId) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AssetSeries row : assetSeriesMapper.findLatestPerCode(userId)) {
            totals.put(row.getCode(), row.getTotal());
        }
        return totals;
    }

    /** 未归档的资产：归档（asset.archived = 1）是从总览与分布里移出的唯一开关，与定投是否进行中无关 */
    private List<Asset> unarchivedAssets(Long userId) {
        return assetMapper.findByUser(userId).stream()
                .filter(asset -> !Boolean.TRUE.equals(asset.getArchived()))
                .toList();
    }
}
