package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.StoreAdminView;
import com.yunxi.application.dto.StoreCommand;
import com.yunxi.application.dto.StoreView;
import com.yunxi.application.service.StoreAdminAppService;
import com.yunxi.application.service.StoreAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.interfaces.dto.StoreRequest;
import com.yunxi.interfaces.dto.UpdateStatusRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店接口 —— 一个**混合前缀**：读开放，写只认管理员。
 *
 * ═══ 鉴权（2026-09-18 起，比原来多了一半）═══
 *
 *   · **读** GET /api/stores、GET /api/stores/all —— **任意有效 token**
 *     （和价目表的读接口同款口径）。门店信息本来就是公开的：顾客下单必须先选门店，
 *     看不到店就没法下单；拦在 token 这一层（/api/** 由 JwtInterceptor 统一把守）
 *     已足够挡住公网匿名访问
 *   · **写** POST / PUT —— **只认管理员**。这道闸门在 JwtInterceptor 的
 *     checkRoleGate 里，**按方法分**（GET 放行、其余拦），因为门店的读不能误伤
 *   · 顾客 token 打**写**接口一律 401（那三个方法里各有一句 `requireStaff`）
 *     —— 上面那道闸门只管"staff 内部管理员还是店长"，顾客由这里挡；
 *     **读接口不挡顾客**（和 /api/stores 一样是"任意有效 token"）
 *
 * ═══ 为什么按方法分，而不是把管理面挪到 /api/admin/stores ═══
 *
 * 挪走更"干净"（闸门就只剩前缀判断，不用看方法），但代价是门店的读写分居两个前缀，
 * "门店归谁管"要看两个文件才拼得出来。更重要的是：现存的口径是
 * **门店的读对所有人开放**，而 verify-stores.sh 有一条断言硬钉着
 * "管理员 GET /api/stores 必须 200" —— 按方法分是这个口径最直接的表达。
 *
 * ═══ /api/stores/all 为什么另开一个而不是给老的加参数 ═══
 *
 * GET /api/stores 的语义是"**能下单的店**"（它只返回营业中的），这是顾客下单页和
 * 员工建单页指着的东西。给它加个 ?status=all 就有了两个意思，而且前端那条
 * "列表里没有 99 号停业店"的断言会开始依赖参数默认值。另开一个路由，
 * 两个语义各自都说得清。
 */
@RestController
@RequestMapping("/api")
public class StoreController {

    private final StoreAppService storeAppService;
    private final StoreAdminAppService storeAdminAppService;

    public StoreController(StoreAppService storeAppService,
                           StoreAdminAppService storeAdminAppService) {
        this.storeAppService = storeAppService;
        this.storeAdminAppService = storeAdminAppService;
    }

    // ═══════════════════════ 读（任意有效 token）═══════════════════════

    /**
     * 营业中的门店列表（只含 status=1）—— 顾客下单页与员工建单页共用的下拉框。
     * **这个方法一个字没改**：它只返回能下单的店，加停业店会直接改掉顾客下单页的行为。
     */
    @GetMapping("/stores")
    public Result<List<StoreView>> listStores() {
        return storeAppService.listOpenStores();
    }

    /**
     * **含停业**的门店列表 —— 管理页用。
     *
     * 口径：任意有效 token 都能读（和上面同款）。停业的店名和地址不是秘密，
     * 而闸门按"写才要管理员"来分，这里多一条路径例外只会让规则难读。
     * 它是管理**页**的数据来源，不是管理**权限**的边界 —— 后者由写接口把守。
     */
    @GetMapping("/stores/all")
    public Result<List<StoreAdminView>> listAllStores() {
        return storeAdminAppService.listAllStores();
    }

    // ═══════════════════════ 写（仅管理员）═══════════════════════════

    /**
     * 新建门店。例：POST /api/stores  body {"name":"云洗城南店","address":"...","phone":null}
     * 新建一律**营业**（没有"建出来就是停业的店"这种需求，要停再调一次 status）。
     */
    @PostMapping("/stores")
    public Result<StoreAdminView> createStore(@RequestBody StoreRequest request,
                                              HttpServletRequest http) {
        requireStaff(http);
        return storeAdminAppService.createStore(toCommand(request));
    }

    /** 改门店资料（名字/地址/电话）。例：PUT /api/stores/3 */
    @PutMapping("/stores/{id}")
    public Result<StoreAdminView> updateStore(@PathVariable Long id,
                                              @RequestBody StoreRequest request,
                                              HttpServletRequest http) {
        requireStaff(http);
        return storeAdminAppService.updateStore(id, toCommand(request));
    }

    /**
     * 启用（1）/ 停业（0）。例：PUT /api/stores/3/status  body {"status":0}
     *
     * 停业**立刻生效**：顾客的选店列表里马上看不到这家店，往它下单会得到
     * "门店不存在或已停业"。但店里员工的 token 不受影响（token 里签的是 storeId，
     * 不是营业状态），也不用踢人 —— 见 StoreAdminAppService 的类注释。
     */
    @PutMapping("/stores/{id}/status")
    public Result<StoreAdminView> updateStoreStatus(@PathVariable Long id,
                                                    @RequestBody UpdateStatusRequest request,
                                                    HttpServletRequest http) {
        requireStaff(http);
        return storeAdminAppService.updateStatus(id, request.status());
    }

    // ═══════════════════════ 内部方法 ═══════════════════

    /** 接口层 DTO → 应用层命令对象（不直接传 domain 的 Store，§3.1） */
    private StoreCommand toCommand(StoreRequest request) {
        return new StoreCommand(request.name(), request.address(), request.phone());
    }

    /**
     * 把**顾客** token 挡在门外（店长由闸门 403，到不了这里）。
     * 只在写接口上调 —— 读接口对顾客开放是刻意的。
     */
    private void requireStaff(HttpServletRequest http) {
        if (!"staff".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
    }
}
