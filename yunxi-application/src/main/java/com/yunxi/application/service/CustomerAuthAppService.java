package com.yunxi.application.service;

import com.yunxi.application.dto.CustomerIdentity;
import com.yunxi.common.Result;
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 顾客认证应用服务 —— 注册（注册即登录）与登录。
 *
 * 这段逻辑原来长在 CustomerAuthController 里，直接拿 CustomerMapper 查库。
 */
@Service
public class CustomerAuthAppService {

    private final CustomerRepository customerRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public CustomerAuthAppService(CustomerRepository customerRepository,
                                  BCryptPasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册 —— 成功即登录，返回足以签 token 的身份。
     */
    public Result<CustomerIdentity> register(String name, String phone, String password) {
        // 1. 空值校验（不校验的话 password 为 null 会一路传到 BCrypt 里炸掉）
        if (name == null || name.isBlank()
                || phone == null || phone.isBlank()
                || password == null || password.isBlank()) {
            return Result.fail(400, "姓名、手机号、密码不能为空");
        }
        // 2. 手机号查重 —— 这只是快速路径。并发下两个请求可能同时过这一关，
        //    真正拦得住的是下头第三步的唯一键，所以第 3 步必须接住冲突
        if (customerRepository.findByPhone(phone).isPresent()) {
            return Result.fail(400, "该手机号已注册，请直接登录");
        }
        // 3. BCrypt 加密后入库（Bug 5 教训：哈希必须由 encoder 生成，不能手写）
        Customer customer = new Customer();
        customer.setName(name);
        customer.setPhone(phone);
        customer.setPasswordHash(passwordEncoder.encode(password));  // storeId 留空
        Long customerId;
        try {
            customerId = customerRepository.save(customer);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时过了查重，uk_phone 拦下后一个
            return Result.fail(400, "该手机号已注册，请直接登录");
        }
        return Result.ok(new CustomerIdentity(customerId, phone));
    }

    /**
     * 登录。
     *
     * 和员工登录一样，"手机号不存在"与"密码错误"回同一句，防手机号枚举。
     */
    public Result<CustomerIdentity> login(String phone, String password) {
        Customer customer = customerRepository.findByPhone(phone).orElse(null);
        if (customer == null) {
            return Result.fail(401, "手机号或密码错误");
        }
        // 门店单顾客（柜台建档）从来没设过密码，无法在线登录。
        // 判断在 Customer.hasPassword 上：不挡的话 null 传给 BCrypt 会抛异常
        if (!customer.hasPassword()) {
            return Result.fail(401, "该手机号未设置密码，请先注册");
        }
        if (!passwordEncoder.matches(password, customer.getPasswordHash())) {
            return Result.fail(401, "手机号或密码错误");
        }
        return Result.ok(new CustomerIdentity(customer.getId(), phone));
    }
}
