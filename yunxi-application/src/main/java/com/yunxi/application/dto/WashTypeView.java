package com.yunxi.application.dto;

import com.yunxi.domain.price.WashType;

/**
 * 洗涤方式的**出参**（固定 3 种，由 V1 建表时插入，不可增删 —— 设计文档 §4.5）。
 */
public record WashTypeView(
        Long id,
        String name,
        String description
) {

    public static WashTypeView from(WashType washType) {
        return new WashTypeView(
                washType.getId(),
                washType.getName(),
                washType.getDescription());
    }
}
