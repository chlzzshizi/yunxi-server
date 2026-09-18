package com.yunxi.application.service;

import com.yunxi.application.dto.StoreAdminView;
import com.yunxi.application.dto.StoreCommand;
import com.yunxi.common.Result;
import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 门店管理应用服务 —— 建店、改店、启用/停业，外加"含停业的门店列表"（§4.2）。
 *
 * ═══ 为什么不并进 StoreAppService ═══
 *
 * StoreAppService 只有一个方法（列营业中的门店），它的读者是**顾客和店长**：
 * 下单要选店。这个类的读者只有**管理员**：管理门店。两件事的鉴权口径、
 * 出参投影、校验规则全都不同，合成一个类的唯一结果是"这个类归谁"变得说不清。
 * 拆开的先例是 CustomerAppService（员工代客建档）与 CustomerProfileAppService
 * （顾客自助改档案）—— 长得像、读者不同，就分成两个。
 *
 * ═══ 为什么改门店不动作废 token ═══
 *
 * 员工的 token 里签的是 **storeId**，不是门店的营业状态。所以：
 *   · 改名字/地址/电话 —— 与 token 无关，本来就不该动
 *   · 停业 —— token 里的 storeId 依然是对的，它没变成"过期事实"
 * 停业的真实后果发生在**下单那一刻**：OrderAppService 会拿 storeId 现查
 * findOpenById，查到已停业就回"门店不存在或已停业"。也就是说停业是**即时生效**的，
 * 不需要靠踢人下线来实现。
 *
 * （顺便说明"停业门店 ≠ 数据隔离"：它只是一条"别往这派活"的提示，
 * 店长照样能管所有门店的订单，那一层的口径由 §6.4 的"门店只是地理位置"决定。）
 */
@Service
public class StoreAdminAppService {

    private final StoreRepository storeRepository;

    public StoreAdminAppService(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    /** 全部门店（**含停业**）—— 管理页用。与 StoreAppService.listOpenStores 是两个投影 */
    public Result<List<StoreAdminView>> listAllStores() {
        return Result.ok(storeRepository.findAll().stream().map(StoreAdminView::from).toList());
    }

    /** 新建门店。新建一律**营业** —— 没有"建出来就是停业的店"这种需求 */
    public Result<StoreAdminView> createStore(StoreCommand cmd) {
        Result<Void> invalid = validate(cmd);
        if (invalid != null) {
            return Result.fail(invalid.code(), invalid.message());
        }
        Store store = new Store();
        store.setName(cmd.name().trim());
        store.setAddress(cmd.address().trim());
        store.setPhone(blankToNull(cmd.phone()));
        store.setStatus(1);
        storeRepository.insert(store);   // id 由实现回填
        return Result.ok(StoreAdminView.from(store));
    }

    /** 改门店资料（名字/地址/电话）。停业走 updateStatus，不在这里 */
    public Result<StoreAdminView> updateStore(Long id, StoreCommand cmd) {
        Store store = storeRepository.findById(id).orElse(null);
        if (store == null) {
            return Result.fail(404, "门店不存在");
        }
        Result<Void> invalid = validate(cmd);
        if (invalid != null) {
            return Result.fail(invalid.code(), invalid.message());
        }
        store.setName(cmd.name().trim());
        store.setAddress(cmd.address().trim());
        store.setPhone(blankToNull(cmd.phone()));
        storeRepository.update(store);
        return Result.ok(StoreAdminView.from(store));
    }

    /**
     * 启用（1）/ 停业（0）。软停业，**不硬删** —— 门店被订单引用着，删了历史订单的
     * store_id 就指向虚空（而且库里没有外键，数据库不会替你拦）。
     *
     * 停业后：顾客的选店列表（GET /api/stores）里立刻看不到这家店，
     * 往它下单会得到"门店不存在或已停业"；但它仍出现在管理列表（/all）里，
     * 管理员随时能把它重新开起来。
     */
    public Result<StoreAdminView> updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            return Result.fail(400, "状态只能是 0(停业) 或 1(营业)");
        }
        Store store = storeRepository.findById(id).orElse(null);
        if (store == null) {
            return Result.fail(404, "门店不存在");
        }
        store.setStatus(status);
        storeRepository.update(store);
        return Result.ok(StoreAdminView.from(store));
    }

    // ═══════════════════════ 内部方法 ═══════════════════

    /** 必填 + 长度。长度上限住在 Store 的静态方法里（超了抛 BusinessException → 400） */
    private Result<Void> validate(StoreCommand cmd) {
        if (isBlank(cmd.name())) {
            return Result.fail(400, "门店名称不能为空");
        }
        if (isBlank(cmd.address())) {
            return Result.fail(400, "地址不能为空");
        }
        Store.requireValidName(cmd.name());
        Store.requireValidAddress(cmd.address());
        Store.requireValidPhone(cmd.phone());
        return null;
    }

    /** 空白存 NULL 不存空串（理由见 StaffAdminAppService.blankToNull / V9 的教训） */
    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
