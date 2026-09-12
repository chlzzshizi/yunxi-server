package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.CustomerProfileView;
import com.yunxi.application.dto.CustomerView;
import com.yunxi.application.service.CustomerAppService;
import com.yunxi.application.service.CustomerProfileAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.interfaces.dto.LookupOrCreateRequest;
import com.yunxi.interfaces.dto.UpdateProfileRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客接口 —— 一个**混合前缀**，两类人各走各的方法。
 *
 * 与 CustomerAuthController 的区别：那个在 /api/auth/customer/**，是**顾客自己**
 * 注册登录（无 token 门槛，因为它就是来拿 token 的）。两个前缀长得像，别混。
 *
 * 这个类里两种身份并存，看着别扭，但它和 /api/orders 是同一种结构：
 *   · /lookup-or-create  员工代客建档（顾客 token 401）
 *   · /me                顾客自己看/改档案（员工 token 401）
 * 共同点是**资源都是"顾客"**，所以前缀相同；差别是**谁在操作**，
 * 所以按方法判断身份，而不是按前缀。
 *
 * 为什么不分两个 Controller 各挂一半：那样 /api/customers/** 到底有谁能用，
 * 得翻两个文件才拼得出来。混在一处、每人一个 requireXxx，是能一眼看完的。
 *
 * 鉴权三道，各管各的：
 *   · 无 token / token 无效 → 401（JwtInterceptor）
 *   · 管理员 → 403（JwtInterceptor 的角色闸门，按 URL 收口，不在这重复判断）
 *   · 身份不对 → 401（下面各方法自己判断，"请使用员工/顾客账号"）
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerAppService customerAppService;
    private final CustomerProfileAppService customerProfileAppService;

    public CustomerController(CustomerAppService customerAppService,
                              CustomerProfileAppService customerProfileAppService) {
        this.customerAppService = customerAppService;
        this.customerProfileAppService = customerProfileAppService;
    }

    /**
     * 手机号查顾客，没有就建档 —— 员工建门店单前先拿到 customerId。
     * 例：POST /api/customers/lookup-or-create  body {"phone":"13700000001","name":"张三"}
     *
     * **为什么是 POST 而不是 GET**：它会写库。GET 是"安全方法"的承诺，
     * 而浏览器预取、网关重试、用户狂点刷新 —— 任何一个都会凭空多出一堆顾客。
     * 换个角度说：即使语义上"查"占九成，只要那剩下的一成有副作用，就不该是 GET。
     */
    @PostMapping("/lookup-or-create")
    public Result<CustomerView> lookupOrCreate(@RequestBody LookupOrCreateRequest request,
                                               HttpServletRequest http) {
        if (!"staff".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
        // 建档店取自 token，不取请求体（§6.4 身份铁律）。
        // 管理员这里是 null，但他已经在拦截器里被 403 了，到不了这一行
        Long storeId = (Long) http.getAttribute("storeId");
        return customerAppService.lookupOrCreate(request.phone(), request.name(), storeId);
    }

    // ──────────────── 以下两个是顾客自助（个人中心） ────────────────

    /**
     * 看自己的档案 —— 个人中心进页面时读一次。
     * 例：GET /api/customers/me
     *
     * 为什么需要这个接口：登录只回一个 token，里面没有姓名。前端要显示
     * "你好，张三"，除了再查一次没有别的办法。
     */
    @GetMapping("/me")
    public Result<CustomerProfileView> getMyProfile(HttpServletRequest http) {
        return customerProfileAppService.getProfile(requireCustomer(http));
    }

    /**
     * 改自己的名字 —— 个人中心点保存。
     * 例：PUT /api/customers/me  body {"name":"张三"}
     *
     * 用 PUT 而不是 POST：改的是"我"这个资源的 name，同一个请求发十次，
     * 结果和发一次一样（幂等）。POST 的语义是"新建一个东西"，而这里不新建任何东西。
     *
     * **customerId 必须从 token 取**，不能收请求体里的（UpdateProfileRequest
     * 里压根没有这个字段，就是为了不给这个机会）。这一行是本功能唯一的安全边界 ——
     * 应用层的 rename 没有第二道锁（它就是要覆盖旧名字），改错了人是拦不住的。
     */
    @PutMapping("/me")
    public Result<CustomerProfileView> updateMyProfile(@RequestBody UpdateProfileRequest request,
                                                       HttpServletRequest http) {
        return customerProfileAppService.updateName(requireCustomer(http), request.name());
    }

    /**
     * 取顾客身份 —— 身份只信 token，不信请求体。
     *
     * 与 OrderController 里的同名方法是一个写法：员工拿顾客 token 调这里要明确
     * 说"请使用顾客账号"，而不是让他带着一个查不到的 customerId 往下走到 404。
     */
    private Long requireCustomer(HttpServletRequest http) {
        if (!"customer".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用顾客账号登录");
        }
        return (Long) http.getAttribute("customerId");
    }
}

