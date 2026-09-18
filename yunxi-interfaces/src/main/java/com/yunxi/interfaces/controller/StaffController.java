package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.CreateStaffCommand;
import com.yunxi.application.dto.StaffView;
import com.yunxi.application.dto.UpdateStaffCommand;
import com.yunxi.application.service.StaffAdminAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.interfaces.dto.CreateStaffRequest;
import com.yunxi.interfaces.dto.ResetPasswordRequest;
import com.yunxi.interfaces.dto.UpdateStaffRequest;
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
 * 员工管理接口 —— 管理员专属（§4.2「增删改查店长/员工」）。
 *
 * 设计文档 §6.4 点名了这个前缀，但一直没实现；2026-09-18 落地。
 * 背景见 §8："管理员被授权做的事，恰好就是没实现的那部分。这不是权限配错了。"
 *
 * ═══ 鉴权三道，各管各的 ═══
 *
 *   · 无 token / token 无效 / 已被作废 → 401（JwtInterceptor）
 *   · **店长** → 403（JwtInterceptor 的角色闸门，按 URL 收口，不在这重复判断）
 *   · **顾客** → 401（下面 requireStaff，"请使用员工账号操作"）
 *
 * 中间那道是**反方向**的：既有的闸门只拦管理员（"管理员不参与订单操作"），
 * 这个前缀需要的是"拦店长"，那条规则和它并排写在 checkRoleGate 里。
 *
 * 为什么**整个前缀**都归管理员，连读接口也是（不像 /api/stores 的读对所有员工开放）：
 *   · 店长没有任何一处需要员工名册（他的活是订单和定价）
 *   · 名册里有 phone，而且这是最可能长出"重置密码"这类接口的地方 ——
 *     一个没人需要的读接口，将来只会变成一个漏点
 *   · 局部开读要往闸门里再塞一条"GET 放行、其余禁止"的方法级判断，
 *     而 /api/customers 的注释刚说过"按前缀一刀切就对了"
 *
 * ═══ 「员工」就是店长 ═══
 * staff 表里只有 role=0 / role=1 两档，没有"员工"这个角色（见 StaffRole）。
 * 所以 POST 一个 role=1 = 新建一个店长，没有第三档。
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffAdminAppService staffAdminAppService;

    public StaffController(StaffAdminAppService staffAdminAppService) {
        this.staffAdminAppService = staffAdminAppService;
    }

    /** 员工列表（**含已停用**）—— 停掉的人必须看得见，否则没法把他启用回来 */
    @GetMapping
    public Result<List<StaffView>> list(HttpServletRequest http) {
        requireStaff(http);
        return staffAdminAppService.listStaff();
    }

    /** 员工详情（编辑页按 id 回显）。例：GET /api/staff/5 */
    @GetMapping("/{id}")
    public Result<StaffView> detail(@PathVariable Long id, HttpServletRequest http) {
        requireStaff(http);
        return staffAdminAppService.getStaff(id);
    }

    /**
     * 新建员工。例：POST /api/staff
     * body {"username":"mgr3","password":"pw","name":"张三","role":1,"storeId":1,"phone":null}
     */
    @PostMapping
    public Result<StaffView> create(@RequestBody CreateStaffRequest request,
                                    HttpServletRequest http) {
        requireStaff(http);
        // 接口层 DTO → 应用层命令对象，不直接传 domain 的 Staff（§3.1：接口层不引用 domain）
        return staffAdminAppService.createStaff(new CreateStaffCommand(
                request.username(), request.password(), request.name(),
                request.role(), request.storeId(), request.phone()));
    }

    /**
     * 改资料：姓名 / 角色 / 门店 / 手机号（调岗就在这里）。
     * 例：PUT /api/staff/5  body {"name":"李四","role":1,"storeId":2,"phone":"13700000000"}
     *
     * 改 role 或 storeId 会**作废该员工已签发的全部 token**（token 里签着旧值）。
     * 改姓名/手机号不会 —— 改个错别字不该把柜台电脑上的人弹下线。
     */
    @PutMapping("/{id}")
    public Result<StaffView> update(@PathVariable Long id,
                                    @RequestBody UpdateStaffRequest request,
                                    HttpServletRequest http) {
        requireStaff(http);
        return staffAdminAppService.updateStaff(id, new UpdateStaffCommand(
                request.name(), request.role(), request.storeId(), request.phone()));
    }

    /**
     * 重置密码。例：PUT /api/staff/5/password  body {"password":"newpw"}
     * **副作用：该员工手上所有 token 立刻作废**（改密码 = 从前凭据不算数 + 全设备下线）。
     */
    @PutMapping("/{id}/password")
    public Result<StaffView> resetPassword(@PathVariable Long id,
                                           @RequestBody ResetPasswordRequest request,
                                           HttpServletRequest http) {
        requireStaff(http);
        return staffAdminAppService.resetPassword(id, request.password());
    }

    /**
     * 启用（1）/ 停用（0）。例：PUT /api/staff/5/status  body {"status":0}
     * **停用后那张已签发的 token 立刻失效**，不用等 24 小时自然过期。
     *
     * ⚠️ 可以停用/降级**自己**，也可以把最后一个管理员降成店长 —— 系统不拦
     * （2026-09-18 用户明确拍板）。真锁死了只能直连数据库改回来。
     */
    @PutMapping("/{id}/status")
    public Result<StaffView> updateStatus(@PathVariable Long id,
                                          @RequestBody UpdateStatusRequest request,
                                          HttpServletRequest http) {
        requireStaff(http);
        return staffAdminAppService.updateStatus(id, request.status());
    }

    // ═══════════════════════ 内部方法 ═══════════════════

    /**
     * 把**顾客** token 挡在门外。
     *
     * 为什么这道判断在 controller 而不在闸门里：闸门只管员工之间的事
     * （管理员 vs 店长），"顾客能用哪些接口"一律由各 controller 自己判断 ——
     * 这是 checkRoleGate 注释里写明的分工，不在这重复。
     */
    private void requireStaff(HttpServletRequest http) {
        if (!"staff".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
    }
}
