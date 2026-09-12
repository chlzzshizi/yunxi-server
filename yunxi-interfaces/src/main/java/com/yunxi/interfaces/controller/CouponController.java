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

    /** 店长发券 */
    @PostMapping
    public Result<CouponPO> createCoupon(@RequestBody CreateCouponRequest request) {
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