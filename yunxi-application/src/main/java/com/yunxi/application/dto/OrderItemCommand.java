package com.yunxi.application.dto;

/**
 * 建单时的一条明细 —— **入参**（应用层的命令对象）。
 *
 * 为什么不让 controller 直接 new 一个 domain 的 OrderItem 传进来：
 * 那是"接口层引用 domain"，分层就破了（见 OrderItemView 的注释）。
 * 命令对象是应用层的入口形状：调用方说什么，应用层再翻译成领域语言。
 *
 * 没有 unitPrice 字段：价格由后端查价目表算（OrderAppService.priceItems），
 * 请求里就算带了也不作数 —— 老前端多传这个字段不会报错，会被忽略。
 *
 * 字段用包装类型（Long/Integer）而不是基本类型：这样"没传"和"传了 0"
 * 是两件事，能各自给出可读的 400，而不是 NPE 变成 500。
 */
public record OrderItemCommand(
        Long categoryId,
        Long washTypeId,
        Integer quantity,
        String photos
) {}
