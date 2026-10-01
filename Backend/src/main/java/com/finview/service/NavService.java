package com.finview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.finview.component.PythonRunner;
import com.finview.entity.NavTrend;
import com.finview.mapper.NavTrendMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 净值数据源：调 resources/python/fund_info.py（akshare）把某只基金的历史单位净值抓进 nav_trend。
 *
 * 三条设计约束：
 * - **绝不抛异常**：外部接口挂了、没装 akshare、断网都只记日志，序列那边继续按「净值 = 1」的兜底跑，
 *   不能让一个可选的增强功能把登录或录入接口带崩；
 * - **一天最多抓一次**：基金净值一天只出一个，每次抓取要起一个 python 进程 + 走一次 HTTP，
 *   同一天重复抓纯属浪费，用 {@link #lastAttempt} 在内存里挡掉（进程重启后重试一次，可接受）；
 * - **抓全历史而不是只抓最新**：只有拿到每一条扣款日当天的净值，份额才算得准
 *   （脚本原本 tail(1) 只返回最后一行，会把历史定投全按净值 1 折算）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavService {

    /** 抓取脚本，见 resources/python/fund_info.py */
    private static final String SCRIPT = "fund_info.py";

    private final PythonRunner pythonRunner;
    private final NavTrendMapper navTrendMapper;

    /** code → 当天是否已经尝试过抓取（无论成败，避免一天内反复起 python 进程） */
    private final Map<String, LocalDate> lastAttempt = new ConcurrentHashMap<>();

    /**
     * 抓某个 code 的净值并入库，一天最多实际抓一次（登录后的自动补数据走这条）。
     *
     * @param since 只抓这一天（含）之后的净值，通常传该 code 最早一条调整记录的日期；
     *              null 表示抓全历史
     * @return 是否真的有新数据写入（写入了才需要重算序列）
     */
    public boolean refresh(String code, LocalDate since) {
        if (code == null || code.isBlank()) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (today.equals(lastAttempt.get(code))) {
            log.debug("今天已经尝试过抓净值，跳过：code={}", code);
            return false;
        }
        lastAttempt.put(code, today);
        return fetchAndStore(code, since);
    }

    /** 手动刷新（/nav/refresh）：不受「一天一次」限制，适合「我现在就要看最新市值」 */
    public boolean refreshNow(String code, LocalDate since) {
        if (code == null || code.isBlank()) {
            return false;
        }
        lastAttempt.put(code, LocalDate.now());
        return fetchAndStore(code, since);
    }

    /** 抓取并落库；已经有当天的净值就不再跑外部接口（净值一天只出一个） */
    private boolean fetchAndStore(String code, LocalDate since) {
        LocalDate maxDay = navTrendMapper.findMaxDayByCode(code);
        if (maxDay != null && !maxDay.isBefore(LocalDate.now())) {
            log.debug("已有当天净值，跳过抓取：code={}, day={}", code, maxDay);
            return false;
        }

        List<NavTrend> rows = fetch(code, since);
        if (rows.isEmpty()) {
            return false;
        }
        navTrendMapper.upsertBatch(rows);
        log.info("净值已更新，code={}，{} 行，最新 {} = {}",
                code, rows.size(), rows.get(rows.size() - 1).getDay(), rows.get(rows.size() - 1).getValue());
        return true;
    }

    /** 跑脚本并解析 JSON，任何失败都返回空列表（调用方按「没有新数据」处理） */
    private List<NavTrend> fetch(String code, LocalDate since) {
        JsonNode result;
        try {
            List<String> args = since == null
                    ? List.of(code)
                    : List.of(code, since.toString());
            result = pythonRunner.run(SCRIPT, args);
        } catch (Exception ex) {
            log.warn("抓净值失败（沿用已有数据），code={}：{}", code, ex.getMessage());
            return List.of();
        }

        if (!result.path("success").asBoolean(false)) {
            log.warn("抓净值返回失败，code={}，error={}", code, result.path("error").asText(""));
            return List.of();
        }

        List<NavTrend> rows = new ArrayList<>();
        for (JsonNode item : result.path("data")) {
            NavTrend row = parse(item, code);
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            log.warn("抓净值返回了空数据，code={}", code);
        }
        return rows;
    }

    /**
     * 解析脚本返回的一行：{@code {"净值日期": "2026-09-28", "单位净值": 3.9801, "日增长率": -0.78}}。
     * 日期可能是带时间的字符串（pandas 的 Timestamp 转出来带 00:00:00），只取前 10 位。
     */
    private NavTrend parse(JsonNode item, String code) {
        String dayText = item.path("净值日期").asText("");
        BigDecimal value = decimal(item.path("单位净值"));
        if (dayText.length() < 10 || value == null || value.signum() <= 0) {
            log.warn("跳过一行无法解析的净值，code={}，原始数据={}", code, item);
            return null;
        }
        try {
            return new NavTrend(LocalDate.parse(dayText.substring(0, 10)), value,
                    decimal(item.path("日增长率")), code);
        } catch (Exception ex) {
            log.warn("跳过一行日期格式异常的净值，code={}，净值日期={}", code, dayText);
            return null;
        }
    }

    /** JSON 数值 → BigDecimal；缺失、字符串 NaN 之类一律返回 null */
    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
