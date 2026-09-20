package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.entity.Allocation;
import com.finview.entity.AssetSummary;
import com.finview.entity.CategorySum;
import com.finview.entity.DistributionItem;
import com.finview.mapper.AssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AssetService {

    /** 未定投的现金/货币基金储备（元），固定追加到 cash 大类 */
    private static final long CASH_RESERVE = 128_000L;

    /** allocation 固定返回顺序（即使为 0） */
    private static final List<String> CATEGORY_ORDER = List.of("fund", "stock", "bond", "cash");

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "fund", "基金",
            "stock", "股票",
            "bond", "债券",
            "cash", "现金"
    );

    private static final Set<String> DISTRIBUTION_DIMS = Set.of("industry", "region", "currency");

    private final AssetMapper assetMapper;

    /** 资产总览：仅汇总进行中持仓，按大类累加 current，并追加固定现金储备 */
    public AssetSummary summary() {
        Map<String, Long> sumByCategory = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            sumByCategory.put(category, 0L);
        }
        for (CategorySum row : assetMapper.selectActiveCategorySums()) {
            if (sumByCategory.containsKey(row.getCategory()) && row.getValue() != null) {
                sumByCategory.put(row.getCategory(), row.getValue());
            }
        }
        sumByCategory.merge("cash", CASH_RESERVE, Long::sum);

        List<Allocation> allocation = CATEGORY_ORDER.stream()
                .map(category -> new Allocation(category,
                        CATEGORY_LABELS.get(category),
                        sumByCategory.get(category)))
                .toList();

        AssetSummary summary = new AssetSummary();
        summary.setAllocation(allocation);
        summary.setTotalAssets(allocation.stream().mapToLong(Allocation::getValue).sum());
        return summary;
    }

    /**
     * 资产分布。
     *
     * @param dim 为 null 返回全维度 Map；否则返回单维度列表，非法维度抛 400
     */
    public Object distribution(String dim) {
        if (dim == null || dim.isEmpty()) {
            Map<String, List<DistributionItem>> data = new LinkedHashMap<>();
            for (String key : List.of("industry", "region", "currency")) {
                data.put(key, assetMapper.selectDistribution(key));
            }
            return data;
        }
        if (!DISTRIBUTION_DIMS.contains(dim)) {
            throw BusinessException.badRequest("未知的分布维度: " + dim);
        }
        return assetMapper.selectDistribution(dim);
    }
}
