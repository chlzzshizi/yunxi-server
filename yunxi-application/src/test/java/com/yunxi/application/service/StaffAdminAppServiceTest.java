package com.yunxi.application.service;

import com.yunxi.application.dto.CreateStaffCommand;
import com.yunxi.application.dto.StaffView;
import com.yunxi.application.dto.UpdateStaffCommand;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.common.enums.StaffRole;
import com.yunxi.domain.staff.Staff;
import com.yunxi.domain.staff.StaffRepository;
import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * StaffAdminAppService 单元测试 —— Mockito 假仓储，真 BCrypt。
 *
 * ═══ 这里最值钱的是"谁会被踢下线" ═══
 *
 * 打接口能验出"停用后旧 token 死了"，但**验不出"改姓名没有踢人"** ——
 * 后者是个"什么都没发生"的断言，只有单元测试能精确钉住
 * （verifyNoInteractions / times(1)）。而这条规则的价值恰恰在于它别误伤：
 * 管理员改个错别字，不该把柜台电脑上的人弹下线。
 *
 * 密码编码器用**真的** BCrypt（不是 mock），理由同 StaffAuthAppServiceTest。
 */
class StaffAdminAppServiceTest {

    private StaffRepository staffRepository;
    private StoreRepository storeRepository;
    private StaffTokenRevoker tokenRevoker;
    private StaffAdminAppService service;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        storeRepository = mock(StoreRepository.class);
        tokenRevoker = mock(StaffTokenRevoker.class);
        service = new StaffAdminAppService(staffRepository, storeRepository,
                new BCryptPasswordEncoder(), tokenRevoker);
    }

    // ═══════════════════ 造数据的小工具 ═══════════════════

    private Store store(Long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setAddress("某某路 1 号");
        s.setStatus(1);
        return s;
    }

    private Staff staff(Long id, String username, int role, Long storeId) {
        Staff s = new Staff();
        s.setId(id);
        s.setUsername(username);
        s.setPasswordHash(new BCryptPasswordEncoder().encode("pw"));
        s.setName("张店长");
        s.setRole(role);
        s.setStoreId(storeId);
        s.setStatus(1);
        return s;
    }

    private void storeExists(Long id, String name) {
        when(storeRepository.findById(id)).thenReturn(Optional.of(store(id, name)));
    }

    private void staffExists(Staff s) {
        when(staffRepository.findById(s.getId())).thenReturn(Optional.of(s));
    }

    private static CreateStaffCommand createCmd(String username, int role, Long storeId) {
        return new CreateStaffCommand(username, "pw123", "李四", role, storeId, null);
    }

    // ═══════════════════════ 建号 ═══════════════════════

    @Nested
    @DisplayName("新建员工")
    class Create {

        @BeforeEach
        void storeIsThere() {
            storeExists(1L, "云洗中央门店");
        }

        @Test
        @DisplayName("建一个店长 → 200，密码被 BCrypt 哈希（库里不是明文），status=1")
        void createsManager() {
            when(staffRepository.existsByUsername("mgr3")).thenReturn(false);

            Result<StaffView> r = service.createStaff(createCmd("mgr3", 1, 1L));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().username()).isEqualTo("mgr3");
            assertThat(r.data().role()).isEqualTo(StaffRole.MANAGER);
            assertThat(r.data().storeId()).isEqualTo(1L);
            assertThat(r.data().storeName()).isEqualTo("云洗中央门店");
            assertThat(r.data().status()).isEqualTo(1);

            // 落库的那个对象：密码是哈希不是明文
            var captor = org.mockito.ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).insert(captor.capture());
            assertThat(captor.getValue().getPasswordHash())
                    .startsWith("$2")
                    .isNotEqualTo("pw123");
        }

        @Test
        @DisplayName("新建**不作废**任何 token —— 新账号没有旧票可作废")
        void createDoesNotRevoke() {
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);

            service.createStaff(createCmd("mgr3", 1, 1L));

            verifyNoInteractions(tokenRevoker);
        }

        @Test
        @DisplayName("role=0（管理员）→ storeId 被强制成 null，哪怕传了值")
        void adminForcesNullStore() {
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);

            // 传了 storeId=1，但 role=0 —— 忽略，不报错（前端的门店下拉框常留着旧值）
            Result<StaffView> r = service.createStaff(createCmd("admin2", 0, 1L));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().storeId()).isNull();
            assertThat(r.data().storeName()).isNull();
        }

        @Test
        @DisplayName("店长不给门店 → 200，storeId 为 null（库里允许，不额外拦）")
        void managerMayHaveNoStore() {
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);

            Result<StaffView> r = service.createStaff(createCmd("mgr4", 1, null));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().storeId()).isNull();
        }

        @Test
        @DisplayName("门店 id 不存在 → 400 门店不存在（库里没外键，只能应用层拦）")
        void rejectsMissingStore() {
            when(storeRepository.findById(999L)).thenReturn(Optional.empty());

            Result<StaffView> r = service.createStaff(createCmd("mgr5", 1, 999L));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("门店不存在");
            verify(staffRepository, never()).insert(any());
        }

        @Test
        @DisplayName("停业的门店**可以**归人 —— 拿 findOpenById 会把停业误判成不存在")
        void stoppedStoreIsStillAssignable() {
            Store stopped = store(2L, "已停业门店");
            stopped.setStatus(0);
            when(storeRepository.findById(2L)).thenReturn(Optional.of(stopped));
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);

            Result<StaffView> r = service.createStaff(createCmd("mgr6", 1, 2L));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().storeName()).isEqualTo("已停业门店");
        }

        @Test
        @DisplayName("用户名重复 → 400 用户名已存在（先查再报，不靠撞唯一键）")
        void rejectsDuplicateUsername() {
            when(staffRepository.existsByUsername("mgr3")).thenReturn(true);

            Result<StaffView> r = service.createStaff(createCmd("mgr3", 1, 1L));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("用户名已存在");
            verify(staffRepository, never()).insert(any());
        }

        @Test
        @DisplayName("并发下被别人抢先建了同名 → 撞唯一键也回同一句 400，不是 500")
        void duplicateKeyRaceGivesSameMessage() {
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);
            // existsByUsername 查完到 insert 之间，别人插了同名账号
            org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_username"))
                    .when(staffRepository).insert(any());

            Result<StaffView> r = service.createStaff(createCmd("mgr3", 1, 1L));

            assertThat(r.code()).isEqualTo(400);
            // 与先查再报那句**逐字相同** —— 对调用方来说这就是同一件事
            assertThat(r.message()).isEqualTo("用户名已存在");
        }

        @Test
        @DisplayName("用户名/密码/姓名为空 → 各回各的 400")
        void requiredFields() {
            assertThat(service.createStaff(
                    new CreateStaffCommand("  ", "pw", "李四", 1, null, null)).message())
                    .isEqualTo("用户名不能为空");
            assertThat(service.createStaff(
                    new CreateStaffCommand("mgr3", "", "李四", 1, null, null)).message())
                    .isEqualTo("密码不能为空");
            assertThat(service.createStaff(
                    new CreateStaffCommand("mgr3", "pw", null, 1, null, null)).message())
                    .isEqualTo("姓名不能为空");
        }

        @Test
        @DisplayName("角色为 null → 返回 400 角色不能为空（必填，走 Result）")
        void nullRole() {
            assertThat(service.createStaff(
                    new CreateStaffCommand("mgr3", "pw", "李四", null, null, null)).message())
                    .isEqualTo("角色不能为空");
        }

        @Test
        @DisplayName("角色非法 → 复用 StaffRole.fromCode 那句（**抛** BusinessException，不是 Result）")
        void illegalRole() {
            // 这句是 StaffRole.fromCode 抛的，全局处理器接住转成 400。
            // 复用而不另造一句 —— 否则同一个错误会有两种说法。
            assertThatThrownBy(() -> service.createStaff(
                    new CreateStaffCommand("mgr3", "pw", "李四", 5, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("没有这个角色: 5");
        }

        @Test
        @DisplayName("长度越界 → BusinessException（全局处理器转 400），不是撞 VARCHAR 的英文 SQL 异常")
        void lengthLimits() {
            // 三条都由 domain 的静态方法抛 —— "长度上限只有一个家"
            assertThatThrownBy(() -> service.createStaff(new CreateStaffCommand(
                    "mgr3", "pw", "衣".repeat(21), 1, null, null)))
                    .hasMessage("姓名不能超过 20 个字");
            assertThatThrownBy(() -> service.createStaff(new CreateStaffCommand(
                    "u".repeat(31), "pw", "李四", 1, null, null)))
                    .hasMessage("用户名不能超过 30 个字");
            assertThatThrownBy(() -> service.createStaff(new CreateStaffCommand(
                    "mgr3", "pw", "李四", 1, null, "1".repeat(21))))
                    .hasMessage("手机号不能超过 20 个字");
            verify(staffRepository, never()).insert(any());
        }

        @Test
        @DisplayName("刚好 20 个 emoji 的姓名 → 放行（codePointCount 数的是字符，不是 UTF-16 码元）")
        void emojiNameCountsAsChars() {
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);

            Result<StaffView> r = service.createStaff(new CreateStaffCommand(
                    "mgr3", "pw", "😀".repeat(20), 1, null, null));

            assertThat(r.code()).isEqualTo(200);
        }

        @Test
        @DisplayName("密码超过 72 字节 → 400（BCrypt 的输入上限，超了会截断或抛异常）")
        void passwordByteLimit() {
            // 25 个汉字 = 75 字节 > 72，但只有 25 个字符 —— 按字符数是"没超"的
            String tooLong = "密".repeat(25);

            Result<StaffView> r = service.createStaff(new CreateStaffCommand(
                    "mgr3", tooLong, "李四", 1, null, null));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).contains("密码过长");
        }

        @Test
        @DisplayName("手机号传空白串 → 存 null，不存空串（V9 的教训：'' 不等于 NULL）")
        void blankPhoneBecomesNull() {
            when(staffRepository.existsByUsername(anyString())).thenReturn(false);

            Result<StaffView> r = service.createStaff(new CreateStaffCommand(
                    "mgr3", "pw", "李四", 1, null, "   "));

            assertThat(r.data().phone()).isNull();
            var captor = org.mockito.ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).insert(captor.capture());
            assertThat(captor.getValue().getPhone()).isNull();
        }

        @Test
        @DisplayName("用户名首尾空格被 trim 掉（否则 ' mgr3' 和 'mgr3' 是两个账号）")
        void usernameIsTrimmed() {
            when(staffRepository.existsByUsername("mgr3")).thenReturn(false);

            Result<StaffView> r = service.createStaff(createCmd("  mgr3  ", 1, 1L));

            assertThat(r.data().username()).isEqualTo("mgr3");
        }
    }

    // ═══════════════════════ 改资料 ═══════════════════════

    @Nested
    @DisplayName("改资料 —— 谁会被踢下线")
    class Update {

        @Test
        @DisplayName("**改姓名/手机号不作废 token** —— 改个错别字不该把人弹下线")
        void renameDoesNotRevoke() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            staffExists(s);
            storeExists(1L, "云洗中央门店");

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("李四", 1, 1L, "13700000000"));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().name()).isEqualTo("李四");
            assertThat(r.data().phone()).isEqualTo("13700000000");
            verifyNoInteractions(tokenRevoker);
        }

        @Test
        @DisplayName("改角色（管理员降成店长）→ **作废该员工全部 token** 一次")
        void demoteRevokes() {
            Staff s = staff(5L, "admin2", 0, null);
            staffExists(s);
            storeExists(1L, "云洗中央门店");

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("张管理", 1, 1L, null));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().role()).isEqualTo(StaffRole.MANAGER);
            // token 里签着旧的 role=0，不作废的话他还能继续管人与店（最长 24 小时）
            verify(tokenRevoker).revokeAll(5L);
        }

        @Test
        @DisplayName("降成店长时门店不存在 → 400，且**不**作废 token、**不**写库")
        void demoteToMissingStoreChangesNothing() {
            Staff s = staff(5L, "admin2", 0, null);
            staffExists(s);
            when(storeRepository.findById(999L)).thenReturn(Optional.empty());

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("张管理", 1, 999L, null));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("门店不存在");
            // 校验失败就该什么都不发生 —— 白白踢人下线才是更坏的失败
            verify(staffRepository, never()).update(any());
            verifyNoInteractions(tokenRevoker);
        }

        @Test
        @DisplayName("改所属门店（调岗）→ 作废 token（token 里的 storeId 变成旧值）")
        void storeChangeRevokes() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            staffExists(s);
            storeExists(2L, "云洗城南店");

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("张店长", 1, 2L, null));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().storeId()).isEqualTo(2L);
            verify(tokenRevoker).revokeAll(5L);
        }

        @Test
        @DisplayName("改门店为空（撤销归店）→ 也算变了，同样作废")
        void clearingStoreRevokes() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            staffExists(s);

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("张店长", 1, null, null));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().storeId()).isNull();
            verify(tokenRevoker).revokeAll(5L);
        }

        @Test
        @DisplayName("员工不存在 → 404 员工不存在")
        void missingStaff() {
            when(staffRepository.findById(9999L)).thenReturn(Optional.empty());

            Result<StaffView> r = service.updateStaff(9999L,
                    new UpdateStaffCommand("李四", 1, null, null));

            assertThat(r.code()).isEqualTo(404);
            assertThat(r.message()).isEqualTo("员工不存在");
        }
    }

    // ═══════════════════════ 停用 / 启用 ═══════════════════════

    @Nested
    @DisplayName("启用与停用")
    class Status {

        @Test
        @DisplayName("停用 → 200 且作废该员工全部 token")
        void disableRevokes() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            staffExists(s);

            Result<StaffView> r = service.updateStatus(5L, 0);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().status()).isZero();
            verify(tokenRevoker).revokeAll(5L);
        }

        @Test
        @DisplayName("启用 → 200 但**不**动作废：停用期间签不出票，没有可作废的东西")
        void enableDoesNotRevoke() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            s.setStatus(0);
            staffExists(s);

            Result<StaffView> r = service.updateStatus(5L, 1);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().status()).isEqualTo(1);
            verifyNoInteractions(tokenRevoker);
        }

        @Test
        @DisplayName("status 不是 0/1 → 400（形状问题，拦在写库之前）")
        void statusRange() {
            assertThat(service.updateStatus(5L, 2).message())
                    .isEqualTo("状态只能是 0(停用) 或 1(启用)");
            assertThat(service.updateStatus(5L, null).code()).isEqualTo(400);
            verifyNoInteractions(staffRepository);
        }

        @Test
        @DisplayName("员工不存在 → 404")
        void missingStaff() {
            when(staffRepository.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.updateStatus(9L, 0).code()).isEqualTo(404);
        }
    }

    // ═══════════════════════ 重置密码 ═══════════════════════

    @Nested
    @DisplayName("重置密码")
    class Password {

        @Test
        @DisplayName("改密码 → 写新哈希，并**一定**作废全部 token（改密 = 全设备下线）")
        void resetRevokes() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            String oldHash = s.getPasswordHash();
            staffExists(s);
            storeExists(1L, "云洗中央门店");

            Result<StaffView> r = service.resetPassword(5L, "brandNewPw");

            assertThat(r.code()).isEqualTo(200);
            assertThat(s.getPasswordHash()).isNotEqualTo(oldHash);
            assertThat(new BCryptPasswordEncoder().matches("brandNewPw", s.getPasswordHash()))
                    .isTrue();
            verify(tokenRevoker).revokeAll(5L);
        }

        @Test
        @DisplayName("员工不存在 → 404，**连密码都不看**（先查人、后查密码）")
        void missingStaff() {
            when(staffRepository.findById(404L)).thenReturn(Optional.empty());

            Result<StaffView> r = service.resetPassword(404L, "brandNewPw");

            assertThat(r.code()).isEqualTo(404);
            assertThat(r.message()).isEqualTo("员工不存在");
            // 顺序反过来的话（先校验密码再查人），"人不在 + 密码为空"会报
            // 密码不能为空 —— 把管理员往"是不是密码写错了"的方向引，
            // 而真正的原因是他点了一个已经被删掉的员工
            assertThat(service.resetPassword(404L, "  ").code()).isEqualTo(404);

            verify(staffRepository, never()).update(any());
            verifyNoInteractions(tokenRevoker);
        }

        @Test
        @DisplayName("空密码 → 400；超 72 字节 → 400；都不写库")
        void rejections() {
            Staff s = staff(5L, "mgr3", 1, 1L);
            staffExists(s);

            assertThat(service.resetPassword(5L, "  ").message()).isEqualTo("密码不能为空");
            assertThat(service.resetPassword(5L, "密".repeat(25)).message()).contains("密码过长");
            verify(staffRepository, never()).update(any());
            verifyNoInteractions(tokenRevoker);
        }
    }

    // ═══════════════════════ 查 ═══════════════════════

    @Nested
    @DisplayName("列表与详情")
    class Read {

        @Test
        @DisplayName("列表含**已停用**的员工，且门店名一次查完（不是逐个 findById）")
        void listIncludesDisabled() {
            Staff active = staff(5L, "mgr3", 1, 1L);
            Staff disabled = staff(6L, "mgr4", 1, 1L);
            disabled.setStatus(0);
            when(staffRepository.findAll()).thenReturn(List.of(active, disabled));
            when(storeRepository.findAll()).thenReturn(List.of(store(1L, "云洗中央门店")));

            Result<List<StaffView>> r = service.listStaff();

            assertThat(r.data()).hasSize(2);
            assertThat(r.data().get(1).status()).isZero();
            assertThat(r.data()).allSatisfy(v ->
                    assertThat(v.storeName()).isEqualTo("云洗中央门店"));
            // 一次 findAll，不在循环里 findById
            verify(storeRepository).findAll();
            verify(storeRepository, never()).findById(any());
        }

        @Test
        @DisplayName("员工的门店查不到（没归店 / 脏数据）→ storeName 为 null，不抛")
        void danglingStoreIdIsTolerated() {
            Staff s = staff(5L, "mgr3", 1, 777L);
            when(staffRepository.findAll()).thenReturn(List.of(s));
            when(storeRepository.findAll()).thenReturn(List.of());   // 777 不在里面

            Result<List<StaffView>> r = service.listStaff();

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().get(0).storeName()).isNull();
        }

        @Test
        @DisplayName("详情：员工不存在 → 404")
        void detailMissing() {
            when(staffRepository.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.getStaff(9L).code()).isEqualTo(404);
        }

        @Test
        @DisplayName("详情：拼上门店名，字段逐个对得上（编辑页按 id 回显就是这条）")
        void detailReturnsView() {
            staffExists(staff(5L, "mgr3", 1, 1L));
            storeExists(1L, "云洗中央门店");

            Result<StaffView> r = service.getStaff(5L);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().id()).isEqualTo(5L);
            assertThat(r.data().username()).isEqualTo("mgr3");
            assertThat(r.data().name()).isEqualTo("张店长");
            assertThat(r.data().role()).isEqualTo(StaffRole.MANAGER);
            assertThat(r.data().storeId()).isEqualTo(1L);
            assertThat(r.data().storeName()).isEqualTo("云洗中央门店");
            assertThat(r.data().status()).isEqualTo(1);
        }

        @Test
        @DisplayName("详情：role 是 NULL（库里的脏行）→ 原样返回 null，不是 400")
        void nullRolePassesThrough() {
            Staff dirty = staff(5L, "mgr3", 1, 1L);
            dirty.setRole(null);     // staff.role 没有 NOT NULL 约束，这种行是可能的
            staffExists(dirty);
            storeExists(1L, "云洗中央门店");

            Result<StaffView> r = service.getStaff(5L);

            // 出参这一层刻意放行 null：读接口不该因为**一行**的 role 是空的
            // 就整个 400。而**越界的码**（比如 3）照样让 StaffRole.fromCode 抛 ——
            // 那是"不是任何已知角色"，必须响，不能悄悄显示成前端看不懂的空值
            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().role()).isNull();
        }
    }

    // ═══════════════════ 改资料的字段校验 ═══════════════════
    //
    // 上面那个 Update 组钉的是"谁会被踢下线"，这一组钉的是**字段本身合不合法**。
    // 为什么要单开一组：这套校验在 createStaff 和 updateStaff 里是**各写了一遍**
    // （两处都在方法体开头，没有抽成共用方法），建号那一侧测全了、
    // 改资料这一侧原先一条都没有 —— 于是"建号时填不进去的脏数据，改资料时能塞进去"
    // 这个口子没有任何东西在守。而这两个入口在真实系统里是同一个编辑表单。

    @Nested
    @DisplayName("改资料的字段校验（改资料不能比建号松）")
    class UpdateValidation {

        /** 5 号店长（归 1 号门店）已在库里，且 1 号门店存在 —— 一次备好后续所有用例的前置 */
        private void existingManager() {
            staffExists(staff(5L, "mgr3", 1, 1L));
            storeExists(1L, "云洗中央门店");
        }

        @Test
        @DisplayName("姓名空白 → 400 姓名不能为空，不写库")
        void blankNameRejected() {
            existingManager();

            for (String bad : new String[]{null, "", "   "}) {
                Result<StaffView> r = service.updateStaff(5L,
                        new UpdateStaffCommand(bad, 1, 1L, null));
                assertThat(r.code()).isEqualTo(400);
                assertThat(r.message()).isEqualTo("姓名不能为空");
            }
            verify(staffRepository, never()).update(any());
        }

        @Test
        @DisplayName("姓名超长 → 抛，且和建号是同一句")
        void tooLongNameRejected() {
            existingManager();

            assertThatThrownBy(() -> service.updateStaff(5L,
                    new UpdateStaffCommand("衣".repeat(21), 1, 1L, null)))
                    .hasMessage("姓名不能超过 20 个字");
            verify(staffRepository, never()).update(any());
        }

        @Test
        @DisplayName("手机号超长 → 抛（上限只有 domain 那一处，两个入口共用）")
        void tooLongPhoneRejected() {
            existingManager();

            assertThatThrownBy(() -> service.updateStaff(5L,
                    new UpdateStaffCommand("李四", 1, 1L, "1".repeat(21))))
                    .hasMessage("手机号不能超过 20 个字");
            verify(staffRepository, never()).update(any());
        }

        @Test
        @DisplayName("手机号传空白串 → 存 null（把已填的手机号改回「没填」是合法操作）")
        void blankPhoneBecomesNull() {
            existingManager();

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("李四", 1, 1L, "   "));

            assertThat(r.code()).isEqualTo(200);
            var captor = org.mockito.ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).update(captor.capture());
            assertThat(captor.getValue().getPhone()).isNull();
        }

        @Test
        @DisplayName("role=null → 400 角色不能为空（走 Result，不是抛）")
        void nullRoleRejected() {
            existingManager();

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("李四", null, 1L, null));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("角色不能为空");
            verify(staffRepository, never()).update(any());
        }

        @Test
        @DisplayName("role 非法（3）→ 抛 StaffRole.fromCode 那句，不另造一句")
        void illegalRoleRejected() {
            existingManager();

            // 复用的是 StaffRole.fromCode 的原话，不在这里另写 ——
            // 同一个错误两种说法，用户会当成两回事
            assertThatThrownBy(() -> service.updateStaff(5L,
                    new UpdateStaffCommand("李四", 3, 1L, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("没有这个角色");
        }

        @Test
        @DisplayName("改成管理员 → storeId 被强制成 null，哪怕传了值（与建号同一条规则）")
        void adminGetsNoStore() {
            existingManager();

            Result<StaffView> r = service.updateStaff(5L,
                    new UpdateStaffCommand("张管理", 0, 1L, null));

            assertThat(r.code()).isEqualTo(200);
            var captor = org.mockito.ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).update(captor.capture());
            assertThat(captor.getValue().getRole()).isZero();
            assertThat(captor.getValue().getStoreId()).isNull();
        }

        @Test
        @DisplayName("姓名首尾空格被 trim（否则「李四 」和「李四」在库里看不出是两个人）")
        void nameIsTrimmed() {
            existingManager();

            service.updateStaff(5L, new UpdateStaffCommand("  李四  ", 1, 1L, null));

            var captor = org.mockito.ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).update(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("李四");
        }
    }

    // ═══════════════════════ 出参形状 ═══════════════════════

    @Nested
    @DisplayName("StaffView 的形状")
    class ResponseShape {

        @Test
        @DisplayName("**没有** password / passwordHash 字段 —— 类型上就没有这个位置")
        void hasNoPasswordField() {
            List<String> names = Arrays.stream(StaffView.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(Collectors.toList());

            assertThat(names).containsExactly(
                    "id", "username", "name", "role", "storeId", "storeName", "phone", "status");
            assertThat(names).noneMatch(n -> n.toLowerCase().contains("password"));
        }
    }
}
