package com.yunxi.interfaces.security;


import com.yunxi.application.service.StaffTokenRevoker;
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
    private final StaffTokenRevoker tokenRevoker;

    public JwtInterceptor(JwtUtil jwtUtil, StringRedisTemplate redisTemplate,
                          StaffTokenRevoker tokenRevoker) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.tokenRevoker = tokenRevoker;
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
            Long staffId = jwtUtil.getStaffId(token);
            request.setAttribute("staffId", staffId);
            request.setAttribute("username", jwtUtil.getUsername(token));
            request.setAttribute("role", jwtUtil.getRole(token));
            request.setAttribute("storeId", jwtUtil.getStoreId(token)); // 旧 token 无此 claim 时为 null

            // 4.5 这张票被作废了没（停用 / 降级 / 调岗 / 改密码 —— 见 StaffTokenRevoker）。
            //
            // 为什么排在角色闸门**之前**：一个被停用的管理员不该听到
            // "员工与门店管理只对管理员开放，请使用管理员账号" —— 那句话把他
            // 指向一个他做不到的动作（他连登录都登不进来）。"你的登录已经作废"
            // 是比"你这个角色不能来这"更前置、更可行动的事实（Bug 20 的教训：
            // 报错要指向真正的原因）。
            if (tokenRevoker.isRevoked(staffId, jwtUtil.getIssuedAt(token))) {
                throw new BusinessException(401, "账号已被停用或权限已变更，请重新登录");
            }
        }

        // 5. 角色闸门（管理员能碰哪些 URL、哪些 URL 只许管理员碰）
        checkRoleGate(request, type, token);

        return true;  // 放行
    }

    /**
     * 角色闸门 —— 双向的两类规则。
     *
     * **方向一（2026-09-18 新增）：某些 URL 只对管理员开放 ⇒ 拦店长。**
     * **方向二（原有）：管理员不参与日常经营 ⇒ 拦管理员。**
     *
     * 两条方向相反，读的时候别串了。方向一必须写在方向二**前面**：
     * 方向二开头就是 `if (role != ADMIN) return;`，店长在那里直接放行了 ——
     * 那种写法根本没有地方安放"这个前缀不许店长进"。
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
        String uri = request.getRequestURI();

        // ── 方向一：管理员专区 ⇒ 拦店长（2026-09-18 新增）──
        //
        // 闸门原来只有方向二，而方向二的第一句就是"不是管理员就放行"，
        // 于是**店长能到达任何一个 URL**。"只许管理员进"这个方向在代码里
        // 从来不存在 —— 员工管理一落地，它就必须存在了，否则店长能建号、
        // 能改别人的角色、能停用管理员。
        //
        // 门店这条**必须按方法分**（GET 放行、其余拦），不能按前缀一刀切：
        //   · 门店的读对所有员工（和顾客）开放是既有口径（§6.4「任意有效 token」）
        //   · verify-stores.sh 有一条断言硬钉着"管理员 GET /api/stores 必须 200"
        //     （B9b）—— 按前缀一刀切会把它变成 403，那条断言就红了
        //   · 而且"能下单的店"本来就该让所有人看得见，看不到店就没法下单
        //
        // /api/staff 则是整个前缀（含读）都归管理员，理由写在 StaffController 的类注释里。
        //
        // 顺带：这里不会误伤 /api/auth/staff/login 之类 ——
        // "/api/auth/..." 第 6 个字符是 'a' 不是 's'，前缀根本对不上；
        // 而且 /api/auth/** 本来就被 WebMvcConfig 排除在拦截器之外，两层都安全。
        boolean adminOnly = uri.startsWith("/api/staff")
                || (uri.startsWith("/api/stores") && !"GET".equalsIgnoreCase(request.getMethod()));
        if (adminOnly && (role == null || role != StaffRole.ADMIN.getCode())) {
            throw new BusinessException(403, "员工与门店管理只对管理员开放，请使用管理员账号");
        }

        // ── 方向二：管理员不参与日常经营 ⇒ 拦管理员（订单 / 定价写 / 顾客 三条，
        //    2026-09-19 加上"发券"这条第四条；每条的就地注释写着它自己的理由）──
        if (role == null || role != StaffRole.ADMIN.getCode()) {
            return;   // 店长：订单与定价都是他的本职，以下三条都不关他的事
        }
        if (uri.startsWith("/api/orders")) {
            // 含读接口：管理员连"看一眼订单列表"都不需要，
            // 放开读只会让"他到底能不能管订单"这个问题重新变模糊
            throw new BusinessException(403, "管理员不参与订单操作，请使用店长账号");
        }
        if ("PUT".equalsIgnoreCase(request.getMethod()) && uri.startsWith("/api/prices")) {
            throw new BusinessException(403, "管理员不能修改价格，请使用店长账号");
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/coupons".equals(uri)) {
            // 发券（2026-09-19 拍板：**管理员不发券**）。理由与订单/顾客同一条：
            // 券发出去就是给人抢、给人下单核销的，整条链路都是顾客侧业务 ——
            // 管理员既然不碰订单也不碰顾客，就没有发券的理由。
            //
            // 拍板前这里的判据是"是员工就行"（CouponController 里那句
            // `!"staff".equals(type)`），管理员**能**发券。那是 Bug 42 补闸时
            // 明确留下的口径问题（当时标成"待拍板"），不是漏。
            //
            // 判据只认 **POST 且路径精确等于 /api/coupons**：
            //   · 抢券 POST /api/coupons/{id}/grab 也是 POST，但那是**顾客**的动作，
            //     管理员打到那儿该收到 controller 的「请使用顾客账号登录」——
            //     被这里截住会把它换成一句错话（他不是"不该发券"，是没有顾客账号）
            //   · 读（GET /api/coupons、/api/coupons/mine）本次没动，与 /api/prices
            //     同形："看得见，不能写"。要连读一起收是另一个决定。
            throw new BusinessException(403, "管理员不参与发券，请使用店长账号");
        }
        if (uri.startsWith("/api/customers")) {
            // 这个前缀下全是顾客业务，没有一件是管理员的：
            //   · /lookup-or-create 建档 —— 它是为建单服务的（下一个动作就是
            //     POST /api/orders），而管理员不碰订单，那他也就没有建顾客的理由
            //   · /me 个人中心 —— 那是顾客自己看/改自己的档案，管理员没有顾客账号
            // 所以这里按**前缀**一刀切就对了。措辞也得覆盖整个前缀：
            // 说成"不参与顾客建档"会在 /me 上变成一句错话（他不是不能建档，
            // 是压根没有"自己"这个档案）。
            // 注意这里是 /api/customers 不是 /api/auth/customer：顾客**自己**注册登录
            // 走的是后者，和这条闸门无关（那时他还不是任何账号）
            throw new BusinessException(403, "管理员不参与顾客相关操作，请使用店长账号");
        }
    }
}