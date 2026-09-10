package com.yunxi.interfaces.dto;

import java.util.List;

/**
 * 设置价格请求体。
 *
 * 用数组而不是固定三个字段（plainPrice/refinedPrice/ironPrice）：
 * 前端只需要传"改动的那几项"（改普洗价时精洗是后端算的），
 * 固定字段会逼着前端把没改的值也回传一遍，回传错了就静默覆盖。
 */
public record SavePricesRequest(
        List<PriceItemRequest> prices
) {}
