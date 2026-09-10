package com.yunxi.interfaces.security;


import com.yunxi.common.BusinessException;
import com.yunxi.common.enums.StaffRole;
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

        // 5. 角色闸门（管理员能碰哪些 URL）
        checkRoleGate(request, type, token);

        return true;  // 放行
    }

    /**
     * 角色闸门 —— 管理员（role=0）不参与日常经营。
     *
     * 为什么这条规则住在拦截器、而不是各个 Controller 或应用服务里：
     *   设计文档 §6.4 是**按 URL 写**的（"`/api/orders/**` 一律 403"、"定价写=店长"），
     *   所以它本质上是"这个前缀归谁"的路径级策略，和 Spring Security 的
     *   `antMatchers(...).hasRole(...)` 是同一类东西 —— 一处收口，不会漏。
     *   若塞进应用服务，就得给 6 个方法各加一个 role 参数，
     *   规则反而被摊薄成 6 份，将来加一个订单接口就多一个漏点。
     *
     * 与"归属校验"（顾客只能看自己的订单）是两回事：
     *   那种校验跟订单数据有关，仍然留在 OrderAppService 里（那里能单测）。
     *
     * 顾客不受这里管：顾客能用哪些接口由各 Controller 自己判断（如抢券、下单）。
     */
    private void checkRoleGate(HttpServletRequest request, String type, String token) {
        if (!"staff".equals(type)) {
            return;
        }
        Integer role = jwtUtil.getRole(token);
        if (role == null || role != StaffRole.ADMIN.getCode()) {
            return;   // 店长：订单与定价都是他的本职，全放行
        }
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/orders")) {
            // 含读接口：管理员连"看一眼订单列表"都不需要，
            // 放开读只会让"他到底能不能管订单"这个问题重新变模糊
            throw new BusinessException(403, "管理员不参与订单操作，请使用店长账号");
        }
        if ("PUT".equalsIgnoreCase(request.getMethod()) && uri.startsWith("/api/prices")) {
            throw new BusinessException(403, "管理员不能修改价格，请使用店长账号");
        }
    }
}