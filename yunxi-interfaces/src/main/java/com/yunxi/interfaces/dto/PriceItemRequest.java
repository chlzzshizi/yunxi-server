package com.yunxi.interfaces.dto;

import java.math.BigDecimal;

/**
 * 一条价格请求 —— 嵌套 Record（写法同 OrderItemRequest）。
 * 分类 id 由路径参数给出，这里只带"哪种洗涤方式、多少钱"。
 */
public record PriceItemRequest(
        Long washTypeId,
        BigDecimal price
) {}
