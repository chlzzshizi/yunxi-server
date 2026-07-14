package com.yunxi.common;

public record Result<T>(int code, String message, T data) {
    public  static <T> Result<T> ok(T data) {
        return new Result<>(200,"success",data);
    }
    public  static Result<Void> fail(int code, String message) {
        return new Result<>(code,message,null);
    }
}
