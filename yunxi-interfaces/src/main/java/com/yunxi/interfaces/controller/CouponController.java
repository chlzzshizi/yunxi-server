package com.yunxi.interfaces.controller;

import com.yunxi.application.service.CouponAppService;
import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.po.CouponPO;
import com.yunxi.interfaces.dto.CreateCouponRequest;
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
     * 顾客抢券
     * TODO: customerId 目前是临时参数，顾客 JWT 落地后改为从 token 取（第 3 步）
     */
    @PostMapping("/{id}/grab")
    public Result<String> grab(@PathVariable Long id,
                               @RequestParam Long customerId) {
        return couponAppService.grabCoupon(id, customerId);
    }
}