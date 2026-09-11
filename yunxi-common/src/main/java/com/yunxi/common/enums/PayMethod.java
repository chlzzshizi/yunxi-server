package com.yunxi.common.enums;

import com.yunxi.common.BusinessException;

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
        // 抛 BusinessException 而不是 IllegalArgumentException —— 完整理由见 OrderStatus.fromCode：
        // 业务消息不该借用 JDK 公共异常类型，否则 GlobalExceptionHandler 分不清
        // 哪句是"要讲给用户听的话"、哪句是框架的内部报错（2026-09-12 BCrypt 泄漏）
        throw new BusinessException("没有这个支付方式: " + code);
    }

    public String getCode() { return code; }
    public String getName() { return name; }
}
