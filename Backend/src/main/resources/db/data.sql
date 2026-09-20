-- FinView 种子数据（幂等：INSERT IGNORE，可重复执行）
-- 金额单位：人民币元。基金主数据/调整记录/存钱计划与前端 mock 数据集保持一致。

-- ==================== 定投计划 ====================
INSERT IGNORE INTO fund (id, name, code, category, active, frequency, amount, start_date, principal, `current`) VALUES
('csi300',        '沪深300指数增强',     '005827', 'fund',  1, 'monthly', 3000, '2019-03', 246000, 312400),
('nasdaq',        '纳斯达克100(QDII)',   '270042', 'fund',  1, 'weekly',  600,  '2020-01', 174000, 268900),
('dividend',      '中证红利低波',        '515100', 'fund',  1, 'monthly', 1500, '2021-06', 82500,  96800),
('bond-fund',     '稳健债券配置',        '003376', 'bond',  1, 'monthly', 2000, '2020-09', 126000, 141200),
('growth-stock',  '新能源龙头组合',      'SZ-EV',  'stock', 1, 'weekly',  500,  '2021-01', 108000, 94600),
('gold',          '黄金ETF联接',         '000216', 'fund',  1, 'daily',   50,   '2022-03', 40000,  52700),
('hk-tech',       '恒生科技(已归档)',    '513130', 'stock', 0, 'monthly', 0,    '2021-02', 72000,  61300),
('reit',          '基础设施REITs(已归档)','180301','fund',  0, 'monthly', 0,    '2021-09', 48000,  53900);

-- ==================== 定投调整记录 ====================
INSERT IGNORE INTO plan_adjustment (fund_id, adjustment_id, adjustment_date, reason, action, frequency, amount, note, sort_order) VALUES
-- 沪深300
('csi300', 'a1', '2019-03-10', '计划启动',               '开始定投', 'monthly', 2000, '以沪深300为核心底仓', 1),
('csi300', 'a2', '2020-04-06', '市场大幅回撤，估值处于低位', '加大定投', 'monthly', 3500, NULL,                  2),
('csi300', 'a3', '2022-05-18', '现金流阶段性紧张',         '下调金额', 'monthly', 2000, NULL,                  3),
('csi300', 'a4', '2024-01-15', '估值修复，恢复常态投入',    '上调金额', 'monthly', 3000, NULL,                  4),
-- 纳斯达克100
('nasdaq', 'b1', '2020-01-08', '布局海外科技资产',         '开始定投', 'weekly',  500,  NULL,                  1),
('nasdaq', 'b2', '2023-02-20', 'AI 主线景气度提升',        '上调金额', 'weekly',  600,  NULL,                  2),
-- 红利低波
('dividend', 'c1', '2021-06-12', '配置低波动分红资产对冲', '开始定投', 'monthly', 1500, NULL,                  1),
-- 债券
('bond-fund', 'd1', '2020-09-01', '构建组合防御垫',       '开始定投', 'monthly', 1500, NULL,                  1),
('bond-fund', 'd2', '2022-11-10', '债市回调后提升配置',   '上调金额', 'monthly', 2000, NULL,                  2),
-- 新能源
('growth-stock', 'e1', '2021-01-20', '看好电动化长期趋势',     '开始定投', 'weekly', 700, NULL, 1),
('growth-stock', 'e2', '2023-08-14', '行业竞争加剧，控制仓位', '下调金额', 'weekly', 500, NULL, 2),
-- 黄金
('gold', 'f1', '2022-03-05', '对冲通胀与地缘风险', '开始定投', 'daily', 50, NULL, 1),
-- 恒生科技（已归档）
('hk-tech', 'g1', '2021-02-18', '布局港股互联网',         '开始定投', 'monthly', 2500, NULL,                   1),
('hk-tech', 'g2', '2022-10-24', '监管与流动性压力持续',    '下调金额', 'monthly', 1500, NULL,                   2),
('hk-tech', 'g3', '2023-12-30', '调整配置结构，止盈离场',  '停止定投', NULL,      NULL, '本金转投红利低波策略', 3),
-- REITs（已归档）
('reit', 'h1', '2021-09-10', '试水基础设施REITs',  '开始定投', 'monthly', 1500, NULL,           1),
('reit', 'h2', '2024-03-01', '达到预期目标收益',    '停止定投', NULL,      NULL, '计划圆满结束', 2);

-- ==================== 定投走势（按月生成 2025-09 截止） ====================
-- 本金自 0 线性增长至 principal；市值在 current 线性轨迹上叠加确定性波动，
-- 起点为 0/0，末点强制对齐 principal/current。
INSERT IGNORE INTO fund_series (fund_id, day, principal, total)
WITH RECURSIVE nums (n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM nums WHERE n < 120
),
bounds AS (
    SELECT id,
           STR_TO_DATE(CONCAT(start_date, '-01'), '%Y-%m-%d') AS start_day,
           STR_TO_DATE('2025-09-01', '%Y-%m-%d')               AS end_day,
           principal,
           `current`
    FROM fund
)
SELECT b.id,
       DATE_ADD(b.start_day, INTERVAL nu.n MONTH) AS day,
       CASE WHEN nu.n = TIMESTAMPDIFF(MONTH, b.start_day, b.end_day)
            THEN b.principal
            ELSE FLOOR(b.principal * nu.n / TIMESTAMPDIFF(MONTH, b.start_day, b.end_day))
       END AS principal,
       CASE WHEN nu.n = 0 THEN 0
            WHEN nu.n = TIMESTAMPDIFF(MONTH, b.start_day, b.end_day) THEN b.`current`
            ELSE FLOOR(b.`current` * nu.n / TIMESTAMPDIFF(MONTH, b.start_day, b.end_day)
                 * (1 + 0.08 * (nu.n / TIMESTAMPDIFF(MONTH, b.start_day, b.end_day))
                        * SIN(nu.n * 0.9 + LENGTH(b.id))))
       END AS total
