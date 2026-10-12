USE finview;

-- 种子数据（可重复执行）。
--
-- 本文件在每次启动时都会执行（spring.sql.init.mode=always），所以：
--   * 只写幂等语句（INSERT IGNORE / ON DUPLICATE KEY UPDATE / 先查后插），
--   * **绝不写 DROP TABLE**——历史上这里 DROP 过 asset/asset_series/nav_trend/saving_plans/plan_asset，
--     现在这些表都承载真实数据（asset_series 由 SeriesService 逐日生成），再 DROP 就是删用户数据。
--
-- 测试账号（admin/demo）由 DataInitializer 用 PasswordEncoder 现算密码后插入，不在这里写。
-- 资产、净值、每日序列都由业务逻辑生成，不需要种子数据。

-- ---------- 存钱计划：界面已能新建（存钱计划页 →「新建计划」），这里只是「想直接插库」的样例 ----------
-- 完成度不再是存下来的字段：它在读接口里按关联资产的最新市值算（SavingsService.toResponse），
-- 所以下面不再写 current_amount 列（该列已删）。
-- 计划 ↔ 资产是多对多，靠 plan_asset 关联；「总资产」计划（type='asset'）不用关联 ——
-- 它默认包含该用户全部未归档资产。给小愿望挂资产的样例（幂等，可重复执行）：
-- INSERT INTO `saving_plans` (user_id, name, description, type, target_amount,
--                             start_date, target_date, monthly_plan_amount)
-- SELECT 7, '30岁资产目标', '财务自由，从现在开始', 'asset', 600000,
--        '2026-01-01', '2030-12-31', 5000
--   FROM DUAL
--  WHERE NOT EXISTS (SELECT 1 FROM `saving_plans` WHERE user_id = 7 AND name = '30岁资产目标');
--
-- INSERT IGNORE INTO `plan_asset` (user_id, plan_id, asset_id)
-- SELECT a.user_id, (SELECT id FROM `saving_plans` WHERE user_id = 7 AND name = '一次旅行'), a.id
--   FROM `asset` a
--  WHERE a.user_id = 7 AND a.code IN ('013402', '161725');

-- ---------- 资产分布（/assets/distribution）依赖 asset 的分类与三个标签列，没有净值链路的资产也需要手工补 ----------
-- UPDATE `asset` SET category = 'fund', industry = '宽基指数', region = 'A股', currency = '人民币 CNY'
--  WHERE user_id = 7 AND code = '013402';
