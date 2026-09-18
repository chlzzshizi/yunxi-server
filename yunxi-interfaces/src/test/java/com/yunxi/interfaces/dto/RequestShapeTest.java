package com.yunxi.interfaces.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体 record 的**字段集合**测试 —— 不测逻辑，只测"这个位置存不存在"。
 *
 * 看着像废话，其实是唯一能表达一类断言的地方：**有些字段不该有**。
 * 值校验能测（必填、超长、非法），但"请求体里根本没有 price 这一项"没法用
 * 业务断言表达 —— 没有的东西没法输入。反射钉住字段集合，就把这句话变成了一句
 * 会红的断言：谁哪天往 record 里加一个字段，这里立刻红。
 *
 * 为什么这件事值得守：一个字段就是一条**从外面塞数据的路**。
 * 价钱、状态、别人的 id —— 这些一旦能被请求体指定，"服务端说了算"
 * 就只剩一句口头约定。出参那边有一模一样的同类（StaffView 没有 password，
 * 钉在 StaffAdminAppServiceTest）—— 那边防的是"把不该说的说出去"，
 * 这边防的是"把不该收的收进来"。
 *
 * 存在的代价也明说：这些断言会在**合法新增字段**时变红。那是有意的 ——
 * 加字段时被迫来这里想一秒"这个字段凭什么能由调用方给"。
 */
class RequestShapeTest {

    /** 某个 Request record 的字段名，按声明顺序 */
    private static List<String> fieldsOf(Class<? extends Record> request) {
        return Arrays.stream(request.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }

    // ════════════════ 下单 ════════════════

    @Nested
    @DisplayName("下单：价钱和状态都不能由调用方给")
    class Order {

        @Test
        @DisplayName("明细只有「哪件衣服、怎么洗、几件、照片」，**没有价钱**")
        void orderItemHasNoPrice() {
            List<String> names = fieldsOf(OrderItemRequest.class);

            assertThat(names).containsExactly("categoryId", "washTypeId", "quantity", "photos");
            // 价格是服务端拿 categoryId + washTypeId 去 clothes_prices 现查的
            // （OrderAppService.priceItems）。请求体里放一个价，等于同时打开了
            // "客户端定价"，而且服务端还得决定"以谁为准"—— 干脆不给这个位置
            assertThat(names).noneMatch(n -> n.toLowerCase().contains("price")
                    || n.toLowerCase().contains("amount"));
        }

        @Test
        @DisplayName("建单请求里没有 status，也没有任何金额")
        void createOrderCannotSetStatusOrAmounts() {
            List<String> names = fieldsOf(CreateOrderRequest.class);

            assertThat(names).containsExactly("storeId", "customerId", "source", "items",
                    "appointmentTime", "deliveryAddress", "remark", "couponId");
            // status 只由状态机推进（Order.updateStatus 是唯一入口，连号 1→7），
            // 金额是服务端算的：这两个要是能从请求体写，调用方就能自己决定
            // "这单多少钱、走到哪一步了" —— 那状态机和算价钱都白写了
            assertThat(names).noneMatch(n -> n.equals("status")
                    || n.toLowerCase().contains("amount"));
        }
    }

    // ════════════════ 员工写接口 ════════════════

    @Nested
    @DisplayName("员工写接口：改资料 / 改状态 / 改密码是三个入口")
    class StaffWrite {

        @Test
        @DisplayName("改资料只有姓名、角色、门店、电话，**捎带不了 username / status / password**")
        void updateStaffCarriesOnlyProfileFields() {
            assertThat(fieldsOf(UpdateStaffRequest.class))
                    .containsExactly("name", "role", "storeId", "phone");
            // 那三样各有各的入口：username 是身份、status 走 /{id}/status、
            // password 走 /{id}/password。合进一个请求体，一次调用就能同时
            // 改资料和停用账号 —— 而这两件事该留下的日志完全不同
        }

        @Test
        @DisplayName("改状态只有 status 一个字段：改谁由路径决定")
        void statusRequestOnlyCarriesStatus() {
            assertThat(fieldsOf(UpdateStatusRequest.class)).containsExactly("status");
            // 没有 id / staffId / storeId：对象来自 PUT /api/{staff,stores}/{id}/status
            // 的路径变量。体里再放一个 id，就得回答"路径写 A、体里写 B 听谁的"。
            // 这个 record 员工和门店共用，所以它连一个"给谁的"字段都不该有
        }

        @Test
        @DisplayName("重置密码只有 password：重置谁由路径决定")
        void resetPasswordOnlyCarriesPassword() {
            assertThat(fieldsOf(ResetPasswordRequest.class)).containsExactly("password");
            // 同理：这里要是能带 staffId，改密码的对象就能从请求体里指定 ——
            // 而"我能改谁的密码"是由路径 + 闸门（/api/staff 只许管理员）决定的
        }
    }

    @Nested
    @DisplayName("员工建档")
    class StaffCreate {

        @Test
        @DisplayName("六项里没有 status：建号 ≠ 决定他能不能登录")
        void createStaffHasNoStatus() {
            assertThat(fieldsOf(CreateStaffRequest.class)).containsExactly(
                    "username", "password", "name", "role", "storeId", "phone");
            // 启用/停用走 /{id}/status。status 放进来，"建一个停用的账号"
            // 就有了两个入口，而这两件事（这个人是谁 / 他能不能进来）该分开
        }

        @Test
        @DisplayName("只有明文 password，没有 passwordHash 这个位置")
        void createStaffCannotSupplyAHash() {
            List<String> names = fieldsOf(CreateStaffRequest.class);

            // Bug 5 是"明文入库"（加密漏了），这半边是它的镜像：
            // 外部直接给哈希 —— 那样 BCrypt 那一步等于没有，谁都能拿着
            // 一个自己算好的串建号。所以密码只能是明文，加密是服务端的事
            assertThat(names).contains("password");
            assertThat(names).noneMatch(n -> n.toLowerCase().contains("hash"));
        }
    }

    // ════════════════ 门店与顾客 ════════════════

    @Nested
    @DisplayName("门店写接口")
    class StoreWrite {

        @Test
        @DisplayName("建店/改店只有名字、地址、电话 —— 营业状态不在其中")
        void storeRequestHasNoStatus() {
            assertThat(fieldsOf(StoreRequest.class)).containsExactly("name", "address", "phone");
            // 新建一律营业，停业走 PUT /api/stores/{id}/status。
            // 这两个入口分开，"刚刚建错了一家店"和"这家店黄了"才是两件不同的事
        }
    }

    @Nested
    @DisplayName("顾客档案")
    class CustomerProfile {

        @Test
        @DisplayName("lookup-or-create 只有手机号和姓名，**没有 storeId**")
        void lookupOrCreateHasNoStoreId() {
            assertThat(fieldsOf(LookupOrCreateRequest.class)).containsExactly("phone", "name");
            // 建档门店取自 token 里的 storeId（CustomerController：「建档店取自 token，
            // 不取请求体」）。体里放一个 storeId，店员就能把顾客建到任何一家店去 ——
            // 而"他是哪家店的顾客"往后会影响谁看得到他的档案
        }

        @Test
        @DisplayName("顾客改自己的档案只能改姓名：手机号是登录名，不是资料")
        void updateProfileOnlyCarriesName() {
            assertThat(fieldsOf(UpdateProfileRequest.class)).containsExactly("name");
            // 手机号是登录凭据（也是 token 的 subject）。能随手改掉它，
            // 等于把"换个号继续用同一个账号"做成了一次普通资料编辑
        }
    }
}
