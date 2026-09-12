package com.yunxi.infrastructure.persistence.repository;

import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import com.yunxi.infrastructure.persistence.mapper.StoreMapper;
import com.yunxi.infrastructure.persistence.po.StorePO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 门店仓储实现 —— 用 MyBatis/MySQL 实现 domain 层定义的 StoreRepository。
 */
@Repository
public class StoreRepositoryImpl implements StoreRepository {

    private final StoreMapper storeMapper;

    public StoreRepositoryImpl(StoreMapper storeMapper) {
        this.storeMapper = storeMapper;
    }

    @Override
    public List<Store> findOpen() {
        return storeMapper.selectOpen().stream().map(this::toStore).toList();
    }

    @Override
    public Optional<Store> findOpenById(Long id) {
        return Optional.ofNullable(storeMapper.selectOpenById(id)).map(this::toStore);
    }

    // ═══════════════════ 内部转换方法 ═══════════════════

    /** PO(数据库) → Store(domain) */
    private Store toStore(StorePO po) {
        Store s = new Store();
        s.setId(po.getId());
        s.setName(po.getName());
        s.setAddress(po.getAddress());
        s.setPhone(po.getPhone());
        return s;
    }
}
