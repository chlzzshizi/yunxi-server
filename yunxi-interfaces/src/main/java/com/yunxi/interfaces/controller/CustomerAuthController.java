package com.yunxi.interfaces.controller;


import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.mapper.CustomerMapper;
import com.yunxi.infrastructure.persistence.po.CustomerPO;
import com.yunxi.interfaces.security.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口 —— 顾客注册/登录（注册即登录）。
 */
@RestController
@RequestMapping("/api/auth/customer")
public class CustomerAuthController {

    private final CustomerMapper customerMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public CustomerAuthController(CustomerMapper customerMapper, JwtUtil jwtUtil) {
        this.customerMapper = customerMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 注册（注册即登录，直接返回 Token）
     * POST /api/auth/customer/register
     * Body: { "name": "张三", "phone": "13800138000", "password": "123456" }
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String phone = body.get("phone");
        String password = body.get("password");

        // 1. 空值校验
        if (name == null || name.isBlank()
                || phone == null || phone.isBlank()
                || password == null || password.isBlank()) {
            return Result.fail(400, "姓名、手机号、密码不能为空");
        }
        // 2. 手机号查重（快速路径；并发下的权威兜底是 uk_phone 唯一键）
        if (customerMapper.selectByPhone(phone) != null) {
            return Result.fail(400, "该手机号已注册，请直接登录");
        }
        // 3. BCrypt 加密后入库（Bug 5 教训：哈希必须由 encoder 生成，不能手写）
        CustomerPO customer = new CustomerPO();
        customer.setName(name);
        customer.setPhone(phone);
        customer.setPassword(passwordEncoder.encode(password));  // storeId 留空
        try {
            customerMapper.insert(customer);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时过了查重，uk_phone 拦下后一个
            return Result.fail(400, "该手机号已注册，请直接登录");
        }
        // 4. 注册即登录：用回填的自增 id 签发顾客 Token
        String token = jwtUtil.generateCustomerToken(customer.getId(), phone);
        return Result.ok(Map.of("token", token));
    }

    /**
     * 登录
     * POST /api/auth/customer/login
     * Body: { "phone": "13800138000", "password": "123456" }
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String password = body.get("password");

        // 1. 查客户
        CustomerPO customer = customerMapper.selectByPhone(phone);
        if (customer == null) {
            return Result.fail(401, "手机号或密码错误");
        }
        // 2. 门店单顾客（到店自动创建）没有密码，无法在线登录——要抢券请先注册
        //    （password 为 null 时直接 matches 会抛异常，必须显式挡掉）
        if (customer.getPassword() == null) {
            return Result.fail(401, "该手机号未设置密码，请先注册");
        }
        // 3. 验证密码
        if (!passwordEncoder.matches(password, customer.getPassword())) {
            return Result.fail(401, "手机号或密码错误");
        }
        // 4. 生成顾客 Token
        String token = jwtUtil.generateCustomerToken(customer.getId(), phone);
        return Result.ok(Map.of("token", token));
    }
}
