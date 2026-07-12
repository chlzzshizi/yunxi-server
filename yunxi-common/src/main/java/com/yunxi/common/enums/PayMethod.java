package com.yunxi.common.enums;

public enum PayMethod {

    CASH("cash", "现金"),
    WECHAT("wechat", "微信"),
    ALIPAY("alipay", "支付宝"),
    BALANCE("balance", "余额");

    private final String code;
    private final String name;

    PayMethod(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static PayMethod fromCode(String code) {
        for (PayMethod m : values()) {
            if (m.code.equals(code)) return m;
        }
        throw new IllegalArgumentException("没有这个支付方式: " + code);
    }

    public String getCode() { return code; }
    public String getName() { return name; }
}
