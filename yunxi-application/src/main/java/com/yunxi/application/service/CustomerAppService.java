package com.yunxi.application.service;

import com.yunxi.application.dto.CustomerView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 顾客应用服务 —— 门店单建单前的"手机号查 / 建档"。
 *
 * 为什么不复用 CustomerAuthAppService：那个是**顾客自己**注册登录（设密码、发 token），
 * 这里是**员工代客**建档（不设密码 —— 门店单顾客从不上线登录）。两件事长得像，
 * 规则却相反：注册查重后**报错**（"该手机号已注册"），建档查重后**返回老顾客**
 * （店员要的是"把人找到"，不是"这人不能建"）。合成一个类，第一个 if 就得先问
 * "这次是哪一种"，之后每改一处都要同时想两遍。
 *
 * 只有员工能建档：顾客 token 在接口层被挡（401），管理员被 JwtInterceptor 的角色闸门
 * 挡在门外（403）—— 建档是为建单服务的，而管理员不参与日常经营（§4.2）。
 */
@Service
public class CustomerAppService {

    private final CustomerRepository customerRepository;

    public CustomerAppService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * 手机号查顾客，查不到就建档。
     *
     * @param phone   手机号，必填
     * @param name    姓名；"要新建"时必须，且老顾客**没名字**时也会被用它补上
     *                （线上注册的顾客 name 为 NULL，见 V9）。已有名字则一个字都不动
     * @param storeId 建档店（员工 token 里的门店）。设计文档 §7：
     *                customers.store_id = 归属门店（门店单=建档店，网单=配送地址对应的店），
     *                仅作归属标记、不做数据隔离
     *
     * 结果只有一个：**同一个手机号永远是同一个顾客**，且只有第一次 created=true。
     * 正因为重复调用不会多建人，这个接口才敢用 POST。
     */
    public Result<CustomerView> lookupOrCreate(String phone, String name, Long storeId) {
        // 错误一律抛 BusinessException（GlobalExceptionHandler 统一转 Result.fail）——
        // 和 OrderAppService / PriceAppService 一样。这里**特意不用** return Result.fail：
        // 那是个正常返回，@Transactional 看到它不会回滚，而本方法会写库（save）。
        // 眼下还没有事务包围，但"失败要能被事务看见"这个性质现在就得是对的，
        // 不能等哪天给它加上 @Transactional 才发现半条顾客被提交了。
        // （CustomerAuthAppService 用的是 return 那一派 —— 它更早，也没在事务里改数据）
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        // 1. 老顾客：**不覆盖姓名**，但档案里压根没名字时补上。
        //
        //    这两件事看起来矛盾，判据是"这次操作是改写了已有信息，还是补全了缺失信息"：
        //      · 有名字 + 传了别的名字 → **一个字都不动**。柜台顺手打个错别字
        //        不该悄悄改档案，改名字是"顾客管理"的事
        //      · 没名字 + 传了名字     → 补上。线上注册的顾客（V9 起 name 可空）
        //        档案里就是空的，不补他就永远没有名字
        //    MySQL 那边还有第二道锁（UPDATE 带 name IS NULL），漏判也只会"没生效"
        Customer existing = customerRepository.findByPhone(phone).orElse(null);
        if (existing != null) {
            if (isBlank(existing.getName()) && !isBlank(name)) {
                Customer.requireValidName(name);   // 只在这一步真要写库时才校验长度
                customerRepository.fillName(existing.getId(), name);
                existing.setName(name);   // 回给前端的也带上刚补的名字
            }
            return Result.ok(CustomerView.existing(existing));
        }
        // 2. 新顾客：姓名必填
        if (isBlank(name)) {
            throw new BusinessException("新顾客必须填写姓名");
        }
        Customer.requireValidName(name);
        Customer customer = new Customer();
        customer.setName(name);
        customer.setPhone(phone);
        customer.setStoreId(storeId);
        // passwordHash 留空：门店单顾客没有线上密码。
        // 他若哪天想用小程序查进度，走注册流程设密码即可 —— 注册查到手机号已存在
        // 且**没有密码**时会走"激活"分支给他补上（见 CustomerAuthAppService.register）。
        // 这条路径 2026-09-12 之前是**堵的**：注册说"已注册请登录"、登录说
        // "未设置密码请先注册"，两句话互相指着对方，他永远进不来
        try {
            Long id = customerRepository.save(customer);
            return Result.ok(CustomerView.created(id, name, phone));
        } catch (DuplicateKeyException e) {
            // 并发：两个店员同时给同一手机号建档，uk_phone 拦下后一个。
            // 这里**和注册相反** —— 注册撞键要报错（顾客以为注册成功了），
            // 建档撞键只是"别人抢先建了"，那就当老顾客返回，单子照样能建
            Customer raced = customerRepository.findByPhone(phone).orElse(null);
            if (raced == null) {
                throw e;   // 查不到说明不是手机号冲突，别把真异常吞成"老顾客"
            }
            return Result.ok(CustomerView.existing(raced));
        }
    }

    /**
     * null 和空白一律算"没有"。
     *
     * 直接写 `name == null || name.isBlank()` 也行，但这段判断在一处关于
     * "补名字还是不动"的规则里出现两次（老顾客/新顾客各一次），
     * 而这两个条件的语义必须是同一个 —— 写两遍就会有哪天改了一处漏了另一处。
     * 注意这里也把 "" 算作没有：名字是空串，等于没有名字。
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
