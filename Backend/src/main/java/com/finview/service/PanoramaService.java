package com.finview.service;

import com.finview.common.CashAsset;
import com.finview.controller.dto.PanoramaResponse;
import com.finview.entity.Adjustment;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.mapper.AdjustmentMapper;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 「资金全景」页（3D 塔视图）：把该用户的资产按展示桶分组，连同每只资产的**未来累计变化**一起给前端。
 *
 * 口径（与前面几个页面一致，改之前先读这里）：
 * - 桶 / 市值：与 `/assets/summary` 同源 —— 未归档资产、最新市值（序列末行，缺序列退回 asset.total），
 *   分组用 {@link AssetService#bucketOf}（基金再按 asset_type 细分股基/债基/货基）；
 * - **基金的未来投入 = 「只算现金流入、不含市场变化」**：把 action ∈ {1,2} 的事件交给
 *   {@link SeriesService#fold} 推进到 12 个月末，逐日取周期扣款的 `amount + fee` 累加（含申购费）。
 *   从**明天**开始算 —— 今天的扣款已经体现在当前市值/余额里了；
 * - **现金塔 = 「定投分析」那张现金累计卡的同一套算式**（2026-10-15 加）：逐月
 *   `每月固定新增 − 当月定投支出`（支出 = 各基金当月扣款之和，新增走 CashFlowService 的
 *   默认值 + 单月覆盖），所以现金可以**为负 / 变矮**（塔高按 0 画，数字照实给）；
 *   本月（窗口第 0 个月）只扣支出、不加新增 —— 新增是整月假设，本月已到账的部分在今天的余额里；
 * - 月份窗口 = **本月（剩余）+ 往后 11 个自然月**（与「定投分析」的"下月起 12 整月"故意不同：
 *   这里画的是"从现在开始长"，本月的剩余投入也在路上）；
 * - 全链路只读，绝不写 asset_series / asset。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PanoramaService {

    /** 时间窗月数：本月 + 往后 11 个自然月 */
    private static final int MONTHS = 12;

    private static final int MONEY_SCALE = 2;

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 桶内资产配色轮转（3D 分段与右侧折线共用）；与「存钱计划 / 定投日历」同一套，货币基金多用第 6 档 */
    private static final List<String> ASSET_COLORS =
            List.of("--chart-1", "--chart-3", "--chart-4", "--chart-5", "--chart-6");

    /** 只有「定投开始 / 结束」影响周期扣款；买入 / 卖出不算「现金流入」 */
    private static final Set<Integer> DCA_ACTIONS = Set.of(1, 2);

    private final AssetMapper assetMapper;
    private final AssetSeriesMapper assetSeriesMapper;
    private final AdjustmentMapper adjustmentMapper;
    private final TradingCalendarService tradingCalendarService;
    private final CashFlowService cashFlowService;

    /** 「资金全景」：按展示桶分组的资产 + 未来 12 个自然月的累计变化 */
    public PanoramaResponse panorama(Long userId) {
        LocalDate today = LocalDate.now();
        List<YearMonth> months = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            months.add(YearMonth.from(today).plusMonths(i));
        }

        // 未来投入：与预测/日历同一套 fold（只喂 DCA 事件、推进到窗口末月）
        tradingCalendarService.ensureLoaded();
        List<Asset> assets = assetMapper.findByUser(userId).stream()
                .filter(asset -> !Boolean.TRUE.equals(asset.getArchived()))
                .toList();
        LocalDate horizonEnd = months.get(MONTHS - 1).atEndOfMonth();
        Map<String, SeriesService.PlanState> states = SeriesService.fold(
                userId,
                dcaEvents(adjustmentMapper.findByUser(userId, null, null, null, null)),
                null,
                ratesOf(assets),
                calendarOf(assets),
                horizonEnd);
        Map<YearMonth, Map<String, BigDecimal>> outlays = outlayByMonth(today, months, states);
        Map<String, List<BigDecimal>> cumulativeByCode = cumulativeByCode(months, outlays);
        // 现金塔：与「定投分析」的现金累计卡同一套算式（新增 − 支出），可以为负
        List<BigDecimal> cashSeries = cashSeries(months, outlays, cashFlowService.monthlyInflows(userId, months));

        Map<String, BigDecimal> totals = latestTotals(userId);

        // 按展示桶分组（只收有资产的桶）；桶内先按市值降序，再按 code 稳定排序
        Map<String, List<Asset>> byBucket = new LinkedHashMap<>();
        for (Asset asset : assets) {
            BigDecimal value = valueOf(asset, totals);
            List<BigDecimal> cumulative = CashAsset.isCash(asset.getCode())
                    ? cashSeries
                    : cumulativeByCode.getOrDefault(asset.getCode(), zeroSeries());
            boolean hasFuture = cumulative.get(MONTHS - 1).signum() != 0;
            if (value.signum() == 0 && !hasFuture) {
                continue;   // 既没市值、未来也不变的资产不占塔（0 高的一截看不见）
            }
            byBucket.computeIfAbsent(AssetService.bucketOf(asset), key -> new ArrayList<>()).add(asset);
        }

        List<PanoramaResponse.BucketResponse> buckets = new ArrayList<>();
        for (Map.Entry<String, List<Asset>> entry : byBucket.entrySet()) {
            List<Asset> bucketAssets = entry.getValue().stream()
                    .sorted(Comparator
                            .comparing((Asset asset) -> valueOf(asset, totals)).reversed()
                            .thenComparing(Asset::getCode))
                    .toList();

            List<PanoramaResponse.AssetItem> items = new ArrayList<>();
            BigDecimal bucketValue = BigDecimal.ZERO;
            for (int i = 0; i < bucketAssets.size(); i++) {
                Asset asset = bucketAssets.get(i);
                BigDecimal value = scale(valueOf(asset, totals));
                bucketValue = bucketValue.add(value);
                items.add(new PanoramaResponse.AssetItem(
                        asset.getCode(),
                        StringUtils.hasText(asset.getName()) ? asset.getName() : asset.getCode(),
                        value,
                        ASSET_COLORS.get(i % ASSET_COLORS.size()),
                        CashAsset.isCash(asset.getCode())
                                ? cashSeries
                                : cumulativeByCode.getOrDefault(asset.getCode(), zeroSeries())));
            }
            buckets.add(new PanoramaResponse.BucketResponse(
                    entry.getKey(), AssetService.bucketLabel(entry.getKey()), bucketValue, items));
        }
        buckets.sort(Comparator.comparing(PanoramaResponse.BucketResponse::getValue).reversed());

        return new PanoramaResponse(
                today.toString(),
                months.stream().map(month -> month.format(YEAR_MONTH)).toList(),
                buckets);
    }

    /**
     * 窗口内 rows 的按月归口：月 → code → 该月扣款合计（amount + fee）。
     * 只算**今天之后**、且落在窗口月份里的日子 —— 今天及以前的扣款已经在当前市值 / 余额里了。
     * 逐 code 的累计投入与现金的逐月净变化都从这个表派生，两条线不可能对不上。
     *
     * 纯计算、包级静态，单测直接打（假日历 + 真 fold）。
     */
    static Map<YearMonth, Map<String, BigDecimal>> outlayByMonth(LocalDate asOf, List<YearMonth> months,
                                                                Map<String, SeriesService.PlanState> planStates) {
        Set<YearMonth> inWindow = new LinkedHashSet<>(months);
        Map<YearMonth, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        for (YearMonth month : months) {
            result.put(month, new LinkedHashMap<>());
        }
        for (Map.Entry<String, SeriesService.PlanState> entry : planStates.entrySet()) {
            for (AssetSeries row : entry.getValue().getRows()) {
                if (row.getDay() == null || !row.getDay().isAfter(asOf)) {
                    continue;   // 今天及以前的扣款已经在当前市值/余额里
                }
                YearMonth month = YearMonth.from(row.getDay());
                if (!inWindow.contains(month)) {
                    continue;   // 窗口之外
                }
                BigDecimal outlay = zeroIfNull(row.getAmount()).add(zeroIfNull(row.getFee()));
                if (outlay.signum() != 0) {
                    result.get(month).merge(entry.getKey(), outlay, BigDecimal::add);
                }
            }
        }
        return result;
    }

    /**
     * 每只 code 的逐月累计投入（把 {@link #outlayByMonth} 的月度值累加，scale 归到分）。
     * 窗口里一分钱都不投的 code 也给一条全 0 序列，调用方不用兜底。
     */
    static Map<String, List<BigDecimal>> cumulativeByCode(List<YearMonth> months,
                                                          Map<YearMonth, Map<String, BigDecimal>> outlays) {
        Map<String, List<BigDecimal>> result = new LinkedHashMap<>();
        for (String code : allCodes(outlays)) {
            List<BigDecimal> cumulative = new ArrayList<>();
            BigDecimal running = BigDecimal.ZERO;
            for (YearMonth month : months) {
                running = running.add(zeroIfNull(outlays.getOrDefault(month, Map.of()).get(code)));
                cumulative.add(scale(running));
            }
            result.put(code, cumulative);
        }
        return result;
    }

    /**
     * 现金的逐月累计净变化，与「定投分析」的现金累计卡**同一套算式**：
     * 第 i 个月末的变化 = Σ_{k≤i}(当月固定新增 − 当月定投支出)。
     *
     * **本月（窗口第 0 个月）只扣支出、不加新增**：「每月固定新增」是整月假设，
     * 本月已经到账的那部分就在今天的现金余额里，窗口只把剩下的扣款算进来
     * （与「定投分析」把当月整月排除在窗口外的理由一样）。
     * 结果可以是负数（支出 > 余额 + 新增），塔高按 0 画、数字照实给前端。
     */
    static List<BigDecimal> cashSeries(List<YearMonth> months, Map<YearMonth, Map<String, BigDecimal>> outlays,
                                       Map<YearMonth, BigDecimal> inflows) {
        List<BigDecimal> cumulative = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < months.size(); i++) {
            YearMonth month = months.get(i);
            BigDecimal outlay = outlays.getOrDefault(month, Map.of()).values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal inflow = i == 0 ? BigDecimal.ZERO : zeroIfNull(inflows.get(month));
            running = running.add(inflow).subtract(outlay);
            cumulative.add(scale(running));
        }
        return cumulative;
    }

    /** outlayByMonth 里出现过的所有 code（保持月份与桶内插入的顺序） */
    private static Set<String> allCodes(Map<YearMonth, Map<String, BigDecimal>> outlays) {
        Set<String> codes = new LinkedHashSet<>();
        for (Map<String, BigDecimal> byCode : outlays.values()) {
            codes.addAll(byCode.keySet());
        }
        return codes;
    }

    /* ==================== 私有辅助（与 CashFlowService 同款，别看串） ==================== */

    /** 最近一次市值（每个 code 一行），与 /assets/summary 同一个取数 */
    private Map<String, BigDecimal> latestTotals(Long userId) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AssetSeries row : assetSeriesMapper.findLatestPerCode(userId)) {
            totals.put(row.getCode(), row.getTotal());
        }
        return totals;
    }

    /** 某资产当前市值：优先序列末行，没有序列用 asset 表的快照（总资产页同款兜底） */
    private static BigDecimal valueOf(Asset asset, Map<String, BigDecimal> totals) {
        BigDecimal value = totals.get(asset.getCode());
        if (value == null) {
            value = asset.getTotal();
        }
        return zeroIfNull(value);
    }

    private static Map<String, BigDecimal> ratesOf(List<Asset> assets) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (asset.getRate() != null) {
                rates.put(asset.getCode(), asset.getRate());
            }
        }
        return rates;
    }

    private Function<String, Predicate<LocalDate>> calendarOf(List<Asset> assets) {
        Map<String, String> markets = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (StringUtils.hasText(asset.getMarket())) {
                markets.put(asset.getCode(), asset.getMarket());
            }
        }
        return code -> day -> tradingCalendarService.isTradingDay(day, markets.get(code));
    }

    private static List<Adjustment> dcaEvents(List<Adjustment> events) {
        return events.stream()
                .filter(event -> DCA_ACTIONS.contains(event.getAction()))
                .filter(event -> !CashAsset.isCash(event.getCode()))
                .toList();
    }

    private static List<BigDecimal> zeroSeries() {
        List<BigDecimal> zeros = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            zeros.add(BigDecimal.ZERO.setScale(MONEY_SCALE));
        }
        return zeros;
    }

    private static BigDecimal scale(BigDecimal value) {
        return zeroIfNull(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
