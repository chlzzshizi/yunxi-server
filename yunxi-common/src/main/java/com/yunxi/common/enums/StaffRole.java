package com.yunxi.common.enums;

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
        throw new IllegalArgumentException("没有这个角色: " + code);
    }

    public int getCode() { return code; }
    public String getName() { return name; }
}

