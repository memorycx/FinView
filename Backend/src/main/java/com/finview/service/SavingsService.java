package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.controller.dto.SavingsAssetResponse;
import com.finview.controller.dto.SavingsMonthPointResponse;
import com.finview.controller.dto.SavingsPlanResponse;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.entity.SavingPlan;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import com.finview.mapper.SavingPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 存钱计划业务：读计划 + 把关联资产组装成前端要的形状。
 *
 * 计划与资产的关系存 asset.plan_id（表设计.md 用 asset.plan_id 取代了 plan_asset 关联表），
 * 所以「计划里有哪些资产」是一次 asset 表查询，资产金额取 asset_series 的最新市值。
 *
 * 只有读接口：前端目前也没有新建/编辑计划的入口（详情页的「新建计划」会带 id=new 过来，
 * 这里按「计划不存在」返回 404，与前端 mock 的行为一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsService {

    /** 计划内资产的配色轮转，取值与前端 --chart-N 变量一致（前端直接写 var()，缺了会渲染成透明） */
    private static final List<String> ASSET_COLORS =
            List.of("--chart-1", "--chart-3", "--chart-4", "--chart-5");

    private final SavingPlanMapper savingPlanMapper;
    private final AssetMapper assetMapper;
    private final AssetSeriesMapper assetSeriesMapper;

    /** 某用户的全部计划，按 id 升序 */
    public List<SavingsPlanResponse> list(Long userId) {
        return savingPlanMapper.findByUser(userId).stream()
                .map(plan -> toResponse(userId, plan))
                .toList();
    }

    /** 计划详情，不存在或不属于当前用户抛 404 */
    public SavingsPlanResponse getById(Long userId, String id) {
        SavingPlan plan = savingPlanMapper.findByIdAndUser(parseId(id), userId);
        if (plan == null) {
            throw BusinessException.notFound("存钱计划不存在: " + id);
        }
        return toResponse(userId, plan);
    }

    /** id 是路径参数：非数字（比如「新建计划」传的 new）直接当作不存在，别让它冒成 500 */
    private Long parseId(String id) {
        try {
            return Long.valueOf(id.trim());
        } catch (RuntimeException ex) {
            throw BusinessException.notFound("存钱计划不存在: " + id);
        }
    }

    private SavingsPlanResponse toResponse(Long userId, SavingPlan plan) {
        SavingsPlanResponse response = new SavingsPlanResponse();
        response.setId(String.valueOf(plan.getId()));
        response.setName(plan.getName());
        response.setDescription(plan.getDescription());
        response.setType(plan.getType());
        response.setImage(plan.getImage());
        response.setCurrentAmount(zeroIfNull(plan.getCurrentAmount()));
        response.setTargetAmount(zeroIfNull(plan.getTargetAmount()));
        // 前端会直接 targetDate.slice(0, 7)，null 会让模板渲染抛错，所以缺日期给空串
        response.setStartDate(plan.getStartDate() == null ? "" : plan.getStartDate().toString());
        response.setTargetDate(plan.getTargetDate() == null ? "" : plan.getTargetDate().toString());
        response.setMonthlyPlanAmount(zeroIfNull(plan.getMonthlyPlanAmount()));
        response.setQuote(plan.getQuote());
        response.setNote(plan.getNote());
        response.setNoteImage(plan.getNoteImage());

        List<Asset> assets = assetMapper.findByUserAndPlanId(userId, plan.getId());
        response.setAssets(toAssets(userId, assets));
        response.setSeries(monthlySeries(userId, assets));
        return response;
    }

    /** 计划内的资产：名称 + 最新市值 + 轮转配色 */
    private List<SavingsAssetResponse> toAssets(Long userId, List<Asset> assets) {
        Map<String, BigDecimal> totals = latestTotals(userId, assets);
        List<SavingsAssetResponse> result = new ArrayList<>();
        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            String name = StringUtils.hasText(asset.getName()) ? asset.getName() : asset.getCode();
            BigDecimal amount = totals.getOrDefault(asset.getCode(), zeroIfNull(asset.getTotal()));
            result.add(new SavingsAssetResponse(name, amount, ASSET_COLORS.get(i % ASSET_COLORS.size())));
        }
        return result;
    }

    /**
     * 月度走势：每个自然月取该月最后一天的市值合计，某个月没有任何序列行就沿用上个月的合计
     * （数据是按天生成的，但计划详情只画月度，没必要把上千个日度点丢给图表）。
     */
    private List<SavingsMonthPointResponse> monthlySeries(Long userId, List<Asset> assets) {
        if (assets.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        assets.forEach(asset -> codes.add(asset.getCode()));

        // 月份 → (code → 该月末的市值)
        Map<YearMonth, Map<String, BigDecimal>> byMonth = new TreeMap<>();
        for (AssetSeries row : assetSeriesMapper.findByUserAndCodes(userId, codes)) {
            byMonth.computeIfAbsent(YearMonth.from(row.getDay()), month -> new HashMap<>())
                    .put(row.getCode(), row.getTotal());
        }

        Map<String, BigDecimal> carried = new HashMap<>();
        List<SavingsMonthPointResponse> series = new ArrayList<>();
        byMonth.forEach((month, totals) -> {
            carried.putAll(totals);
            BigDecimal sum = carried.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            series.add(new SavingsMonthPointResponse(month.toString(), sum));
        });
        return series;
    }

    /** 每个 code 最新一天的市值 */
    private Map<String, BigDecimal> latestTotals(Long userId, List<Asset> assets) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        assets.forEach(asset -> codes.add(asset.getCode()));

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AssetSeries row : assetSeriesMapper.findLatestPerCode(userId)) {
            if (codes.contains(row.getCode())) {
                totals.put(row.getCode(), row.getTotal());
            }
        }
        return totals;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
