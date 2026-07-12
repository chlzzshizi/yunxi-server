package com.yunxi.common.enums;

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
        throw new IllegalArgumentException("没有这个来源: " + code);
    }

    public int getCode() { return code; }
    public String getName() { return name; }
}