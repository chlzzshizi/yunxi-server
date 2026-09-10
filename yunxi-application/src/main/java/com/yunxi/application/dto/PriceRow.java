package com.yunxi.application.dto;

import java.math.BigDecimal;

/**
 * 价目表的一行 —— **读模型**，把"分类 × 洗涤方式"拼平成页面能直接渲染的形状。
 *
 * 为什么放 dto 而不是 domain：它不表达业务规则，只表达"前端想要的样子"。
 * 页面要几列它就长几列，改版时它跟着变，而领域对象不该为了展示改来改去。
 *
 * categoryName / washTypeName 是冗余进来的：请求方要的是"衬衫 普洗 15.00"，
 * 让它自己拿 id 去三张表里 JOIN，等于把拼装逻辑复制到每一个调用方
 * （前端、以后可能的报表、app）。
 *
 * supported 同样是把领域规则（价格 0 = 不支持）搬过来，规则本体仍在
 * {@code ClothesPrice.isSupported()}，这里只是搬运，不另立一套判断。
 */
public record PriceRow(
        Long categoryId,
        String categoryName,
        Long parentId,          // 前端按它分组：同一父分类的叶子归到一起
        Long washTypeId,
        String washTypeName,
        BigDecimal price,
        boolean supported       // false → 前端置灰不可选
) {}
