-- ============================================
-- V4__seed_store_manager.sql  插入演示门店 + 店长账号
--
-- 为什么需要：
--   V3 只种了 admin（role=0 管理员，store_id 按设计为 NULL）。
--   订单是"门店维度"的事务 —— 创建订单、推进状态、列表查询都要用到
--   token 里的 storeId。管理员没有门店，所以订单接口会明确拒绝他。
--   演示和验收都需要一个真正挂在门店下的店长账号。
--
-- 账号：manager / admin123
--   （password 字段复用 V3 里 admin123 的 BCrypt 哈希，两个账号同密码）
-- ============================================

-- 1. 演示门店（显式指定 id=1，方便下面的店长引用）
INSERT INTO stores (id, name, address, phone, status)
VALUES (1, '云洗中央门店', '浙江省杭州市西湖区文一西路 100 号', '0571-88888888', 1);

-- 2. 店长（role=1，挂在 1 号门店下）
INSERT INTO staff (username, password, name, role, store_id, phone, status)
VALUES ('manager',
        '$2a$10$fEzKJTH469Zd9GB0CKMLseS/iFVndCGene.WQiQ53Q/isi2yZa5oS',
        '门店店长',
        1,
        1,
        '13800000001',
        1);
