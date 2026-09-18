package com.yunxi.interfaces.dto;

/**
 * 新建员工请求体（POST /api/staff，仅管理员）。
 *
 * 没有 status：新建一律启用。做一个"建出来就是停用的账号"是两步动作
 * （建 + 停），塞进创建请求体只是给前端多一个可以传错的字段。
 *
 * role 是**数字码**（0=管理员 1=店长），不是 "ADMIN"/"MANAGER" —— 这是本仓库
 * 既有口径：枚举在**返回体**里是名字、在**请求参数**里是数字（README 专门写过，
 * Bug 28 就是这条没对齐来的）。别"顺手统一"，前端两套都按这个来。
 *
 * 这里只有字段，没有任何校验注解 —— 全项目没有一个 jakarta.validation 注解，
 * 校验一律手写（必填在应用层、长度在 domain 的静态方法里）。
 */
public record CreateStaffRequest(
        String username,
        String password,     // 明文，应用层负责 BCrypt
        String name,
        Integer role,
        Long storeId,        // 可为空：管理员必须为空，店长允许为空
        String phone
) {}
