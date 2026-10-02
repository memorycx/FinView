# FinView

个人投资 / 储蓄看板。

- `Backend/` — Spring Boot 3.3.5 + Java 17 + MyBatis + MySQL 8，端口 8080，`context-path: /api`
- `Frontend/` — Vue 3 + Vite + Tailwind v4 + Pinia，认证走 JWT（`POST /auth/login`、`POST /auth/register`、`GET /auth/me`）

前后端连接：`Frontend/.env` 里 `VITE_USE_MOCK=false` + `VITE_API_BASE_URL=http://localhost:8080/api`，
所以前端默认直连真实后端，改后端接口时前端会立刻受影响。

## 常用命令

后端（在 `Backend/` 下执行）：

```bash
./mvnw -o spring-boot:run    # 启动
./mvnw -o clean              # 清理 target/
./mvnw -o compile            # 只编译
```

前端（在 `Frontend/` 下执行）：

```bash
npm run dev         # 开发服务器
npm run type-check  # vue-tsc 类型检查
```

## 本地踩坑

这三条都**不是代码 bug**，但报错信息看起来很像，容易往错误的方向排查。

### 1. 启动报 `ConflictingBeanDefinitionException` → 先 `clean`

项目历史上移动过类的包路径（如 `com.finview.python.PythonRunner` → `com.finview.Utility.PythonRunner`）。
旧包下的 `.class` 会残留在 `target/classes` 里，Spring 扫描到两个同名 bean 就直接启动失败。

→ 先 `./mvnw clean` 再启动。别去改 `@Component` 的名字或加 `@Qualifier`，根因是构建产物不干净。

### 2. 启动报 `Port 8080 was already in use` → 有个残留 JVM

`spring-boot:run` 会 fork 出独立的 Java 进程。**只结束 maven 进程，fork 出来的 JVM 还活着**，
端口依然被占，下一次启动就失败。

```bash
netstat -ano | grep ":8080"      # 拿到 LISTENING 那行的 PID
taskkill //PID <pid> //F         # Git Bash 里斜杠要写两个
```

前端同理：`npm run dev` 的 npm 包装进程和真正监听端口的 node 进程不是同一个 PID。
**杀掉包装进程后 vite 可能还活着**，下次 `npm run dev` 会静默换到 5174 —— 于是你以为在看新代码，
其实 5173 上跑的还是旧的。看到端口不是 5173 时，按上面的办法找 LISTENING 的 PID 再杀一次。

### 3. Windows / Git Bash 下测中文接口，别用 `curl -d`

```bash
curl -X POST .../auth/register -H 'Content-Type: application/json' \
     -d '{"nickname":"张三"}'          # ✗ 服务端收到非法 UTF-8，返回「请求参数格式不正确」
```

Git Bash 把参数交给原生 `curl.exe` 时会按 ANSI 代码页（GBK）转码，服务端拿到的是坏字节。
注意 shell 内部**是**正确的 UTF-8（`printf '张三' | od -An -tx1` 能验证），所以很容易误判成后端编码问题——
后端、MySQL（utf8mb4）、JWT 对中文都是正常的。

→ 把 payload 写进文件再发，绕开参数转换：

```bash
printf '{"nickname":"\xe5\xbc\xa0\xe4\xb8\x89"}' > /tmp/p.json
curl -X POST .../auth/register -H 'Content-Type: application/json' --data-binary @/tmp/p.json
```

## 数据库

表结构以根目录《表设计.md》为准：`user`、`adjustments`、`nav_trend`、`asset`、`asset_series`、`saving_plans`。
计划与资产的关联走 `asset.plan_id`（没有 plan_asset 表）。

`schema.sql` 与 `data.sql` 每次启动都会执行（`spring.sql.init.mode=always`），两边都必须可反复执行：
建表用 `CREATE TABLE IF NOT EXISTS`，加列 / 改注释先查 `information_schema` 再 `PREPARE` 一句 DDL
（已满足时走 `DO 0`），**永远不要写 DROP TABLE**——表里都是真实数据。

