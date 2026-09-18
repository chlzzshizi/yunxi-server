package com.yunxi.application.service;

import com.yunxi.application.dto.StoreAdminView;
import com.yunxi.application.dto.StoreCommand;
import com.yunxi.common.Result;
import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoreAdminAppService 单元测试 —— Mockito 假仓储。
 *
 * 重点钉两件事：
 *   1. **两个投影不混**：listAllStores 用 findAll（含停业），
 *      而 StoreAppService 那条路用 findOpen（只营业中）—— 走错一个，
 *      管理页就看不见停业的店，或者顾客下单页会冒出停业的店
 *   2. 校验失败时**什么都不做**（不写库），别把"拒绝了"和"写了一半"混在一起
 */
class StoreAdminAppServiceTest {

    private StoreRepository storeRepository;
    private StoreAdminAppService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        service = new StoreAdminAppService(storeRepository);
    }

    private Store store(Long id, String name, int status) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setAddress("某某路 1 号");
        s.setPhone("0571-88888888");
        s.setStatus(status);
        return s;
    }

    private static StoreCommand cmd(String name, String address, String phone) {
        return new StoreCommand(name, address, phone);
    }

    @Nested
    @DisplayName("列表")
    class ListAll {

        @Test
        @DisplayName("用 findAll（**含停业**），不是 findOpen —— 管理员要能看见自己停掉的店")
        void includesStoppedStores() {
            when(storeRepository.findAll()).thenReturn(List.of(
                    store(1L, "云洗中央门店", 1),
                    store(99L, "已停业店", 0)));

            Result<List<StoreAdminView>> r = service.listAllStores();

            assertThat(r.data()).hasSize(2);
            assertThat(r.data().get(1).status()).isZero();
            // 走错成 findOpen 的话，管理员就永远看不到停业的店，
            // 也就没法把它重新开起来
            verify(storeRepository).findAll();
            verify(storeRepository, never()).findOpen();
        }
    }

    @Nested
    @DisplayName("新建门店")
    class Create {

        @Test
        @DisplayName("新建 → status=1（营业），id 由仓储回填")
        void createsOpenStore() {
            // 模拟仓储回填自增主键
            org.mockito.Mockito.doAnswer(inv -> {
                inv.getArgument(0, Store.class).setId(7L);
                return null;
            }).when(storeRepository).insert(any());

            Result<StoreAdminView> r = service.createStore(cmd("云洗城南店", "城南路 2 号", null));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().id()).isEqualTo(7L);
            assertThat(r.data().status()).isEqualTo(1);

            var captor = org.mockito.ArgumentCaptor.forClass(Store.class);
            verify(storeRepository).insert(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(1);
        }

        @Test
        @DisplayName("电话传空白串 → 存 null，不存空串（V9：'' 不等于 NULL）")
        void blankPhoneBecomesNull() {
            service.createStore(cmd("云洗城南店", "城南路 2 号", "   "));

            var captor = org.mockito.ArgumentCaptor.forClass(Store.class);
            verify(storeRepository).insert(captor.capture());
            assertThat(captor.getValue().getPhone()).isNull();
        }

        @Test
        @DisplayName("名称/地址为空 → 400，不写库")
        void requiredFields() {
            assertThat(service.createStore(cmd("  ", "城南路 2 号", null)).message())
                    .isEqualTo("门店名称不能为空");
            assertThat(service.createStore(cmd("云洗城南店", null, null)).message())
                    .isEqualTo("地址不能为空");
            verify(storeRepository, never()).insert(any());
        }

        @Test
        @DisplayName("长度越界 → BusinessException（全局处理器转 400），不是撞 VARCHAR 的英文 SQL 异常")
        void lengthLimits() {
            // 必填走 Result.fail，超长走 domain 静态方法抛 —— 两种机制，
            // 但**到了 HTTP 上都是 400**（BusinessException 被全局处理器接住）。
            // 这里断言的是抛，因为超长确实不经过返回值。
            assertThatThrownBy(() -> service.createStore(cmd("店".repeat(51), "路", null)))
                    .hasMessage("门店名称不能超过 50 个字");
            assertThatThrownBy(() -> service.createStore(cmd("店", "路".repeat(201), null)))
                    .hasMessage("地址不能超过 200 个字");
            assertThatThrownBy(() -> service.createStore(cmd("店", "路", "1".repeat(21))))
                    .hasMessage("电话不能超过 20 个字");
            verify(storeRepository, never()).insert(any());
        }
    }

    @Nested
    @DisplayName("改门店")
    class Update {

        @Test
        @DisplayName("改名/址/电话 → 200，**status 不被顺手改掉**（那走另一个端点）")
        void updatesInfoKeepingStatus() {
            Store stopped = store(3L, "老名字", 0);   // 已停业
            when(storeRepository.findById(3L)).thenReturn(Optional.of(stopped));

            Result<StoreAdminView> r = service.updateStore(3L,
                    cmd("新名字", "新地址 9 号", "0571-12345678"));

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().name()).isEqualTo("新名字");
            // 改个名字不该把停业的店悄悄开起来
            assertThat(r.data().status()).isZero();
        }

        @Test
        @DisplayName("门店不存在 → 404 门店不存在")
        void missingStore() {
            when(storeRepository.findById(9999L)).thenReturn(Optional.empty());

            Result<StoreAdminView> r = service.updateStore(9999L, cmd("店", "路", null));

            assertThat(r.code()).isEqualTo(404);
            assertThat(r.message()).isEqualTo("门店不存在");
            verify(storeRepository, never()).update(any());
        }

        @Test
        @DisplayName("改的门店**已停业**照样能改 —— findById 不过滤 status")
        void canUpdateStoppedStore() {
            when(storeRepository.findById(99L))
                    .thenReturn(Optional.of(store(99L, "停业中", 0)));

            Result<StoreAdminView> r = service.updateStore(99L, cmd("还是要改", "路", null));

            assertThat(r.code()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("启用与停业")
    class Status {

        @Test
        @DisplayName("停业 → status=0，写库")
        void stop() {
            when(storeRepository.findById(1L))
                    .thenReturn(Optional.of(store(1L, "云洗中央门店", 1)));

            Result<StoreAdminView> r = service.updateStatus(1L, 0);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().status()).isZero();
            verify(storeRepository).update(any());
        }

        @Test
        @DisplayName("重新营业 → status=1")
        void reopen() {
            when(storeRepository.findById(1L))
                    .thenReturn(Optional.of(store(1L, "云洗中央门店", 0)));

            Result<StoreAdminView> r = service.updateStatus(1L, 1);

            assertThat(r.data().status()).isEqualTo(1);
        }

        @Test
        @DisplayName("status 不是 0/1 → 400，不写库、不查库")
        void statusRange() {
            assertThat(service.updateStatus(1L, 2).message())
                    .isEqualTo("状态只能是 0(停业) 或 1(营业)");
            assertThat(service.updateStatus(1L, null).code()).isEqualTo(400);
            verify(storeRepository, never()).update(any());
        }

        @Test
        @DisplayName("门店不存在 → 404")
        void missingStore() {
            when(storeRepository.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.updateStatus(9L, 0).code()).isEqualTo(404);
        }
    }

    // ═══════════════════ 改门店的字段校验 ═══════════════════
    //
    // validate(cmd) 在 createStore 和 updateStore 里调的是**同一个方法**
    // （StaffAdmin 那两处是复制的，这里是共用的），但**调用点仍是两处**：
    // 建店那一侧把必填与三条长度测全了，改店这一侧一条都没有。
    // 所以这一组补的不是 validate 本身（上面 Create 那组已经覆盖了它的内部），
    // 而是"这个调用点确实接上了"—— 少了它，有人把 updateStore 里那两行
    // validate(cmd) 删掉，全部测试照样绿，而脏数据能从编辑页进库。

    @Nested
    @DisplayName("改门店的字段校验（validate 在改的这一侧也接上了）")
    class UpdateValidation {

        /** 3 号门店已在库里 —— updateStore 是**先 findById 再 validate**，顺序不能颠倒 */
        private void existing() {
            when(storeRepository.findById(3L))
                    .thenReturn(Optional.of(store(3L, "老名字", 1)));
        }

        @Test
        @DisplayName("名称空白 → 400 门店名称不能为空，且一个字段都没写进去")
        void blankNameRejected() {
            existing();

            for (String bad : new String[]{null, "", "   "}) {
                Result<StoreAdminView> r = service.updateStore(3L, cmd(bad, "路 1 号", null));
                assertThat(r.code()).isEqualTo(400);
                assertThat(r.message()).isEqualTo("门店名称不能为空");
            }
            verify(storeRepository, never()).update(any());
        }

        @Test
        @DisplayName("地址空白 → 400 地址不能为空")
        void blankAddressRejected() {
            existing();

            Result<StoreAdminView> r = service.updateStore(3L, cmd("新名字", "   ", null));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("地址不能为空");
            verify(storeRepository, never()).update(any());
        }

        @Test
        @DisplayName("三条长度上限在改的路径上也拦（超长抛，不是撞 VARCHAR 变 500）")
        void lengthLimits() {
            existing();

            assertThatThrownBy(() -> service.updateStore(3L, cmd("店".repeat(51), "路", null)))
                    .hasMessage("门店名称不能超过 50 个字");
            assertThatThrownBy(() -> service.updateStore(3L, cmd("店", "路".repeat(201), null)))
                    .hasMessage("地址不能超过 200 个字");
            assertThatThrownBy(() -> service.updateStore(3L, cmd("店", "路", "1".repeat(21))))
                    .hasMessage("电话不能超过 20 个字");
            verify(storeRepository, never()).update(any());
        }

        @Test
        @DisplayName("电话传空白串 → 存 null（把电话清空是合法操作，不是把字段删掉）")
        void blankPhoneBecomesNull() {
            existing();

            Result<StoreAdminView> r = service.updateStore(3L, cmd("新名字", "路 1 号", "   "));

            assertThat(r.code()).isEqualTo(200);
            var captor = org.mockito.ArgumentCaptor.forClass(Store.class);
            verify(storeRepository).update(captor.capture());
            assertThat(captor.getValue().getPhone()).isNull();
        }
    }
}
