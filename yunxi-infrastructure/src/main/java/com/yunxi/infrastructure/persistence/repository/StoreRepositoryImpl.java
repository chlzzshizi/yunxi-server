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

    @Override
    public List<Store> findAll() {
        return storeMapper.selectAll().stream().map(this::toStore).toList();
    }

    @Override
    public Optional<Store> findById(Long id) {
        return Optional.ofNullable(storeMapper.selectById(id)).map(this::toStore);
    }

    @Override
    public void insert(Store store) {
        StorePO po = toPO(store);
        storeMapper.insert(po);
        // 自增主键回填：调用方拿到的 Store 必须带着 id（不然紧接着的响应里 id 是 null）
        store.setId(po.getId());
    }

    @Override
    public void update(Store store) {
        storeMapper.update(toPO(store));
    }

    // ═══════════════════ 内部转换方法 ═══════════════════

    /** PO(数据库) → Store(domain) */
    private Store toStore(StorePO po) {
        Store s = new Store();
        s.setId(po.getId());
        s.setName(po.getName());
        s.setAddress(po.getAddress());
        s.setPhone(po.getPhone());
        s.setStatus(po.getStatus());
        return s;
    }

    /** Store(domain) → PO(数据库) */
    private StorePO toPO(Store s) {
        StorePO po = new StorePO();
        po.setId(s.getId());
        po.setName(s.getName());
        po.setAddress(s.getAddress());
        po.setPhone(s.getPhone());
        po.setStatus(s.getStatus());
        return po;
    }
}
