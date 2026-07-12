-- ============================================
-- V1__init.sql  云洗助手 初始化建表
-- Flyway 会在应用启动时按版本号顺序自动执行
-- ============================================

-- 1. 门店
CREATE TABLE stores (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(50)  NOT NULL COMMENT '门店名称',
    address    VARCHAR(200) NOT NULL COMMENT '地址',
    phone      VARCHAR(20)  DEFAULT NULL COMMENT '电话',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=营业 0=停用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店';

-- 2. 员工（店长/管理员）—— staff 表自登录，替代旧项目查 users 表
CREATE TABLE staff (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(30)  NOT NULL COMMENT '用户名，登录用',
    password   VARCHAR(255) NOT NULL COMMENT '密码，BCrypt 加密',
    name       VARCHAR(20)  NOT NULL COMMENT '姓名',
    role       TINYINT      NOT NULL DEFAULT 1 COMMENT '0=管理员 1=店长',
    store_id   BIGINT       DEFAULT NULL COMMENT '所属门店，管理员为 NULL',
    phone      VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_store_id (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工';

-- 3. 客户（手机号唯一标识，余额>0即为会员，替代旧项目 users+members）
CREATE TABLE customers (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    store_id    BIGINT       DEFAULT NULL COMMENT '注册门店',
    name        VARCHAR(20)  NOT NULL COMMENT '姓名',
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号，唯一标识',
    password    VARCHAR(255) DEFAULT NULL COMMENT '密码（网单注册时设置）',
    birthday    DATE         DEFAULT NULL COMMENT '生日',
    gender      TINYINT      DEFAULT NULL COMMENT '0=未知 1=男 2=女',
    balance     DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '余额，>0即为会员',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone),
    KEY idx_store_id (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户';

-- 4. 客户交易流水（充值/消费）
CREATE TABLE customer_transactions (
    id            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    customer_id   BIGINT         NOT NULL COMMENT '客户ID',
    type          TINYINT        NOT NULL COMMENT '1=充值 2=消费 3=退款',
    amount        DECIMAL(10,2)  NOT NULL COMMENT '金额',
    balance_after DECIMAL(10,2)  NOT NULL COMMENT '交易后余额',
    remark        VARCHAR(200)   DEFAULT NULL COMMENT '备注',
    create_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_customer_id (customer_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户交易流水';

-- 5. 订单
CREATE TABLE orders (
    id                BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no          VARCHAR(32)    NOT NULL COMMENT '订单编号',
    store_id          BIGINT         NOT NULL COMMENT '门店ID',
    customer_id       BIGINT         NOT NULL COMMENT '客户ID',
    staff_id          BIGINT         DEFAULT NULL COMMENT '操作员工ID',
    source            TINYINT        NOT NULL DEFAULT 1 COMMENT '1=门店单 2=网单',
    status            TINYINT        NOT NULL DEFAULT 1 COMMENT '1=待支付 2=已支付 3=洗涤中 4=待出厂 5=待取件 6=派送中 7=已送达 8=已取件',
    total_amount      DECIMAL(10,2)  NOT NULL COMMENT '总金额',
    paid_amount       DECIMAL(10,2)  NOT NULL DEFAULT 0.00 COMMENT '已付金额',
    pay_method        VARCHAR(20)    DEFAULT NULL COMMENT '支付方式：cash/wechat/alipay/balance',
    final_pay_method  VARCHAR(20)    DEFAULT NULL COMMENT '洗后付结账方式',
    appointment_time  DATETIME       DEFAULT NULL COMMENT '预约取送时间（网单）',
    delivery_address  VARCHAR(200)   DEFAULT NULL COMMENT '配送地址（网单）',
    express_no        VARCHAR(50)    DEFAULT NULL COMMENT '快递单号（网单）',
    remark            VARCHAR(500)   DEFAULT NULL COMMENT '备注',
    finish_time       DATETIME       DEFAULT NULL COMMENT '完成时间',
    create_time       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_store_id (store_id),
    KEY idx_customer_id (customer_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- 6. 订单明细
CREATE TABLE order_items (
    id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id     BIGINT         NOT NULL COMMENT '订单ID',
    category_id  BIGINT         NOT NULL COMMENT '衣物分类ID',
    wash_type_id BIGINT         NOT NULL COMMENT '洗涤方式ID',
    quantity     INT            NOT NULL DEFAULT 1 COMMENT '数量',
    unit_price   DECIMAL(10,2)  NOT NULL COMMENT '单价',
    barcode      VARCHAR(50)    DEFAULT NULL COMMENT '条码（门店单）',
    photos       VARCHAR(1000)  DEFAULT NULL COMMENT '衣物照片URL，逗号分隔（网单）',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- 7. 衣物分类（parent_id 自引用实现层级树）
CREATE TABLE clothes_categories (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(30)  NOT NULL COMMENT '分类名称，如"上衣""裤子"',
    icon       VARCHAR(200) DEFAULT NULL COMMENT '图标URL',
    parent_id  BIGINT       DEFAULT NULL COMMENT '父分类ID，NULL=顶级分类',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='衣物分类';

-- 8. 洗涤方式（固定 3 种）
CREATE TABLE wash_types (
    id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    name         VARCHAR(30)    NOT NULL COMMENT '名称：普洗/精洗/单熨',
    description  VARCHAR(200)   DEFAULT NULL COMMENT '描述',
    create_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='洗涤方式';

-- 插入 3 种固定洗涤方式
INSERT INTO wash_types (id, name, description) VALUES
(1, '普洗', '标准水洗'),
(2, '精洗', '深度清洁+护理，价格=普洗价+20元'),
(3, '单熨', '仅熨烫');

-- 9. 定价（category + wash_type 联合唯一）
-- 规则：精洗自动=普洗价+20（后端计算写入），不支持的选项价格存 0.00，前端看到 0 即置灰不可选
CREATE TABLE clothes_prices (
    id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_id  BIGINT         NOT NULL COMMENT '衣物分类ID',
    wash_type_id BIGINT         NOT NULL COMMENT '洗涤方式ID',
    price        DECIMAL(10,2)  NOT NULL COMMENT '价格，不支持=0.00，精洗=普洗价+20',
    create_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_wash (category_id, wash_type_id),
    KEY idx_category_id (category_id),
    KEY idx_wash_type_id (wash_type_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定价';

-- 10. 分拣
CREATE TABLE sorting (
    id         BIGINT    NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id   BIGINT    NOT NULL COMMENT '订单ID',
    item_id    BIGINT    NOT NULL COMMENT '订单明细ID',
    staff_id   BIGINT    NOT NULL COMMENT '操作员工ID',
    status     TINYINT   NOT NULL DEFAULT 0 COMMENT '0=未分拣 1=已分拣',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分拣';

-- 11. 上架
CREATE TABLE shelving (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id   BIGINT       NOT NULL COMMENT '订单ID',
    item_id    BIGINT       NOT NULL COMMENT '订单明细ID',
    staff_id   BIGINT       NOT NULL COMMENT '操作员工ID',
    shelf_no   VARCHAR(20)  DEFAULT NULL COMMENT '货架编号',
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未上架 1=已上架',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上架';

-- 12. 取送任务
CREATE TABLE pickup_delivery (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id        BIGINT       NOT NULL COMMENT '订单ID',
    type            TINYINT      NOT NULL COMMENT '1=取件 2=送件',
    staff_id        BIGINT       NOT NULL COMMENT '操作员工ID',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=处理中 2=已完成',
    address         VARCHAR(200) NOT NULL COMMENT '地址',
    express_company VARCHAR(30)  DEFAULT NULL COMMENT '快递公司',
    express_no      VARCHAR(50)  DEFAULT NULL COMMENT '快递单号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='取送任务';

-- 13. 地址簿（网单客户保存常用地址）
CREATE TABLE address_book (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    customer_id BIGINT       NOT NULL COMMENT '客户ID',
    address     VARCHAR(200) NOT NULL COMMENT '详细地址',
    type        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=家 2=公司 3=其他',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '0=否 1=默认',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_customer_id (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地址簿';

-- 14. 操作日志
CREATE TABLE operation_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    staff_id    BIGINT        NOT NULL COMMENT '操作员工ID',
    staff_name  VARCHAR(20)   NOT NULL COMMENT '员工姓名（冗余，防止员工删除后日志丢失）',
    action      VARCHAR(50)   NOT NULL COMMENT '操作：CREATE/UPDATE/DELETE/LOGIN/PAY等',
    target_type VARCHAR(30)   NOT NULL COMMENT '操作对象：ORDER/CUSTOMER/STAFF等',
    target_id   BIGINT        DEFAULT NULL COMMENT '操作对象ID',
    detail      VARCHAR(1000) DEFAULT NULL COMMENT '详情JSON',
    ip          VARCHAR(50)   DEFAULT NULL COMMENT '操作IP',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_staff_id (staff_id),
    KEY idx_target (target_type, target_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';
