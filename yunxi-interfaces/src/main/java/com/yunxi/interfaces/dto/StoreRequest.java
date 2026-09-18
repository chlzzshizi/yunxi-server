package com.yunxi.interfaces.dto;

/**
 * 门店的名字 / 地址 / 电话 —— 新建（POST /api/stores）与修改（PUT /api/stores/{id}）**共用**。
 *
 * 一个类管两件事，因为这三个字段在两种场合**完全一样**（不像员工：建号要
 * username+password，改资料不要）。硬拆成 CreateStoreRequest / UpdateStoreRequest
 * 只会得到两个字段一字不差的类，它们唯一的区别是"我们打算怎么用它"——
 * 而那个区别已经由 HTTP 方法和 URL 说清楚了，不需要类型再说一遍。
 *
 * 没有 status：停业走 PUT /api/stores/{id}/status。停业是个有独立后果的动作
 * （这家店立刻从顾客的选店列表里消失），不该混在"改个电话"里顺手发生。
 */
public record StoreRequest(
        String name,
        String address,
        String phone
) {}
