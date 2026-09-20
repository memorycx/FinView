# FinView 前端接口文档

> 本文档由前端现有代码（`src/api/`）整理生成，是前后端接口契约的唯一事实来源。
> 整理日期：2026-09-15

## 1. 通用约定

### 1.1 请求基础配置

| 项 | 值 | 说明 |
| --- | --- | --- |
| 请求方式 | 全部为 `GET` | 当前无 POST/PUT/PATCH/DELETE 接口，无请求体 |
| Base URL | `VITE_API_BASE_URL`，默认 `/api` | 完整路径示例：`/api/funds` |
| 请求头 | `Content-Type: application/json` | 见 `src/api/http.ts` |
| Mock 开关 | `VITE_USE_MOCK` | 非 `'false'` 时（即默认）走本地 mock；设为 `VITE_USE_MOCK=false` 走真实 HTTP |
| Mock 延迟 | 120 ~ 300 ms 随机 | 仅用于本地模拟网络延迟 |

接口层目录结构：

```
src/api/
├── http.ts            # 请求核心：真实 fetch / 本地 mock 双通路
├── error.ts           # ApiError 统一错误类
├── types.ts           # 领域模型与出入参类型（接口契约）
├── index.ts           # 统一导出出口
├── modules/
│   ├── funds.ts       # 定投计划
│   ├── portfolio.ts   # 组合总览
│   ├── assets.ts      # 资产分析
│   └── savings.ts     # 存钱计划
└── mock/
    ├── handlers.ts    # Mock 路由表（与后端路由一一对应）
    └── db.ts          # Mock 数据集
```

### 1.2 统一响应包

真实 HTTP 模式下，后端响应体必须为统一信封结构，前端在 `request()` 中自动解包，业务代码拿到的是 `data` 字段：

```json
{
  "code": 0,
  "message": "",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | number | 业务状态码，`0` 表示成功；非 `0` 时前端抛出 `ApiError(code, message)` |
| `message` | string | 错误提示文案 |
| `data` | T | 业务数据，结构见各接口定义 |

HTTP 状态码非 2xx 时，前端抛出 `ApiError(status, "请求失败: {status} {statusText}")`。

### 1.3 错误码

| code | 含义 | 触发场景（mock 现状） |
| --- | --- | --- |
| 400 | 参数错误 | `GET /assets/distribution?dim=xxx` 传入未知维度 |
| 404 | 资源不存在 | 定投计划 / 存钱计划 id 不存在；mock 下请求未实现的路由 |
| 500 | 服务端内部错误 | 后端异常 |
| 其他 | 透传后端业务错误码 | — |

### 1.4 参数规则

- **查询参数**通过 URL query string 传递；值为 `undefined` 的参数会被自动忽略，不传即缺省。
- **路径参数**直接拼接在 URL 中（如 `/funds/csi300`），需做 `encodeURIComponent` 编码（mock 路由解码，真实请求由调用方保证 id 合法）。
- 静态路由优先于动态路由：`/funds/leaderboard` 必须先于 `/funds/:id` 匹配。

## 2. 数据模型与枚举

以下类型定义于 `src/api/types.ts`。

### 2.1 枚举

```ts
/** 定投计划状态筛选（查询参数） */
type FundStatus = 'active' | 'archived' | 'all'

/** 资产分布维度（查询参数） */
type DistributionDim = 'industry' | 'region' | 'currency'

/** 资产大类 */
type AssetCategory = 'fund' | 'stock' | 'bond' | 'cash'

/** 定投频率 */
type InvestFrequency = 'daily' | 'weekly' | 'monthly'

/** 存钱计划类型：总资产目标 / 小愿望 */
type SavingsPlanType = 'asset' | 'wish'
```

### 2.2 定投计划相关

```ts
/** 时间序列数据点（按日/月采样） */
type DayPoint = {
  day: string        // YYYY-MM-DD
  principal: number  // 累计投入本金（元）
  total: number      // 当前总市值（元）
}

/** 定投调整记录 */
type PlanAdjustment = {
  id: string
  date: string                       // YYYY-MM-DD
  reason: string                     // 更改原因
  action: string                     // 调整动作简述，如「开始定投」「加大定投」「停止定投」
  frequency: InvestFrequency | null  // 调整后的频率，null 表示停止定投
  amount: number | null              // 调整后的每期金额（元），null 表示停止定投
  note?: string
}

