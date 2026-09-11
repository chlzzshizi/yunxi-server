package com.yunxi.interfaces.controller;


import com.yunxi.application.dto.StaffIdentity;
import com.yunxi.application.service.StaffAuthAppService;
import com.yunxi.common.Result;
import com.yunxi.interfaces.security.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 认证接口 —— 员工登录/登出。
 *
 * 这一层现在只做两件事：把请求体拆成参数、把身份签成 token。
 * "能不能登录"的规则在 StaffAuthAppService，"怎么查库"在 StaffRepositoryImpl ——
 * 它不再 import 任何 infrastructure 的东西。
 */
@RestController
@RequestMapping("/api/auth/staff")
public class AuthController {

    private final StaffAuthAppService staffAuthAppService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public AuthController(StaffAuthAppService staffAuthAppService, JwtUtil jwtUtil,
                          StringRedisTemplate redisTemplate) {
        this.staffAuthAppService = staffAuthAppService;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录
     * POST /api/auth/staff/login
     * Body: { "username": "admin", "password": "admin123" }
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        Result<StaffIdentity> auth = staffAuthAppService.login(
                body.get("username"), body.get("password"));
        if (auth.code() != 200) {
            // 失败原因（401 用户名或密码错误 / 403 账号已被停用）原样传给前端
            return Result.fail(auth.code(), auth.message());
        }
        // 签 token 留在接口层：JWT 是"这套 HTTP 接口怎么携带身份"的约定，
        // 跟 Authorization 头同级，不该下沉到应用层去
        StaffIdentity who = auth.data();
        String token = jwtUtil.generateToken(who.staffId(), who.username(),
                who.role(), who.storeId());
        return Result.ok(Map.of("token", token));
    }

    /**
     * 登出
     * POST /api/auth/staff/logout
     * Header: Authorization: Bearer <token>
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        // 加入 Redis 黑名单，过期时间 = Token 剩余有效期
        // 简化：存 24 小时
        redisTemplate.opsForValue().set(
                "blacklist:token:" + token,
                "1",
                24, TimeUnit.HOURS
        );
        return Result.ok(null);
    }
}
