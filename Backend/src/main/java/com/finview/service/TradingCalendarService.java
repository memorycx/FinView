package com.finview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.finview.component.PythonRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A 股交易日历：判断某一天能不能定投。
 *
 * 数据来自 resources/python/trade_calendar.py（akshare 的 tool_trade_date_hist_sina），
 * 一次拿到 1990 年至当年年底的全部交易日，之后每天刷新一次即可，所以整份日历放内存里。
 *
 * 为什么不用「当天有没有净值」来判断：净值只在交易日发布没错，但 QDII 的净值要晚 3 天
 * （实测 270042/016452/017641/007721 最新净值停在 9-24，而今天是 9-29），
 * 拿它当交易日判断会把最近几个交易日的定投全吃掉。定投扣款本来就跟着**国内交易日**走，
 * 估值才用滞后净值，两件事分开。
 *
 * 拿不到日历时退化成「周一~周五」：至少周末不会扣款，节假日会误扣（下次拉到日历后重算会纠正）。
 */
@Slf4j
@Service
public class TradingCalendarService {

    /** 抓取脚本，见 resources/python/trade_calendar.py */
    private static final String SCRIPT = "trade_calendar.py";

    /**
     * 日历脚本的单独超时。
     * 它是**同步**挡在登录路径上的（推进序列前必须确定当天是不是交易日），
     * 所以不能用默认的 30 秒，否则外部接口一卡用户就得干等半分钟。
     */
    private static final long TIMEOUT_SECONDS = 10;

    private final PythonRunner pythonRunner;
    private final Executor refreshExecutor;

    /** 交易日集合，整块替换；空集合表示还没拉到 */
    private volatile Set<LocalDate> tradingDays = Set.of();

    /** 日历覆盖范围，超出范围的日子（比如跨年后的新数据）退化成工作日判断 */
    private volatile LocalDate firstDay;
    private volatile LocalDate lastDay;

    /** 上次尝试拉取的日期（成功失败都算），一天最多拉一次，别让脚本被反复拉起 */
    private volatile LocalDate lastAttempt;

    public TradingCalendarService(PythonRunner pythonRunner,
                                  @Qualifier("navRefreshExecutor") Executor refreshExecutor) {
        this.pythonRunner = pythonRunner;
        this.refreshExecutor = refreshExecutor;
    }

    /**
     * 某天在某个标的市场算不算「可以扣款」：A 股交易日 ∩ 标的市场开市日。
     *
     * market 来自 asset.market（QDII 标 us / hk，见 {@link MarketCalendar}）；
     * A 股基金为 null，只看 A 股日历 —— 与历史行为一致。
     */
    public boolean isTradingDay(LocalDate day, String market) {
        return isTradingDay(day) && MarketCalendar.isOpen(market, day);
    }

    /**
     * 某天是不是交易日。
     * 日历已加载且在覆盖范围内时按日历判断；否则退化成「周一~周五」。
     */
    public boolean isTradingDay(LocalDate day) {
        Set<LocalDate> days = tradingDays;
        LocalDate from = firstDay;
        LocalDate to = lastDay;
        if (!days.isEmpty() && from != null && to != null
                && !day.isBefore(from) && !day.isAfter(to)) {
            return days.contains(day);
        }
        return isWeekday(day);
    }

    /** 今天是不是交易日（登录时逐日推进会用到） */
    public boolean isTradingDay() {
        return isTradingDay(LocalDate.now());
    }

    /**
     * 确保日历已加载：没有就同步拉一次（最多等 10 秒）。
     * 由 {@link SeriesService} 在写序列前调用 —— 序列一旦按错误的交易日写下去，
     * 增量推进不会回头改，所以这里宁可等几秒。
     *
     * 不能拿 lastAttempt 提前返回：启动预热是异步的，它一进 {@link #load} 就会把
     * lastAttempt 标成今天，此时日历其实还没拉回来，提前返回会让这一天的序列按
     * 「周一~周五」的退化规则生成（真机上踩过：节假日多扣了 4 笔）。
     * 直接进 load()，synchronized 会挡住重复抓取 —— 异步那个正在拉就等它拉完。
     */
    public void ensureLoaded() {
        if (!tradingDays.isEmpty()) {
            return;
        }
        load();
    }

    /**
     * 异步预热 / 每日刷新：一天最多拉一次，失败只记日志。
     * 启动后调一次能挡住「第一次登录时同步等日历」；跨天后调一次能把新一年的交易日补进来。
     */
    public void refreshAsync() {
        if (LocalDate.now().equals(lastAttempt)) {
            return;
        }
        refreshExecutor.execute(this::load);
    }

    /** 真正去拉日历，失败退化成工作日规则（不抛异常，别把登录带崩） */
    private synchronized void load() {
        LocalDate today = LocalDate.now();
        if (today.equals(lastAttempt)) {
            return;
        }
        lastAttempt = today;

        JsonNode result;
        try {
            result = pythonRunner.run(SCRIPT, java.util.List.of(), TIMEOUT_SECONDS);
        } catch (Exception ex) {
            log.warn("加载交易日历失败，本次回退到「周一~周五」判断：{}", ex.getMessage());
            return;
        }
        if (!result.path("success").asBoolean(false)) {
            log.warn("交易日历返回失败：{}，本次回退到「周一~周五」判断", result.path("error").asText(""));
            return;
        }

        Set<LocalDate> days = new HashSet<>();
        for (JsonNode node : result.path("data")) {
            try {
                days.add(LocalDate.parse(node.asText().substring(0, 10)));
            } catch (RuntimeException ignored) {
                // 单条脏数据跳过就好，别让整份日历作废
            }
        }
        if (days.isEmpty()) {
            log.warn("交易日历解析后为空，本次回退到「周一~周五」判断");
            return;
        }

        LocalDate min = days.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate max = days.stream().max(LocalDate::compareTo).orElseThrow();
        firstDay = min;
        lastDay = max;
        tradingDays = Set.copyOf(days);
        log.info("交易日历已加载：{} 天，覆盖 {} ~ {}", days.size(), min, max);
    }

    private static boolean isWeekday(LocalDate day) {
        DayOfWeek week = day.getDayOfWeek();
        return week != DayOfWeek.SATURDAY && week != DayOfWeek.SUNDAY;
    }
}
