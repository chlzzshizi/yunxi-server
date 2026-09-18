package com.yunxi.domain.staff;

import com.yunxi.common.BusinessException;

/**
 * 员工 —— 登录 + 员工管理（2026-09-18 起）。
 *
 * 类注释原本写着"字段刻意只留登录用得到的那些……等那块功能落地时再按需要补"。
 * 落地时补的**只有 phone 一个**：它是员工管理列表上唯一一个登录用不到、
 * 管理要用的字段。role / storeId / status 早就在（登录和闸门都要用）。
 *
 * 仍然没有 createTime / updateTime —— 列表页不显示它们，加进来就是两个没人读的字段
 * （DDL 里那两列是 DEFAULT CURRENT_TIMESTAMP / ON UPDATE，数据库自己填，不需要谁去写）。
 */
public class Staff {

    /** 与 staff.username 的 VARCHAR(30) 对齐 */
    public static final int USERNAME_MAX_LENGTH = 30;
    /** 与 staff.name 的 VARCHAR(20) 对齐 */
    public static final int NAME_MAX_LENGTH = 20;
    /** 与 staff.phone 的 VARCHAR(20) 对齐 */
    public static final int PHONE_MAX_LENGTH = 20;

    private Long id;
    private String username;
    private String passwordHash;   // BCrypt 哈希，绝不能是明文
    private String name;
    private Integer role;          // 0=管理员 1=店长
    private Long storeId;          // 所属门店；签进 token，订单归属校验只信它不信请求体
    private String phone;          // 手机号，可空
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

    // ═══════════ 长度校验（照抄 Customer.requireValidName 的形状）═══════════
    //
    // 三条都是**只管长度，不管必填**：null / 空串一律放行，由调用方决定
    // "要不要这个名字"之后再调。必填与否是应用层的事，而且各接口不一样。
    //
    // 为什么非要在写库前拦一道：列宽是 VARCHAR(N)，超长会一路走到 INSERT
    // 才被 MySQL 弹回来，报的是 DataTooLong 英文 SQL 异常（500），
    // 而不是一句给用户看的话。拦在这里，30 / 20 / 20 这三个数字就只有这一个家。
    //
    // codePointCount 而不是 length()：length() 数 UTF-16 码元，一个 emoji 算 2，
    // 而 MySQL 的 VARCHAR(N) 数**字符**、算 1（完整理由见 Customer.requireValidName）。

    public static void requireValidUsername(String username) {
        requireWithin(username, USERNAME_MAX_LENGTH, "用户名");
    }

    public static void requireValidName(String name) {
        requireWithin(name, NAME_MAX_LENGTH, "姓名");
    }

    public static void requireValidPhone(String phone) {
        requireWithin(phone, PHONE_MAX_LENGTH, "手机号");
    }

    private static void requireWithin(String value, int max, String label) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw new BusinessException(label + "不能超过 " + max + " 个字");
        }
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

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
