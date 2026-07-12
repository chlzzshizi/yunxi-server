package com.yunxi.common.enums;

public enum OrderStatus {
    PENDING_PAY(1,"待支付"),
    PAID(2,"已支付"),
    WASHING(3,"洗涤中"),
    PENDING_DELIVERY(4, "待出厂"),
    PENDING_PICKUP(5, "待取件"),
    DELIVERING(6, "派送中"),
    DELIVERED(7, "已送达"),
    PICKED_UP(8, "已取件");
    private final int code;
    private final String name;

    OrderStatus(int code, String name) {
        this.code = code;
        this.name = name;
    }
public static OrderStatus fromCode(int code){
        for(OrderStatus s : values()){
            if(s.code == code)return s;
        }
        throw new IllegalAccessError("没有这个状态:"+code);
}
    public int getCode() {return code;}
    public String getName(){return name;}
}