FROM bounds b
INNER JOIN nums nu ON nu.n <= TIMESTAMPDIFF(MONTH, b.start_day, b.end_day);

-- ==================== 存钱计划 ====================
INSERT IGNORE INTO savings_plan
(id, name, description, type, image, current_amount, target_amount, start_date, target_date,
 monthly_plan_amount, quote, note, note_image) VALUES
('total-asset',    '30岁资产目标', '财务自由，从现在开始', 'asset', NULL,
 160000, 600000, '2025-01-01', '2030-12-31', 5000, NULL, NULL, NULL),
('japan-trip',     '日本旅行', '去看更大的世界，体验不同的风景和文化。', 'wish',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=japanese%20landmark%20torii%20gate%20kyoto%20travel&image_size=landscape_4_3',
 8000, 10000, '2025-05-01', '2026-12-01', 1000,
 '世界这么大，我想去看看。', '希望明年能去日本旅行，体验樱花季，吃美食，看看富士山！',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=mount%20fuji%20with%20cherry%20blossoms%20landscape&image_size=landscape_4_3'),
('buy-computer',   '买电脑', '提升生产力', 'wish',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=modern%20laptop%20on%20desk%20productivity&image_size=landscape_4_3',
 7200, 12000, '2025-06-01', '2026-12-15', 1200, NULL, NULL, NULL),
('graduation-trip','毕业旅行', '给大学生活一个完美的句号', 'wish',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=suitcase%20travel%20adventure%20wanderlust&image_size=landscape_4_3',
 3500, 8000, '2025-03-01', '2027-06-01', 800, NULL, NULL, NULL),
('future-home',    '未来的家', '一个温暖的小家', 'wish',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=modern%20cozy%20house%20dream%20home&image_size=landscape_4_3',
 60000, 500000, '2022-01-01', '2030-12-31', 8000, NULL, NULL, NULL);

-- ==================== 存钱计划持有资产 ====================
INSERT IGNORE INTO savings_asset (plan_id, name, amount, color_var, sort_order) VALUES
('total-asset',     '沪深300指数增强', 312400, '--chart-1', 1),
('total-asset',     '纳斯达克100',    268900, '--chart-3', 2),
('total-asset',     '稳健债券配置',   141200, '--chart-4', 3),
('total-asset',     '黄金ETF联接',    52700,  '--chart-5', 4),
('total-asset',     '红利低波',       96800,  '--chart-1', 5),
('total-asset',     '现金储备',       128000, '--chart-5', 6),
('japan-trip',      '旅行专用银行卡', 4200,   '--chart-1', 1),
('japan-trip',      '余额宝',         2300,   '--chart-3', 2),
('japan-trip',      '货币基金',       1500,   '--chart-4', 3),
('buy-computer',    '科技基金',       5200,   '--chart-1', 1),
('buy-computer',    '现金',           2000,   '--chart-5', 2),
('graduation-trip', '余额宝',         2100,   '--chart-3', 1),
('graduation-trip', '货币基金',       1400,   '--chart-4', 2),
('future-home',     '沪深300增强',    312400, '--chart-1', 1),
('future-home',     '红利低波',       96800,  '--chart-3', 2),
('future-home',     '纳斯达克100',    190800, '--chart-4', 3),
('future-home',     '现金',           128000, '--chart-5', 4);

-- ==================== 存钱计划月度走势 ====================
INSERT IGNORE INTO savings_series (plan_id, month, amount, sort_order) VALUES
('total-asset', '2025-01', 142000, 1),
('total-asset', '2025-03', 148500, 2),
('total-asset', '2025-05', 151200, 3),
('total-asset', '2025-07', 154800, 4),
('total-asset', '2025-09', 157600, 5),
('total-asset', '2025-11', 160000, 6),
('japan-trip', '2025-05', 4500, 1),
('japan-trip', '2025-07', 5800, 2),
('japan-trip', '2025-09', 7000, 3),
('japan-trip', '2025-11', 8000, 4),
('buy-computer', '2025-06', 4800, 1),
('buy-computer', '2025-08', 5800, 2),
('buy-computer', '2025-10', 6600, 3),
('buy-computer', '2025-12', 7200, 4),
('graduation-trip', '2025-03', 1200, 1),
('graduation-trip', '2025-06', 1800, 2),
('graduation-trip', '2025-09', 2600, 3),
('graduation-trip', '2025-12', 3500, 4),
('future-home', '2025-01', 42000, 1),
('future-home', '2025-04', 48000, 2),
('future-home', '2025-07', 54000, 3),
('future-home', '2025-10', 60000, 4);

-- ==================== 资产分布 ====================
INSERT IGNORE INTO asset_distribution (dim, name, value, sort_order) VALUES
('industry', '宽基指数',   312400, 1),
('industry', '科技互联网', 330200, 2),
('industry', '红利低波',   96800,  3),
('industry', '新能源',     94600,  4),
('industry', '固收债券',   141200, 5),
('industry', '黄金商品',   52700,  6),
('industry', '现金储备',   128000, 7),
('region', 'A股',        603200, 1),
('region', '美股',       268900, 2),
('region', '港股',       61300,  3),
('region', '跨境(QDII)', 0,      4),
('region', '商品/海外',  52700,  5),
('region', '现金',       128000, 6),
('currency', '人民币 CNY', 697700, 1),
('currency', '美元 USD',   268900, 2),
('currency', '港币 HKD',   61300,  3);
