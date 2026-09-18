package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.MyCouponView;
import com.yunxi.application.service.CouponAppService;
import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.po.CouponPO;
import com.yunxi.interfaces.dto.CreateCouponRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 折扣券接口 — 店长发券、顾客抢券。
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponAppService couponAppService;

    public CouponController(CouponAppService couponAppService) {
        this.couponAppService = couponAppService;
    }

    /**
     * 店长发券。
     *
     * **身份闸是 2026-09-18 补的（Bug 42）**：原先这里没有任何身份判断 ——
     * `JwtInterceptor.checkRoleGate` 遇到非 staff 直接 `return`（"顾客能用哪些接口
     * 由各 Controller 自己判断"，而本方法忘了判断），于是**任何一张有效票**都能建券：
     * 顾客给自己发一张 0.01 的券 → 定时任务 60 秒内把它推成"进行中" → 抢下来下单抵扣。
     * 影响是**直接的钱**，不是脏数据。
     *
     * 判据与下面两个方法同形（只是方向相反），文案与其余五个 controller 的员工闸一致。
     * 规则留给"店长发券"这句话旁边的代价是：以后新写接口仍要记得加一句 ——
     * 更稳的做法是把"发券只归员工"上移到 `checkRoleGate` 按前缀收口（见 bug-record 的候选修法 B）。
     */
    @PostMapping
    public Result<CouponPO> createCoupon(@RequestBody CreateCouponRequest request,
                                         HttpServletRequest http) {
        if (!"staff".equals(http.getAttribute("type"))) {
            return Result.fail(401, "请使用员工账号操作");
        }
        CouponPO coupon = new CouponPO();
        coupon.setName(request.name());
        coupon.setDiscount(request.discount());
        coupon.setTotalStock(request.totalStock());
        coupon.setStartTime(request.startTime());
        coupon.setEndTime(request.endTime());
        return couponAppService.createCoupon(coupon);
    }

    /** 可用券列表 */
    @GetMapping
    public Result<List<CouponPO>> listCoupons() {
        return couponAppService.listCoupons();
    }

    /**
     * 顾客抢券（需要顾客 Token，身份从 token 取）
     */
    @PostMapping("/{id}/grab")
    public Result<String> grab(@PathVariable Long id, HttpServletRequest request) {
        // 只认顾客身份：员工 token 能进拦截器，但 type != customer 在此拒绝
        if (!"customer".equals(request.getAttribute("type"))) {
            return Result.fail(401, "请使用顾客账号登录");
        }
        Long customerId = (Long) request.getAttribute("customerId");
        return couponAppService.grabCoupon(id, customerId);
    }

    /**
     * 我的券（未使用，含 expired 标记）—— 顾客自助，身份只从 token 取。
     *
     * 路径叫 /mine 而不是 /my：和 /api/customers/me 同一个约定，
     * 一眼能认出"这是当前登录者自己的东西，不吃任何 id 参数"。
     *
     * 员工 token 给 401 而不是 403：券是顾客的私产，员工压根不是"权限不够"，
     * 而是根本没有对应的东西 —— 与抢券接口保持同一句话。
     */
    @GetMapping("/mine")
    public Result<List<MyCouponView>> myCoupons(HttpServletRequest request) {
        if (!"customer".equals(request.getAttribute("type"))) {
            return Result.fail(401, "请使用顾客账号登录");
        }
        return couponAppService.listMyCoupons((Long) request.getAttribute("customerId"));
    }
}