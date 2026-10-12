package com.finview.service;

import com.finview.common.CashAsset;
import com.finview.controller.dto.PlanAdjustmentResponse;
import com.finview.entity.Adjustment;
import com.finview.entity.Asset;
import com.finview.entity.AssetSeries;
import com.finview.entity.NavTrend;
import com.finview.entity.User;
import com.finview.mapper.AdjustmentMapper;
import com.finview.mapper.AssetMapper;
import com.finview.mapper.AssetSeriesMapper;
import com.finview.mapper.NavTrendMapper;
import com.finview.mapper.UserMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DayOfWeek;
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
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 《表设计.md》「核心更新流程」的实现：把 adjustments 事件流逐日推进成 asset_series。
 *
 * <pre>
 * 用户登录（或新增/修改/删除一条调整记录）
 *   └─ 取 user.update_series_time 作为 last_update
 *   └─ 对每个 (user_id, code) 分组处理 adjustments
 *        └─ 从 last_update+1 逐日推进到 today
 *             └─ 应用当天的 action
 *             └─ 写入 asset_series
 *   └─ 更新 user.update_series_time = today
 * </pre>
 *
 * 单日内的处理顺序（表设计.md 场景 4，把 action=2 挪到周期扣款之后 —— 见下）：
 * <ol>
 *   <li>action=3/4：离散收支，直接加减本金；</li>
 *   <li>action=1：标记定投生效 / 更新频率金额 / 必要时重置周期锚点；</li>
 *   <li>周期扣款：当天是周期点、定投进行中、**且当天是交易日** → 累加当期金额；</li>
 *   <li>action=2：标记定投失效。</li>
 * </ol>
 * 第 3、4 步的顺序与场景 2 的「[起点, 终点] 内每到一个周期点就累加」保持一致：
 * **结束定投当天只要恰好是周期点，仍然扣款**（013402 的 6/29 是周一、008887 的 8/6 是 daily）。
 *
 * 几条不写在表结构里、但会直接影响数字的规则：
 * - **扣款日**：daily 每个交易日；weekly **每周一**；monthly **每月 1 日**（固定的自然周期，
 *   不是「起点 + N 天」）。所以周一/1 号当天新开的定投当天就扣，其它日子开的要等到下一个周一/1 号。
 * - **调整什么时候生效**：只改金额（定投下调/上调）**当天就按新金额扣**；
 *   改频率（每日 → 每周）**当天仍按原计划扣**，新频率次日生效
 *   —— 008887 在 2026-09-02 由每日改每周，那天扣的仍是每日那一笔，之后从 9/7（周一）开始走周投。
 * - **非交易日不扣款**（周末、节假日），判定见 {@link TradingCalendarService}：
 *   每日定投直接跳过那一天（否则下一个交易日会连扣两笔）；
 *   每周 / 每月定投顺延到下一个交易日补扣（券商通行规则）。顺延期间若定投被结束，那笔补扣作废。
 *   标了 asset.market（us / hk）的 **QDII 还要叠加标的市场休市日**，两市场都开市才扣
 *   （见 {@link MarketCalendar}；2026-09-30 加：017641 曾在美股劳动节 09-07 被多扣一笔）。
 * - **停止定投当天仍算一个周期点**（表设计.md 场景 2 的 [起点, 终点]，用户确认过）。
 * - **一天只扣一次款**：同一天出现多条 action=1（重复录入）不会重复扣款，
 *   因为扣款由「当天是不是周期点」决定，与事件条数无关。
 * - **买入要扣申购手续费**：周期扣款和「一笔收入」都按 asset.rate（百分数）扣
 *   手续费 = 金额 × rate / 100（**四舍五入到分，不足 1 分按 1 分**），**扣完的净额才计入本金与份额**；
 *   「一笔支出」（卖出）不是买入，不扣费率，全额冲减本金；没配费率（null/0）就不收。
 *   每笔收的手续费逐日记进 asset_series.fee（累计手续费 = Σfee）；前端收益折线图
 *   按「市值 − 本金 − 累计手续费」算收益，把申购费当成本。
 * - **市值 = 累计份额 × 当日净值**，份额 = Σ(当日净投入 / 当日净值)。当天没有净值时沿用最近一次
 *   （QDII 净值晚几天，估值自然滞后，不影响扣款），整段没有净值时按净值 1 处理
 *   （此时 total 恰好等于 principal，收益率 0）。
 * - **清仓**：一笔支出的金额 ≥ 当天持仓市值时视为**清仓**（2026-10-01 按 000051 的真实成交确认的口径）：
 *   份额归零、市值跟着变 0（不会留下负份额的空头），但**本金与手续费照常按净额累计** ——
 *   累计收益 = 市值 − 本金 − 手续费 要跨轮连续，不能因为清仓就把已实现收益抹掉
 *   （平台到账金额含已实现盈亏，卖出价高于投入时本金会变成负数，这是「净投入」的正常结果）。
 *   这一天同时是**年化收益率的轮次分界**：上一轮的钱已经全部收回，再和这一轮混着算，
 *   会把「24 天赚 4.9%」按整段持有期摊掉。收益率的**分母**用累计投入（Σ 正的当日出资），
 *   不用「本金 + 手续费」—— 本金为负或很小时后者会算出 +297% 这种假数字。
 *   同一天清仓后又买入（当天的定投）算新一轮的第一笔。
 * - 序列仍然**每个自然日一行**，非交易日 amount 为 0；这样 Σamount == 末行 principal 的对账
 *   不变式、以及增量推进按天推进的逻辑都不用动（清仓当天也一样：amount 记真实到账金额）。
 * - **最后更新时间**：写 asset 快照时把 `asset.last_update_time` 刷成当时时刻（登录推进、增删改调整记录、
 *   抓到新净值后的重算、手动生成序列都会走到；现金没有序列，取最后一次余额快照刷新的时刻）。
 *   折线图页脚的「最后更新」读它，组合总览取该用户**全部 asset 行**（含已归档与现金）的 MAX；
 *   存量行由 schema.sql 回填成所属用户的 user.create_time。
 *
 * 写入策略：登录走 {@link #advance}（只补 last_update 之后的新行，首次等于全量）；
 * 调整记录被增删改、或新净值到达时走 {@link #rebuild}（整段重算），
 * 否则改一条历史记录会留下永远错下去的旧行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeriesService {

    /** 动作码，与 AdjustmentService 的 VALID_ACTIONS 一致 */
    private static final int ACTION_START = 1;
    private static final int ACTION_STOP = 2;
    private static final int ACTION_INCOME = 3;
    private static final int ACTION_EXPENSE = 4;

    /** 时间线上的动作文案。action=1 的文案由 {@link #startLabel} 按与上一状态的差异细分 */
    private static final String LABEL_START = "开始定投";
    private static final String LABEL_UP = "上调金额";
    private static final String LABEL_DOWN = "下调金额";
    private static final String LABEL_FREQUENCY = "调整频率";
    private static final String LABEL_STOP = "结束定投";
    private static final String LABEL_INCOME = "一笔收入";
    private static final String LABEL_EXPENSE = "一笔支出";

    /** 频率取值，与前端 InvestFrequency 一致 */
    private static final String FREQ_DAILY = "daily";
    private static final String FREQ_WEEKLY = "weekly";
    private static final String FREQ_MONTHLY = "monthly";

    /** asset 表缺行时的兜底分类 */
    private static final String DEFAULT_CATEGORY = "fund";

    /** 新建基金行的默认类型：股票基金（equity）。用户的选择走 AdjustmentService.updateAssetType，fold 不覆盖 */
    private static final String DEFAULT_ASSET_TYPE = "equity";

    /** 份额的中间精度：只有市值是给前端看的，份额多留几位避免累计误差 */
    private static final int SHARE_SCALE = 8;

    /** 金额精度，与库里的 DECIMAL(18,4) 对齐 */
    private static final int MONEY_SCALE = 4;

    /** 费率是百分数（asset.rate 的 0.12 表示 0.12%），算手续费时除以它 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 手续费精度：平台按**分**收，算出来先四舍五入到分 */
    private static final int FEE_SCALE = 2;

    /** 每笔买入的最低手续费：1 分。四舍五入后不足 1 分也照 1 分扣 */
    private static final BigDecimal MIN_FEE = new BigDecimal("0.01");

    /** 前端的 startDate 是「YYYY-MM」形如 2026-06，PerformanceChart 按长度 7 识别成月份 */
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AdjustmentMapper adjustmentMapper;
    private final AssetSeriesMapper assetSeriesMapper;
    private final NavTrendMapper navTrendMapper;
    private final AssetMapper assetMapper;
    private final UserMapper userMapper;
    private final NavService navService;
    private final TradingCalendarService tradingCalendarService;

    /** 补净值要起 python 进程、走外部接口，绝不能挡在登录响应里，见 AsyncConfig */
    @Qualifier("navRefreshExecutor")
    private final Executor navRefreshExecutor;

    /**
     * 现金与基金买卖的联动起点（含当天，口径见 {@link CashLedger}）：只有这一天及以后的
     * 扣款 / 买入 / 卖出才动现金余额，之前的历史不追溯 —— 用户的历史买入从来没记过现金，
     * 补进来余额会凭空少六千多（2026-10-01 约定）。
     * 2026-10-08 = 国庆休市（10/1–10/7）后第一个 A 股交易日，用 python/trade_calendar.py 核对过。
     *
     * 非 final 字段，Lombok 不会把它收进构造器，所以这里是字段注入（为它加配置注解不值得）。
     */
    @Value("${finview.cash.link-from:2026-10-08}")
    private LocalDate cashLinkFrom;

    /* ==================== 对外入口 ==================== */

    /**
     * 增量推进：登录 / 注册后调用，只写 update_series_time 之后的新行。
     *
     * 水位为 null（第一次用，或迁移过来的老账号）时窗口就是「首个事件 → 今天」，等于全量。
     * 当天已经推进过就直接返回，重复登录不会重复写库。
     */
    @Transactional
    public void advance(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate lastSync = lastSyncDate(userId);
        if (lastSync != null && !lastSync.isBefore(today)) {
            log.debug("序列今天已推进过，跳过：userId={}, lastSync={}", userId, lastSync);
            // 水位挡住了整段推进，但「一条净值都没有」的 code（新基金首次抓取失败）还要补抓：
            // 失败当天不会再走到下面的 refreshNavAsync，不补的话要等第二天登录才会自愈
            refreshMissingNavAsync(userId);
            return;
        }

        // 交易日必须先确定：序列一旦按错误的交易日写下去，增量推进不会再回头改
        tradingCalendarService.ensureLoaded();
        // 跨天后顺手把日历刷新一遍（异步），别拖慢登录
        tradingCalendarService.refreshAsync();

        Map<String, PlanState> states = foldForUser(userId, today);
        for (PlanState state : states.values()) {
            writeRows(userId, state, lastSync);
            // 净值是外部的，异步补；补到了会再回调 rebuild 把历史市值按真实净值重估
            refreshNavAsync(userId, state.getCode(), state.getStartDate());
        }
        // 复用刚 fold 出来的基金状态刷现金余额，不再折叠第二次
        syncCashAsset(userId, states, today);
        userMapper.touchUpdateSeriesTime(userId, LocalDateTime.now());
        log.info("推进 asset_series 完成，userId={}, code 数={}, 水位={} → {}", userId, states.size(), lastSync, today);
    }

    /**
     * 整段重算某个 code 的序列，并顺手异步补一次该 code 的净值。
     * 调整记录写完（增/改）之后调用——新 code 的首次录入也要能拿到净值，
     * 否则「登录时补净值」这条路对当天新录入的 code 是够不着的。
     */
    @Transactional
    public void rebuildAndSyncNav(Long userId, String code) {
        LocalDate since = regenerate(userId, code);
        if (since != null) {
            refreshNavAsync(userId, code, since);
        }
    }

    /**
     * 整段重算某个 code 的序列（不碰净值）。
     * 拿到新净值后由异步任务调用，返回该 code 最早一条调整记录的日期。
     *
     * 注意：本方法内部调用 {@link #regenerate}，在同类内部调用时 @Transactional 不会生效
     * ——重建是幂等的，两次写入之间没有不变式，所以不需要为它单独开事务；
     * 从 AdjustmentService 进来时（{@link #rebuildAndSyncNav}）本来就已经在一个事务里了。
     */
    @Transactional
    public LocalDate rebuild(Long userId, String code) {
        return regenerate(userId, code);
    }

    /**
     * 真正的重算：删掉旧行、从首个事件重放到今天、写回 asset_series 与 asset 快照。
     *
     * @return 该 code 最早一条调整记录的日期（净值只抓这之后的部分），没有记录时返回 null
     */
    private LocalDate regenerate(Long userId, String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        // 和 advance 一样：写序列前必须确定交易日，否则会按「周一~周五」的退化规则扣到节假日上
        tradingCalendarService.ensureLoaded();

        String normalizedCode = code.trim();
        LocalDate today = LocalDate.now();

        // 现金没有净值、没有周期扣款，也不写 asset_series：只有 asset 表的一条余额快照
        if (CashAsset.isCash(normalizedCode)) {
            syncCashAsset(userId, foldForUser(userId, today), today);
            return null;
        }

        LocalDate since = rebuildFundSeries(userId, normalizedCode, today);
        // 现金要在**每个出口**刷新：删掉一只基金的最后一条记录时它没有序列了，
        // 但历史上扣掉的钱必须从现金里退回来，漏了这段余额就会永远偏低
        syncCashAsset(userId, foldForUser(userId, today), today);
        return since;
    }

    /**
     * 单个基金 code 的整段重算（现金不走这里，见 {@link #regenerate}）。
     *
     * @return 该 code 最早一条调整记录的日期（净值只抓这之后的部分），没有记录时返回 null
     */
    private LocalDate rebuildFundSeries(Long userId, String code, LocalDate today) {
        List<Adjustment> events = adjustmentMapper.findByUser(userId, code, null, null, null);
        if (events.isEmpty()) {
            // 记录被删光了：序列跟着清空，asset 行留着（name / category 是用户改过的）
            int removed = assetSeriesMapper.deleteByUserAndCode(userId, code);
            log.info("调整记录已清空，删除序列行 {} 条，userId={}, code={}", removed, userId, code);
            return null;
        }

        String market = loadMarkets(userId).get(code);
        PlanState state = buildSeries(userId, code, events, loadNav(code),
                loadRates(userId).get(code),
                day -> tradingCalendarService.isTradingDay(day, market), today);
        writeRows(userId, state, null);
        pruneOutsideRange(userId, code, state);
        log.info("重建序列，userId={}, code={}, 行数={}, 本金={}",
                userId, code, state.getRows().size(), state.principal);
        return state.getStartDate();
    }

    /**
     * 刷新现金资产的余额快照（{@link CashAsset}），**不写 asset_series** ——
     * 现金没有走势图需求，而一旦写行就要面对 prune、增量水位、空区间清零，
     * 还会被组合曲线和 XIRR 当成一只持仓。只写 asset 一行：
     * AssetService 取不到序列行时会兜底读 asset.total，总资产与分布照常显示。
     *
     * 只在两处调用：登录推进（{@link #advance}）与整段重算（{@link #regenerate}）——
     * 任何一只基金的扣款 / 买入 / 卖出 / 删记录都会动现金。
     *
     * 防御式实现：advance 跑在事务里、调用方（AuthService）只记日志不抛，
     * 这里漏一个空指针会把整段基金推进一起回滚，所以宁可不写也不能炸。
     */
    private void syncCashAsset(Long userId, Map<String, PlanState> fundStates, LocalDate today) {
        List<Adjustment> cashEvents = adjustmentMapper.findByUser(userId, null, null, null, null).stream()
                .filter(event -> CashAsset.isCash(event.getCode()))
                .toList();
        boolean assetExists = assetMapper.findByUser(userId).stream()
                .anyMatch(asset -> CashAsset.isCash(asset.getCode()));
        // 从没用过现金的人不该凭空多出一只资产（记录被删光但资产行还在时仍要刷新，写成 0）
        if (cashEvents.isEmpty() && !assetExists) {
            return;
        }

        BigDecimal balance = CashLedger.balance(cashEvents, fundStates, cashLinkFrom, today);

        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setCode(CashAsset.CODE);
        asset.setName(CashAsset.NAME);
        asset.setCategory(CashAsset.CATEGORY);
        // 类型同样是系统语义（安全资金统计按 bond + cash 计），每次刷新都照写
        asset.setAssetType(CashAsset.ASSET_TYPE);
        asset.setActive(false);
        asset.setStartDate(CashLedger.startDate(cashEvents));
        asset.setPrincipal(balance);
        asset.setTotal(balance);
        // 现金没有 asset_series，「最后更新时间」取最后一次余额快照刷新的时刻（口径见 Asset.lastUpdateTime）
        asset.setLastUpdateTime(LocalDateTime.now());
        assetMapper.upsertCashSnapshot(asset);
        log.info("刷新现金快照，userId={}, 余额={}, 联动起点={}", userId, balance, cashLinkFrom);
    }

    /**
     * 手动刷新净值：同步抓取该用户所有 code 的净值并重算序列。
     * 给 /nav/refresh 用，因此不受「一天只抓一次」的限制；返回真正拿到新数据的 code 数。
     */
    public int refreshNav(Long userId) {
        Map<String, PlanState> states = foldForUser(userId, LocalDate.now());
        int updated = 0;
        for (PlanState state : states.values()) {
            if (navService.refreshNow(state.getCode(), state.getStartDate())) {
                rebuild(userId, state.getCode());
                updated++;
            }
        }
        log.info("手动刷新净值完成，userId={}, 更新 {} / {} 个 code", userId, updated, states.size());
        return updated;
    }

    /**
     * 把某用户的事件流 fold 成每个 code 的状态（含每日序列与时间线），只读不写库。
     * FundService 用它拿计划状态与时间线，AssetService / PortfolioService 用它判断哪些 code 还在定投。
     */
    public Map<String, PlanState> foldForUser(Long userId, LocalDate today) {
        // 现金不进 fold：它的 action=3/4 是「进账 / 出账」，不是买入卖出。混进来的话
        // /funds 会多一只永远没有走势的假计划、组合曲线会把现金和基金流出对冲掉（XIRR 现金流打平）、
        // 每次登录还会给 CASH 抓一次永远抓不到的净值。余额另由 syncCashAsset 单独刷。
        List<Adjustment> events = adjustmentMapper.findByUser(userId, null, null, null, null).stream()
                .filter(event -> !CashAsset.isCash(event.getCode()))
                .toList();
        if (events.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = events.stream()
                .map(Adjustment::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // QDII 的扣款日要叠加标的市场休市日，所以交易日判断按 code 传入（见 MarketCalendar）
        Map<String, String> markets = loadMarkets(userId);
        Function<String, Predicate<LocalDate>> calendar =
                code -> day -> tradingCalendarService.isTradingDay(day, markets.get(code));
        return fold(userId, events, loadNav(codes), loadRates(userId), calendar, today);
    }

    /**
     * fold 的核心：纯计算，不碰数据库，方便直接用真实数据做单元测试。
     *
     * @param navByCode     每个 code 的「日期 → 单位净值」，按日期升序；缺失的 code 视作没有净值数据
     * @param rateByCode    每个 code 的申购费率（百分数），缺失 / null 表示不计费
     * @param isTradingDay  所有 code 共用一个交易日判断（测试用：传一个假日历就能精确断言节假日行为）
     * @param today         推进到此日为止（含）
     * @return code → 计划状态，保持 code 首次出现的顺序
     */
    static Map<String, PlanState> fold(Long userId, List<Adjustment> events,
                                       Map<String, NavigableMap<LocalDate, BigDecimal>> navByCode,
                                       Map<String, BigDecimal> rateByCode,
                                       Predicate<LocalDate> isTradingDay,
                                       LocalDate today) {
        Function<String, Predicate<LocalDate>> sameCalendar = code -> isTradingDay;
        return fold(userId, events, navByCode, rateByCode, sameCalendar, today);
    }

    /**
     * 生产环境的 fold：交易日判断按 code 区分 —— QDII 除 A 股日历外还要叠加标的市场休市日
     * （见 {@link TradingCalendarService#isTradingDay(LocalDate, String)}）。
     *
     * @param isTradingDayOf code → 该 code 的交易日判断
     */
    static Map<String, PlanState> fold(Long userId, List<Adjustment> events,
                                       Map<String, NavigableMap<LocalDate, BigDecimal>> navByCode,
                                       Map<String, BigDecimal> rateByCode,
                                       Function<String, Predicate<LocalDate>> isTradingDayOf,
                                       LocalDate today) {
        // 只按 code 分组（保持首次出现顺序）；事件顺序由 buildSeries 统一排，见那里的注释
        Map<String, List<Adjustment>> byCode = events.stream()
                .collect(Collectors.groupingBy(Adjustment::getCode, LinkedHashMap::new, Collectors.toList()));

        Map<String, PlanState> states = new LinkedHashMap<>();
        byCode.forEach((code, codeEvents) -> states.put(code, buildSeries(userId, code, codeEvents,
                navByCode == null ? null : navByCode.get(code),
                rateByCode == null ? null : rateByCode.get(code),
                isTradingDayOf.apply(code), today)));
        return states;
    }

    /* ==================== fold 实现 ==================== */

    /** 单个 code：按天推进，产出每日序列与时间线 */
    static PlanState buildSeries(Long userId, String code, List<Adjustment> events,
                                 NavigableMap<LocalDate, BigDecimal> navs, BigDecimal rate,
                                 Predicate<LocalDate> isTradingDay, LocalDate today) {
        // 排序放在这里而不是交给调用方保证：(date, adjustment_id) 升序。
        // 同一天可以既有定投又有买入，自增主键才代表真实录入顺序；
        // 而 mapper 返回的是**日期倒序**，漏了这步窗口起点和累计本金都会算错
        // （增量推进走 fold 有排序，整段重建走这里，两条路必须一致）。
        List<Adjustment> ordered = events.stream()
                .sorted(Comparator.comparing(Adjustment::getDate)
                        .thenComparing(Adjustment::getAdjustmentId))
                .toList();

        LocalDate first = ordered.get(0).getDate();
        PlanState state = new PlanState(code, first);

        // 事件按天分组；组内保持录入顺序（上面已排好）
        Map<LocalDate, List<Adjustment>> byDay = ordered.stream()
                .collect(Collectors.groupingBy(Adjustment::getDate, TreeMap::new, Collectors.toList()));

        // 窗口 = [首条记录, today]，首条记录在未来（录错日期）时至少也写一行，避免序列为空
        LocalDate end = first.isAfter(today) ? first : today;
        for (LocalDate day = first; !day.isAfter(end); day = day.plusDays(1)) {
            List<Adjustment> dayEvents = byDay.getOrDefault(day, List.of());
            // 当天的净投入：周期扣款 + 一笔收入 − 一笔支出，最后原样落进 amount 列
            BigDecimal dayAmount = BigDecimal.ZERO;
            // 当天被收走的手续费（各笔买入之和），落进 fee 列；卖出不收费
            BigDecimal dayFee = BigDecimal.ZERO;
            // 清仓那一刻「当天已累计的净额」；非 null 表示当天清过仓，份额要按清仓后的净额重建
            BigDecimal clearedAt = null;
            List<TimelineItem> timelineItems = new ArrayList<>();

            // 调整前的计划快照：由每日定投改成每周定投这种**改频率**的调整，
            // 当天仍按原计划扣款（008887 的 9/2 要扣每日的那一笔），新频率次日才生效
            boolean activeBefore = state.active;
            String frequencyBefore = state.frequency;
            BigDecimal amountBefore = state.amount;

            // 1. 离散收支：买入（action=3）要扣申购手续费，卖出（action=4）不是买入所以全额冲减
            for (Adjustment event : dayEvents) {
                if (event.getAction() == ACTION_INCOME) {
                    BigDecimal net = netOfFee(event.getAmount(), rate);
                    BigDecimal fee = feeOf(event.getAmount(), rate);
                    dayFee = dayFee.add(fee);
                    state.principal = state.principal.add(net);
                    dayAmount = dayAmount.add(net);
                    timelineItems.add(new TimelineItem(event, LABEL_INCOME));
                } else if (event.getAction() == ACTION_EXPENSE) {
                    // 卖出前的持仓市值：现有份额按当天净值估值，再加当天已经记过的净额
                    BigDecimal holdingValue = state.shares.multiply(navOn(navs, day)).add(dayAmount);
                    boolean clears = holdingValue.signum() > 0
                            && event.getAmount().compareTo(holdingValue) >= 0;
                    if (clears) {
                        // 清仓：只把**份额**归零（市值跟着变 0，不留下负份额的空头）。
                        // 本金与手续费照常按净额累计：累计收益 = 市值 − 本金 − 手续费 要跨轮连续，
                        // 清了就是「把已实现收益也抹掉」（见类注释）
                        state.shares = BigDecimal.ZERO;
                        state.lastClearance = day;
                    }
                    state.principal = state.principal.subtract(event.getAmount());
                    dayAmount = dayAmount.subtract(event.getAmount());
                    if (clears) {
                        // 新一轮的起点 = 这一笔（清仓的卖出，含当天更早的买卖）之后的当天净额，
                        // 取在这一笔**之后**，否则这笔卖出本身会被当成新投入折成负份额
                        clearedAt = dayAmount;
                    }
                    timelineItems.add(new TimelineItem(event, LABEL_EXPENSE));
                }
            }

            // 2. 定投开始 / 调整频率金额
            boolean frequencyChanged = false;
            for (Adjustment event : dayEvents) {
                if (event.getAction() == ACTION_START) {
                    if (activeBefore && !Objects.equals(frequencyBefore, event.getFrequency())) {
                        frequencyChanged = true;
                    }
                    timelineItems.add(new TimelineItem(event, startLabel(state, event)));
                    applyStart(state, event);
                }
            }

            // 3. 周期扣款：交易日才扣，非交易日按频率跳过或顺延（结束当天仍算周期点，见类注释）。
            //    改频率当天用调整前的计划；只改金额（定投下调）当天就用新金额；
            //    新开/重启的定投（调整前没在投）当天就按新计划判断。
            DeductionPlan plan = frequencyChanged
                    ? new DeductionPlan(activeBefore, frequencyBefore, amountBefore)
                    : new DeductionPlan(state.active, state.frequency, state.amount);
            // 周期扣款也是一次买入，同样要扣申购手续费，余下的净额才进本金与份额
            BigDecimal rawDeduction = deductionOf(state, plan, day, isTradingDay);
            BigDecimal deduction = netOfFee(rawDeduction, rate);
            dayFee = dayFee.add(feeOf(rawDeduction, rate));
            state.principal = state.principal.add(deduction);
            dayAmount = dayAmount.add(deduction);

            // 4. 结束定投：放在扣款之后，所以停止当天该扣的还会扣
            for (Adjustment event : dayEvents) {
                if (event.getAction() == ACTION_STOP) {
                    state.active = false;
                    timelineItems.add(new TimelineItem(event, LABEL_STOP));
                }
            }

            // 时间线按录入顺序（adjustment_id）展示，与上面的计算顺序解耦：
            // 013402 在 2026-06-15 是「先每周定投、后买入」，展示不该被算账顺序打乱
            timelineItems.stream()
                    .sorted(Comparator.comparing(item -> item.event.getAdjustmentId()))
                    .forEach(item -> state.timeline.add(toTimelineItem(item)));

            // 5. 落一行：份额按当天净值折算，没有净值就按 1 处理（此时 total == principal）。
            //    清仓当天份额已经被置零，这里只能把「清仓之后」的净额折成份额
            //    （同一天清仓后又定投的部分算新一轮的第一笔）
            BigDecimal nav = navOn(navs, day);
            BigDecimal netShares = clearedAt == null ? dayAmount : dayAmount.subtract(clearedAt);
            state.shares = state.shares.add(netShares.divide(nav, SHARE_SCALE, RoundingMode.HALF_UP));
            BigDecimal total = state.shares.multiply(nav).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            state.rows.add(new AssetSeries(userId, code, day, dayAmount, dayFee, state.principal, total));
        }
        return state;
    }

    /**
     * 当天该扣多少钱，并推进「顺延」状态。
     *
     * - 非交易日：每日定投直接跳过（下一个交易日会正常扣款，顺延会连扣两笔）；
     *   每周/每月定投把当期金额记进 pending，等下一个交易日补扣 —— 券商通行做法。
     * - 交易日：正常扣当天的周期点，并把之前顺延的都补上；如果定投已经结束，补扣作废。
     *
     * @param plan 当天适用的计划（改频率当天传调整前的，见调用处）
     * @return 当天要加的金额（0 表示不扣）
     */
    private static BigDecimal deductionOf(PlanState state, DeductionPlan plan,
                                          LocalDate day, Predicate<LocalDate> isTradingDay) {
        boolean periodPoint = plan.active() && plan.amount() != null
                && isPeriodPoint(plan.frequency(), day);

        if (!isTradingDay.test(day)) {
            if (periodPoint && !FREQ_DAILY.equals(plan.frequency())) {
                state.pending.add(plan.amount());
            }
            return BigDecimal.ZERO;
        }

        if (!plan.active()) {
            // 定投已结束（当天结束的也算）：顺延中的补扣不再执行
            state.pending.clear();
            return BigDecimal.ZERO;
        }

        BigDecimal total = periodPoint ? plan.amount() : BigDecimal.ZERO;
        for (BigDecimal pending : state.pending) {
            total = total.add(pending);
        }
        state.pending.clear();
        return total;
    }

    /** 当天适用的一套计划参数（真实状态，或改频率当天的调整前状态） */
    private record DeductionPlan(boolean active, String frequency, BigDecimal amount) {
    }

    /**
     * 扣掉申购手续费之后的净额：手续费 = 金额 × 费率 / 100，净额 = 金额 − 手续费。
     *
     * asset.rate 存的是**百分数**（0.12 表示 0.12%，与 nav_trend.rate 同一套约定），
     * null / 0 / 负数一律当作不计费（没配费率就不收，不会因为「最低 1 分」倒收一笔）。
     *
     * 三条修正，都是按平台实际的收费方式来的：
     * - **先四舍五入到分**：0.012 元 → 0.01 元（用户确认的平台口径）；
     * - **不足 1 分按 1 分收**：每期定投才十来块，0.08% 的费率算出来只有 0.004 元，
     *   四舍五入到分就是 0，平台还是会收 1 分，所以兜一个 1 分的下限；
     * - 手续费不会超过买入金额本身（金额小于 1 分时按金额扣），免得净额变成负数。
     */
    /** 一笔买入被收的手续费 = 原始金额 − 扣费后的净额（没配费率或金额为 0 时是 0） */
    private static BigDecimal feeOf(BigDecimal amount, BigDecimal rate) {
        return amount == null ? BigDecimal.ZERO : amount.subtract(netOfFee(amount, rate));
    }

    private static BigDecimal netOfFee(BigDecimal amount, BigDecimal rate) {
        // 金额为 0（没有买入）时直接返回，别让 0 被换算成 0.0000 把当天的 scale 也带过去
        if (amount == null || amount.signum() == 0 || rate == null || rate.signum() <= 0) {
            return amount;
        }
        BigDecimal fee = amount.multiply(rate).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP)
                .setScale(FEE_SCALE, RoundingMode.HALF_UP)
                .max(MIN_FEE)
                .min(amount);
        // 净额统一到金额精度（4 位），免得序列里同一种数出现两种精度
        return amount.subtract(fee).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 应用一条「定投开始 / 调整」：更新频率与金额，并把定投标记为进行中 */
    private static void applyStart(PlanState state, Adjustment event) {
        state.active = true;
        state.everInvested = true;
        state.frequency = event.getFrequency();
        state.amount = event.getAmount();
    }

    /**
     * action=1 的文案按「与上一状态的差异」细分。
     * 一律写「定投开始」的话，一条持续下调金额的计划在时间线上看起来像重启了好几次。
     */
    private static String startLabel(PlanState state, Adjustment event) {
        if (!state.everInvested || state.amount == null || event.getAmount() == null) {
            return LABEL_START;
        }
        int diff = event.getAmount().compareTo(state.amount);
        if (diff > 0) {
            return LABEL_UP;
        }
        if (diff < 0) {
            return LABEL_DOWN;
        }
        if (!Objects.equals(event.getFrequency(), state.frequency)) {
            return LABEL_FREQUENCY;
        }
        return LABEL_START;
    }

    /**
     * 当天是不是扣款日：daily 每个交易日、weekly **每周一**、monthly **每月 1 日**。
     *
     * 固定的自然周期（周一 / 1 日）而不是「起点 + N 天」：用户按券商的实际扣款习惯定的，
     * 见类注释。非交易日的处理交给 {@link #deductionOf}（daily 跳过、周月顺延）。
     */
    static boolean isPeriodPoint(String frequency, LocalDate day) {
        if (frequency == null) {
            return false;
        }
        return switch (frequency) {
            case FREQ_DAILY -> true;
            case FREQ_WEEKLY -> day.getDayOfWeek() == DayOfWeek.MONDAY;
            case FREQ_MONTHLY -> day.getDayOfMonth() == 1;
            default -> false;
        };
    }

    /**
     * 当天的估值净值：取「不晚于当天」的最近一条净值，没有则按 1 处理。
     * 按 1 处理等于 total == principal（零收益），是缺数据时唯一不会算错的兜底。
     */
    private static BigDecimal navOn(NavigableMap<LocalDate, BigDecimal> navs, LocalDate day) {
        if (navs == null || navs.isEmpty()) {
            return BigDecimal.ONE;
        }
        Map.Entry<LocalDate, BigDecimal> entry = navs.floorEntry(day);
        if (entry == null || entry.getValue() == null || entry.getValue().signum() <= 0) {
            return BigDecimal.ONE;
        }
        return entry.getValue();
    }

    /**
     * 时间线条目。
     * 两条归一化，都是前端渲染依赖的：
     * - 结束定投的记录把频率/金额置成 null，前端靠它显示「停止定投」徽标；
     * - 只有「定投开始/调整」才带频率：买进卖出和结束定投的频率一律为 null
     *   （库里手工改出来的 action=3 带 frequency 的行也不能漏出去，前端会拿它显示「周投」）。
     */
    private static PlanAdjustmentResponse toTimelineItem(TimelineItem item) {
        Adjustment event = item.event;
        boolean stopped = event.getAction() == ACTION_STOP;
        boolean invests = event.getAction() == ACTION_START;

        PlanAdjustmentResponse response = new PlanAdjustmentResponse();
        response.setId(String.valueOf(event.getAdjustmentId()));
        response.setDate(event.getDate().toString());
        response.setReason(event.getReason());
        response.setAction(item.label);
        response.setFrequency(invests ? event.getFrequency() : null);
        response.setAmount(stopped ? null : event.getAmount());
        response.setNote(event.getNote());
        return response;
    }

    /** 时间线的中间态：文案在算账时定下来，排序要等当天所有事件处理完 */
    private record TimelineItem(Adjustment event, String label) {
    }

    /* ==================== 写库 ==================== */

    /**
     * 把 fold 结果写进 asset_series 与 asset 快照。
     *
     * @param fromExclusive 只写这一天之后的行（增量推进用）；null 表示整段覆盖
     */
    private void writeRows(Long userId, PlanState state, LocalDate fromExclusive) {
        List<AssetSeries> rows = state.getRows();
        if (fromExclusive != null) {
            List<AssetSeries> fresh = rows.stream()
                    .filter(row -> row.getDay().isAfter(fromExclusive))
                    .toList();
            if (fresh.isEmpty() && assetSeriesMapper.findLatestByUserAndCode(userId, state.getCode()) == null) {
                // 这个 code 一行都还没有（比如刚录入的新 code），增量窗口是空的也得补全
                fresh = rows;
            }
            rows = fresh;
        }
        if (!rows.isEmpty()) {
            assetSeriesMapper.upsertBatch(rows);
        }
        upsertAssetSnapshot(userId, state);
    }

    /**
     * 清掉本次重算区间之外的老行。
     *
     * 整段重算是 upsert，只覆盖它写到的那些天（[首个事件, today]，逐日连续）；
     * 序列起点一旦后移 —— 删掉最早一条调整记录、或把最早那条的日期改晚 —— 起点之前的老行
     * 没有任何人再去写它们，就会永远留在库里，走势图凭空多出一段「记录已经不存在」的历史
     * （2026-10-01 实测：删掉 6-01 那条后，6 月的 30 行仍在，本金还停在上一次的 1000）。
     *
     * 顺序是**先写新行、再删旧行**：中途失败最多多留一天脏数据，不会把好数据删没了；
     * 重算出空序列（事件全在未来之类）时整段清掉，与「一条记录都没有」同样处理。
     */
    private void pruneOutsideRange(Long userId, String code, PlanState state) {
        List<AssetSeries> rows = state.getRows();
        if (rows.isEmpty()) {
            int removed = assetSeriesMapper.deleteByUserAndCode(userId, code);
            log.info("重算结果为空序列，清掉旧行 {} 条，userId={}, code={}", removed, userId, code);
            return;
        }
        int removed = assetSeriesMapper.deleteOutsideRange(userId, code,
                rows.get(0).getDay(), rows.get(rows.size() - 1).getDay());
        if (removed > 0) {
            log.info("清掉重算区间外的残留行 {} 条，userId={}, code={}, 区间={} ~ {}",
                    removed, userId, code, rows.get(0).getDay(), rows.get(rows.size() - 1).getDay());
        }
    }

    /**
     * 刷新 asset 表的快照列。name / category / asset_type 等元数据由用户维护，upsertSnapshot 不会覆盖。
     *
     * 顺手把 last_update_time 刷成「现在」：能走到这里就说明这个 code 刚生成 / 重算过一次序列，
     * 折线图页脚的「最后更新」直接读它（新插入的行也走同一条 upsert，初始值就是首次生成时刻）。
     */
    private void upsertAssetSnapshot(Long userId, PlanState state) {
        AssetSeries last = state.lastRow();
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setCode(state.getCode());
        // 只在首次插入时落库：库里已有名字 / 类型的话，upsertSnapshot 刻意不更新这三列
        asset.setName(state.getCode());
        asset.setCategory(DEFAULT_CATEGORY);
        asset.setAssetType(DEFAULT_ASSET_TYPE);
        asset.setActive(state.isActive());
        asset.setFrequency(state.getFrequency());
        asset.setStartDate(state.getStartDate());
        asset.setPrincipal(last.getPrincipal());
        asset.setTotal(last.getTotal());
        asset.setLastUpdateTime(LocalDateTime.now());
        assetMapper.upsertSnapshot(asset);
    }

    /** 异步补净值，补到了就重算该 code（历史市值要按真实净值重估） */
    private void refreshNavAsync(Long userId, String code, LocalDate since) {
        navRefreshExecutor.execute(() -> {
            try {
                if (navService.refresh(code, since)) {
                    rebuild(userId, code);
                }
            } catch (Exception ex) {
                // 外部数据源是尽力而为的：拉不到就继续用「净值 = 1」的兜底，不该影响登录
                log.warn("异步补净值失败，userId={}, code={}：{}", userId, code, ex.getMessage());
            }
        });
    }

    /**
     * 对「一行净值都没有」的 code 异步补抓一次 —— 新录入基金的首次抓取失败了（比如偶发网络错误），
     * 之后每次登录都重试直到抓到。只看「有没有」：正常 QDII 的净值滞后两三天不算缺，不用管。
     */
    private void refreshMissingNavAsync(Long userId) {
        List<Adjustment> events = adjustmentMapper.findByUser(userId, null, null, null, null);
        Set<String> codes = events.stream()
                .map(Adjustment::getCode)
                // 现金没有净值数据源，补抓只会每次登录都白起一个 python 进程
                .filter(code -> !CashAsset.isCash(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String code : codes) {
            if (navTrendMapper.findMaxDayByCode(code) != null) {
                continue;
            }
            LocalDate since = events.stream()
                    .filter(event -> code.equals(event.getCode()))
                    .map(Adjustment::getDate)
                    .min(LocalDate::compareTo)
                    .orElse(LocalDate.now());
            log.info("补抓缺失净值，userId={}, code={}", userId, code);
            refreshNavAsync(userId, code, since);
        }
    }

    /** 读某 code 的全部净值，按日期升序 */
    private NavigableMap<LocalDate, BigDecimal> loadNav(String code) {
        List<NavTrend> navs = navTrendMapper.findByCode(code);
        NavigableMap<LocalDate, BigDecimal> map = new TreeMap<>();
        for (NavTrend nav : navs) {
            if (nav.getDay() != null && nav.getValue() != null) {
                map.put(nav.getDay(), nav.getValue());
            }
        }
        return map;
    }

    /** 批量读净值，每个 code 一次查询（净值行数可能上千，按 code 分开查比 IN 一把捞更划算） */
    private Map<String, NavigableMap<LocalDate, BigDecimal>> loadNav(Set<String> codes) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> navByCode = new LinkedHashMap<>();
        for (String code : codes) {
            navByCode.put(code, loadNav(code));
        }
        return navByCode;
    }

    /** 每个 code 的标的市场（asset.market），没标的的不放进来（= 只看 A 股日历） */
    private Map<String, String> loadMarkets(Long userId) {
        Map<String, String> markets = new LinkedHashMap<>();
        for (Asset asset : assetMapper.findByUser(userId)) {
            if (StringUtils.hasText(asset.getMarket())) {
                markets.put(asset.getCode(), asset.getMarket());
            }
        }
        return markets;
    }

    /** 每个 code 的申购费率（asset.rate），没设置过的不放进来 */
    private Map<String, BigDecimal> loadRates(Long userId) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Asset asset : assetMapper.findByUser(userId)) {
            if (asset.getRate() != null) {
                rates.put(asset.getCode(), asset.getRate());
            }
        }
        return rates;
    }

    /** 取 user.update_series_time 的日期部分，没推进过则为 null */
    private LocalDate lastSyncDate(Long userId) {
        User user = userMapper.findById(userId);
        return user == null || user.getUpdateSeriesTime() == null
                ? null
                : user.getUpdateSeriesTime().toLocalDate();
    }

    /* ==================== fold 结果 ==================== */

    /**
     * 单个计划 fold 过程中的可变状态；fold 结束后不再改动，直接当作结果用。
     * 包级可见是为了让单元测试能直接断言 fold 的结果。
     */
    @Getter
    static final class PlanState {

        /** 资产编码 */
        private final String code;

        /** 计划开始日期，取最早一条记录 */
        private final LocalDate startDate;

        /** 最后一个状态事件是不是 action=1（即当前是否在定投） */
        private boolean active;

        /** 有没有出现过 action=1；用来区分「已结束」和「从未定投」 */
        private boolean everInvested;

        /** 当前定投频率；计划结束后保留停投前的值 */
        private String frequency;

        /** 当前每期定投金额；计划结束后保留停投前的值 */
        private BigDecimal amount;

        /** 顺延待扣的金额：周期点落在非交易日时记在这里，下一个交易日补扣 */
        private final List<BigDecimal> pending = new ArrayList<>();

        /** 累计投入本金 = Σ(周期扣款) + Σ(action=3) − Σ(action=4)；卖出多于投入时为负（收益跨轮累计的代价） */
        private BigDecimal principal = BigDecimal.ZERO;

        /** 最近一次清仓的日期；年化收益率只从这一天之后起算（上一轮已经结束，不该混进来） */
        private LocalDate lastClearance;

        /** 累计份额 = Σ(当日净投入 / 当日净值) */
        private BigDecimal shares = BigDecimal.ZERO;

        /** 每日序列，按日期正序、逐日连续 */
        private final List<AssetSeries> rows = new ArrayList<>();

        /** 调整记录，按时间正序（同日按录入顺序） */
        private final List<PlanAdjustmentResponse> timeline = new ArrayList<>();

        PlanState(String code, LocalDate startDate) {
            this.code = code;
            this.startDate = startDate;
        }

        /** 序列末行即「当前」；没有行时兜一个全 0 的当天行，调用方不必判空 */
        AssetSeries lastRow() {
            return rows.isEmpty()
                    ? new AssetSeries(null, code, startDate, BigDecimal.ZERO, BigDecimal.ZERO, principal, principal)
                    : rows.get(rows.size() - 1);
        }

        /** 计划开始时间，前端要的是「YYYY-MM」 */
        String startMonth() {
            return startDate.format(YEAR_MONTH);
        }
    }
}
