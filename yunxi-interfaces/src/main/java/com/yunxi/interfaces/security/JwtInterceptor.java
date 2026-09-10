package com.yunxi.interfaces.security;


import com.yunxi.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器 —— 每个请求进来时校验 Token。
 */
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public JwtInterceptor(JwtUtil jwtUtil, StringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 1. 获取 Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录");
        }
        String token = authHeader.replace("Bearer ", "");

        // 2. 检查黑名单（Redis）
        String blacklisted = redisTemplate.opsForValue()
                .get("blacklist:token:" + token);
        if (blacklisted != null) {
            throw new BusinessException(401, "Token 已失效，请重新登录");
        }

        // 3. 验证 Token
        if (!jwtUtil.validateToken(token)) {
            throw new BusinessException(401, "Token 无效或已过期");
        }

        // 4. 按身份类型把信息放进请求属性，Controller 后面能取到
        //    type 识别员工/顾客（防横向越权：员工 token 不能抢券、顾客 token 不能管店）
        String type = jwtUtil.getType(token);
        if (!"staff".equals(type) && !"customer".equals(type)) {
            throw new BusinessException(401, "Token 无效或已过期");
        }
        request.setAttribute("type", type);
        if ("customer".equals(type)) {
            request.setAttribute("customerId", jwtUtil.getCustomerId(token));
        } else {
            request.setAttribute("staffId", jwtUtil.getStaffId(token));
            request.setAttribute("username", jwtUtil.getUsername(token));
            request.setAttribute("role", jwtUtil.getRole(token));
            request.setAttribute("storeId", jwtUtil.getStoreId(token)); // 旧 token 无此 claim 时为 null
        }

        return true;  // 放行
    }
}