package com.yunxi.interfaces.dto;

/**
 * 修改员工资料请求体（PUT /api/staff/{id}，仅管理员）。
 *
 * **没有 username，也没有 status** —— 两个都是刻意的缺席，不是漏了：
 *
 * · username 建后不可改。它是登录身份，还签在 token 的 subject 里
 *   （JwtUtil.getUsername 读的就是它），改了会让手上所有 token 的"我是谁"变错。
 *   要换用户名 = 建新号 + 停用旧号。**这里没有这个字段，所以传了也不会生效** ——
 *   比"文档里写一句别改"强。
 *
 * · status 走 PUT /api/staff/{id}/status。理由不是洁癖：停用有一个这里没有的
 *   副作用 —— 作废该员工已签发的**全部** token。把 status 混在普通字段里，
 *   等于让一次静默的"全设备下线"搭着一次改姓名一起发生。
 */
public record UpdateStaffRequest(
        String name,
        Integer role,
        Long storeId,
        String phone
) {}
