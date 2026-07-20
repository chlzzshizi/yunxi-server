-- ============================================
-- V2__add_coupons.sql  折扣券抢购
-- ============================================

-- 15. 折扣券
CREATE TABLE coupons (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)   NOT NULL COMMENT '券名称，如"5折洗护券"',
    discount    DECIMAL(3,2)  NOT NULL COMMENT '折扣率，0.50=5折',
    total_stock INT           NOT NULL COMMENT '总库存',
    start_time  DATETIME      NOT NULL COMMENT '开抢时间',
    end_time    DATETIME      NOT NULL COMMENT '截止时间',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=未开始 2=进行中 3=已结束',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='折扣券';

-- 16. 抢券记录
CREATE TABLE coupon_grabs (
    id          BIGINT    NOT NULL AUTO_INCREMENT COMMENT '主键',
    coupon_id   BIGINT    NOT NULL COMMENT '折扣券ID',
    customer_id BIGINT    NOT NULL COMMENT '客户ID',
    grab_time   DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抢券时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_customer (coupon_id, customer_id),
    KEY idx_coupon_id (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢券记录';
