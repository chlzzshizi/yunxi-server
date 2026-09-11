package com.yunxi.common.enums;

import com.yunxi.common.BusinessException;

/**
 * 员工角色 —— 与 staff.role 列、JWT 的 role claim 同一个码。
 *
 * 权限模型（设计文档 §4.2，2026-09-11 拍板）：
 *   管理员：只管人与店 —— 增删改查店长/员工、管理门店、系统设置；
 *          **不参与订单操作，也不能改价**
 *   店长：  管**所有门店**的订单（门店只是地理位置的标记，不做数据隔离），并负责定价
 *
 * 写成枚举而不是到处 `role == 0`：那个 0 是"管理员"还是"店长"，
 * 读代码的人每次都得回去翻表。
 *
 * 注意：这里只放"角色是什么"，不放"某个接口归谁管" ——
 * 后者是 URL 级的闸门，在 JwtInterceptor 里统一收口（见 checkRoleGate）。
 */
public enum StaffRole {

    ADMIN(0, "系统管理员"),
    MANAGER(1, "店长");

    private final int code;
    private final String name;

    StaffRole(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static StaffRole fromCode(int code) {
        for (StaffRole r : values()) {
            if (r.code == code) return r;
        }
        // 抛 BusinessException 而不是 IllegalArgumentException —— 完整理由见 OrderStatus.fromCode：
        // 业务消息不该借用 JDK 公共异常类型，否则 GlobalExceptionHandler 分不清
        // 哪句是"要讲给用户听的话"、哪句是框架的内部报错（2026-09-12 BCrypt 泄漏）
        throw new BusinessException("没有这个角色: " + code);
    }

    public int getCode() { return code; }
    public String getName() { return name; }
}

