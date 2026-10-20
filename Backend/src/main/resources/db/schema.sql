-- FinView 建表 / 迁移脚本
--
-- spring.sql.init.mode=always，本文件**每次启动都会执行**，所以：
--   1. 建表一律 CREATE TABLE IF NOT EXISTS；
--   2. 加列 / 改注释一律先查 information_schema 再 PREPARE 执行，已满足时走 'DO 0' 空操作；
--   3. 绝不写 DROP TABLE / DROP COLUMN 之外还会删数据的语句。
-- 表结构以根目录《表设计.md》为准。

-- 创建数据库
CREATE DATABASE IF NOT EXISTS finview DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE finview;

-- ============================== 1. user 用户表 ==============================
CREATE TABLE IF NOT EXISTS `user` (
                        `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户主键',
                        `user_name` VARCHAR(100) NOT NULL UNIQUE COMMENT '用户名',
                        `password` VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
                        `nickname` VARCHAR(64) DEFAULT NULL COMMENT '昵称，注册可选，缺省用用户名',
                        `email` VARCHAR(100) DEFAULT NULL UNIQUE COMMENT '邮箱，注册可选',
                        `role` VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色：user或admin',
                        `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
                        `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        `update_series_time` DATETIME DEFAULT NULL COMMENT 'asset_series 上次推进到的日期，见 SeriesService.advance',
                        `monthly_inflow` DECIMAL(18,4) DEFAULT NULL COMMENT '每月固定新增（定投分析页的现金流假设），NULL = 用户未设置'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 兼容历史库：老版本 user 表没有 nickname 列，且 email 是 NOT NULL。
-- 下面两条按 information_schema 判断，已满足时执行 DO 0 空操作，可重复启动。
SET @has_nickname = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'nickname');
SET @ddl = IF(@has_nickname = 0,
              'ALTER TABLE `user` ADD COLUMN `nickname` VARCHAR(64) DEFAULT NULL AFTER `password`',
              'DO 0');
PREPARE migrate_user FROM @ddl;
EXECUTE migrate_user;
DEALLOCATE PREPARE migrate_user;

SET @email_nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'email');
SET @ddl = IF(@email_nullable = 'NO',
              'ALTER TABLE `user` MODIFY COLUMN `email` VARCHAR(100) DEFAULT NULL',
              'DO 0');
PREPARE migrate_user FROM @ddl;
EXECUTE migrate_user;
DEALLOCATE PREPARE migrate_user;

-- update_series_time：表设计.md 里的列名。历史库里叫 update_series（值全 NULL 的草稿），
-- 先加新列 → 把旧列的值搬过来 → 再删旧列，避免留下列名不一致的两份状态。
SET @has_update_series_time = (SELECT COUNT(*) FROM information_schema.COLUMNS
                               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                                 AND COLUMN_NAME = 'update_series_time');
SET @ddl = IF(@has_update_series_time = 0,
              'ALTER TABLE `user` ADD COLUMN `update_series_time` DATETIME DEFAULT NULL COMMENT ''asset_series 上次推进到的日期''',
              'DO 0');
PREPARE migrate_user FROM @ddl;
EXECUTE migrate_user;
DEALLOCATE PREPARE migrate_user;

SET @has_legacy_series = (SELECT COUNT(*) FROM information_schema.COLUMNS
                          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                            AND COLUMN_NAME = 'update_series');
SET @ddl = IF(@has_legacy_series = 1,
              'UPDATE `user` SET update_series_time = update_series WHERE update_series_time IS NULL',
              'DO 0');
PREPARE migrate_user FROM @ddl;
EXECUTE migrate_user;
DEALLOCATE PREPARE migrate_user;

SET @ddl = IF(@has_legacy_series = 1,
              'ALTER TABLE `user` DROP COLUMN `update_series`',
              'DO 0');
PREPARE migrate_user FROM @ddl;
EXECUTE migrate_user;
DEALLOCATE PREPARE migrate_user;

-- monthly_inflow：「定投分析」页手工维护的现金流假设（每月固定新增），只由 CashFlowService 读写。
-- NULL = 用户还没设置（页面显示「未设置」，预测按 0 计）。口径：
--   12 个月后预计现金 = 当前可用现金 + 12 × 每月固定新增 − 12 × 每月定投总额。
SET @has_monthly_inflow = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                             AND COLUMN_NAME = 'monthly_inflow');
SET @ddl = IF(@has_monthly_inflow = 0,
              'ALTER TABLE `user` ADD COLUMN `monthly_inflow` DECIMAL(18,4) DEFAULT NULL COMMENT ''每月固定新增（定投分析页的现金流假设），NULL = 用户未设置'' AFTER `update_series_time`',
              'DO 0');
PREPARE migrate_user FROM @ddl;
EXECUTE migrate_user;
DEALLOCATE PREPARE migrate_user;

-- ============================== 2. adjustments 资产调整记录表 ==============================
CREATE TABLE IF NOT EXISTS `adjustments` (
                               `adjustment_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '调整记录ID',
                               `user_id` BIGINT NOT NULL COMMENT '用户id',
                               `code` VARCHAR(64) NOT NULL COMMENT '资产编码',
                               `date` DATE NOT NULL COMMENT '记录日期',
                               `reason` VARCHAR(255) COMMENT '变动原因',
                               `action` TINYINT NOT NULL COMMENT '动作：1定投开始，2结束定投，3买入，4卖出（现金的进账/出账走3/4）',
                               `deferred` TINYINT NOT NULL DEFAULT 0 COMMENT '是否从下一交易日起生效：1是0否（仅 action=1；0 = 按原规则当日生效）',
                               `frequency` VARCHAR(64) COMMENT '频率',
                               `amount` DECIMAL(18,4) NOT NULL COMMENT '金额',
                               `note` VARCHAR(512) COMMENT '备注',
                               FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
                               INDEX idx_user_code (`user_id`, `code`, `date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产调整记录表';

-- 兼容历史库：deferred 是后加的列（定投详情「调整定投」用，从下一交易日起生效）。
SET @has_adjustment_deferred = (SELECT COUNT(*) FROM information_schema.COLUMNS
                                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'adjustments' AND COLUMN_NAME = 'deferred');
SET @ddl = IF(@has_adjustment_deferred = 0,
              'ALTER TABLE `adjustments` ADD COLUMN `deferred` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否从下一交易日起生效：1是0否（仅 action=1）'' AFTER `action`',
              'DO 0');
PREPARE migrate_adjustment_deferred FROM @ddl;
EXECUTE migrate_adjustment_deferred;
DEALLOCATE PREPARE migrate_adjustment_deferred;

-- ============================== 3. nav_trend 外部获取净值表 ==============================
CREATE TABLE IF NOT EXISTS `nav_trend` (
                             `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                             `day` DATE NOT NULL COMMENT '日期',
                             `value` DECIMAL(18,8) NOT NULL COMMENT '单位净值（货币基金为每万份收益合成的复权净值）',
                             `rate` DECIMAL(18,4) DEFAULT NULL COMMENT '日增长率(%)，与 py 返回的「日增长率」同义',
                             `code` VARCHAR(64) NOT NULL COMMENT '资产编码',
                             UNIQUE KEY idx_day_code (`day`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产净值趋势（外部获取）';

SET @has_nav_rate = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nav_trend' AND COLUMN_NAME = 'rate');
SET @ddl = IF(@has_nav_rate = 0,
              'ALTER TABLE `nav_trend` ADD COLUMN `rate` DECIMAL(18,4) DEFAULT NULL COMMENT ''日增长率(%)'' AFTER `value`',
              'DO 0');
PREPARE migrate_nav FROM @ddl;
EXECUTE migrate_nav;
DEALLOCATE PREPARE migrate_nav;

-- value 4 位小数 → 8 位（2026-10-14）：货币基金的净值是「每万份收益」合成的复权净值，
-- 每天只涨 ~0.00008，4 位小数会把收益抹平（市值恒等于本金）；普通基金的 4 位净值不受影响。
SET @nav_value_scale = (SELECT NUMERIC_SCALE FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nav_trend' AND COLUMN_NAME = 'value');
SET @ddl = IF(IFNULL(@nav_value_scale, 0) < 8,
              'ALTER TABLE `nav_trend` MODIFY COLUMN `value` DECIMAL(18,8) NOT NULL COMMENT ''单位净值（货币基金为每万份收益合成的复权净值）''',
              'DO 0');
PREPARE migrate_nav_value FROM @ddl;
EXECUTE migrate_nav_value;
DEALLOCATE PREPARE migrate_nav_value;

-- ============================== 4. saving_plans 储蓄计划表 ==============================
-- 计划与资产的多对多关联见第 7 节的 plan_asset 表（2026-10-05 起不再用 asset.plan_id）。
-- current_amount 已退役：完成度按关联资产的最新市值算出来（SavingsService.toResponse），
-- 存量库里的这一列由第 7 节守卫式删掉。
CREATE TABLE IF NOT EXISTS `saving_plans` (
                                `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                                `user_id` BIGINT NOT NULL COMMENT '用户id',
                                `name` VARCHAR(100) NOT NULL COMMENT '计划名称',
                                `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
                                `type` VARCHAR(64) DEFAULT NULL COMMENT '计划类型：asset 总资产 / life 生活保障 / wish 愿望',
                                `image` VARCHAR(255) DEFAULT NULL COMMENT '头图地址',
                                `target_amount` DECIMAL(18,4) DEFAULT NULL COMMENT '目标金额：NULL = 不设目标（只看这组资产的收益）',
                                `start_date` DATE DEFAULT NULL COMMENT '计划开始日期',
                                `target_date` DATE DEFAULT NULL COMMENT '计划目标日期',
                                `monthly_plan_amount` DECIMAL(18,4) DEFAULT NULL COMMENT '每月计划投入',
                                `quote` VARCHAR(255) DEFAULT NULL COMMENT '备注引言',
                                `note` TEXT COMMENT '详细备注',
                                `note_image` VARCHAR(255) DEFAULT NULL COMMENT '备注图片',
                                FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储蓄计划表';

-- ============================== 5. asset 资产主表 ==============================
-- active / frequency / startDate / principal / total / last_update_time 是 fold 出来的快照（SeriesService 维护），
-- name / category / industry / region / currency / archived / market 是用户维护的元数据，fold 不覆盖。
CREATE TABLE IF NOT EXISTS `asset` (
                         `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                         `user_id` BIGINT NOT NULL COMMENT '用户id',
                         `name` VARCHAR(100) NOT NULL COMMENT '资产名称',
                         `code` VARCHAR(64) NOT NULL COMMENT '资产编码',
                         `category` VARCHAR(64) DEFAULT NULL COMMENT '资产分类：fund/stock/bond/cash；fund 的股基/债基细分看 asset_type',
                         `asset_type` VARCHAR(16) DEFAULT NULL COMMENT '资产类型：equity 股基 / bond 债基 / money 货币基金 / cash 现金（仅 CASH 行，系统维护）；基金行只写 equity/bond/money，NULL 按 equity 算（安全资金统计按 bond+money+cash 计）',
                         `active` TINYINT DEFAULT 1 COMMENT '定投是否进行中 1是0否',
                         `archived` TINYINT NOT NULL DEFAULT 0 COMMENT '是否归档 1是0否（用户维护的元数据，fold 不覆盖）',
                         `frequency` VARCHAR(64) DEFAULT NULL COMMENT '当前定投频率',
                         `rate` DECIMAL(18,4) DEFAULT NULL COMMENT '申购费率(%)，买入时按 金额×rate/100 扣手续费；NULL 或 0 表示不计费',
                         `startDate` DATE DEFAULT NULL COMMENT '计划开始日期',
                         `principal` DECIMAL(18,4) NOT NULL COMMENT '累计投入本金（快照）',
                         `total` DECIMAL(18,4) NOT NULL COMMENT '当前市值 = 份额 × 最新净值（快照）',
                         `industry` VARCHAR(64) DEFAULT NULL COMMENT '行业标签，供 /assets/distribution 使用',
                         `region` VARCHAR(64) DEFAULT NULL COMMENT '区域标签，供 /assets/distribution 使用',
                         `currency` VARCHAR(64) DEFAULT NULL COMMENT '币种标签，供 /assets/distribution 使用',
                         `market` VARCHAR(16) DEFAULT NULL COMMENT '标的市场：us / hk；NULL = 只看 A 股日历（A 股基金）',
                         `last_update_time` DATETIME DEFAULT NULL COMMENT '最后更新时间：最后一次生成/重算 asset_series 的时刻（现金为最后一次余额快照刷新）',
                         FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
                         UNIQUE KEY idx_user_code (`user_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产表';

-- 兼容历史库：industry / region / currency / archived 都是后加的列。
-- （原来的 plan_id 兼容块已删：关联改走 plan_asset，存量列由第 7 节守卫式删掉。）
SET @has_rate = (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset' AND COLUMN_NAME = 'rate');
SET @ddl = IF(@has_rate = 0,
              'ALTER TABLE `asset` ADD COLUMN `rate` DECIMAL(18,4) DEFAULT NULL COMMENT ''申购费率(%)，买入时按 金额×rate/100 扣手续费'' AFTER `frequency`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

SET @has_industry = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset' AND COLUMN_NAME = 'industry');
SET @ddl = IF(@has_industry = 0,
              'ALTER TABLE `asset` ADD COLUMN `industry` VARCHAR(64) DEFAULT NULL COMMENT ''行业标签'' AFTER `total`, ADD COLUMN `region` VARCHAR(64) DEFAULT NULL COMMENT ''区域标签'' AFTER `industry`, ADD COLUMN `currency` VARCHAR(64) DEFAULT NULL COMMENT ''币种标签'' AFTER `region`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- archived：用户维护的「是否归档」标记（1 已归档 / 0 未归档），与 fold 出来的 active（定投是否进行中）
-- 是两回事——定投结束不等于归档。存量行一律补 0，即都算未归档。
SET @has_archived = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset' AND COLUMN_NAME = 'archived');
SET @ddl = IF(@has_archived = 0,
              'ALTER TABLE `asset` ADD COLUMN `archived` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否归档 1是0否（用户维护的元数据，fold 不覆盖）'' AFTER `active`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- market：标的市场（us / hk），QDII 的扣款日要叠加标的市场休市日（2026-09-30 加，见 MarketCalendar）。
-- NULL = 只看 A 股日历，与历史行为一致，A 股基金不用标。
SET @has_market = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset' AND COLUMN_NAME = 'market');
SET @ddl = IF(@has_market = 0,
              'ALTER TABLE `asset` ADD COLUMN `market` VARCHAR(16) DEFAULT NULL COMMENT ''标的市场：us / hk；NULL = 只看 A 股日历（A 股基金）'' AFTER `currency`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- asset_type：资产类型（2026-10-02 加，用户维护的元数据、fold 不覆盖）。
-- 基金行：equity 股基 / bond 债基 / money 货币基金（2026-10-13 加，录入界面可选，见 AdjustmentRequest.assetType）；
-- CASH 行：cash（系统维护）。
-- NULL = 未标注，按股基算（下面回填成 equity）；「安全资金占比」（我的资产页）
-- 按 bond + money + cash 的市值 / 总资产算，见 AssetService.summary。
SET @has_asset_type = (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset'
                         AND COLUMN_NAME = 'asset_type');
SET @ddl = IF(@has_asset_type = 0,
              'ALTER TABLE `asset` ADD COLUMN `asset_type` VARCHAR(16) DEFAULT NULL COMMENT ''资产类型：equity 股基 / bond 债基 / cash 现金（仅 CASH 行，系统维护）；基金行只写 equity/bond，NULL 按 equity 算（安全资金统计按 bond+cash 计）'' AFTER `category`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- 列注释跟着取值走（2026-10-13 加了 money 货币基金）：拿注释本身当迁移标记 —— 注释里有 money
-- 就是改过了，之后每次启动都走 DO 0（与 saving_plans.type 的迁移同一套做法，不会反复 MODIFY）。
SET @asset_type_comment_old = (SELECT COUNT(*) FROM information_schema.COLUMNS
                               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset'
                                 AND COLUMN_NAME = 'asset_type'
                                 AND COLUMN_COMMENT NOT LIKE '%money%');
SET @ddl = IF(@asset_type_comment_old = 1,
              'ALTER TABLE `asset` MODIFY COLUMN `asset_type` VARCHAR(16) DEFAULT NULL COMMENT ''资产类型：equity 股基 / bond 债基 / money 货币基金 / cash 现金（仅 CASH 行，系统维护）；基金行只写 equity/bond/money，NULL 按 equity 算（安全资金统计按 bond+money+cash 计）''',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- 存量行回填：现金是唯一能机械判定的一类（保留 code CASH / category=cash），先标上。
UPDATE `asset`
SET asset_type = 'cash'
WHERE asset_type IS NULL
  AND (code = 'CASH' OR category = 'cash');

-- 存量基金一律回填成股票基金（2026-10-02 用户约定：细分先默认 equity，债基逐只在录入时改）。
-- **顺序在现金回填之后**：万一有手工写成 category='fund' 的 CASH 行，先被上面那句认领走。
-- 只填 NULL、可重复执行：手工标过的 bond 不会被覆盖。
-- 唯一的债基 019851 汇添富稳宏6个月持有债券A 是 2026-10-02 用一次性 UPDATE 标的
-- （「UPDATE asset SET asset_type='bond' WHERE code='019851'」）——**故意不写进本文件**：
-- 写进来就每次启动都把它强行改回 bond，用户以后自己改不动。
UPDATE `asset`
SET asset_type = 'equity'
WHERE asset_type IS NULL
  AND category = 'fund';

-- last_update_time：最后更新时间（2026-10-02 加）。每生成 / 重算一次该资产的 asset_series，
-- SeriesService 就把这里刷成当时时刻（现金没有序列，取最后一次余额快照刷新的时刻）；
-- 折线图页脚的「最后更新」用它，组合总览取该用户全部 asset 行的 MAX。
SET @has_last_update_time = (SELECT COUNT(*) FROM information_schema.COLUMNS
                             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset'
                               AND COLUMN_NAME = 'last_update_time');
SET @ddl = IF(@has_last_update_time = 0,
              'ALTER TABLE `asset` ADD COLUMN `last_update_time` DATETIME DEFAULT NULL COMMENT ''最后更新时间：最后一次生成/重算 asset_series 的时刻（现金为最后一次余额快照刷新）'' AFTER `market`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- 存量行的初始值回填 = 所属用户的账号创建时间（user.create_time），只填 NULL、可重复执行；
-- 之后每次生成 series 都会被 SeriesService 覆盖成当时时刻，新行由 upsert 直接写入不会留 NULL。
UPDATE `asset` a
    JOIN `user` u ON a.user_id = u.id
SET a.last_update_time = u.create_time
WHERE a.last_update_time IS NULL;

-- 老库把 principal / total 的列注释写反了（「当前净值」/「当前总投入金额」），这里纠正成真实语义。
-- 类型保持原样，只改注释，可重复执行。
SET @ddl = 'ALTER TABLE `asset`
    MODIFY COLUMN `principal` DECIMAL(18,4) NOT NULL COMMENT ''累计投入本金（快照）'',
    MODIFY COLUMN `total` DECIMAL(18,4) NOT NULL COMMENT ''当前市值 = 份额 × 最新净值（快照）''';
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- asset_type 的列注释同步成新语义：列已经存在时上面的 ADD COLUMN 分支不会再执行，注释得单独纠正。
-- 同样只改注释、类型原样，可重复执行。
SET @ddl = 'ALTER TABLE `asset`
    MODIFY COLUMN `asset_type` VARCHAR(16) DEFAULT NULL COMMENT ''资产类型：equity 股基 / bond 债基 / cash 现金（仅 CASH 行，系统维护）；基金行只写 equity/bond，NULL 按 equity 算（安全资金统计按 bond+cash 计）''';
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- ============================== 6. asset_series 资产每日序列表 ==============================
-- 由 SeriesService 按《表设计.md》的核心更新流程逐日推进写入：
--   amount    当日净投入 = 当日周期扣款 + 买入 − 卖出（可为负）
--   fee       当日手续费 = 当日各笔买入的手续费之和（卖出为 0）；累计手续费 = Σfee
--   principal 截至当日的累计投入本金
--   total     截至当日的总市值 = 累计份额 × 当日净值
-- 不变式：Σamount == 末行 principal。
CREATE TABLE IF NOT EXISTS `asset_series` (
                                `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                                `user_id` BIGINT NOT NULL COMMENT '用户id',
                                `code` VARCHAR(64) NOT NULL COMMENT '资产编码',
                                `day` DATE NOT NULL COMMENT '日期',
                                `amount` DECIMAL(18,4) NOT NULL COMMENT '当日净投入',
                                `fee` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '当日手续费（买入按 金额×rate/100 收取，卖出为 0）',
                                `principal` DECIMAL(18,4) NOT NULL COMMENT '截至当日的累计投入本金',
                                `total` DECIMAL(18,4) NOT NULL COMMENT '截至当日的总市值',
                                FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
                                UNIQUE KEY idx_user_code_day (`user_id`, `code`, `day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产每日序列表';

SET @ddl = 'ALTER TABLE `asset_series`
    MODIFY COLUMN `amount` DECIMAL(18,4) NOT NULL COMMENT ''当日净投入'',
    MODIFY COLUMN `principal` DECIMAL(18,4) NOT NULL COMMENT ''截至当日的累计投入本金'',
    MODIFY COLUMN `total` DECIMAL(18,4) NOT NULL COMMENT ''截至当日的总市值''';
PREPARE migrate_series FROM @ddl;
EXECUTE migrate_series;
DEALLOCATE PREPARE migrate_series;

-- fee：当日手续费（2026-09-30 加的列）。存量行默认 0，历史手续费要靠重算回填
-- （SeriesService.rebuild / 抓到新净值 / 增删改调整记录都会整段重算）。
SET @has_series_fee = (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset_series' AND COLUMN_NAME = 'fee');
SET @ddl = IF(@has_series_fee = 0,
              'ALTER TABLE `asset_series` ADD COLUMN `fee` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT ''当日手续费（买入按 金额×rate/100 收取，卖出为 0）'' AFTER `amount`',
              'DO 0');
PREPARE migrate_series FROM @ddl;
EXECUTE migrate_series;
DEALLOCATE PREPARE migrate_series;

-- ============================== 7. plan_asset 计划↔资产关联表（多对多） ==============================
-- 2026-10-05 起「计划里有哪些资产」由这张表表达，asset.plan_id 退役。
-- 一个资产可以同时属于多个小愿望；「总资产」计划不查这张表 —— 它默认包含该用户全部未归档资产
-- （SavingsService.assetsOf），所以给小愿望之外的计划插关联是没有意义的（服务端会拒绝）。
--
-- 注意：本段以前是 `DROP TABLE IF EXISTS plan_asset`（那时这张表被废弃）。
-- 现在它是真表，**绝不能再无条件 DROP** —— schema.sql 每次启动都跑，DROP 会把数据清空。
--
-- 唯一的例外：极老的库里可能残留一张「废弃时期」留下的、形状不同的 plan_asset。
-- CREATE TABLE IF NOT EXISTS 会把它原样留着，之后任何带 user_id 的语句都会 1054。
-- 所以先按形状判断：没有 asset_id 列的老表先清掉（它里面不可能有本功能的数据）。
SET @pa_exists = (SELECT COUNT(*) FROM information_schema.TABLES
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plan_asset');
SET @pa_new = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plan_asset' AND COLUMN_NAME = 'asset_id');
SET @ddl = IF(@pa_exists = 1 AND @pa_new = 0, 'DROP TABLE `plan_asset`', 'DO 0');
PREPARE migrate_plan_asset FROM @ddl;
EXECUTE migrate_plan_asset;
DEALLOCATE PREPARE migrate_plan_asset;

CREATE TABLE IF NOT EXISTS `plan_asset` (
                                `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                                `user_id` BIGINT NOT NULL COMMENT '所属用户id（关联表也带，所有语句照约定直接按 user_id 过滤）',
                                `plan_id` BIGINT NOT NULL COMMENT '存钱计划id',
                                `asset_id` BIGINT NOT NULL COMMENT '资产id',
                                UNIQUE KEY `uk_plan_asset` (`plan_id`, `asset_id`),
                                FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
                                FOREIGN KEY (`plan_id`) REFERENCES `saving_plans`(`id`) ON DELETE CASCADE,
                                FOREIGN KEY (`asset_id`) REFERENCES `asset`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存钱计划与资产的多对多关联';

-- 把存量 asset.plan_id 搬进关联表，然后删列。两步都随「列还在不在」守卫，只跑一次；
-- 列没了以后再 SELECT a.plan_id 会直接 1054，所以搬数据也必须包在 IF(...) 里。
SET @has_asset_plan_id = (SELECT COUNT(*) FROM information_schema.COLUMNS
                          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset' AND COLUMN_NAME = 'plan_id');
-- JOIN saving_plans 两个作用：① 老库的 plan_id 没有外键，可能指向已删的计划，
-- 直接插会撞新表的外键（1452）把启动搞挂；② 顺带要求计划与资产同属一个用户，不搬跨用户的脏关联。
SET @ddl = IF(@has_asset_plan_id = 1,
              'INSERT IGNORE INTO `plan_asset` (user_id, plan_id, asset_id)
               SELECT a.user_id, a.plan_id, a.id
                 FROM `asset` a
                 JOIN `saving_plans` p ON p.id = a.plan_id AND p.user_id = a.user_id
                WHERE a.plan_id IS NOT NULL',
              'DO 0');
PREPARE migrate_plan_asset FROM @ddl;
EXECUTE migrate_plan_asset;
DEALLOCATE PREPARE migrate_plan_asset;

SET @ddl = IF(@has_asset_plan_id = 1, 'ALTER TABLE `asset` DROP COLUMN `plan_id`', 'DO 0');
PREPARE migrate_plan_asset FROM @ddl;
EXECUTE migrate_plan_asset;
DEALLOCATE PREPARE migrate_plan_asset;

-- current_amount 退役：完成度改成按关联资产的最新市值算（SavingsService），列不再有人读写。
SET @has_current_amount = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'saving_plans' AND COLUMN_NAME = 'current_amount');
SET @ddl = IF(@has_current_amount = 1, 'ALTER TABLE `saving_plans` DROP COLUMN `current_amount`', 'DO 0');
PREPARE migrate_plan_current FROM @ddl;
EXECUTE migrate_plan_current;
DEALLOCATE PREPARE migrate_plan_current;

-- target_amount 放开 NOT NULL（2026-10-05）：不设目标的计划（只看这组资产的收益）没有目标金额，
-- 也就没有目标日期 —— 见 SavingsService.validateNewPlan。守卫看 IS_NULLABLE，改过一次就不再执行。
SET @target_required = (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'saving_plans'
                          AND COLUMN_NAME = 'target_amount' AND IS_NULLABLE = 'NO');
SET @ddl = IF(@target_required = 1,
              'ALTER TABLE `saving_plans` MODIFY COLUMN `target_amount` DECIMAL(18,4) NULL COMMENT ''目标金额：NULL = 不设目标（只看这组资产的收益）''',
              'DO 0');
PREPARE migrate_plan_target FROM @ddl;
EXECUTE migrate_plan_target;
DEALLOCATE PREPARE migrate_plan_target;

-- 计划类型拆成三个：asset 总资产 / life 生活保障 / wish 愿望（2026-10-05，原来是 asset / wish）。
-- 存量的 wish 行统一归到 life（用户定的）。
--
-- 这里**不能**写成「每次启动都把 wish 改成 life」——那样以后新建的「愿望」计划重启一次就变成
-- 生活保障了。所以用列注释当迁移标记：注释里出现 life（= 迁过了）就整段跳过，
-- 迁移与改注释在同一个启动里一前一后跑完，之后永远走 DO 0。
SET @type_unmigrated = (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'saving_plans'
                          AND COLUMN_NAME = 'type' AND COLUMN_COMMENT NOT LIKE '%life%');
SET @ddl = IF(@type_unmigrated = 1,
              'UPDATE `saving_plans` SET `type` = ''life'' WHERE `type` = ''wish''',
              'DO 0');
PREPARE migrate_plan_type FROM @ddl;
EXECUTE migrate_plan_type;
DEALLOCATE PREPARE migrate_plan_type;

SET @ddl = IF(@type_unmigrated = 1,
              'ALTER TABLE `saving_plans` MODIFY COLUMN `type` VARCHAR(64) DEFAULT NULL COMMENT ''计划类型：asset 总资产 / life 生活保障 / wish 愿望''',
              'DO 0');
PREPARE migrate_plan_type FROM @ddl;
EXECUTE migrate_plan_type;
DEALLOCATE PREPARE migrate_plan_type;

-- ============================== 8. monthly_inflow_override 每月新增的单月覆盖（定投分析） ==============================
-- 「定投分析」页的每月固定新增有两层：user.monthly_inflow 是**默认值**（卡片 1 编辑），
-- 本表是**单月覆盖**（点柱状图的柱子设置），按 (user_id, month) 唯一。
-- 预测时：某月有覆盖行就用覆盖值，否则用默认值；删掉覆盖行 = 恢复默认。
-- month 用 CHAR(7) 存 'YYYY-MM'（按自然月存档，窗口往后滚时已设过的月份自然跟着走）。
CREATE TABLE IF NOT EXISTS `monthly_inflow_override` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户主键',
    `month` CHAR(7) NOT NULL COMMENT '目标月份，YYYY-MM',
    `amount` DECIMAL(18,4) NOT NULL COMMENT '该月的每月固定新增（元）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_month` (`user_id`, `month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定投分析的每月固定新增-单月覆盖';

-- ============================== 9. saving_plan_types 存钱计划的类型（内置 + 自定义） ==============================
-- 类型拆成「code（稳定标识）+ name（显示名，可改）」两层：saving_plans.type 存的仍是 code，
-- 所以改名只动这张表、不用碰任何计划。内置三种（asset 总资产 / life 生活保障 / wish 愿望）
-- 由 SavingsService 在首次访问时按 (user_id, code) 幂等补种（INSERT IGNORE），
-- 自定义类型 code = 'c' + 随机串，name 由用户填。
-- 只有 asset 是系统类型：不能改名 / 删除（总资产计划的全账号唯一、必须带目标都绑在它身上）。
CREATE TABLE IF NOT EXISTS `saving_plan_types` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户id',
    `code` VARCHAR(32) NOT NULL COMMENT '类型标识（计划表存的就是它）：内置 asset/life/wish，自定义 c+随机串',
    `name` VARCHAR(32) NOT NULL COMMENT '类型名称（显示用，可改）',
    `sort_order` INT NOT NULL DEFAULT 100 COMMENT '排序：内置 0/1/2，自定义往后排',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_code` (`user_id`, `code`),
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存钱计划的类型（内置 + 用户自定义）';
