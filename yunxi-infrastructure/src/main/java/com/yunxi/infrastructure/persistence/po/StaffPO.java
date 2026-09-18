package com.yunxi.infrastructure.persistence.po;

/**
 * 员工表 staff 的数据库映射对象。
 *
 * 注意列名是 password、字段名也是 password —— 只有 domain 的 Staff 管它叫
 * passwordHash（那一层要强调"这是哈希不是明文"）。改名的活在
 * StaffRepositoryImpl.toStaff 里做。
 *
 * 不映射 create_time / update_time：没有任何响应显示它们，
 * DDL 里 DEFAULT CURRENT_TIMESTAMP / ON UPDATE 已经把两列填好了。
 */
public class StaffPO {

    private Long id;
    private String username;
    private String password;
    private String name;
    private Integer role;        // 0=管理员 1=店长
    private Long storeId;
    private String phone;
    private Integer status;      // 1=启用 0=停用

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
