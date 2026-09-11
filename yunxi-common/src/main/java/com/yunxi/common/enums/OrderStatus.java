package com.yunxi.common.enums;

import com.yunxi.common.BusinessException;

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
     *
     * 抛 BusinessException（项目自己的业务异常），**不是 IllegalArgumentException**。
     * 后者是 JDK 的公共类型，Spring 内部也在用，GlobalExceptionHandler 分不清
     * 哪条消息是"我们要讲给用户听的话"、哪条是"框架的内部报错"。
     * 2026-09-12 踩过：BCryptPasswordEncoder.matches(null, ..) 抛的
     * IllegalArgumentException 带着英文原文 "rawPassword cannot be null" 直接漏给前端。
     * 既然那条通道是给我们自己的消息用的，就不该借用公共类型。
     *
     * BusinessException 是 Exception 的子类（不是 IllegalAccessError 那种 Error），
     * GlobalExceptionHandler 接得住，返回体仍是 JSON。
     * 与 OrderSource.fromCode / PayMethod.fromCode / StaffRole.fromCode 保持一致。
     */
    public static OrderStatus fromCode(int code){
        for(OrderStatus s : values()){
            if(s.code == code)return s;
        }
        throw new BusinessException("没有这个状态: " + code);
    }
    public int getCode() {return code;}
    public String getName(){return name;}
}
