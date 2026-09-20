package com.finview.service;

import com.finview.entity.DayPoint;
import com.finview.entity.FundSeriesPoint;
import com.finview.mapper.FundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final FundMapper fundMapper;

    /**
     * 组合总览走势：按年份汇总全部进行中持仓的本金与市值。
     * 每年取该基金「不超过 {year}-12-01」的最新数据点求和；
     * 该年尚无数据点的基金跳过；返回点日期统一取 {year}-12-01。
     */
    public List<DayPoint> yearlySeries() {
        List<FundSeriesPoint> all = fundMapper.selectActiveSeries();
        Map<String, List<FundSeriesPoint>> byFund = all.stream()
                .collect(Collectors.groupingBy(FundSeriesPoint::getFundId));

        int minYear = all.stream().mapToInt(p -> p.getDay().getYear()).min().orElse(LocalDate.now().getYear());
        int maxYear = all.stream().mapToInt(p -> p.getDay().getYear()).max().orElse(minYear);

        List<DayPoint> result = new ArrayList<>(maxYear - minYear + 1);
        for (int year = minYear; year <= maxYear; year++) {
            LocalDate cutoff = LocalDate.of(year, 12, 1);
            long principal = 0;
            long total = 0;
            for (List<FundSeriesPoint> series : byFund.values()) {
                Optional<FundSeriesPoint> pick = series.stream()
                        .filter(p -> !p.getDay().isAfter(cutoff))
                        .reduce((first, second) -> second); // 有序流，保留最后一个
                if (pick.isPresent()) {
                    principal += pick.get().getPrincipal();
                    total += pick.get().getTotal();
                }
            }
            result.add(new DayPoint(cutoff, principal, total));
        }
        return result;
    }
}
