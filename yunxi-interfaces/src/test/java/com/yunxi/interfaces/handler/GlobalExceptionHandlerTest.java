package com.yunxi.interfaces.handler;

import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // ════════════════ 另外四条通道 ════════════════
    //
    // 上面两组钉的是"哪条通道回显原文"。这四条是同一个判断的**另外四张脸**：
    // 一律 400/404（不是 500），一律泛化，唯一的例外是**参数名**——
    // 判据不是"哪一类异常"，而是"这句话是我们自己写的，还是库写给开发者的"：
    //   · 参数名来自我们自己声明的 @RequestParam / 路径变量 → 属于接口契约，可以点名
    //   · 值、类名、Jackson 原文 → 都是调用方或库塞进来的，只进日志

    @Nested
    @DisplayName("四条装配通道：400 / 404 的文案各自钉住")
    class FramingChannels {

        @Test
        @DisplayName("缺参数 → 400，且**说出是哪个参数**")
        void missingParamNamesTheParameter() {
            Result<Void> r = handler.handleMissingParam(
                    new MissingServletRequestParameterException("amount", "BigDecimal"));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("缺少参数: amount");
            // 这是唯一一条会点名具体东西的通道 —— 名字是我们自己在 controller 里写的，
            // 讲出来才能让人知道该补什么；而类型名（BigDecimal）就没必要讲
            assertThat(r.message()).doesNotContain("BigDecimal");
        }

        @Test
        @DisplayName("参数类型不对 → 400：说名字，**不回显值**（值是调用方塞的任意串）")
        void typeMismatchNamesTheParameterButNotTheValue() {
            Result<Void> r = handler.handleTypeMismatch(
                    new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("参数格式不正确: id");
            // 值只进日志：回显它帮不到调用方（是他刚传的），却把响应体长度
            // 交给了调用方 —— 一个不设上限的字符串
            assertThat(r.message()).doesNotContain("abc");
        }

        @Test
        @DisplayName("请求体读不出来 → 400 泛化：Jackson 原文里带着类名（Bug 23 同一族）")
        void notReadableDoesNotEchoJacksonDetail() {
            Result<Void> r = handler.handleNotReadable(new HttpMessageNotReadableException(
                    "Cannot deserialize value of type `java.lang.Integer` from String \"abc\""));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("请求体格式不正确");
            assertThat(r.message()).doesNotContain("java.lang");
            assertThat(r.message()).doesNotContain("Cannot deserialize");
        }

        @Test
        @DisplayName("静态资源不存在 → 404（不是 400，更不是 500），不回显路径")
        void noResourceIs404Not500() {
            Result<Void> r = handler.handleNoResource(
                    new NoResourceFoundException(HttpMethod.GET, "favicon.ico"));

            // 浏览器自己会来要 favicon.ico、robots.txt 这些。算成 500 的话
            // 日志里会堆满"服务器内部错误"，真出事的那条就淹在里面了 ——
            // 这一句是这个 handler 的存在理由，回错码等于它白写
            assertThat(r.code()).isEqualTo(404);
            assertThat(r.message()).isEqualTo("资源不存在");
            assertThat(r.message()).doesNotContain("favicon");
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
