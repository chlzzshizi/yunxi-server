package com.yunxi.interfaces.dto;

/**
 * 重置某个员工的密码（PUT /api/staff/{id}/password，仅管理员）。
 *
 * 为什么需要这个接口：`员工管理`的"改"里如果没有它，管理员建号时把密码打错
 * 一个字符就**没有补救路径**（只能改库）。这不是锦上添花，是建号功能的一半。
 *
 * 没有 userId / staffId 字段 —— 改谁由 URL 路径参数决定（§6.4 身份铁律：
 * 请求体只提供业务数据，不提供"我是谁/改谁"）。和 UpdateProfileRequest 同一个道理。
 *
 * 副作用：作废该员工**已签发的全部** token。改密码的整个语义就是
 * "从前的凭据不算数了"，不作废等于密码改了但别人的会话还活着。
 * （也是被盗号后的标准处置：改密码 = 全设备下线。）
 */
public record ResetPasswordRequest(
        String password
) {}