/** 定投计划（一只基金或一个股票组合） */
type Fund = {
  id: string
  name: string
  code: string
  category: AssetCategory
  active: boolean              // 定投是否进行中（false = 已归档）
  frequency: InvestFrequency   // 当前定投频率
  amount: number               // 当前每期定投金额（元）
  startDate: string            // 开始时间 YYYY-MM
  principal: number            // 累计投入本金（元）
  current: number              // 当前市值（元）
  series: DayPoint[]           // 本金/市值走势
  adjustments: PlanAdjustment[]// 定投调整记录（按时间正序）
}
```

### 2.3 资产分析相关

```ts
/** 资产大类配置项 */
type Allocation = {
  category: AssetCategory
  label: string   // 大类中文名：基金 / 股票 / 债券 / 现金
  value: number   // 当前市值（元）
}

/** 资产总览 */
type AssetSummary = {
  totalAssets: number     // 总资产（元）
  allocation: Allocation[]// 按资产大类的市值分布
}

/** 分布统计项 */
type DistributionItem = {
  name: string
  value: number
}

/** 全维度分布数据（不带 dim 参数时返回） */
type DistributionData = Record<DistributionDim, DistributionItem[]>
// 即 { industry: DistributionItem[], region: DistributionItem[], currency: DistributionItem[] }
```

### 2.4 存钱计划相关

```ts
/** 计划内持有的资产项 */
type SavingsAsset = {
  name: string
  amount: number
  colorVar?: string  // 图表主题色 CSS 变量，如 '--chart-1'
}

/** 月度存入数据点 */
type SavingsMonthPoint = {
  month: string   // YYYY-MM
  amount: number  // 当月金额（元）
}

