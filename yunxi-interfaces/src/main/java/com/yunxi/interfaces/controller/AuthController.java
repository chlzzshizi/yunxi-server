package com.yunxi.interfaces.controller;


import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.mapper.StaffMapper;
import com.yunxi.infrastructure.persistence.po.StaffPO;
import com.yunxi.interfaces.security.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 认证接口 —— 员工登录/登出。
 */
@RestController
@RequestMapping("/api/auth/staff")
public class AuthController {

    private final StaffMapper staffMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    public AuthController(StaffMapper staffMapper, JwtUtil jwtUtil,
                          StringRedisTemplate redisTemplate) {
        this.staffMapper = staffMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录
     * POST /api/auth/staff/login
     * Body: { "username": "admin", "password": "admin123" }
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        // 1. 查用户
        StaffPO staff = staffMapper.selectByUsername(username);
        if (staff == null) {
            return Result.fail(401, "用户名或密码错误");
        }
        // 2. 验证码是否停用
        if (staff.getStatus() == 0) {
            return Result.fail(403, "账号已被停用");
        }
        // 3. 验证密码
        if (!passwordEncoder.matches(password, staff.getPassword())) {
            return Result.fail(401, "用户名或密码错误");
        }
        // 4. 生成 Token
        String token = jwtUtil.generateToken(staff.getId(), staff.getUsername(), staff.getRole());

        // 5. 返回
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
