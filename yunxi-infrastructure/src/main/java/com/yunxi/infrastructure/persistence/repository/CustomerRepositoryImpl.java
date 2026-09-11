package com.yunxi.infrastructure.persistence.repository;

import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import com.yunxi.infrastructure.persistence.mapper.CustomerMapper;
import com.yunxi.infrastructure.persistence.po.CustomerPO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 顾客仓储实现 —— 用 MyBatis/MySQL 实现 domain 层定义的 CustomerRepository。
 */
@Repository
public class CustomerRepositoryImpl implements CustomerRepository {

    private final CustomerMapper customerMapper;

    public CustomerRepositoryImpl(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public Optional<Customer> findByPhone(String phone) {
        CustomerPO po = customerMapper.selectByPhone(phone);
        return po == null ? Optional.empty() : Optional.of(toCustomer(po));
    }

    @Override
    public Long save(Customer customer) {
        CustomerPO po = new CustomerPO();
        po.setName(customer.getName());
        po.setPhone(customer.getPhone());
        po.setPassword(customer.getPasswordHash());
        po.setStoreId(customer.getStoreId());
        // 手机号撞唯一键时 DuplicateKeyException 从这里冒上去 —— 按 CustomerRepository
        // 接口的约定，不在这里吞掉：调用方才知道该给用户提示什么
        customerMapper.insert(po);
        return po.getId();   // MyBatis 把自增主键回填进 PO
    }

    // ═══════════════════ 内部转换方法 ═══════════════════

    /** PO(数据库) → Customer(domain)。注意列名是 password，domain 里叫 passwordHash */
    private Customer toCustomer(CustomerPO po) {
        Customer c = new Customer();
        c.setId(po.getId());
        c.setStoreId(po.getStoreId());
        c.setName(po.getName());
        c.setPhone(po.getPhone());
        c.setPasswordHash(po.getPassword());
        return c;
    }
}
