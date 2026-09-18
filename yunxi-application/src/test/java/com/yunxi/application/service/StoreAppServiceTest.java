package com.yunxi.application.service;

import com.yunxi.application.dto.StoreView;
import com.yunxi.common.Result;
import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoreAppService 单元测试 —— 顾客/员工侧的"营业中门店"投影。
 *
 * 这个类薄得像张纸（一次 findOpen + 一次 map），它存在的理由是**接线**：
 * 接口层要的是一条不经过 domain 类型的路（类注释写明了）。所以这里钉的
 * 也不是什么算法，就一件事：**它用的是 findOpen，不是 findAll**。
 *
 * 这条规则和 StoreAdminAppServiceTest 那条是同一枚硬币的两面 —— 那边要求
 * 管理页用 findAll（含停业），这边要求顾客侧用 findOpen（只有营业中）。
 * 两边各写一遍不是重复：**换错任何一边，另一边照样绿**。顾客下单页要是
 * 冒出停业的店，人下了单没人接单，而管理页的测试什么都不说。
 */
class StoreAppServiceTest {

    private StoreRepository storeRepository;
    private StoreAppService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        service = new StoreAppService(storeRepository);
    }

    private Store store(Long id, String name, String phone) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setAddress("某某路 1 号");
        s.setPhone(phone);
        s.setStatus(1);
        return s;
    }

    @Nested
    @DisplayName("营业中的门店")
    class ListOpen {

        @Test
        @DisplayName("用 findOpen（**只有营业中**），不是 findAll —— 停业的店不该出现在下单页")
        void onlyOpenStores() {
            when(storeRepository.findOpen()).thenReturn(List.of(
                    store(1L, "云洗中央门店", "0571-88888888"),
                    store(3L, "云洗城南店", null)));   // 电话可以不填

            Result<List<StoreView>> r = service.listOpenStores();

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data()).hasSize(2);
            assertThat(r.data().get(0).id()).isEqualTo(1L);
            assertThat(r.data().get(0).name()).isEqualTo("云洗中央门店");
            assertThat(r.data().get(0).address()).isEqualTo("某某路 1 号");
            assertThat(r.data().get(0).phone()).isEqualTo("0571-88888888");
            // 没填就是没填 —— DTO 不替人编一个空串出来（NULL 和 '' 在这里
            // 确实是一个意思，但出参的形状该跟库里一致，前端好判断）
            assertThat(r.data().get(1).phone()).isNull();

            verify(storeRepository).findOpen();
            verify(storeRepository, never()).findAll();
        }

        @Test
        @DisplayName("一家都没营业 → 空列表，不是 null")
        void emptyIsEmptyList() {
            when(storeRepository.findOpen()).thenReturn(List.of());

            assertThat(service.listOpenStores().data()).isEmpty();
        }
    }
}
