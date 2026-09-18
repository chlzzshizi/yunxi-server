package com.yunxi.application.dto;

/**
 * 修改员工资料（姓名 / 角色 / 门店 / 手机号）—— **入参**。
 *
 * 两个**故意的缺席**：
 *
 * · **没有 username**。它是登录身份，还签在 token 的 subject 里
 *   （JwtUtil.getUsername 读的就是它），建后不可改 —— 改了会让手上所有 token
 *   的"我是谁"变成错的。要换用户名 = 建新号 + 停用旧号。
 *   这里没有这个字段，比"文档里写一句别改"强：从类型上就传不进来。
 *
 * · **没有 status**。启用/停用走 PUT /api/staff/{id}/status，单独一个端点。
 *   理由不是洁癖：停用有一个这里没有的副作用 —— **踢掉这个人已签发的 token**
 *   （见 StaffTokenRevoker）。把 status 混进普通字段里，等于让一次静默的
 *   "全设备下线"搭着一次改姓名一起发生。
 *
 * 同理，改 role 和 storeId 也会作废 token（token 里存着旧值），
 * 但那两样**就是这个接口的业务本身**，没法拆开，所以在应用层比较后触发。
 */
public record UpdateStaffCommand(
        String name,
        Integer role,
        Long storeId,
        String phone
) {}
