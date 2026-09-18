package com.yunxi.interfaces.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 —— 生成 Token、解析 Token、验证 Token。
 */
@Component
public class JwtUtil {

    private final SecretKey key;       // 签名用的钥匙
    private final long expiration;     // 过期时间（毫秒）

    /** 从 application.yml 读取配置 */
    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration) {
        // 把字符串秘钥转成 HMAC-SHA256 算法用的钥匙
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * 生成 Token
     * @param staffId   员工 ID
     * @param username  用户名
     * @param role      角色（0=管理员 1=店长）
     * @param storeId   所属门店 ID（订单归属校验用，不信请求体只信 token）
     * @return JWT 字符串
     */
    public String generateToken(Long staffId, String username, int role, Long storeId) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)                   // 主题 = 用户名
                .claim("staffId", staffId)           // 自定义字段：员工 ID
                .claim("role", role)                  // 自定义字段：角色
                .claim("storeId", storeId)           // 自定义字段：所属门店
                .claim("type", "staff")              // 身份类型：员工
                .issuedAt(now)                        // 签发时间
                .expiration(expireDate)               // 过期时间
                .signWith(key)                        // 签名
                .compact();                           // 打包成字符串
    }

    /**
     * 生成顾客 Token
     * @param customerId 顾客 ID
     * @param phone      手机号
     * @return JWT 字符串
     */
    public String generateCustomerToken(Long customerId, String phone) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(phone)                       // 主题 = 手机号
                .claim("customerId", customerId)      // 自定义字段：顾客 ID
                .claim("type", "customer")            // 身份类型：顾客
                .issuedAt(now)                        // 签发时间
                .expiration(expireDate)               // 过期时间
                .signWith(key)                        // 签名
                .compact();                           // 打包成字符串
    }

    /**
     * 解析 Token（内部用）
     * @param token JWT 字符串
     * @return Token 里存的所有信息（Claims）
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)       // 用同一个钥匙验证
                .build()
                .parseSignedClaims(token)
                .getPayload();          // 取出 Token 里的数据部分
    }

    /**
     * 验证 Token 是否有效
     * @return true=有效  false=过期或伪造
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;              // 任何异常都算无效
        }
    }

    /** 从 Token 取员工 ID */
    public Long getStaffId(String token) {
        return parseToken(token).get("staffId", Long.class);
    }

    /** 从 Token 取用户名 */
    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    /** 从 Token 取角色 */
    public Integer getRole(String token) {
        return parseToken(token).get("role", Integer.class);
    }

    /** 从 Token 取员工所属门店 ID（旧 token 无此 claim 时返回 null，前端会提示重新登录） */
    public Long getStoreId(String token) {
        return parseToken(token).get("storeId", Long.class);
    }

    /**
     * 从 Token 取**签发时间**（iat）。
     *
     * 唯一的用途是配合 StaffTokenRevoker 判断"这张票是不是在某人被停用/降级
     * 之前签的"。**它不是我新加的 claim** —— generateToken 从第一天起就写了
     * `.issuedAt(now)`，这里只是把它读出来（jjwt 的 Claims.getIssuedAt 现成的）。
     * 所以这个改动没有碰签发路径、没有碰登录接口。
     *
     * 返回 jjwt 原生的 Date（可能为 null：老 token 若没有 iat）。
     * **调用方必须处理 null** —— 拿不到签发时间时证明不了这张票是新的，
     * 只能当旧的处理（见 StaffTokenRevoker.isRevoked）。
     *
     * 注意 iat 是**秒**精度（JWT 的 NumericDate 就是秒），下面比对时的方向
     * 和由此产生的亚秒级窗口，在 StaffTokenRevoker 的类注释里讲清楚了。
     */
    public Date getIssuedAt(String token) {
        return parseToken(token).getIssuedAt();
    }

    /** 从 Token 取身份类型（staff=员工 customer=顾客） */
    public String getType(String token) {
        return parseToken(token).get("type", String.class);
    }

    /** 从 Token 取顾客 ID */
    public Long getCustomerId(String token) {
        return parseToken(token).get("customerId", Long.class);
    }
}
