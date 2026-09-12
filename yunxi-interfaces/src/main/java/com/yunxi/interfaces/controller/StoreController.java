package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.StoreView;
import com.yunxi.application.service.StoreAppService;
import com.yunxi.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店接口。
 *
 * 鉴权：**任意有效 token**（和价目表的读接口同款口径）。
 * 门店信息本来就是公开的 —— 顾客下单必须先选门店，看不到店就没法下单；
 * 拦在 token 这一层（/api/** 由 JwtInterceptor 统一把守）已足够挡住公网匿名访问。
 */
@RestController
@RequestMapping("/api")
public class StoreController {

    private final StoreAppService storeAppService;

    public StoreController(StoreAppService storeAppService) {
        this.storeAppService = storeAppService;
    }

    /** 营业中的门店列表（只含 status=1）—— 顾客下单页与员工建单页共用的下拉框 */
    @GetMapping("/stores")
    public Result<List<StoreView>> listStores() {
        return storeAppService.listOpenStores();
    }
}
