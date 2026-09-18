package com.yunxi.infrastructure.persistence.repository;

import com.yunxi.domain.staff.Staff;
import com.yunxi.domain.staff.StaffRepository;
import com.yunxi.infrastructure.persistence.mapper.StaffMapper;
import com.yunxi.infrastructure.persistence.po.StaffPO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 员工仓储实现 —— 用 MyBatis/MySQL 实现 domain 层定义的 StaffRepository。
 */
@Repository
public class StaffRepositoryImpl implements StaffRepository {

    private final StaffMapper staffMapper;

    public StaffRepositoryImpl(StaffMapper staffMapper) {
        this.staffMapper = staffMapper;
    }

    @Override
    public Optional<Staff> findByUsername(String username) {
        return Optional.ofNullable(staffMapper.selectByUsername(username)).map(this::toStaff);
    }

    @Override
    public Optional<Staff> findById(Long id) {
        return Optional.ofNullable(staffMapper.selectById(id)).map(this::toStaff);
    }

    @Override
    public List<Staff> findAll() {
        return staffMapper.selectAll().stream().map(this::toStaff).toList();
    }

    @Override
    public void insert(Staff staff) {
        StaffPO po = toPO(staff);
        staffMapper.insert(po);
        // 自增主键回填：调用方拿到的 Staff 必须带着 id（不然紧接着的响应里 id 是 null）
        staff.setId(po.getId());
    }

    @Override
    public void update(Staff staff) {
        staffMapper.update(toPO(staff));
    }

    @Override
    public boolean existsByUsername(String username) {
        return staffMapper.countByUsername(username) > 0;
    }

    // ═══════════════════ 内部转换方法 ═══════════════════
    //
    // 两个方向都在这一处做"列名 password ↔ 字段名 passwordHash"的翻译。
    // 只在一个方向翻译是最容易漏的：查出来对、写进去错，而症状是
    // "登录突然全挂了"，排查会先怀疑 BCrypt 而不是这里。

    /** PO(数据库) → Staff(domain)。注意列名是 password，domain 里叫 passwordHash */
    private Staff toStaff(StaffPO po) {
        Staff s = new Staff();
        s.setId(po.getId());
        s.setUsername(po.getUsername());
        s.setPasswordHash(po.getPassword());
        s.setName(po.getName());
        s.setRole(po.getRole());
        s.setStoreId(po.getStoreId());
        s.setPhone(po.getPhone());
        s.setStatus(po.getStatus());
        return s;
    }

    /** Staff(domain) → PO(数据库) */
    private StaffPO toPO(Staff s) {
        StaffPO po = new StaffPO();
        po.setId(s.getId());
        po.setUsername(s.getUsername());
        po.setPassword(s.getPasswordHash());
        po.setName(s.getName());
        po.setRole(s.getRole());
        po.setStoreId(s.getStoreId());
        po.setPhone(s.getPhone());
        po.setStatus(s.getStatus());
        return po;
    }
}
