-- V9：顾客姓名改为可空（2026-09-12 口径）
--
-- 背景：顾客线上注册**只要手机号 + 密码**，不再要求填姓名。
-- 但 V1 建的 customers.name 是 NOT NULL 且无默认值，不传姓名 INSERT 直接报
-- "Field 'name' doesn't have a default value"。
--
-- 为什么不是"写入空字符串":  '' 和 NULL 在 SQL 里是两回事 ——
-- `WHERE name IS NULL` 查不到 ''，`WHERE name = ''` 也查不到 NULL。
-- 用 '' 糊过去等于往库里塞了个假值，之后所有的"有没有名字"判断都要写两遍。
--
-- 改完之后两种顾客的名字来源就分开了，各自都说得通：
--   网单顾客（线上注册）   name 为 NULL  —— 线上确实不知道他是谁
--   门店单顾客（柜台建档）  name 必填     —— 店员面对面，知道名字（应用层校验）
-- 线上顾客后来到店，店员建档时会**只在没名字时**把名字补上（见 CustomerAppService）
ALTER TABLE customers
    MODIFY COLUMN name VARCHAR(20) DEFAULT NULL COMMENT '姓名（网单注册可空，柜台建档必填）';
