package com.finview.service;

import com.finview.common.Xirr;
import com.finview.controller.dto.DayPointResponse;
import com.finview.controller.dto.PortfolioSeriesResponse;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 组合总览业务：把进行中持仓的每日序列按天加总，给看板的「投资组合总览」曲线与组合年化收益率。
 *
 * 口径与前端 mock 的 portfolioSeries 一致：只汇总**未归档**的 code（定投结束但没归档的仍算持有），
 * 每个 code 从它自己首个事件那天起才有数据，所以某一天的和 = 当天已有序列的持仓之和
 * （还没开始的持仓不参与，不会出现先高后低的假曲线）。
 *
 * 组合年化是 XIRR，现金流同样只取未归档的 code（与曲线、与组合收益率同源）；
 * 口径（为什么是 −(amount + fee)、为什么要有 30 天门槛）见 {@link Xirr}。
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

    /**
     * 组合走势 + 组合年化收益率。
     * series 按日期升序，principal = 累计本金合计，total = 市值合计，fee = 当日手续费合计；
     * 没有任何进行中的持仓时返回空曲线 + null 年化。
     */
    public PortfolioSeriesResponse series(Long userId) {
        Set<String> codes = new LinkedHashSet<>();
        for (Asset asset : assetMapper.findByUser(userId)) {
            if (!Boolean.TRUE.equals(asset.getArchived())) {
                codes.add(asset.getCode());
            }
        }
        if (codes.isEmpty()) {
            return new PortfolioSeriesResponse(List.of(), null);
        }

        NavigableMap<LocalDate, BigDecimal[]> byDay = aggregate(assetSeriesMapper.findByUserAndCodes(userId, codes));

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
        return new PortfolioSeriesResponse(points, Xirr.annualizedRate(flows));
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
