package com.yunxi.application.service;

import com.yunxi.application.dto.StoreView;
import com.yunxi.common.Result;
import com.yunxi.domain.store.StoreRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 门店应用服务 —— 目前只有"列出营业中的门店"这一件事。
 *
 * 薄得像张纸，但它不是多余的：接口层要的是一条**不经过 domain 类型**的路。
 * 没有它，controller 就得直接拿 StoreRepository（接口层引用 domain，破 §3.1），
 * 或者直接拿 StoreMapper（接口层引用 infrastructure，同罪）。
 * 应用层的价值不全是"编排多个域"，也包括"替接口层挡住它不该认识的东西"。
 */
@Service
public class StoreAppService {

    private final StoreRepository storeRepository;

    public StoreAppService(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    /**
     * 营业中的门店列表。
     * 顾客下单要选店、员工建门店单也要看店，所以这是"任意有效 token 都能读"的接口 ——
     * 门店信息本就是公开的，拦在 token 这一层（挡公网匿名）就够了。
     */
    public Result<List<StoreView>> listOpenStores() {
        return Result.ok(storeRepository.findOpen().stream().map(StoreView::from).toList());
    }
}
