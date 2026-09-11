package com.yunxi.domain.staff;

/**
 * 员工 —— 目前只服务于"登录"这一件事。
 *
 * 字段刻意只留登录用得到的那些：员工管理（增删改查、调岗）还没做，
 * 提前把 phone、createTime 都塞进来，只会让这个对象看起来比它实际会做的事情多。
 * 等那块功能落地时再按需要补。
 */
public class Staff {

    private Long id;
    private String username;
    private String passwordHash;   // BCrypt 哈希，绝不能是明文
    private String name;
    private Integer role;          // 0=管理员 1=店长
    private Long storeId;          // 所属门店；签进 token，订单归属校验只信它不信请求体
    private Integer status;        // 1=启用 0=停用

    /**
     * 这个账号现在能不能登录。
     *
     * "停用"是账号自身的状态，不是登录流程的判断 —— 所以规则住在 Staff 上，
     * 而不是散在某个 service 的 if 里。写成"只有明确启用才放行"（而不是
     * "status 不等于 0 就放行"）：将来多出别的状态值时，默认是拒绝而不是误放。
     */
    public boolean canLogin() {
        return status != null && status == 1;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
