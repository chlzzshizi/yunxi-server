package com.yunxi.application.service;

import com.yunxi.application.dto.CustomerProfileView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import org.springframework.stereotype.Service;

/**
 * 顾客个人中心 —— 顾客**看/改自己**的档案。
 *
 * 至此顾客域有三个应用服务，按**谁在操作**分，别搞混：
 *   · CustomerAuthAppService    —— 顾客自己**进门**（注册、登录、发 token）
 *   · CustomerProfileAppService —— 顾客自己**改资料**（就是这里）
 *   · CustomerAppService        —— **员工代客**建档（柜台，不设密码）
 *
 * 为什么进门和改资料要分成两个类：两者都会"写 customers 表"，但规则正相反。
 * 注册要查手机号查重（同一个号只能有一个账号），个人中心压根不管手机号
 * （手机号是账号本身，改它等于换账号，没做那个功能）。合成一个类，第一个 if
 * 就得先问"这次是注册还是改资料"，之后每改一处都要同时想两遍 ——
 * 和 CustomerAppService 的 javadoc 里那条理由是同一条。
 *
 * **本类唯一的安全假设：传进来的 customerId 必须来自 token**。
 * rename 的 SQL 没有第二道锁（它就是要覆盖旧名字），所以这里没有任何东西
 * 挡得住"改别人的名字"—— 如果哪天有人把 customerId 改成从请求体里读，
 * 这行代码会照常工作、照常成功，只是改错了人。接口层那一行的注释不是装饰。
 */
@Service
public class CustomerProfileAppService {

    private final CustomerRepository customerRepository;

    public CustomerProfileAppService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /** 看自己的档案。token 里只有 customerId，名字和手机号得查库 */
    public Result<CustomerProfileView> getProfile(Long customerId) {
        requireLoggedIn(customerId);
        return Result.ok(CustomerProfileView.of(load(customerId)));
    }

    /**
     * 改自己的名字。
     *
     * 名字是**必填**的：这是"设置姓名"这个动作，不是"补全"（补全那条路在
     * CustomerAppService，判据是"档案里有没有"）。个人中心点保存却传个空名字，
     * 意思只能是"把名字清掉"，而档案里留个空名字没有任何用处 ——
     * 直接 400 比默默写个空串诚实。
     *
     * 顺序是「先便宜的纯校验、后碰库」，和 CustomerAppService 里"手机号为空
     * 连库都不用查"是同一条：坏数据连一次主键查询都不值当。
     */
    public Result<CustomerProfileView> updateName(Long customerId, String name) {
        requireLoggedIn(customerId);
        if (name == null || name.isBlank()) {
            throw new BusinessException("姓名不能为空");
        }
        Customer.requireValidName(name);
        Customer customer = load(customerId);   // 到这里才碰库
        // 错误一律抛 BusinessException（不用 return Result.fail）：那是个正常返回，
        // @Transactional 看到它不会回滚。这里眼下只有一条 UPDATE 不需要事务，
        // 但"失败要能被事务看见"这个性质现在就得是对的，
        // 别等哪天加了 @Transactional 才发现半次改名被提交了
        customerRepository.rename(customerId, name);
        customer.setName(name);   // 回给前端的带上改完的名字，前端不用再查一次
        return Result.ok(CustomerProfileView.of(customer));
    }

    /**
     * customerId 为 null 是**接口层漏了校验**的迹象（token 里正常一定有），
     * 报 401 而不是 404：那不是"这个顾客不存在"，是"你没带身份来"。
     *
     * 单独拎出来而不是塞进 load()：两个方法一个要"先校验参数再碰库"、
     * 一个只查库，混在一起就得靠调用顺序去保证，那顺序将来会被改掉。
     */
    private void requireLoggedIn(Long customerId) {
        if (customerId == null) {
            throw new BusinessException(401, "请使用顾客账号登录");
        }
    }

    /** 按 id 取顾客，取不到就报错（前提：customerId 已非空） */
    private Customer load(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException(404, "顾客不存在"));
    }
}
