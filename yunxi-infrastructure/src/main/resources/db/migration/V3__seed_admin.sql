-- ============================================
-- V3__seed_admin.sql  插入测试管理员
-- 密码：admin123（BCrypt 加密）
-- ============================================

INSERT INTO staff (username, password, name, role, phone, status)
VALUES ('admin',
        '$2a$10$fEzKJTH469Zd9GB0CKMLseS/iFVndCGene.WQiQ53Q/isi2yZa5oS',
        '系统管理员',
        0,
        '13800000000',
        1);
