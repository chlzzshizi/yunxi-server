package com.yunxi.interfaces.handler;

import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单元测试 —— 只钉一件事：**哪条通道回显原文**。
 *
 * 这是 interfaces 模块的第一个测试。之所以这里能有单测、controller 却只能靠
 * E2E 脚本验：controller 的活儿是"接请求、转 DTO、签 token"，离了 HTTP 就没什么可测；
 * 而这个类是纯函数 —— 塞一个异常进去、拿一个 Result 出来，new 一个就能跑。
 *
 * 钉它的理由是一次真实的泄漏（Bug 23）：BCryptPasswordEncoder.matches(null, ..)
 * 抛的 IllegalArgumentException 带着英文原文 "rawPassword cannot be null" 被原样
 * 回给前端，等于把用的什么密码库讲给外面听。原文只该进日志。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("框架异常的原文不许出网")
    class NoEcho {

        @Test
        @DisplayName("IllegalArgumentException → 400 泛化消息，不含原文（Bug 23 现场）")
        void illegalArgumentDoesNotEcho() {
            Result<Void> r = handler.handleIllegalArgument(
                    new IllegalArgumentException("rawPassword cannot be null"));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("参数不正确");
            // 逐字验一遍泄漏的原文：只断言"等于参数不正确"的话，
            // 哪天有人改成 e.getMessage() 加上前缀，这条就悄悄失效了
            assertThat(r.message()).doesNotContain("rawPassword");
            assertThat(r.message()).doesNotContain("null");
            assertThat(r.data()).isNull();
        }

        @Test
        @DisplayName("兜底 500 也不回显 —— 那类异常里常带着 SQL 和表结构")
        void unknownExceptionDoesNotEcho() {
            Result<Void> r = handler.handleException(
                    new RuntimeException("Table 'yunxi.staff' doesn't exist"));

            assertThat(r.code()).isEqualTo(500);
            assertThat(r.message()).isEqualTo("服务器内部错误");
            assertThat(r.message()).doesNotContain("yunxi.staff");
        }
    }

    @Test
    @DisplayName("业务异常仍原样透传：那条通道是「要讲给用户听的话」，别被上头的泛化顺手改掉")
    void businessExceptionKeepsItsMessage() {
        Result<Void> r = handler.handleBusinessException(
                new BusinessException(409, "该优惠券已被使用，请刷新后重试"));

        assertThat(r.code()).isEqualTo(409);
        assertThat(r.message()).isEqualTo("该优惠券已被使用，请刷新后重试");
    }
}
