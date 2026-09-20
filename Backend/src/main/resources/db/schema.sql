-- FinView 后端建表脚本（MySQL 8.x，幂等，可重复执行）

-- ==================== 用户表 ====================
CREATE TABLE IF NOT EXISTS `user` (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    username     VARCHAR(64)  NOT NULL COMMENT '用户名，唯一',
    password     VARCHAR(128) NOT NULL COMMENT 'BCrypt 加密密码',
    email        VARCHAR(128) NULL COMMENT '邮箱',
    nickname     VARCHAR(64)  NULL COMMENT '昵称',
    role         VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色: USER / ADMIN',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户';

-- 定投计划
CREATE TABLE IF NOT EXISTS fund (
    id          VARCHAR(32)  NOT NULL COMMENT '计划 id，如 csi300',
    name        VARCHAR(100) NOT NULL COMMENT '计划名称',
    code        VARCHAR(32)  NOT NULL COMMENT '基金/组合代码',
    category    VARCHAR(16)  NOT NULL COMMENT '资产大类: fund/stock/bond/cash',
    active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否进行中',
    frequency   VARCHAR(16)  NOT NULL COMMENT '定投频率: daily/weekly/monthly',
    amount      INT          NOT NULL DEFAULT 0 COMMENT '当前每期金额（元）',
    start_date  VARCHAR(7)   NOT NULL COMMENT '开始时间 YYYY-MM',
    principal   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计投入本金（元）',
    `current`   BIGINT       NOT NULL DEFAULT 0 COMMENT '当前市值（元）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定投计划';

-- 定投计划本金/市值走势
CREATE TABLE IF NOT EXISTS fund_series (
    fund_id    VARCHAR(32) NOT NULL COMMENT '定投计划 id',
    day        DATE        NOT NULL COMMENT '数据点日期',
    principal  BIGINT      NOT NULL DEFAULT 0 COMMENT '累计本金（元）',
    total      BIGINT      NOT NULL DEFAULT 0 COMMENT '总市值（元）',
    PRIMARY KEY (fund_id, day),
    CONSTRAINT fk_fund_series_fund FOREIGN KEY (fund_id) REFERENCES fund (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定投走势';

-- 定投调整记录
CREATE TABLE IF NOT EXISTS plan_adjustment (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    fund_id          VARCHAR(32)  NOT NULL COMMENT '定投计划 id',
    adjustment_id    VARCHAR(16)  NOT NULL COMMENT '记录业务 id，如 a1',
    adjustment_date  DATE         NOT NULL COMMENT '调整日期',
    reason           VARCHAR(255) NOT NULL COMMENT '更改原因',
    action           VARCHAR(64)  NOT NULL COMMENT '调整动作',
    frequency        VARCHAR(16)  NULL COMMENT '调整后频率，NULL=停止定投',
    amount           INT          NULL COMMENT '调整后每期金额，NULL=停止定投',
    note             VARCHAR(500) NULL COMMENT '备注',
    sort_order       INT          NOT NULL DEFAULT 0 COMMENT '同计划内排序',
    PRIMARY KEY (id),
    UNIQUE KEY uk_adjustment (fund_id, adjustment_id),
    CONSTRAINT fk_adjustment_fund FOREIGN KEY (fund_id) REFERENCES fund (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定投调整记录';

-- 存钱计划
CREATE TABLE IF NOT EXISTS savings_plan (
    id                  VARCHAR(32)  NOT NULL COMMENT '计划 id',
    name                VARCHAR(100) NOT NULL COMMENT '计划名称',
    description         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '描述',
    type                VARCHAR(16)  NOT NULL COMMENT 'asset=总资产目标 / wish=小愿望',
    image               VARCHAR(500) NULL COMMENT '详情页头图 URL',
    current_amount      BIGINT       NOT NULL DEFAULT 0 COMMENT '已存金额（元）',
    target_amount       BIGINT       NOT NULL DEFAULT 0 COMMENT '目标金额（元）',
    start_date          DATE         NOT NULL COMMENT '开始日期',
    target_date         DATE         NOT NULL COMMENT '目标日期',
    monthly_plan_amount INT          NOT NULL DEFAULT 0 COMMENT '每月计划存入（元）',
    quote               VARCHAR(255) NULL COMMENT '引言',
    note                VARCHAR(500) NULL COMMENT '备注',
    note_image          VARCHAR(500) NULL COMMENT '备注配图 URL',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='存钱计划';

-- 存钱计划内持有的资产
CREATE TABLE IF NOT EXISTS savings_asset (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id     VARCHAR(32)  NOT NULL COMMENT '存钱计划 id',
    name        VARCHAR(100) NOT NULL COMMENT '资产名称',
    amount      BIGINT       NOT NULL DEFAULT 0 COMMENT '金额（元）',
    color_var   VARCHAR(32)  NULL COMMENT '图表主题色 CSS 变量',
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_savings_asset_order (plan_id, sort_order),
    KEY idx_savings_asset_plan (plan_id),
    CONSTRAINT fk_savings_asset_plan FOREIGN KEY (plan_id) REFERENCES savings_plan (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='存钱计划资产';

-- 存钱计划月度存入走势
CREATE TABLE IF NOT EXISTS savings_series (
    plan_id     VARCHAR(32) NOT NULL COMMENT '存钱计划 id',
    month       VARCHAR(7)  NOT NULL COMMENT '月份 YYYY-MM',
    amount      BIGINT      NOT NULL DEFAULT 0 COMMENT '当月累计金额（元）',
    sort_order  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (plan_id, month),
    CONSTRAINT fk_savings_series_plan FOREIGN KEY (plan_id) REFERENCES savings_plan (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='存钱计划月度走势';

-- 资产分布（行业 / 区域 / 币种）
CREATE TABLE IF NOT EXISTS asset_distribution (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    dim         VARCHAR(16)  NOT NULL COMMENT '维度: industry/region/currency',
    name        VARCHAR(64)  NOT NULL COMMENT '分布项名称',
    value       BIGINT       NOT NULL DEFAULT 0 COMMENT '市值（元）',
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_distribution (dim, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资产分布';
