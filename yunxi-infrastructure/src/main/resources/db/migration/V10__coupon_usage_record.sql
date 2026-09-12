-- ============================================
-- V10__coupon_usage_record.sql
-- 券的**使用记录**：这张券什么时候、被谁、用在哪张订单上核销的
--
-- 为什么需要它：2026-09-12 起门店单也能用券（原先只有网单能用，见设计文档 §5.8）。
-- 门店单的 customerId 来自请求体，员工**可以**替顾客核销券，而顾客当场未必知情 ——
-- 技术上没有任何东西能证明"顾客本人同意"。兜底只有这份记录：
--
--     记录 != 授权。它拦不住这件事，只能让它在事后可查。
--
-- 为什么不建独立流水表：uk_coupon_customer 保证一人一券只抢一次、用也只一次，
-- "一次抢券 <-> 一次消费"是**一对一**，记录长在抢券行上天然不会出现一对多。
-- 将来若支持一券分次抵扣，再拆表。
--
-- 为什么不做外键（与 orders.store_id / customer_id 同款取舍）：这三列是**审计快照**，
-- 记的是"写入那一刻发生了什么"。不该被后来的改名/删除牵动，也不该挡着删号。
-- ============================================

ALTER TABLE coupon_grabs
    ADD COLUMN used_time     DATETIME DEFAULT NULL
        COMMENT '核销时刻（与 used 在同一条 CAS 语句里写入）' AFTER used,
    ADD COLUMN used_order_id BIGINT   DEFAULT NULL
        COMMENT '用在哪张订单上' AFTER used_time,
    ADD COLUMN used_staff_id BIGINT   DEFAULT NULL
        COMMENT '经手员工ID：门店单=建单员工，网单顾客自助=NULL' AFTER used_order_id;
