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

-- ---------- 存钱计划：目前没有创建入口，需要时在这里手工插一条（改成自己的 user_id） ----------
-- 计划的 current_amount 是独立字段，不会自动等于关联资产市值；
-- 关联资产靠 asset.plan_id 指向计划 id，例：
-- INSERT INTO `saving_plans` (user_id, name, description, type, target_amount,
--                             start_date, target_date, monthly_plan_amount, current_amount)
-- SELECT 7, '30岁资产目标', '财务自由，从现在开始', 'asset', 600000,
--        '2026-01-01', '2030-12-31', 5000, 0
--   FROM DUAL
--  WHERE NOT EXISTS (SELECT 1 FROM `saving_plans` WHERE user_id = 7 AND name = '30岁资产目标');
--
-- UPDATE `asset` SET plan_id = (SELECT id FROM `saving_plans` WHERE user_id = 7 AND name = '30岁资产目标')
--  WHERE user_id = 7 AND code IN ('013402', '161725');

-- ---------- 资产分布（/assets/distribution）依赖 asset 的分类与三个标签列，没有净值链路的资产也需要手工补 ----------
-- UPDATE `asset` SET category = 'fund', industry = '宽基指数', region = 'A股', currency = '人民币 CNY'
--  WHERE user_id = 7 AND code = '013402';