`asset_series`（每日 本金 / 手续费 / 市值）不是手工维护的表，是 `SeriesService` 按《表设计.md》的
核心更新流程生成的：登录 / 注册时增量推进（水位 `user.update_series_time`），
增删改调整记录、或抓到新净值后整段重建。它是走势图、资产总览、组合走势的共同数据源。

净值由 `NavService` 调 `resources/python/fund_info.py`（akshare）抓取，**登录后异步补**，
失败只记日志不影响接口；需要马上看最新市值时调 `POST /api/nav/refresh`。
「当天已推进过」时登录也会对**一条净值都没有的 code**（新基金首次抓取失败）补抓一次，
否则失败当天不会重试（水位挡住了 advance）。

定投只在**交易日**扣款（周末/节假日不扣），交易日按 `trade_calendar.py` 拉的 A 股交易日历判断
（`TradingCalendarService`，启动时预热、每天刷一次；拉不到退化成「周一~周五」）。
**QDII 基金还会叠加标的市场休市日**：`asset.market` 标了 `us` / `hk` 后，扣款日 = A 股交易日 ∩ 标的市场开市
（`MarketCalendar`：美股按 NYSE 规则生成任意年份；港股用 2026 年官方表，**跨年要补新表**）。
每日定投遇非交易日直接跳过，每周/每月顺延到下一个交易日补扣。详细口径见 `SeriesService` 类注释。

买入（周期扣款与「一笔收入」）还要扣申购手续费：费率存在 `asset.rate`（**百分数**，0.12 = 0.12%），
按 金额 × rate/100 **四舍五入到分、不足 1 分按 1 分**，扣完的净额才计入本金与份额；
`asset.rate` 没配（NULL/0）就不收，卖出一笔支出不收费率。
手续费逐笔记在 `asset_series.fee`（当日值，卖出为 0，累计 = Σfee）；
**收益率统一口径**：收益 = 市值 − 本金 − 累计手续费（本金是**净投入**，卖出已冲减，所以这个差值跨清仓连续），
收益率 = 收益 / **累计投入**（= Σ 历年买入的「净投入 + 手续费」，卖出日不计入；`FundResponse.invested` /
`DayPointResponse.invested` 由后端算好，前端直接用）。没清过仓时「累计投入 == 本金 + 累计手续费」，
两个分母相等；清过仓的基金本金会变成负数，只有累计投入能当分母（折线图、计划列表、排行榜、详情页都一致，改的时候别只改一处）。

**「一笔支出」金额 ≥ 当日持仓市值时按清仓处理**（2026-10-01 按 000051 的真实成交定）：
**份额归零、市值跟着变 0**（不留负份额的空头），但**本金与手续费照常按净额累计**——
累计收益 = 市值 − 本金 − 手续费 必须跨轮连续，把本金清零等于把已实现收益也抹掉
（用户明确要求「清仓后累计收益不要清零」）。这一天同时是**年化收益率的轮次分界**：
上一轮的钱已经全部收回，再和这一轮混着算会把短期收益摊掉。同一天清仓后又买入算新一轮第一笔，
详见 `SeriesService` 类注释。

**年化收益率 = 后端 XIRR**（`common/Xirr.java`）：现金流 = −(amount + fee)、末日 + 市值，
ACT/365，持有不足 30 天或数学上无解返回 null（前端显示「—」）。
它和上面的「收益率」是两个口径（期末简单收益 vs 资金加权年化），看板上同时存在，别互相替换：
定投持续加仓时「期末值 / 成本 开 12/月数 次方」会系统性偏差，而且亏损会被算成 0。

口径改动（费率、周期、交易日规则）**不会自动回填历史**：重算只在登录增量推进、
增删改调整记录、抓到新净值时发生，所以改完要跑一次重算（或 `POST /api/nav/refresh`）。
想一次重算全部资产：投资看板组合总览下、定投计划卡片里的「更新全部资产序列」按钮
= `POST /api/funds/series`（逐只走单只重算那条路，含已归档、不含现金，各自异步补净值）。

