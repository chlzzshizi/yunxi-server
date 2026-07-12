package com.yunxi.common.enums;

public class BusinessException extends RuntimeException {
    private  final int code;
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
    public BusinessException(String message) {
        this(400, message);
    }
    public int getCode() {return code;}
}