/** 存钱计划 */
type SavingsPlan = {
  id: string
  name: string
  description: string
  type: SavingsPlanType
  image?: string                 // 详情页头图 URL，留空则占位
  currentAmount: number          // 已存金额（元）
  targetAmount: number           // 目标金额（元）
  startDate: string              // YYYY-MM-DD
  targetDate: string             // YYYY-MM-DD
  monthlyPlanAmount: number      // 每月计划存入（元）
  quote?: string
  note?: string
  noteImage?: string
  assets: SavingsAsset[]         // 计划内持有的资产
  series: SavingsMonthPoint[]    // 月度存入走势
}
```

## 3. 接口清单（共 8 个端点 / 9 个前端函数）

| # | 方法 | 路径 | 说明 | 前端函数 | 调用页面/组件 |
| --- | --- | --- | --- | --- | --- |
| 1 | GET | `/funds` | 定投计划列表 | `getFunds` | Dashboard、AssetOverview |
| 2 | GET | `/funds/:id` | 定投计划详情 | `getFund` | 已导出，暂无页面调用 |
| 3 | GET | `/funds/leaderboard` | 收益排行榜 | `getFundLeaderboard` | Leaderboard |
| 4 | GET | `/portfolio/series` | 组合总览走势 | `getPortfolioSeries` | Dashboard |
| 5 | GET | `/assets/summary` | 资产总览 | `getAssetSummary` | AssetOverview、AssetDistribution |
| 6 | GET | `/assets/distribution` | 资产分布（全维度/单维度） | `getAssetDistribution` / `getAssetDistributionByDim` | AssetDistribution（全维度） |
| 7 | GET | `/savings/plans` | 存钱计划列表 | `getSavingsPlans` | SavingsView |
| 8 | GET | `/savings/plans/:id` | 存钱计划详情 | `getSavingsPlan` | SavingsDetail |

> 下文完整 URL 均省略 Base URL 前缀 `/api`。
> 响应示例展示的是解包后的 `data` 内容；HTTP 原始响应体外层包一层 `{ "code": 0, "message": "", "data": ... }`。

---

### 3.1 获取定投计划列表

- **函数**：`getFunds(params?: { status?: FundStatus })`（`src/api/modules/funds.ts`）
- **URL**：`GET /funds`

**Query 参数**

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | string | 否 | `active`=进行中，`archived`=已归档，`all`=全部；**不传等价于 `all`** |

**响应**：`Fund[]`

```jsonc
[
  {
    "id": "csi300",
    "name": "沪深300指数增强",
    "code": "005827",
    "category": "fund",        // fund | stock | bond | cash
    "active": true,
    "frequency": "monthly",    // daily | weekly | monthly
    "amount": 3000,
    "startDate": "2019-03",
    "principal": 246000,
    "current": 312400,
    "series": [
      { "day": "2019-03-01", "principal": 0, "total": 0 }
      // ...按 YYYY-MM-01 逐月排列，末点与 principal/current 对齐
    ],
    "adjustments": [
      {
        "id": "a1",
        "date": "2019-03-10",
        "reason": "计划启动",
        "action": "开始定投",
        "frequency": "monthly",
        "amount": 2000,
        "note": "以沪深300为核心底仓"
      }
    ]
  }
  // ...
]
```

**现有调用**

| 调用方 | 传参 |
| --- | --- |
| `src/components/Dashboard.vue` | `{ status: 'all' }` |
| `src/components/AssetOverview.vue` | `{ status: 'active' }` |

---

### 3.2 获取单个定投计划详情

- **函数**：`getFund(id: string)`（`src/api/modules/funds.ts`）
- **URL**：`GET /funds/:id`

**路径参数**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 计划 id，如 `csi300`、`nasdaq`、`hk-tech` |

**响应**：`Fund`（结构同 3.1 列表项，含完整 `series` 与 `adjustments`）

**错误**

| code | message 格式 | 场景 |
| --- | --- | --- |
| 404 | `定投计划不存在: {id}` | id 不存在 |

> 备注：该函数已从 `@/api` 导出，当前页面暂无调用（列表页通过 3.1 一次性拿到全量数据）。

---

### 3.3 收益排行榜

- **函数**：`getFundLeaderboard()`（`src/api/modules/funds.ts`）
- **URL**：`GET /funds/leaderboard`
- **参数**：无

**响应**：`Fund[]` —— 包含**已归档**在内的全部计划，按收益率降序排列。
收益率口径：`(current - principal) / principal`。

```jsonc
[
  { "id": "nasdaq", "name": "纳斯达克100(QDII)", "principal": 174000, "current": 268900 /* ...Fund 完整字段 */ },
  { "id": "gold",   "name": "黄金ETF联接",      "principal": 40000,  "current": 52700  /* ... */ }
  // 收益率从高到低
]
```

**现有调用**：`src/components/Leaderboard.vue`

---

### 3.4 组合总览走势

- **函数**：`getPortfolioSeries()`（`src/api/modules/portfolio.ts`）
- **URL**：`GET /portfolio/series`
- **参数**：无

**响应**：`DayPoint[]` —— 按年份汇总全部**进行中**（`active=true`）持仓的本金与市值，每年一个数据点，日期取该年 `12-01`。mock 当前覆盖 2019—2025 共 7 个点。

```jsonc
[
  { "day": "2019-12-01", "principal": 30207, "total": 30046 },
  { "day": "2020-12-01", "principal": 104199, "total": 103078 }
  // ...每年一个点，直至 2025-12-01，共 7 个点
]
```

> 说明：mock 实际取各基金 series 中「不超过 `{year}-12-01` 的最新数据点」求和；某只基金在该年尚无数据点时跳过。后端实现可直接按年聚合，无需复刻该插值逻辑。

**现有调用**：`src/components/Dashboard.vue`

---

### 3.5 资产总览

- **函数**：`getAssetSummary()`（`src/api/modules/assets.ts`）
- **URL**：`GET /assets/summary`
- **参数**：无

**响应**：`AssetSummary`

```jsonc
{
  "totalAssets": 1094600,   // 各大类市值之和（元）
  "allocation": [
    { "category": "fund",  "label": "基金", "value": 730800 },
    { "category": "stock", "label": "股票", "value": 94600 },
    { "category": "bond",  "label": "债券", "value": 141200 },
    { "category": "cash",  "label": "现金", "value": 128000 }
  ]
}
```

**口径说明（mock 现状，后端需对齐）**

- 仅汇总 `active=true` 的持仓，按 `category` 累加 `current`；
- 现金储备固定追加 `128000` 元到 `cash` 大类；
- `allocation` 固定按 `fund / stock / bond / cash` 顺序返回全部四个大类（即使为 0）。

**现有调用**：`src/components/AssetOverview.vue`、`src/components/AssetDistribution.vue`

---

### 3.6 资产分布

- **函数**：
  - `getAssetDistribution()`（不带参数，返回全维度）
  - `getAssetDistributionByDim(dim: DistributionDim)`（带 `dim`，返回单维度）
- **文件**：`src/api/modules/assets.ts`
- **URL**：`GET /assets/distribution`

**Query 参数**

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `dim` | string | 否 | `industry`（行业）/ `region`（区域）/ `currency`（币种）。不传返回三个维度的全集 |

#### 3.6.1 不带 dim —— 响应 `DistributionData`

```jsonc
{
  "industry": [
    { "name": "宽基指数",   "value": 312400 },
    { "name": "科技互联网", "value": 330200 },
    { "name": "红利低波",   "value": 96800 },
    { "name": "新能源",     "value": 94600 },
    { "name": "固收债券",   "value": 141200 },
    { "name": "黄金商品",   "value": 52700 },
    { "name": "现金储备",   "value": 128000 }
  ],
  "region": [
    { "name": "A股",          "value": 603200 },
    { "name": "美股",         "value": 268900 },
    { "name": "港股",         "value": 61300 },
    { "name": "跨境(QDII)",   "value": 0 },
    { "name": "商品/海外",    "value": 52700 },
    { "name": "现金",         "value": 128000 }
  ],
  "currency": [
    { "name": "人民币 CNY", "value": 697700 },
    { "name": "美元 USD",   "value": 268900 },
    { "name": "港币 HKD",   "value": 61300 }
  ]
}
```

#### 3.6.2 带 dim —— 响应 `DistributionItem[]`

`GET /assets/distribution?dim=industry`

```json
[
  { "name": "宽基指数", "value": 312400 },
  { "name": "科技互联网", "value": 330200 }
]
```

**错误**

| code | message 格式 | 场景 |
| --- | --- | --- |
| 400 | `未知的分布维度: {dim}` | `dim` 不是 industry/region/currency |

**现有调用**：`src/components/AssetDistribution.vue`（不带参数，一次取全维度）。`getAssetDistributionByDim` 已导出，暂无页面调用。

---

### 3.7 获取存钱计划列表

- **函数**：`getSavingsPlans()`（`src/api/modules/savings.ts`）
- **URL**：`GET /savings/plans`
- **参数**：无

**响应**：`SavingsPlan[]`

```jsonc
[
  {
    "id": "total-asset",
    "name": "30岁资产目标",
    "description": "财务自由，从现在开始",
    "type": "asset",              // asset | wish
    "currentAmount": 160000,
    "targetAmount": 600000,
    "startDate": "2025-01-01",
    "targetDate": "2030-12-31",
    "monthlyPlanAmount": 5000,
    // image/quote/note/noteImage 为可选字段
    "assets": [
      { "name": "沪深300指数增强", "amount": 312400, "colorVar": "--chart-1" }
      // ...
    ],
    "series": [
      { "month": "2025-01", "amount": 142000 },
      { "month": "2025-11", "amount": 160000 }
    ]
  },
  {
    "id": "japan-trip",
    "name": "日本旅行",
    "type": "wish",
    "image": "https://.../landscape_4_3",
    "quote": "世界这么大，我想去看看。",
    "note": "希望明年能去日本旅行……",
    "noteImage": "https://.../landscape_4_3"
    // ...其余字段同上
  }
]
```

**现有调用**：`src/views/SavingsView.vue`

---

### 3.8 获取单个存钱计划详情

- **函数**：`getSavingsPlan(id: string)`（`src/api/modules/savings.ts`）
- **URL**：`GET /savings/plans/:id`

**路径参数**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 计划 id，如 `total-asset`、`japan-trip`、`buy-computer`、`graduation-trip`、`future-home` |

**响应**：`SavingsPlan`（结构同 3.7 列表项）

**错误**

| code | message 格式 | 场景 |
| --- | --- | --- |
| 404 | `存钱计划不存在: {id}` | id 不存在 |

**现有调用**：`src/views/SavingsDetail.vue`（id 来自路由参数 `/savings/:id`）

---

## 4. 前端调用方式

页面/组件统一从 `@/api` 导入接口函数，配合 `useRequest` 组合式函数管理 `loading / error / data`，组件挂载时自动发起请求：

```ts
import { useRequest } from '@/composables/useApi'
import { getFunds } from '@/api'

const { data: funds, loading, error, reload } = useRequest(() =>
  getFunds({ status: 'active' }),
)
```

- 请求失败时统一抛出 `ApiError`（`src/api/error.ts`），业务层可通过 `error.code` 判断错误类型。
- 切换真实后端：设置环境变量 `VITE_USE_MOCK=false`，并按需配置 `VITE_API_BASE_URL`。

## 5. 备注

- 当前项目无后端工程，上述路径、参数、响应结构以 `src/api/types.ts` 类型定义与 `src/api/mock/handlers.ts` mock 路由行为为准，后端落地时需保持一致。
- 金额单位均为**人民币元**（`number` 类型）；日期字段格式已在模型注释中标注（`YYYY-MM-DD` / `YYYY-MM`）。
- 除本文档列出的 8 个端点外，前端不存在其他 HTTP 调用（已全局扫描确认，无绕过 `request()` 的直接 `fetch`）。
