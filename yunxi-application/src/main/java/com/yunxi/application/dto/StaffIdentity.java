package com.yunxi.application.dto;

/**
 * 登录成功后交给接口层的员工身份 —— 刚好够签一个 token，不多不少。
 *
 * 为什么不直接把 Staff 给出去：Staff 是 domain 对象，按 §3.1 的硬规则
 * domain 对象不出现在 controller 的签名里。而且这个 DTO 里**没有 passwordHash**，
 * 从类型上就杜绝了哈希被顺手带进响应的可能。
 */
public record StaffIdentity(Long staffId, String username, int role, Long storeId) {
}
