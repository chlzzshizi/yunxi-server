package com.yunxi.interfaces.controller;


import com.yunxi.application.dto.CustomerIdentity;
import com.yunxi.application.service.CustomerAuthAppService;
import com.yunxi.common.Result;
import com.yunxi.interfaces.security.JwtUtil;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口 —— 顾客注册/登录（注册即登录）。
 *
 * 这一层现在只做两件事：把请求体拆成参数、把身份签成 token。
 * "手机号能不能注册""没密码的顾客怎么提示"都在 CustomerAuthAppService 里 ——
 * 它不再 import 任何 infrastructure 的东西。
 */
@RestController
@RequestMapping("/api/auth/customer")
public class CustomerAuthController {

    private final CustomerAuthAppService customerAuthAppService;
    private final JwtUtil jwtUtil;

    public CustomerAuthController(CustomerAuthAppService customerAuthAppService,
                                  JwtUtil jwtUtil) {
        this.customerAuthAppService = customerAuthAppService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 注册（注册即登录，直接返回 Token）
     * POST /api/auth/customer/register
     * Body: { "name": "张三", "phone": "13800138000", "password": "123456" }
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody Map<String, String> body) {
        Result<CustomerIdentity> auth = customerAuthAppService.register(
                body.get("name"), body.get("phone"), body.get("password"));
        return toTokenResult(auth);
    }

    /**
     * 登录
     * POST /api/auth/customer/login
     * Body: { "phone": "13800138000", "password": "123456" }
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        Result<CustomerIdentity> auth = customerAuthAppService.login(
                body.get("phone"), body.get("password"));
        return toTokenResult(auth);
    }

    /**
     * 认证结果 → token 响应。
     *
     * 注册和登录除了调用的服务方法不同，后半段完全一样，所以收成一处：
     * 两处各写一遍的话，将来改 token 的返回结构就会漏掉一个。
     */
    private Result<Map<String, String>> toTokenResult(Result<CustomerIdentity> auth) {
        if (auth.code() != 200) {
            // 失败原因（400 该手机号已注册 / 401 手机号或密码错误 …）原样传给前端
            return Result.fail(auth.code(), auth.message());
        }
        CustomerIdentity who = auth.data();
        String token = jwtUtil.generateCustomerToken(who.customerId(), who.phone());
        return Result.ok(Map.of("token", token));
    }
}
