package com.yunxi.interfaces.dto;

/**
 * 门店单建档请求。
 *
 * 只有手机号和姓名两个字段 —— 没有 storeId：建档店取自员工 token，
 * 请求体只提供业务数据（§6.4 身份铁律）。真放了 storeId，
 * 就等于给"把顾客挂到别的店名下"开了一条口子。
 *
 * 也没有密码：门店单顾客是柜台建档，不上线登录。
 * 他想要密码，走顾客注册（那时 phone 唯一键会认出他来）。
 */
public record LookupOrCreateRequest(
        String phone,
        String name
) {}
