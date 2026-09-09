package com.yunxi.interfaces.task;


import com.yunxi.application.service.CouponAppService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 券状态定时推进任务 — 每分钟执行一次。 */
@Component
public class CouponStatusTask {

    private final CouponAppService couponAppService;

    public CouponStatusTask(CouponAppService couponAppService) {
        this.couponAppService = couponAppService;
    }

    /** 每分钟第 0 秒执行：把到点的券推进状态 */
    @Scheduled(cron = "0 * * * * ?")
    public void run() {
        int changed = couponAppService.refreshCouponStatus();
        if (changed > 0) {
            System.out.println("券状态推进: " + changed + " 张");
        }
    }
}
