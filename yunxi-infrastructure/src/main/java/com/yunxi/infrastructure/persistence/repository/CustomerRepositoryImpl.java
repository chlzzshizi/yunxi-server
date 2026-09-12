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
    public Optional<Customer> findById(Long customerId) {
        CustomerPO po = customerMapper.selectById(customerId);
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

    @Override
    public boolean updatePassword(Long customerId, String passwordHash) {
        // 0 行 = 这行已经有密码了（并发下别人抢先激活）—— 当作"没生效"报上去，
        // 由应用层决定提示什么。不在这里抛异常：仓储不该替调用方决定业务语义
        return customerMapper.updatePassword(customerId, passwordHash) > 0;
    }

    @Override
    public void fillName(Long customerId, String name) {
        customerMapper.fillName(customerId, name);
    }

    @Override
    public void rename(Long customerId, String name) {
        // 没有返回值：调用方已经手握这一行（个人中心是"先查到、再改"），
        // 真有并发也不会互相算错账——名字这一列不像密码，后写的赢就是他要的
        customerMapper.rename(customerId, name);
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
