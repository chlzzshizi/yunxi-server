-- ============================================
-- V5__pricing_fulfillment_coupon.sql
-- 为三块新能力补表结构：券-订单抵扣、分拣/上架的幂等写入
-- DDL 与种子数据拆两个文件（V6）：种子最可能要微调，
-- 而 Flyway 校验和不允许修改已执行过的文件
-- ============================================

-- 1. 抢券记录增加"用掉没有"——"抢到手"和"用在订单上"是两个不同的状态
ALTER TABLE coupon_grabs
    ADD COLUMN used TINYINT NOT NULL DEFAULT 0 COMMENT '0=未使用 1=已使用' AFTER grab_time,
    ADD KEY idx_customer_used (customer_id, used);

-- 2. 订单记录用了哪张券、抵了多少钱
--    total_amount 存的是**折后应付**：pay()/updateStatus()/finalPay() 三处都拿
--    paid_amount 与 total_amount 比"付清没"。若 total_amount 保持折前价，
--    用券的顾客到收银台会被要求付全款；改了语义另两处比较就不用动。
--    discount_amount 只作展示与对账，不参与任何状态判断。
ALTER TABLE orders
    ADD COLUMN discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00
        COMMENT '券抵扣金额（total_amount 已是折后应付）' AFTER total_amount,
    ADD COLUMN coupon_id BIGINT DEFAULT NULL COMMENT '使用的优惠券ID' AFTER pay_method;

-- 3. 分拣/上架：一条订单明细最多一行记录
--    唯一键既是数据约束（防脏数据），也是幂等写入的抓手——
--    INSERT ... ON DUPLICATE KEY UPDATE 靠它把"前端双击"变成一次更新而非两行
ALTER TABLE sorting  ADD UNIQUE KEY uk_item_id (item_id);
ALTER TABLE shelving ADD UNIQUE KEY uk_item_id (item_id);
