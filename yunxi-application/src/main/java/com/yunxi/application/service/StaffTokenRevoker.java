package com.yunxi.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 员工 token 作废 —— **按人**作废，不是按 token。
 *
 * ═══ 为什么不能复用登出那个黑名单 ═══
 *
 * 既有黑名单是 `blacklist:token:<token>`（AuthController.logout 写、JwtInterceptor 读），
 * 一个键就是一张票。要作废"某个人的全部 token"，得先枚举他手上有哪些 token ——
 * 而系统里**没有这张表**（token 是签出去就不管的），枚举不出来。
 * 所以字面复用做不到，需要一族**按人**的键。这就是本类存在的全部理由。
 *
 * ═══ 做法：失效水位线 ═══
 *
 *   写：revokeAll(staffId) → SET auth:staff:invalidAfter:<staffId> = 当前毫秒
 *   读：isRevoked(staffId, iat) → iat 早于这条水位线 ⇒ 这张票作废
 *
 * 一次 revokeAll 作废该员工**已签发的全部** token。四种情况共用这一个原语：
 * 停用、改角色（降级）、改所属门店、重置密码 —— 这四种都会让手上 token 里的
 * role / storeId 变成**过期事实**。
 *
 * ⚠️ 只处理"停用"是个陷阱：把越权的管理员降成店长，他那张 token 里还写着 role=0，
 * 还能继续管人与店，直到自然过期（最长 24 小时）。那等于留了半个后门。
 *
 * ═══ 它比"布尔停用标记"强在哪 ═══
 *
 * 用一个 `staff:disabled:<id> = 1` 也能挡停用，但**重新启用会把旧票复活**
 * （标记清掉，票还没过期，于是又能用了）。水位线不会：旧票的 iat 永远小于水位线，
 * 启用与否都不影响这个事实。所以"停用 → 启用"之后，那个人必须重新登录。
 *
 * ═══ 代价（写在文档里，不藏着）═══
 *
 * 员工请求的 Redis 从 1 次 GET 变 2 次（黑名单 1 次 + 水位线 1 次）。
 * 拦截器本来每次请求就要查一次黑名单，所以这是 +1，不是从 0 开始。
 *
 * ═══ 已知边界 ═══
 *
 * ① **亚秒级"误杀"**：JWT 的 iat 是**秒**精度，水位线是**毫秒**。
 *    判据用严格小于，方向是**偏保守**的：
 *      · 签发在作废**之前**的票 —— 一定被判作废（floor 到秒只会让 iat 更早）
 *      · 签发在作废**同一个自然秒内**的新票 —— 可能被误判作废，窗口 < 1 秒
 *    也就是"宁可错杀一秒内签的票，绝不放过一秒前签的票"。被误杀的人一秒钟后
 *    重新登录就好；反过来（放过）才是不能接受的。
 *    顺带：水位线存**毫秒**正是为了这个方向 —— 若也存成秒（floor），
 *    "同意秒内先签发、后作废"就会变成 iat == 水位线、判据不成立，于是**漏放**。
 *
 * ② **Redis 被清空 / 回滚到旧快照 ⇒ 水位线全没了，被停用的人手上没到期的旧票会复活
 *    （最长 24 小时）**。这不是本类新引入的洞 —— 那个黑名单是同一个性质，
 *    两者都只活在 Redis 里。真正的兜底在登录那一层：Staff.canLogin() 每次登录都拦，
 *    停用的人**登不进来**，能被"复活"的只有他手上那张还没过期的旧票。
 *
 * ③ 报错只有一句话（"账号已被停用或权限已变更，请重新登录"），不带原因。
 *    要精确报"你被停用了"就得每个请求多一次数据库往返，不值。
 */
@Service
public class StaffTokenRevoker {

    /** 键前缀。**只有这一个家** —— 见类注释：两个模块各写一份字面量，写错就是静默失效 */
    private static final String KEY_PREFIX = "auth:staff:invalidAfter:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlMillis;

    public StaffTokenRevoker(StringRedisTemplate redisTemplate,
                             @Value("${jwt.expiration}") long ttlMillis) {
        this.redisTemplate = redisTemplate;
        this.ttlMillis = ttlMillis;
    }

    /**
     * 作废该员工**当前已签发**的全部 token（此后重新登录拿到的票不受影响）。
     *
     * TTL 取 jwt.expiration（24 小时），与"登出黑名单"那个硬编码的 24 小时同款。
     * **不变式：TTL 必须 ≥ token 有效期**，否则水位线会比它该杀的票先消失，
     * 那些票就复活了。等长是够的 —— 最"年轻"的一张待杀票也是在写入之前签的，
     * 它比水位线先过期。
     *
     * 调用顺序：**先写库，再调这里**。反过来的话，写库失败就会白白把一个人踢下线。
     */
    public void revokeAll(Long staffId) {
        if (staffId == null) {
            return;   // 没有 id 就没有"这个人"，静默跳过比 NPE 好
        }
        redisTemplate.opsForValue().set(
                KEY_PREFIX + staffId,
                String.valueOf(System.currentTimeMillis()),
                ttlMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 这张票是不是已经被作废了。
     *
     * @param issuedAt token 的 iat；**为 null 时一律判作废**（拿不到签发时间就
     *                 证明不了它是新的，安全判断上只能当旧的处理）
     */
    public boolean isRevoked(Long staffId, Date issuedAt) {
        if (staffId == null || issuedAt == null) {
            return true;
        }
        String watermark = redisTemplate.opsForValue().get(KEY_PREFIX + staffId);
        if (watermark == null) {
            return false;   // 从没作废过这个人 —— 绝大多数请求走这一支
        }
        try {
            return issuedAt.getTime() < Long.parseLong(watermark);
        } catch (NumberFormatException e) {
            // 键被别的东西写脏了（手工 SET、序列化变了）。这里**判作废**：
            // 一个读不懂的水位线只可能是异常状态，此时放行比拦下危险得多
            return true;
        }
    }
}
