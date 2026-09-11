package com.yunxi.application.dto;

/**
 * 登录/注册成功后交给接口层的顾客身份 —— 刚好够签一个 token。
 *
 * 同样不含 passwordHash。注意它和后续"顾客建档"要用的 CustomerView 不是一回事：
 * 那个是给员工看的顾客档案，这个是刚通过认证的身份。
 */
public record CustomerIdentity(Long customerId, String phone) {
}
