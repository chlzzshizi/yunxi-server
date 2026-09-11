package com.yunxi.common.enums;

import com.yunxi.common.BusinessException;

public enum OrderSource {

    STORE(1, "门店单"),   // 顾客到店，员工操作
    ONLINE(2, "网单");    // 顾客在线下单

    private final int code;
    private final String name;

    OrderSource(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static OrderSource fromCode(int code) {
        for (OrderSource s : values()) {
            if (s.code == code) return s;
        }
        // 抛 BusinessException 而不是 IllegalArgumentException —— 完整理由见 OrderStatus.fromCode：
        // 业务消息不该借用 JDK 公共异常类型，否则 GlobalExceptionHandler 分不清
        // 哪句是"要讲给用户听的话"、哪句是框架的内部报错（2026-09-12 BCrypt 泄漏）
        throw new BusinessException("没有这个来源: " + code);
    }

    public int getCode() { return code; }
    public String getName() { return name; }
}