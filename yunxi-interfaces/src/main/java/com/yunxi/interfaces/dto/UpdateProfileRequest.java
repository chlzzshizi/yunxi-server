package com.yunxi.interfaces.dto;

/**
 * 个人中心改名请求。
 *
 * 只有 name 一个字段 —— **没有 customerId**。改谁的名字由 token 决定，
 * 请求体只提供业务数据（§6.4 身份铁律）。这里和建档那个 DTO 是同一个道理，
 * 但赌注更大：建档的 storeId 被人改了，最多是把顾客挂错店；这里的 customerId
 * 被人改了，就是**直接改别人的档案**。所以这个字段压根不给你传的机会。
 *
 * 也没有 phone：手机号是账号本身，改它等于换账号。那是另一个功能
 * （要重新验手机号、要处理唯一键冲突），不在今天这版里。
 */
public record UpdateProfileRequest(
        String name
) {}
