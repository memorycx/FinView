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
                        `update_series_time` DATETIME DEFAULT NULL COMMENT 'asset_series 上次推进到的日期，见 SeriesService.advance'
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

-- ============================== 2. adjustments 资产调整记录表 ==============================
CREATE TABLE IF NOT EXISTS `adjustments` (
                               `adjustment_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '调整记录ID',
                               `user_id` BIGINT NOT NULL COMMENT '用户id',
                               `code` VARCHAR(64) NOT NULL COMMENT '资产编码',
                               `date` DATE NOT NULL COMMENT '记录日期',
                               `reason` VARCHAR(255) COMMENT '变动原因',
                               `action` TINYINT NOT NULL COMMENT '动作：1定投开始，2结束定投，3一笔收入，4一笔支出',
                               `frequency` VARCHAR(64) COMMENT '频率',
                               `amount` DECIMAL(18,4) NOT NULL COMMENT '金额',
                               `note` VARCHAR(512) COMMENT '备注',
                               FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
                               INDEX idx_user_code (`user_id`, `code`, `date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产调整记录表';

-- ============================== 3. nav_trend 外部获取净值表 ==============================
CREATE TABLE IF NOT EXISTS `nav_trend` (
                             `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                             `day` DATE NOT NULL COMMENT '日期',
                             `value` DECIMAL(18,4) NOT NULL COMMENT '单位净值',
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

-- ============================== 4. saving_plans 储蓄计划表 ==============================
-- 先建计划表：asset.plan_id 指向它。
CREATE TABLE IF NOT EXISTS `saving_plans` (
                                `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                                `user_id` BIGINT NOT NULL COMMENT '用户id',
                                `name` VARCHAR(100) NOT NULL COMMENT '计划名称',
                                `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
                                `type` VARCHAR(64) DEFAULT NULL COMMENT '计划类型：asset 总资产目标 / wish 小愿望',
                                `image` VARCHAR(255) DEFAULT NULL COMMENT '头图地址',
                                `target_amount` DECIMAL(18,4) NOT NULL COMMENT '目标金额',
                                `start_date` DATE DEFAULT NULL COMMENT '计划开始日期',
                                `target_date` DATE DEFAULT NULL COMMENT '计划目标日期',
                                `monthly_plan_amount` DECIMAL(18,4) DEFAULT NULL COMMENT '每月计划投入',
                                `quote` VARCHAR(255) DEFAULT NULL COMMENT '备注引言',
                                `note` TEXT COMMENT '详细备注',
                                `note_image` VARCHAR(255) DEFAULT NULL COMMENT '备注图片',
                                `current_amount` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '当前已存金额',
                                FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储蓄计划表';

-- ============================== 5. asset 资产主表 ==============================
-- active / frequency / startDate / principal / total 五列是 fold 出来的快照（SeriesService 维护），
-- name / category / plan_id / industry / region / currency / archived / market 是用户维护的元数据，fold 不覆盖。
CREATE TABLE IF NOT EXISTS `asset` (
                         `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                         `user_id` BIGINT NOT NULL COMMENT '用户id',
                         `name` VARCHAR(100) NOT NULL COMMENT '资产名称',
                         `code` VARCHAR(64) NOT NULL COMMENT '资产编码',
                         `category` VARCHAR(64) DEFAULT NULL COMMENT '资产分类：fund/stock/bond/cash',
                         `active` TINYINT DEFAULT 1 COMMENT '定投是否进行中 1是0否',
                         `archived` TINYINT NOT NULL DEFAULT 0 COMMENT '是否归档 1是0否（用户维护的元数据，fold 不覆盖）',
                         `frequency` VARCHAR(64) DEFAULT NULL COMMENT '当前定投频率',
                         `rate` DECIMAL(18,4) DEFAULT NULL COMMENT '申购费率(%)，买入时按 金额×rate/100 扣手续费；NULL 或 0 表示不计费',
                         `startDate` DATE DEFAULT NULL COMMENT '计划开始日期',
                         `principal` DECIMAL(18,4) NOT NULL COMMENT '累计投入本金（快照）',
                         `total` DECIMAL(18,4) NOT NULL COMMENT '当前市值 = 份额 × 最新净值（快照）',
                         `plan_id` BIGINT DEFAULT NULL COMMENT '所属存钱计划id，NULL 表示不计入任何计划',
                         `industry` VARCHAR(64) DEFAULT NULL COMMENT '行业标签，供 /assets/distribution 使用',
                         `region` VARCHAR(64) DEFAULT NULL COMMENT '区域标签，供 /assets/distribution 使用',
                         `currency` VARCHAR(64) DEFAULT NULL COMMENT '币种标签，供 /assets/distribution 使用',
                         `market` VARCHAR(16) DEFAULT NULL COMMENT '标的市场：us / hk；NULL = 只看 A 股日历（A 股基金）',
                         FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
                         UNIQUE KEY idx_user_code (`user_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产表';

-- 兼容历史库：plan_id / industry / region / currency / archived 都是后加的列。
SET @has_plan_id = (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asset' AND COLUMN_NAME = 'plan_id');
SET @ddl = IF(@has_plan_id = 0,
              'ALTER TABLE `asset` ADD COLUMN `plan_id` BIGINT DEFAULT NULL COMMENT ''所属存钱计划id'' AFTER `total`',
              'DO 0');
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

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
              'ALTER TABLE `asset` ADD COLUMN `industry` VARCHAR(64) DEFAULT NULL COMMENT ''行业标签'' AFTER `plan_id`, ADD COLUMN `region` VARCHAR(64) DEFAULT NULL COMMENT ''区域标签'' AFTER `industry`, ADD COLUMN `currency` VARCHAR(64) DEFAULT NULL COMMENT ''币种标签'' AFTER `region`',
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

-- 老库把 principal / total 的列注释写反了（「当前净值」/「当前总投入金额」），这里纠正成真实语义。
-- 类型保持原样，只改注释，可重复执行。
SET @ddl = 'ALTER TABLE `asset`
    MODIFY COLUMN `principal` DECIMAL(18,4) NOT NULL COMMENT ''累计投入本金（快照）'',
    MODIFY COLUMN `total` DECIMAL(18,4) NOT NULL COMMENT ''当前市值 = 份额 × 最新净值（快照）''';
PREPARE migrate_asset FROM @ddl;
EXECUTE migrate_asset;
DEALLOCATE PREPARE migrate_asset;

-- ============================== 6. asset_series 资产每日序列表 ==============================
-- 由 SeriesService 按《表设计.md》的核心更新流程逐日推进写入：
--   amount    当日净投入 = 当日周期扣款 + 一笔收入 − 一笔支出（可为负）
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

-- ============================== 7. plan_asset 已废弃 ==============================
-- 表设计.md 用 asset.plan_id 表达「资产属于哪个存钱计划」，plan_asset 不再需要。
-- 这张表在本项目代码里从未被读写过，旧库里若有残留直接清掉。
DROP TABLE IF EXISTS `plan_asset`;