**现金**是保留 code `CASH` 的一条普通资产（`common/CashAsset.java`，录入页选「现金」只填日期 + 金额）：
手工记录复用 adjustments（**正数 = action=3 进账、负数 = action=4 出账**，存绝对值，全库「金额恒正、方向看 action」
的约定不破），余额 = Σ手工记录 − Σ 起点之后各基金的 (amount + fee)，算式在 `CashLedger`（纯计算，有单测）。
三条容易踩的：
- **联动起点** `finview.cash.link-from`（2026-10-08，国庆后第一个交易日）：只有这一天起的扣款/买入/卖出才动现金，
  **之前的历史一律不补**——用户的历史买入从没记过现金，补进来余额会凭空少六千多。改完要强制重算一次才生效
  （登录被水位挡着，用 `POST /api/funds/{任一code}/series`）。
- **只写 asset 一行快照，不写 asset_series**：现金不需要走势图，写行反而会带来 prune / 增量水位 / 空区间清零，
  还会被组合曲线与 XIRR 当成一只持仓。`AssetService` 取不到序列行时本来就会兜底读 `asset.total`。
- **不进投资看板**：`foldForUser` 把现金滤掉（`/funds` 系列自动干净），`PortfolioService` 也排除它；
  但它**计入** `/assets/summary` 的现金桶与「我的资产」页。顺带：`upsertCashSnapshot` 是唯一会把 category
  写进冲突更新的语句（分类对现金是系统语义，被写成 fund 就回不去了）。

**股基 / 债基（`asset.asset_type`，2026-10-02 加）**：基金的细分类型，`equity` 股票基金 / `bond` 债券基金
（`cash` 只属于保留 code CASH，由 `upsertCashSnapshot` 系统维护）。`category` 仍是大类 `fund`，要细分就读它：
- **NULL 一律按股基算**：存量行由 `schema.sql` 启动时回填成 `equity`，新基金行插入即写 `equity`，
  读侧 `AssetService.bucketOf` 同样兜底——「没标过」和「标成股基」是一回事，没有第三态。
- 「我的资产」页的资产配置环形图把它拆成 **股票基金 / 债券基金 + 股票 / 债券 / 现金共 5 个展示桶**
  （`/assets/summary` 的 `allocation`，`[].category` 是桶 key `equityFund`/`bondFund`/…，**不是 `asset.category` 原值**）；
  **安全资金 = `asset_type ∈ {bond, cash}` 的未归档市值**（同接口的 `safeAssets`），标了债基两处一起生效。
- 录入接口 `POST/PUT /adjustments` 的 `assetType`（equity/bond）**选填、传了才改**：不传 = 不动已有分类，
  给一只债基录常规买入不会被前端默认的「股票基金」翻回去（前端只在用户点过类型卡片时才带）。
  与费率/周期一样，改口径**不会自动回填历史**，要逐只重标。
- 手工改类型：`UPDATE asset SET asset_type='bond' WHERE code='019851';`
  （019851 汇添富稳宏6个月持有债券A 是库里唯一的债基，2026-10-02 已标；这条**故意不写进 schema.sql**，
  否则每次启动都会被强行改回 bond）。

**「最后更新时间」**：`asset.last_update_time`（2026-10-02 加）是折线图页脚显示的值。
口径：该资产最后一次**生成 / 重算 asset_series** 的时刻，由 `SeriesService` 写 asset 快照时刷成当时时刻
（登录增量推进、增删改调整记录、抓到新净值后的重算、手动生成序列都会走到；现金没有序列，取最后一次余额快照刷新）。
存量行由 `schema.sql` 迁移回填成所属用户的 `user.create_time`，新行首次插入即写生成时刻，之后每次生成覆盖。
单只基金页脚用它自己的值（`FundResponse.lastUpdateTime`）；**组合总览取该用户全部 asset 行（含已归档与现金）的 MAX**
（`PortfolioService`，与曲线的持仓范围故意不同），前端由 `Dashboard.vue` 二选一传给 `PerformanceChart`。
