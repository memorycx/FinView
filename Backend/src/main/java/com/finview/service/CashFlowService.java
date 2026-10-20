package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.common.CashAsset;
import com.finview.controller.dto.CashFlowCalendarResponse;
import com.finview.controller.dto.CashFlowForecastResponse;
import com.finview.entity.Adjustment;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.entity.MonthlyInflowOverride;
import com.finview.entity.User;
import com.finview.mapper.AdjustmentMapper;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.MonthlyInflowOverrideMapper;
import com.finview.mapper.UserMapper;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 「定投分析」页（现金流规划 + 现金积累预测）的计算。
 *
 * 口径（与用户逐条确认过，改之前先读这里）：
 *
 * - **窗口 = 下月起 12 个完整自然月**。今天所在月份的剩余扣款刻意不计入 ——
 *   预测的起点是「当前可用现金」，当月已经发生的事都在里面了，未发生的那几天
 *   属于下一个月开始的完整窗口之外。窗口首月 = {@link #windowStart}，末月 = {@link #windowEnd}。
 * - **支出按实际扣款日归月**，不按周期点归月：每周一定投遇节假日顺延到下一个交易日、
 *   每月 1 日同理，顺延到次月的那笔就记在次月（钱是那时候走的）。
 * - **单笔支出 = asset_series 的 amount + fee**，恰好等于配置的定投金额本身 ——
 *   申购费是从定投金额里扣的（netOfFee = 金额 − 费），不是额外多划一笔，
 *   所以支出与 asset.rate 无关（与 CashLedger.balance 同一个式子）。
 * - **每月固定新增是「默认值 + 单月覆盖」两层**（2026-10-13 起）：默认值存 `user.monthly_inflow`
 *   （卡片 1 编辑），单月覆盖存 `monthly_inflow_override`（点柱状图的柱子设置，按 YYYY-MM 存档）。
 *   某月有覆盖行就用覆盖值、否则用默认值；删掉覆盖行 = 恢复默认 —— 覆盖成 0 与删掉覆盖是两回事
 *   （前者是「这个月收入为零」，后者是「跟默认值走」）。
 * - **卡片四个数的自洽算式**：12 个月后预计现金 = 当前现金 + 未来 12 个月新增合计 − 12 × 每月定投总额，
 *   其中每月定投总额 = 12 个月扣款合计 ÷ 12（四舍五入到分）。末月余额直接钉到前者，
 *   把四舍五入误差（≤ 6 分）收在最后一个月里，柱状图仍用各月的真实支出。
 *   没有任何单月覆盖时，新增合计 = 12 × 默认值，算式退化成「现金 + 12×新增 − 12×总额」。
 *
 * 实现方式：**不重写引擎**，把事件流交给 {@link SeriesService#fold} 直接推进到窗口末月，
 * 只取窗口内日期的逐日 rows 按月汇总 —— 周期点、顺延 pending、结束停扣、
 * QDII 叠加标的市场休市日、申购费全部走序列生成时的同一条实现，口径不可能漂移。
 * {@code fold} 也吃「未来日期」：它逐日推进到传入的 today，所以窗口内的 rows 是纯内存产物。
 *
 * 全链路只读：绝不写 asset_series / asset / 现金快照（未来日期的行落库会污染走势图
 * 与「Σamount = 末行 principal」的恒等式）。唯一的写操作在 {@link #updateMonthlyInflow}，
 * 只动 user.monthly_inflow 一列。
 *
 * 已知精度边界：交易日历只覆盖到当年年底（akshare 一次拿 1990 至当年），
 * 窗口里次年的日子会退化成「周一~周五」判断、节假日被当成交易日，扣款日偏多。
 * 接口如实返回 {@link TradingCalendarService#coverageEnd()}，由前端脚注说明；次年日历发布后自动修正。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashFlowService {

    /** 预测窗口的月数：未来 12 个整月（本月的剩余扣款刻意不计入） */
    private static final int WINDOW_MONTHS = 12;

    private static final BigDecimal TWELVE = new BigDecimal("12");

    /** 对外金额统一到「分」，卡片要以分为最小单位对账 */
    private static final int MONEY_SCALE = 2;

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 单月覆盖的月份格式：严格 YYYY-MM（01~12），挡住 '2026-13' / '2026-1' 这类脏值 */
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    /** 只有「定投开始 / 结束」影响周期扣款；买入（3）/ 卖出（4）只改本金与份额，见 dcaEvents */
    private static final Set<Integer> DCA_ACTIONS = Set.of(1, 2);

    /**
     * 日历里每只基金的配色轮转，取值与前端 --chart-N 变量一致（前端直接写 `var()`，缺了会渲染成透明）。
     * 与存钱计划的 SavingsService.ASSET_COLOR_VARS 是同一套，两处别各改各的。
     */
    private static final List<String> FUND_COLOR_VARS =
            List.of("--chart-1", "--chart-3", "--chart-4", "--chart-5");

    private final AdjustmentMapper adjustmentMapper;
    private final AssetMapper assetMapper;
    private final UserMapper userMapper;
    private final MonthlyInflowOverrideMapper monthlyInflowOverrideMapper;
    private final TradingCalendarService tradingCalendarService;

    /** 定投分析：现金流规划（四个数）+ 现金积累预测（12 个月明细） */
    public CashFlowForecastResponse forecast(Long userId) {
        // 日历没加载时 isTradingDay 会退化成「周一~周五」，节假日的扣款会算多，
        // 所以先兜一次加载（同步、最多等 10 秒，与 SeriesService 写序列前的做法一致）
        tradingCalendarService.ensureLoaded();

        List<Asset> assets = assetMapper.findByUser(userId);
        BigDecimal cash = currentCash(assets);
        BigDecimal inflow = monthlyInflow(userId);
        LocalDate today = LocalDate.now();

        Map<String, SeriesService.PlanState> states = SeriesService.fold(
                userId,
                dcaEvents(adjustmentMapper.findByUser(userId, null, null, null, null)),
                null,
                ratesOf(assets),
                calendarOf(assets),
                windowEnd(today));

        return toResponse(project(today, cash, inflow, overrides(userId), states),
                tradingCalendarService.coverageEnd());
    }

    /**
     * 存钱计划详情用：计划内每只资产「当前是否在定投」+ 窗口内逐月定投扣款（按 code 拆分）。
     *
     * 与 {@link #forecast} 同一套 fold / 交易日历 / 费率口径，区别只在汇总维度：
     * 这里不合并成用户级总额，而是保留每只资产，供计划详情画「按资产堆叠」的柱状图。
     *
     * @param assets 计划内资产（提供费率与 QDII 的 market 标记；可以包含现金，现金没有 1/2 事件）
     * @param codes  计划内资产编码，先过滤事件，别把别的计划/已归档资产算进来
     * @param today  判定「当前是否在投」的基准日
     * @param from   窗口首日（含），通常是下月 1 日
     * @param to     窗口末日（含），通常是 +12 个月月末
     * @return code → 定投状态与逐月金额；没有任何 1/2 事件时返回空 map
     */
    Map<String, PlanDca> planDca(Long userId, List<Asset> assets, Set<String> codes,
                                 LocalDate today, LocalDate from, LocalDate to) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<Adjustment> events = adjustmentMapper.findByUser(userId, null, null, null, null).stream()
                .filter(event -> codes.contains(event.getCode()))
                // 现金的 action=3/4 是进账/出账，不是定投；保留的 CASH code 也挡一道
                .filter(event -> !CashAsset.isCash(event.getCode()))
                .toList();
        if (events.isEmpty()) {
            return Map.of();
        }
        // 日历没加载时 isTradingDay 会退化成「周一~周五」，节假日会算成扣款日，先兜一次。
        // 只在这个计划真的有 DCA 事件时才加载，别让没有定投的计划白等一次外部抓取。
        tradingCalendarService.ensureLoaded();
        return projectByCode(userId, events, ratesOf(assets), calendarOf(assets),
                today, from, to);
    }

    /** 保存默认的「每月固定新增」（null = 清空），返回重算后的整份预测（前端一次往返即可刷新两张卡） */
    public CashFlowForecastResponse updateMonthlyInflow(Long userId, BigDecimal monthlyInflow) {
        BigDecimal value = monthlyInflow == null ? null : monthlyInflow.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // 单条 UPDATE 自带原子性，不需要 @Transactional；不碰 update_time（与 touchUpdateSeriesTime 同理，
        // 这是业务假设不是用户资料版本）
        userMapper.updateMonthlyInflow(userId, value);
        log.info("保存每月固定新增（默认值），userId={}, 值={}", userId, value);
        return forecast(userId);
    }

    /**
     * 给定月份列表，返回每月的「每月固定新增」假设值（默认值 + 单月覆盖，某月命中覆盖就用覆盖值）。
     * 「资金全景」的现金塔用它推余额 —— 与 {@link #forecast} 共用 {@link #monthlyInflow} /
     * {@link #overrides} / {@link #inflowOf} 三个取数与判定，改规则不会只改一处。
     */
    Map<YearMonth, BigDecimal> monthlyInflows(Long userId, List<YearMonth> months) {
        BigDecimal base = monthlyInflow(userId) == null ? BigDecimal.ZERO : scale(monthlyInflow(userId));
        Map<String, BigDecimal> custom = overrides(userId);
        Map<YearMonth, BigDecimal> result = new LinkedHashMap<>();
        for (YearMonth month : months) {
            result.put(month, inflowOf(month, base, custom));
        }
        return result;
    }

    /**
     * 保存某个月的「每月固定新增」覆盖值（点柱状图的柱子）。amount 为 null = 删除覆盖、恢复默认；
     * 传 0 是「这个月收入为零」，与恢复默认不是一回事。
     */
    public CashFlowForecastResponse updateMonthlyInflow(Long userId, String month, BigDecimal amount) {
        if (month == null || !MONTH_PATTERN.matcher(month).matches()) {
            throw BusinessException.badRequest("月份格式必须是 YYYY-MM");
        }
        if (amount == null) {
            monthlyInflowOverrideMapper.deleteByUserAndMonth(userId, month);
            log.info("清除每月固定新增的单月覆盖，userId={}, month={}", userId, month);
        } else {
            MonthlyInflowOverride override = new MonthlyInflowOverride();
            override.setUserId(userId);
            override.setMonth(month);
            override.setAmount(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            monthlyInflowOverrideMapper.upsert(override);
            log.info("保存每月固定新增的单月覆盖，userId={}, month={}, 值={}", userId, month, amount);
        }
        return forecast(userId);
    }

    /* ==================== 纯计算（单元测试直接打） ==================== */

    /** 窗口起点：下一个自然月 1 日 */
    static LocalDate windowStart(LocalDate today) {
        return YearMonth.from(today).plusMonths(1).atDay(1);
    }

    /** 窗口终点：第 12 个整月的最后一天（today=2026-10-13 → 2027-10-31） */
    static LocalDate windowEnd(LocalDate today) {
        return YearMonth.from(today).plusMonths(WINDOW_MONTHS).atEndOfMonth();
    }

    /**
     * 把 fold 到窗口末月的计划状态汇总成 12 个月数据点与四个卡片数字。
     *
     * @param currentCash   当前可用现金（元）
     * @param monthlyInflow 默认的每月固定新增；null = 未设置（按 0 参与计算，原样回传给前端）
     * @param overrides     单月覆盖（month 'YYYY-MM' → 金额）；某月命中就用它、否则用默认值
     * @param planStates    {@link SeriesService#fold} 的产物（已推进到窗口末月）
     */
    static Forecast project(LocalDate today, BigDecimal currentCash, BigDecimal monthlyInflow,
                            Map<String, BigDecimal> overrides, Map<String, SeriesService.PlanState> planStates) {
        BigDecimal cash = scale(currentCash);
        BigDecimal base = monthlyInflow == null ? BigDecimal.ZERO : scale(monthlyInflow);
        Map<String, BigDecimal> custom = overrides == null ? Map.of() : overrides;
        LocalDate from = windowStart(today);
        LocalDate to = windowEnd(today);

        // 窗口内按实际扣款日归月：只统计「那天的 rows 里有扣款」的日期（非周期点 / 已结束的日子 amount=0）
        Map<YearMonth, BigDecimal> outlayByMonth = new TreeMap<>();
        for (SeriesService.PlanState state : planStates.values()) {
            for (AssetSeries row : state.getRows()) {
                if (row.getDay() == null || row.getDay().isBefore(from) || row.getDay().isAfter(to)) {
                    continue;
                }
                BigDecimal outlay = zeroIfNull(row.getAmount()).add(zeroIfNull(row.getFee()));
                if (outlay.signum() == 0) {
                    continue;
                }
                outlayByMonth.merge(YearMonth.from(row.getDay()), outlay, BigDecimal::add);
            }
        }

        BigDecimal totalOutlay = scale(outlayByMonth.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal monthlyOutlay = totalOutlay.divide(TWELVE, MONEY_SCALE, RoundingMode.HALF_UP);

        // 先定每月的新增（有单月覆盖就用覆盖值，否则跟默认值走）与支出，再推 12 个月后：
        // 12 个月后 = 现金 + 新增合计 − 12×每月定投总额；把「合计 ÷ 12 四舍五入」的 ≤ 6 分误差
        // 收在最后一个月，卡片上「现金 + 新增合计 − 12×总额 = 12个月后」的算式精确成立。
        // 没有单月覆盖时新增合计 = 12×默认值，退化成「现金 + 12×新增 − 12×总额」
        YearMonth fromMonth = YearMonth.from(from);
        List<YearMonth> window = new ArrayList<>();
        List<BigDecimal> inflows = new ArrayList<>();
        List<BigDecimal> outlays = new ArrayList<>();
        BigDecimal totalInflow = BigDecimal.ZERO;
        for (int i = 0; i < WINDOW_MONTHS; i++) {
            YearMonth month = fromMonth.plusMonths(i);
            // 覆盖成 0 与没覆盖是两回事（前者「这个月收入为零」、后者「跟默认值走」），靠 overridden 区分
            BigDecimal inflow = inflowOf(month, base, custom);
            window.add(month);
            inflows.add(inflow);
            outlays.add(scale(outlayByMonth.getOrDefault(month, BigDecimal.ZERO)));
            totalInflow = totalInflow.add(inflow);
        }
        BigDecimal projectedCash = scale(cash.add(totalInflow).subtract(monthlyOutlay.multiply(TWELVE)));

        List<MonthPoint> months = new ArrayList<>();
        BigDecimal balance = cash;
        for (int i = 0; i < WINDOW_MONTHS; i++) {
            balance = balance.add(inflows.get(i)).subtract(outlays.get(i));
            months.add(new MonthPoint(window.get(i), inflows.get(i), outlays.get(i),
                    i == WINDOW_MONTHS - 1 ? projectedCash : scale(balance),
                    custom.containsKey(window.get(i).format(YEAR_MONTH))));
        }
        return new Forecast(cash, monthlyInflow, monthlyOutlay, projectedCash, totalOutlay, scale(totalInflow),
                fromMonth, fromMonth.plusMonths(WINDOW_MONTHS - 1), months);
    }

    /**
     * 某个月的「每月固定新增」：有单月覆盖就用覆盖值，否则跟默认值 base 走。
     * `base` 由调用方解析（未设置过是 0）—— 预测与「资金全景」的现金塔共用这一处判定。
     */
    static BigDecimal inflowOf(YearMonth month, BigDecimal base, Map<String, BigDecimal> overrides) {
        Map<String, BigDecimal> custom = overrides == null ? Map.of() : overrides;
        String key = month.format(YEAR_MONTH);
        return custom.containsKey(key) ? scale(custom.get(key)) : zeroIfNull(base);
    }

    /**
     * 按 code 投影定投：当前状态 + 窗口内逐月扣款。纯计算、不碰数据库，测试直接打。
     *
     * 为什么 fold 两次：
     * - **当前是否在定投**只看「今天及以前」的事件。未来日期的开始/结束不能影响今天的标记
     *   （例如用户在 12 月才开始一笔定投，10 月不该显示「定投中」）；过滤后交给 fold，
     *   同一天的开始/结束仍按 adjustment_id 顺序处理（结束后当天不再算在投）。
     * - **窗口内的扣款**要吃到全部事件（含未来日期的开始/结束），所以用完整事件流 fold 到 `to`。
     *
     * @return code → 定投状态与逐月金额；金额 = row.amount + row.fee，恰好等于配置的定投金额
     */
    static Map<String, PlanDca> projectByCode(Long userId, List<Adjustment> events,
                                              Map<String, BigDecimal> rateByCode,
                                              Function<String, Predicate<LocalDate>> isTradingDayOf,
                                              LocalDate today, LocalDate from, LocalDate to) {
        // 现金即使有历史遗留的 action=1/2 也不当成定投（它的 3/4 是进账/出账）
        List<Adjustment> dca = dcaEvents(events).stream()
                .filter(event -> !CashAsset.isCash(event.getCode()))
                .toList();
        if (dca.isEmpty()) {
            return Map.of();
        }
        List<Adjustment> upToToday = dca.stream()
                .filter(event -> !event.getDate().isAfter(today))
                .toList();
        Map<String, SeriesService.PlanState> now = upToToday.isEmpty()
                ? Map.of()
                : SeriesService.fold(userId, upToToday, null, rateByCode, isTradingDayOf, today);
        Map<String, SeriesService.PlanState> future = SeriesService.fold(userId, dca, null,
                rateByCode, isTradingDayOf, to);

        Map<String, PlanDca> result = new LinkedHashMap<>();
        for (Map.Entry<String, SeriesService.PlanState> entry : future.entrySet()) {
            Map<YearMonth, BigDecimal> outlayByMonth = new TreeMap<>();
            for (AssetSeries row : entry.getValue().getRows()) {
                if (row.getDay().isBefore(from) || row.getDay().isAfter(to)) {
                    continue;
                }
                BigDecimal outlay = zeroIfNull(row.getAmount()).add(zeroIfNull(row.getFee()));
                if (outlay.signum() == 0) {
                    continue;
                }
                outlayByMonth.merge(YearMonth.from(row.getDay()), outlay, BigDecimal::add);
            }
            // 与定投分析的金额精度一致：对外只到「分」
            outlayByMonth.replaceAll((month, amount) ->
                    amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));

            SeriesService.PlanState nowState = now.get(entry.getKey());
            boolean active = nowState != null && nowState.isActive();
            result.put(entry.getKey(), new PlanDca(
                    active,
                    active ? nowState.getFrequency() : null,
                    active ? nowState.getAmount() : null,
                    outlayByMonth));
        }
        return result;
    }

    /* ==================== 定投日历 ==================== */

    /**
     * 定投日历：某个自然月里每天的扣款金额与执行计划数（+ 本月总览 / 每只基金 / 每周分布）。
     *
     * month 缺省 = 当前月。与预测共用同一套 fold（只喂 action=1/2、推进到窗口末月），
     * 所以「过去的日子」是按当前事件流重算的周期扣款、未来（含本月剩余）是计划值，
     * 整月一个口径；超出导航范围的月份返回空日历（前端会用 minMonth/maxMonth 卡住箭头）。
     */
    public CashFlowCalendarResponse calendar(Long userId, String month) {
        LocalDate today = LocalDate.now();
        YearMonth target = StringUtils.hasText(month) ? parseMonth(month.trim()) : YearMonth.from(today);

        tradingCalendarService.ensureLoaded();
        List<Asset> assets = assetMapper.findByUser(userId);
        // 推进到「窗口末月」与「所选月份那一年的年尾」里更晚的那个：
        // 年度分布要看整年 12 个月，只看窗口的话次年年尾两格会假报 0
        LocalDate foldUntil = windowEnd(today);
        LocalDate yearEnd = YearMonth.of(target.getYear(), 12).atEndOfMonth();
        if (yearEnd.isAfter(foldUntil)) {
            foldUntil = yearEnd;
        }
        Map<String, SeriesService.PlanState> states = SeriesService.fold(
                userId,
                dcaEvents(adjustmentMapper.findByUser(userId, null, null, null, null)),
                null,
                ratesOf(assets),
                calendarOf(assets),
                foldUntil);

        Calendar calendar = aggregate(target, states, namesOf(assets));
        YearMonth maxMonth = YearMonth.from(windowEnd(today));
        // 下界 = 最早有定投记录的月份；一条记录都没有时退回当前月，导航范围永远是合法区间
        YearMonth minMonth = calendar.earliest() == null
                ? YearMonth.from(today) : calendar.earliest();
        return toResponse(calendar, minMonth.isAfter(YearMonth.from(today)) ? YearMonth.from(today) : minMonth,
                maxMonth);
    }

    /** 把某月的请求参数解析成 YearMonth；格式不对直接 400（与单月覆盖同一个正则） */
    private static YearMonth parseMonth(String month) {
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw BusinessException.badRequest("月份格式必须是 YYYY-MM");
        }
        return YearMonth.parse(month);
    }

    /**
     * 纯计算：把 fold 到窗口末月的计划状态按月汇总。
     *
     * - 金额 = 当天周期扣款的 amount + fee（= 配置的定投金额，申购费从里面扣）；
     * - 同一天多只基金的扣款相加，plans 记当天有几只基金在扣；
     * - 买入 / 卖出在调用方就被滤掉了（dcaEvents），不会混进「定投金额」。
     *
     * @param names code → 资产名（asset.name），缺失时退回 code
     */
    static Calendar aggregate(YearMonth month, Map<String, SeriesService.PlanState> planStates,
                              Map<String, String> names) {
        // 先攒「天 → 各基金当天金额」（点某一天要出明细，整月 / 每周 / 年度分布都从这里派生）
        Map<LocalDate, Map<String, BigDecimal>> fundsByDay = new TreeMap<>();
        // 所选月份所属年份的逐月合计 + 年度口径的三个统计（「年度定投金额分布」与「本年定投总览」用）
        Map<YearMonth, BigDecimal> byMonthOfYear = new TreeMap<>();
        Set<String> yearCodes = new LinkedHashSet<>();
        Set<LocalDate> yearDays = new LinkedHashSet<>();
        YearMonth earliest = null;

        for (SeriesService.PlanState state : planStates.values()) {
            for (AssetSeries row : state.getRows()) {
                if (row.getDay() == null) {
                    continue;
                }
                YearMonth rowMonth = YearMonth.from(row.getDay());
                if (earliest == null || rowMonth.isBefore(earliest)) {
                    earliest = rowMonth;
                }
                BigDecimal amount = zeroIfNull(row.getAmount()).add(zeroIfNull(row.getFee()));
                if (amount.signum() == 0) {
                    continue;   // 非扣款日不占位（「当天该扣但顺延走了」的日子也算没扣）
                }
                if (rowMonth.getYear() == month.getYear()) {
                    byMonthOfYear.merge(rowMonth, amount, BigDecimal::add);
                    yearCodes.add(state.getCode());
                    yearDays.add(row.getDay());
                }
                if (!rowMonth.equals(month)) {
                    continue;
                }
                fundsByDay.computeIfAbsent(row.getDay(), day -> new LinkedHashMap<>())
                        .merge(state.getCode(), amount, BigDecimal::add);
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> amountByCode = new LinkedHashMap<>();
        for (Map<String, BigDecimal> dayFunds : fundsByDay.values()) {
            for (Map.Entry<String, BigDecimal> entry : dayFunds.entrySet()) {
                total = total.add(entry.getValue());
                amountByCode.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            }
        }
        total = scale(total);
        int executedDays = fundsByDay.size();
        BigDecimal dailyAverage = executedDays == 0
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : total.divide(BigDecimal.valueOf(executedDays), MONEY_SCALE, RoundingMode.HALF_UP);

        List<MonthDay> days = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            Map<String, BigDecimal> dayFunds = fundsByDay.getOrDefault(date, Map.of());
            List<FundAmount> detail = dayFunds.entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(entry -> new FundAmount(entry.getKey(), scale(entry.getValue())))
                    .toList();
            BigDecimal amount = scale(dayFunds.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            days.add(new MonthDay(date, amount, detail.size(), detail));
        }

        // 每只基金按金额降序；配色轮转与存钱计划同一套（--chart-1/3/4/5）
        List<FundTotal> funds = new ArrayList<>();
        List<String> codes = amountByCode.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            BigDecimal amount = scale(amountByCode.get(code));
            BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO
                    : amount.divide(total, 4, RoundingMode.HALF_UP);
            funds.add(new FundTotal(code, names.getOrDefault(code, code), amount, share,
                    FUND_COLOR_VARS.get(i % FUND_COLOR_VARS.size())));
        }

        List<BigDecimal> weekdays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            weekdays.add(BigDecimal.ZERO.setScale(MONEY_SCALE));
        }
        for (MonthDay day : days) {
            if (day.amount().signum() == 0) {
                continue;
            }
            int index = day.day().getDayOfWeek().getValue() - 1;   // 周一 = 0
            weekdays.set(index, scale(weekdays.get(index).add(day.amount())));
        }

        // 年度分布：所选月份那一年的 1~12 月，恒 12 项
        List<BigDecimal> monthsOfYear = new ArrayList<>();
        BigDecimal yearTotal = BigDecimal.ZERO;
        for (int i = 1; i <= 12; i++) {
            BigDecimal monthAmount = scale(byMonthOfYear.getOrDefault(YearMonth.of(month.getYear(), i), BigDecimal.ZERO));
            monthsOfYear.add(monthAmount);
            yearTotal = yearTotal.add(monthAmount);
        }
        // 年度统计：与月度同一套口径（执行天数 = 当年有扣款的天数；日均 = 年合计 ÷ 执行天数）
        BigDecimal yearDailyAverage = yearDays.isEmpty()
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : yearTotal.divide(BigDecimal.valueOf(yearDays.size()), MONEY_SCALE, RoundingMode.HALF_UP);

        return new Calendar(month, total, executedDays, codes.size(), dailyAverage, days, funds, weekdays,
                monthsOfYear, scale(yearTotal), yearDays.size(), yearCodes.size(), yearDailyAverage, earliest);
    }

    /** 纯计算产物；测试直接断言 */
    record Calendar(YearMonth month, BigDecimal total, int executedDays, int fundCount,
                    BigDecimal dailyAverage, List<MonthDay> days, List<FundTotal> funds,
                    List<BigDecimal> weekdays, List<BigDecimal> monthsOfYear,
                    BigDecimal yearTotal, int yearExecutedDays, int yearFundCount,
                    BigDecimal yearDailyAverage, YearMonth earliest) {
    }

    /** 日历里的一天（funds = 当天各基金的金额，按金额降序；点某一天时右侧列表用它） */
    record MonthDay(LocalDate day, BigDecimal amount, int plans, List<FundAmount> funds) {
    }

    /** 日历某天里的单只基金 */
    record FundAmount(String code, BigDecimal amount) {
    }

    /** 日历里的一只基金（该月合计） */
    record FundTotal(String code, String name, BigDecimal amount, BigDecimal share, String colorVar) {
    }

    /** 纯计算产物；测试直接断言 */
    record Forecast(BigDecimal currentCash, BigDecimal monthlyInflow, BigDecimal monthlyOutlay,
                    BigDecimal projectedCash, BigDecimal totalOutlay, BigDecimal totalInflow,
                    YearMonth fromMonth, YearMonth toMonth, List<MonthPoint> months) {
    }

    /** 单个自然月：新增（含是否被单月覆盖）/ 定投支出 / 月末余额 */
    record MonthPoint(YearMonth month, BigDecimal inflow, BigDecimal outlay, BigDecimal endBalance,
                      boolean overridden) {
    }

    /**
     * 单只资产在计划里的定投投影（纯计算产物，测试直接断言）：
     * active / frequency / amount 是**今天**的状态；outlayByMonth 是窗口内逐月扣款，
     * 只放非零月份，金额已四舍五入到分。
     */
    record PlanDca(boolean active, String frequency, BigDecimal amount,
                   Map<YearMonth, BigDecimal> outlayByMonth) {
    }

    /* ==================== 私有辅助 ==================== */

    /** 当前可用现金：asset 表 code=CASH 那一行的 total（现金没有 asset_series，读的就是这一列），与 /assets/summary 同源 */
    private static BigDecimal currentCash(List<Asset> assets) {
        return assets.stream()
                .filter(asset -> CashAsset.isCash(asset.getCode()))
                .map(Asset::getTotal)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /** 默认的每月固定新增（user.monthly_inflow）；用户行不存在 / 未设置都返回 null */
    private BigDecimal monthlyInflow(Long userId) {
        User user = userMapper.findById(userId);
        return user == null ? null : user.getMonthlyInflow();
    }

    /** 单月覆盖：month 'YYYY-MM' → 金额（(user_id, month) 唯一，一个月份最多一行） */
    private Map<String, BigDecimal> overrides(Long userId) {
        Map<String, BigDecimal> overrides = new LinkedHashMap<>();
        for (MonthlyInflowOverride override : monthlyInflowOverrideMapper.findByUser(userId)) {
            if (override.getMonth() != null && override.getAmount() != null) {
                overrides.put(override.getMonth().trim(), override.getAmount());
            }
        }
        return overrides;
    }

    /**
     * 每个 code 的申购费率（asset.rate，百分数，0.12 = 0.12%）；口径同 SeriesService.loadRates，
     * 没设置过的不放进 map（= 不计费）。
     */
    private static Map<String, BigDecimal> ratesOf(List<Asset> assets) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (asset.getRate() != null) {
                rates.put(asset.getCode(), asset.getRate());
            }
        }
        return rates;
    }

    /** code → 资产名（asset.name），给日历的基金列表用；没名字的 code 在调用处退回 code 本身 */
    private static Map<String, String> namesOf(List<Asset> assets) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (StringUtils.hasText(asset.getName())) {
                names.put(asset.getCode(), asset.getName());
            }
        }
        return names;
    }

    /**
     * code → 该 code 的交易日判断（A 股交易日 ∩ 标的市场开市），口径同 SeriesService.foldForUser：
     * asset.market 标了 us / hk 的 QDII 才会叠加标的市场休市日（见 {@link MarketCalendar}）。
     *
     * 必须声明成 Function 类型再传：fold 还有一个同参数的 Predicate 重载，内联 lambda 会歧义。
     */
    private Function<String, Predicate<LocalDate>> calendarOf(List<Asset> assets) {
        Map<String, String> markets = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (StringUtils.hasText(asset.getMarket())) {
                markets.put(asset.getCode(), asset.getMarket());
            }
        }
        return code -> day -> tradingCalendarService.isTradingDay(day, markets.get(code));
    }

    /**
     * 只保留 action=1（定投开始）/ 2（结束定投）的记录再交给 fold。
     * 「买入 / 卖出」不参与周期扣款逻辑（它们只改本金与份额），滤掉之后
     * fold 出来的逐日 amount/fee 就只剩周期扣款这一项 —— 窗口内如果有未来日期的
     * 买入 / 卖出记录，也不会被当成「定投支出」算进柱状图。
     */
    static List<Adjustment> dcaEvents(List<Adjustment> events) {
        return events.stream()
                .filter(event -> DCA_ACTIONS.contains(event.getAction()))
                .toList();
    }

    private static CashFlowForecastResponse toResponse(Forecast forecast, LocalDate coverageEnd) {
        List<CashFlowForecastResponse.MonthResponse> months = forecast.months().stream()
                .map(month -> new CashFlowForecastResponse.MonthResponse(
                        month.month().format(YEAR_MONTH), month.inflow(), month.outlay(), month.endBalance(),
                        month.overridden()))
                .toList();
        return new CashFlowForecastResponse(
                forecast.currentCash(), forecast.monthlyInflow(), forecast.monthlyOutlay(),
                forecast.projectedCash(), forecast.totalOutlay(),
                forecast.fromMonth().format(YEAR_MONTH), forecast.toMonth().format(YEAR_MONTH),
                coverageEnd == null ? null : coverageEnd.toString(),
                months);
    }

    private static CashFlowCalendarResponse toResponse(Calendar calendar, YearMonth minMonth, YearMonth maxMonth) {
        List<CashFlowCalendarResponse.DayResponse> days = calendar.days().stream()
                .map(day -> new CashFlowCalendarResponse.DayResponse(
                        day.day().toString(), day.amount(), day.plans(),
                        day.funds().stream()
                                .map(fund -> new CashFlowCalendarResponse.DayFundResponse(fund.code(), fund.amount()))
                                .toList()))
                .toList();
        List<CashFlowCalendarResponse.FundResponse> funds = calendar.funds().stream()
                .map(fund -> new CashFlowCalendarResponse.FundResponse(
                        fund.code(), fund.name(), fund.amount(), fund.share(), fund.colorVar()))
                .toList();
        return new CashFlowCalendarResponse(
                calendar.month().format(YEAR_MONTH), minMonth.format(YEAR_MONTH), maxMonth.format(YEAR_MONTH),
                calendar.total(), calendar.executedDays(), calendar.fundCount(), calendar.dailyAverage(),
                days, funds, calendar.weekdays(), calendar.monthsOfYear(),
                calendar.yearTotal(), calendar.yearExecutedDays(), calendar.yearFundCount(),
                calendar.yearDailyAverage());
    }

    private static BigDecimal scale(BigDecimal value) {
        return zeroIfNull(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
