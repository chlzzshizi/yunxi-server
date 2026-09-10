package com.yunxi.common.enums;

/**
 * 订单状态 —— 码值连号 1~7（2026-09-11 口径）。
 *
 * 4 是唯一分叉点，之后门店单走 5、网单走 6，两条路都在 7 汇合成终态：
 *   门店单 1→2→3→4→5→7   网单 1→2→3→4→6→7
 *
 * 为什么把原来的「7 已送达 / 8 已取件」压成「7 已完成」：
 *   - 「已送达」是物流视角，「已取件/已完成」是业务视角，两者对同一笔订单
 *     只是先后发生的两件事，却要顾客点两次确认，纯属自找麻烦
 *   - 删掉一格后码值重新连号，前端"第几步/共几步"能直接拿码值算，不用查映射表
 *
 * 注意：状态码会落库（orders.status 存的是 code，不是枚举名），
 * 所以改码值必须配一条数据迁移（见 V8），否则历史行的 code 会变成"没有这个状态"。
 */
public enum OrderStatus {
    PENDING_PAY(1,"待支付"),
    PAID(2,"已支付"),
    WASHING(3,"洗涤中"),
    PENDING_DELIVERY(4, "待出厂"),
    PENDING_PICKUP(5, "待取件"),
    DELIVERING(6, "派送中"),
    COMPLETED(7, "已完成");
    private final int code;
    private final String name;

    OrderStatus(int code, String name) {
        this.code = code;
        this.name = name;
    }
    /**
     * 数字码 → 枚举。
     * 注意抛的是 IllegalArgumentException（Exception 的子类），
     * 不是 IllegalAccessError（Error 的子类，GlobalExceptionHandler 的
     * @ExceptionHandler(Exception.class) 接不住，会漏成非 JSON 的 500）。
     * 与 OrderSource.fromCode / PayMethod.fromCode 保持一致。
     */
    public static OrderStatus fromCode(int code){
        for(OrderStatus s : values()){
            if(s.code == code)return s;
        }
        throw new IllegalArgumentException("没有这个状态:"+code);
    }
    public int getCode() {return code;}
    public String getName(){return name;}
}
