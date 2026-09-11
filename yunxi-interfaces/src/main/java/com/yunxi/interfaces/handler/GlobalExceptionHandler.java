package com.yunxi.interfaces.handler;

import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器 —— 把所有异常转成统一的 Result.fail() 格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 → 返回自定义 code 和 message */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数不合法 → 400。
     *
     * **刻意不回显 e.getMessage()**（2026-09-12，Bug 23）。
     * 这条通道接的是 JDK / Spring 自己的异常，消息是英文技术细节、写给开发者的：
     * 比如 BCryptPasswordEncoder.matches(null, ..) 抛的 "rawPassword cannot be null"，
     * 原样回显等于把"我们用的什么密码库、内部怎么报错"讲给前端听。
     * 项目自己的业务消息一律走 BusinessException（理由见 OrderStatus.fromCode 的注释），
     * 那条通道才是"要讲给用户听的话"。原文只进日志：日志是给自己排查用的，不怕技术细节。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.fail(400, "参数不正确");
    }

    /** 缺少必填请求参数（如 pay 少了 amount）→ 400，而不是 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(400, "缺少参数: " + e.getParameterName());
    }

    /** 参数类型不对（如 id=abc、amount=xyz）→ 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {} = {}", e.getName(), e.getValue());
        return Result.fail(400, "参数格式不正确: " + e.getName());
    }

    /** 请求体读不出来（JSON 语法错、日期格式错、字段类型不匹配）→ 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(400, "请求体格式不正确");
    }

    /** 静态资源不存在（浏览器自动请求 favicon.ico 等）→ 返回 404，不视为服务器错误 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return Result.fail(404, "资源不存在");
    }

    /** 兜底：其他所有未知异常 → 500 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("服务器内部错误", e);
        return Result.fail(500, "服务器内部错误");
    }
}