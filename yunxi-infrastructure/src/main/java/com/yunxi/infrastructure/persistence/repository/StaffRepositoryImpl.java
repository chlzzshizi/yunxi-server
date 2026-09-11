package com.yunxi.infrastructure.persistence.repository;

import com.yunxi.domain.staff.Staff;
import com.yunxi.domain.staff.StaffRepository;
import com.yunxi.infrastructure.persistence.mapper.StaffMapper;
import com.yunxi.infrastructure.persistence.po.StaffPO;
import org.springframework.stereotype.Repository;

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
        StaffPO po = staffMapper.selectByUsername(username);
        return po == null ? Optional.empty() : Optional.of(toStaff(po));
    }

    // ═══════════════════ 内部转换方法 ═══════════════════

    /** PO(数据库) → Staff(domain)。注意列名是 password，domain 里叫 passwordHash */
    private Staff toStaff(StaffPO po) {
        Staff s = new Staff();
        s.setId(po.getId());
        s.setUsername(po.getUsername());
        s.setPasswordHash(po.getPassword());
        s.setName(po.getName());
        s.setRole(po.getRole());
        s.setStoreId(po.getStoreId());
        s.setStatus(po.getStatus());
        return s;
    }
}
