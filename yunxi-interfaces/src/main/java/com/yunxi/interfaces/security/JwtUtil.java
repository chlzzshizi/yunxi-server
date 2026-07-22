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
     * @return JWT 字符串
     */
    public String generateToken(Long staffId, String username, int role) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)                   // 主题 = 用户名
                .claim("staffId", staffId)           // 自定义字段：员工 ID
                .claim("role", role)                  // 自定义字段：角色
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
}
