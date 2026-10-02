package com.finview.service;

import com.finview.common.CashAsset;
import com.finview.common.Xirr;
import com.finview.controller.dto.DayPointResponse;
import com.finview.controller.dto.PortfolioSeriesResponse;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import com.finview.service.SeriesService.PlanState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 组合总览业务：把进行中持仓的每日序列按天加总，给看板的「投资组合总览」曲线与组合年化收益率。
 *
 * 口径与前端 mock 的 portfolioSeries 一致：只汇总**未归档**的 code（定投结束但没归档的仍算持有），
 * 每个 code 从它自己首个事件那天起才有数据，所以某一天的和 = 当天已有序列的持仓之和
 * （还没开始的持仓不参与，不会出现先高后低的假曲线）。
 *
 * 序列来源与 {@link FundService} 完全一致（这很关键：两个面板读的是同一份东西，不能一个显示
 * 十只、另一个只显示一只）：优先用 asset_series 的行，**库里没有行的 code 回退到内存 fold**
 * —— 清过表、或装好的老库还没走过 advance 时，库里可能是空的或不完整的。
 *
 * 组合年化是 XIRR，现金流同样只取未归档的 code（与曲线、与组合收益率同源）；
 * 口径（为什么是 −(amount + fee)、为什么要有 30 天门槛）见 {@link Xirr}。
 *
 * 「最后更新时间」是 2026-10-02 单独约定的口径：取该用户**全部 asset 行**（含已归档与现金，
 * 与曲线/年化的持仓范围故意不同）last_update_time 的 MAX，即「所有资产里最新的那个」，
 * 前端画在组合总览折线图的页脚。
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    /** aggregate 返回的数组下标：[0] 本金合计，[1] 市值合计，[2] 当日手续费合计，[3] 当日净投入合计 */
    private static final int PRINCIPAL = 0;
    private static final int TOTAL = 1;
    private static final int FEE = 2;
    private static final int AMOUNT = 3;

    private final AssetMapper assetMapper;
    private final AssetSeriesMapper assetSeriesMapper;
    private final SeriesService seriesService;

    /**
     * 组合走势 + 组合年化收益率。
     * series 按日期升序，principal = 累计本金合计，total = 市值合计，fee = 当日手续费合计；
     * 没有任何进行中的持仓时返回空曲线 + null 年化。
     */
    public PortfolioSeriesResponse series(Long userId) {
        List<Asset> assets = assetMapper.findByUser(userId);
        // 「最后更新时间」= 全部 asset 行（含已归档与现金）last_update_time 的 MAX（2026-10-02 约定）：
        // 编辑一只已归档基金、或记一笔现金收支，也会把组合总览的「最后更新」刷到刚才，
        // 虽然曲线本身没变。一条资产都没有时为 null（前端显示「—」）
        LocalDateTime lastUpdateTime = assets.stream()
                .map(Asset::getLastUpdateTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Set<String> codes = new LinkedHashSet<>();
        for (Asset asset : assets) {
            if (Boolean.TRUE.equals(asset.getArchived())) {
                continue;
            }
            // 现金不是持仓：它没有序列行（见 SeriesService.syncCashAsset），一旦混进来
            // 既会拖着一个永远没有行的 code 让 loadRows 每次都白跑全量 fold，
            // 又会让现金的流入流出对冲掉基金的现金流、把组合 XIRR 算成没意义的数
            if (CashAsset.isCash(asset.getCode())) {
                continue;
            }
            codes.add(asset.getCode());
        }
        if (codes.isEmpty()) {
            return new PortfolioSeriesResponse(List.of(), null, lastUpdateTime);
        }

        NavigableMap<LocalDate, BigDecimal[]> byDay = aggregate(loadRows(userId, codes));

        // codes 非空不等于有曲线：asset 行可以比序列活得久 —— 一只基金的调整记录被删光后
        // asset 行还留着（name / category 是用户改过的），序列和 fold 就都没有数据了；
        // 只有现金的用户也会走到这里（现金不进这个集合，但 asset 行在）。
        // 少了这道判断，下面的 lastKey() 会抛 NoSuchElementException，整个看板 500。
        if (byDay.isEmpty()) {
            return new PortfolioSeriesResponse(List.of(), null, lastUpdateTime);
        }

        // 现金流：每天「各 code 净投入 + 当日手续费」之和，末日再追加当天市值合计（虚拟赎回）。
        // TreeMap 保证按日升序，Xirr 要靠这个顺序算持有期（首笔 → 末笔的自然日天数）
        List<Xirr.CashFlow> flows = new ArrayList<>(byDay.size());
        LocalDate lastDay = byDay.lastKey();
        byDay.forEach((day, sums) -> {
            BigDecimal flow = sums[AMOUNT].add(sums[FEE]).negate();
            if (day.equals(lastDay)) {
                flow = flow.add(sums[TOTAL]);
            }
            flows.add(new Xirr.CashFlow(day, flow));
        });

        // 累计投入（收益率的分母）= Σ 正的当日出资，口径与单只基金一致，见 CLAUDE.md
        List<DayPointResponse> points = new ArrayList<>(byDay.size());
        BigDecimal invested = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal[]> entry : byDay.entrySet()) {
            BigDecimal[] sums = entry.getValue();
            invested = invested.add(Xirr.outlay(sums[AMOUNT], sums[FEE]).max(BigDecimal.ZERO));
            points.add(new DayPointResponse(entry.getKey().toString(),
                    sums[PRINCIPAL], sums[TOTAL], sums[FEE], invested));
        }
        return new PortfolioSeriesResponse(points, Xirr.annualizedRate(flows), lastUpdateTime);
    }

    /**
     * 组合要用的序列行：库里有的 code 用库里的，库里**一行都没有**的 code 回退到内存 fold
     * —— 与 {@link FundService#loadSeries} 是同一套兜底，两个面板展示的持仓必须一致。
     *
     * 没有这层兜底的话，「清空 asset_series 后逐只生成」期间组合会只加已生成的那几只：
     * 计划列表还在用 fold 显示全部 10 只持仓，组合曲线却只剩 1 只，末点市值直接塌掉
     * （2026-10-01：000051 生成过、其余 9 只没有行，组合总市值显示成 ¥20）。
     * 已经有了行的 code 一律以库为准，不做合并，避免同一只被加两遍。
     */
    private List<AssetSeries> loadRows(Long userId, Set<String> codes) {
        List<AssetSeries> rows = new ArrayList<>(assetSeriesMapper.findByUserAndCodes(userId, codes));
        Set<String> codesWithRows = rows.stream()
                .map(AssetSeries::getCode)
                .collect(Collectors.toSet());
        if (codesWithRows.containsAll(codes)) {
            return rows;
        }

        Map<String, PlanState> states = seriesService.foldForUser(userId, LocalDate.now());
        for (String code : codes) {
            if (codesWithRows.contains(code)) {
                continue;
            }
            PlanState state = states.get(code);
            if (state != null) {
                rows.addAll(state.getRows());
            }
        }
        return rows;
    }

    /**
     * 纯计算：多只 code 的行按天汇总成一天一行（包级可见，便于单测）。
     *
     * principal / total 是**存量**，同一天各 code 的值直接相加；
     * fee / amount 是**当日流量**（fee 当天收的手续费、amount 当天净投入），相加就是组合当天发生额。
     *
     * **序列先结束的 code 必须结转**：各 code 的序列不一定推进到同一天 —— 编辑一条记录只会重算那一个
     * code（推到 today），别的 code 还停在上次登录那天。不结转的话，组合的**末点**会只剩「今天有行的那几只」，
     * 本金 / 市值直接塌掉（2026-10-01 就出现过：000051 单独被重算到当天，组合末点被压成 4.93 / 20.01）。
     * 结转的日子只沿用存量，amount / fee 记 0：没有买卖就没有现金流。
     */
    static NavigableMap<LocalDate, BigDecimal[]> aggregate(List<AssetSeries> rows) {
        NavigableMap<LocalDate, BigDecimal[]> byDay = new TreeMap<>();
        if (rows.isEmpty()) {
            return byDay;
        }

        // 所有 code 里最晚的一天：每个 code 的存量都结转补到这里为止
        LocalDate lastDay = rows.stream().map(AssetSeries::getDay)
                .max(Comparator.naturalOrder()).orElseThrow();

        Map<String, List<AssetSeries>> byCode = new LinkedHashMap<>();
        for (AssetSeries row : rows) {
            byCode.computeIfAbsent(row.getCode(), code -> new ArrayList<>()).add(row);
        }

        for (List<AssetSeries> codeRows : byCode.values()) {
            codeRows.sort(Comparator.comparing(AssetSeries::getDay));
            for (AssetSeries row : codeRows) {
                add(byDay, row.getDay(), row.getPrincipal(), row.getTotal(), row.getFee(), row.getAmount());
            }
            AssetSeries last = codeRows.get(codeRows.size() - 1);
            for (LocalDate day = last.getDay().plusDays(1); !day.isAfter(lastDay); day = day.plusDays(1)) {
                add(byDay, day, last.getPrincipal(), last.getTotal(), BigDecimal.ZERO, BigDecimal.ZERO);
            }
        }
        return byDay;
    }

    /** 把一行（或结转过来的一份存量）累加进当天的合计 */
    private static void add(NavigableMap<LocalDate, BigDecimal[]> byDay, LocalDate day,
                            BigDecimal principal, BigDecimal total, BigDecimal fee, BigDecimal amount) {
        BigDecimal[] sums = byDay.computeIfAbsent(day,
                d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        sums[PRINCIPAL] = sums[PRINCIPAL].add(principal);
        sums[TOTAL] = sums[TOTAL].add(total);
        sums[FEE] = sums[FEE].add(fee);
        sums[AMOUNT] = sums[AMOUNT].add(amount);
    }
}
