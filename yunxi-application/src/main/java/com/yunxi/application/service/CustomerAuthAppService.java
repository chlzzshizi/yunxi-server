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
     *
     * **只要手机号 + 密码**（2026-09-12 口径）：线上注册不填姓名。
     * 名字的来源分两条，各自都说得通：网单顾客线上注册时没有名字（确实不知道他是谁），
     * 后来到店时由店员在柜台建档补上（见 CustomerAppService）；门店单顾客
     * 本来就是店员面对面建档的，姓名必填。DDL 上 name 可空（V9）。
     */
    public Result<CustomerIdentity> register(String phone, String password) {
        // 1. 空值校验（不校验的话 password 为 null 会一路传到 BCrypt 里炸掉）
        if (phone == null || phone.isBlank()
                || password == null || password.isBlank()) {
            return Result.fail(400, "手机号和密码不能为空");
        }
        // 2. 手机号已存在时要分两种 —— 这是本项目一个真实堵过的死循环：
        //    门店单顾客（柜台建档，有名字、**没密码**）想线上注册，早期会走到
        //    "该手机号已注册，请直接登录"；而他去登录，又会得到
        //    "该手机号未设置密码，请先注册"。**两句话互相指着对方，他永远进不来。**
        //    所以：有密码 = 真重复注册；没密码 = 给他设密码，也就是激活。
        //
        //    （注册是手机号+密码、没有验证码的，"别人拿我手机号注册"这个风险
        //      在正常注册路径上本来就有，激活这条路没有让它变坏。）
        Customer existing = customerRepository.findByPhone(phone).orElse(null);
        if (existing != null) {
            if (existing.hasPassword()) {
                return Result.fail(400, "该手机号已注册，请直接登录");
            }
            if (!customerRepository.updatePassword(
                    existing.getId(), passwordEncoder.encode(password))) {
                // CAS 命中 0 行：这一瞬间别人抢先激活了。回同一句话，
                // 因为对他而言事实就是"这个号已经有密码了"
                return Result.fail(400, "该手机号已注册，请直接登录");
            }
            return Result.ok(new CustomerIdentity(existing.getId(), phone));
        }
        // 3. BCrypt 加密后入库（Bug 5 教训：哈希必须由 encoder 生成，不能手写）
        Customer customer = new Customer();
        customer.setPhone(phone);
        customer.setPasswordHash(passwordEncoder.encode(password));
        // name 不设、storeId 留空：线上注册的顾客没有姓名也没有门店归属
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
        // 顾客存的哈希有了，但请求里可能压根没带密码 —— matches(null, ..) 抛
        // IllegalArgumentException，会被全局处理器当成"参数不合法"回 400，
        // 而不是"登录失败"。放在这一行之前，紧挨着真正需要它的那次调用。
        // 注意别把这句提到 hasPassword 之前：门店单顾客该看到的是"请先注册"，不是"密码错误"。
        if (password == null || password.isBlank()) {
            return Result.fail(401, "手机号或密码错误");
        }
        if (!passwordEncoder.matches(password, customer.getPasswordHash())) {
            return Result.fail(401, "手机号或密码错误");
        }
        return Result.ok(new CustomerIdentity(customer.getId(), phone));
    }
}
