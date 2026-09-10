package com.yunxi.application.dto;

import java.math.BigDecimal;

/**
 * 要写入的一条价格 —— **入参**（应用层的命令对象）。
 *
 * 它是"请求里的一条价格"这个应用层概念，不是领域里的 ClothesPrice：
 * 领域那条带着"这个组合存不存在、可不可用（price=0 即不支持）"，
 * 而这里只是"调用方想把这个洗涤方式定成这个价"的意图。
 * 让 controller 直接 new 领域对象传进来就是接口层引用 domain，分层破了。
 *
 * categoryId 不在这里：它走 URL 路径参数（PUT /api/prices/{categoryId}），
 * 一条请求只改一个分类，明细里再带一次 categoryId 只会带来"两处不一致听谁的"。
 */
public record PriceItem(
        Long washTypeId,
        BigDecimal price
) {}
