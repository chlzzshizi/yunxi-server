package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.application.service.OrderAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.interfaces.dto.CreateOrderRequest;
import com.yunxi.interfaces.dto.OrderItemRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderController 里**只测一件事**：门店单的归属门店取不到时，说给用户的那句话。
 *
 * 起因是 **Bug 36**：建/改店长时 `storeId` 允许为空（合法状态），于是"没有门店的店长"
 * 是个真实存在的账号 —— 他能登录、能读价目表，但建门店单时会撞上这一支。
 * 当时报的是 **401「登录信息已升级，请重新登录」**，而**重登一万次也不会带上门店**：
 * 那句话把"你没有门店"说成了"你的票过期了"，是 Bug 20 的现场。
 * 2026-09-19 拍板"说实话，仍然拒绝"，文案与状态码一起改掉了。
 *
 * 为什么值得单开一个类钉住它：这句话**只有这一条路能走到**，脚本要造出
 * "无店店长"得先建号再登录（`verify-orders.sh` A6 做了），而单测一行 mock 就到现场；
 * 更要紧的是它是**一句会给用户指错方向的文案** —— 上次是读注释读出来的，
 * 而没有任何断言会去看注释。所以这里钉两条：说对了什么、不许再说什么。
 *
 * 不测的：请求体怎么绑定、`@RequestBody` 缺字段谁来报 400（Spring 的活，脚本覆盖着）；
 * 建单的规则在 `OrderAppServiceTest`，身份/角色的闸在 `JwtInterceptorTest`。
 */
class OrderControllerTest {

    private OrderAppService orderAppService;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderAppService = mock(OrderAppService.class);
        controller = new OrderController(orderAppService);
    }

    /** 造一个"已通过拦截器"的员工请求：type/staffId 必有，storeId 可能是 null */
    private HttpServletRequest staffRequest(Long staffId, Long storeId) {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute("type")).thenReturn("staff");
        when(http.getAttribute("staffId")).thenReturn(staffId);
        when(http.getAttribute("storeId")).thenReturn(storeId);
        return http;
    }

    /** 门店单：source=1，顾客由员工指定（storeId 走 token，请求体里那个被忽略） */
    private CreateOrderRequest storeOrder() {
        return new CreateOrderRequest(null, 7L, 1,
                List.of(new OrderItemRequest(11L, 1L, 1, null)),
                null, null, null, null);
    }

    @Nested
    @DisplayName("无店店长建门店单：说实话，仍然拒绝（Bug 36）")
    class StoreLessManager {

        @Test
        @DisplayName("storeId 为 null → 403「账号还没有归属门店…」，且一步都没进应用层")
        void storeLessManagerGetsTheTruth() {
            HttpServletRequest http = staffRequest(9L, null);

            Throwable t = catchThrowable(() -> controller.createOrder(storeOrder(), http));

            assertThat(t).isInstanceOf(BusinessException.class);
            BusinessException e = (BusinessException) t;
            // 403 而不是 401：401 在本项目里到处都意味着"重新登录"，
            // 客户端会照着去做一件没有用的事（重登也不会带上门店）
            assertThat(e.getCode()).isEqualTo(403);
            assertThat(e.getMessage()).isEqualTo("账号还没有归属门店，无法开单，请联系管理员分配门店");
            // 拦在建单之前：不该白跑一趟应用层（那里会查顾客、查门店、算价）
            verify(orderAppService, never()).createOrder(
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("这句话里不许再出现「重新登录」 —— 那正是这次的病（假成因）")
        void messageMustNotTellThemToLogInAgain() {
            // 单独钉一条"不许说什么"，因为这条文案的病不是"不够清楚"，
            // 而是**指向了一个做不到的动作**：重登一万次也不会带上门店。
            // 有人日后把它改回"请重新登录"图省事，这里先红
            Throwable t = catchThrowable(() ->
                    controller.createOrder(storeOrder(), staffRequest(9L, null)));

            assertThat(t).isInstanceOf(BusinessException.class);
            assertThat(t.getMessage())
                    .doesNotContain("重新登录")
                    .doesNotContain("登录信息已升级");
        }
    }

    @Nested
    @DisplayName("对照：有门店的店长照常开单（别把闸做成「谁都不许开」）")
    class ManagerWithStore {

        @Test
        @DisplayName("token 里的门店被原样传给应用层，请求体里那个 storeId 不算数")
        void storeComesFromTokenOnly() {
            // 请求体里塞了 storeId=99（想开在别家店），token 里是 3 —— 必须传 3。
            // 这条防的是"顺手改成读 request.storeId()"这种改法：
            // 那样既能绕过 token，也和设计文档 §4.2 的口径（门店不是权限边界、
            // 但门店单落在店长自己那家）对不上
            CreateOrderRequest withBodyStore = new CreateOrderRequest(99L, 7L, 1,
                    List.of(new OrderItemRequest(11L, 1L, 1, null)),
                    null, null, null, null);

            controller.createOrder(withBodyStore, staffRequest(9L, 3L));

            verify(orderAppService).createOrder(
                    eq(3L), eq(7L), eq(OrderSource.STORE),
                    any(List.class), eq(9L), any(OrderExtras.class), isNull());
        }

        @Test
        @DisplayName("顾客的票进不来（这一层只看得到 staff —— 拦截器已经筛过一遍）")
        void customerTokenRejectedHere() {
            // 正常情况下顾客根本到不了这个方法（JwtInterceptor 先拦），
            // 但这一层的判据不能**押**在"上一道永远不会漏"上：漏一次就是
            // "顾客能替别人开单"。与 OrderAppService 那句"校验不能押在
            // 某一个入口的自觉上"是同一条规矩
            HttpServletRequest http = mock(HttpServletRequest.class);
            when(http.getAttribute("type")).thenReturn("customer");
            when(http.getAttribute("customerId")).thenReturn(5L);

            Throwable t = catchThrowable(() -> controller.createOrder(storeOrder(), http));

            assertThat(t).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) t).getCode()).isEqualTo(401);
            verify(orderAppService, never()).createOrder(
                    anyLong(), anyLong(), any(), any(), any(), any(), any());
        }
    }
}
