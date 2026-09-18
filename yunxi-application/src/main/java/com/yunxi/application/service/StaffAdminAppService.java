package com.yunxi.application.service;

import com.yunxi.application.dto.CreateStaffCommand;
import com.yunxi.application.dto.StaffView;
import com.yunxi.application.dto.UpdateStaffCommand;
import com.yunxi.common.Result;
import com.yunxi.common.enums.StaffRole;
import com.yunxi.domain.staff.Staff;
import com.yunxi.domain.staff.StaffRepository;
import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 员工管理应用服务 —— 建号、改名调岗、重置密码、启用停用（§4.2「管理员只管人与店」）。
 *
 * ═══ 鉴权不在这里 ═══
 *
 * "只有管理员能调这些接口"是 URL 级的闸门，在 JwtInterceptor.checkRoleGate 里
 * 一处收口（见那里的注释）。这里**一行角色判断都没有** —— 若在六个方法里各写一遍
 * `if (role != 0)`，规则就摊薄成六份，将来加第七个方法就多一个漏点。
 *
 * ═══ 「员工」就是店长 ═══
 *
 * 2026-09-18 口径：staff 表里只有 role=0（管理员）和 role=1（店长）两档，
 * StaffRole 枚举里**没有"员工"这个值**。所以"新建一个员工" = 建一个 role=1 的账号，
 * 没有第三档可选。设计文档 §4.2 那句"增删改查店长/员工"读起来像两种角色，
 * 实际是一种。
 *
 * ═══ 校验的分工 ═══
 *
 * 长度上限在 domain 的静态方法里（Staff.requireValidName 等，超了抛
 * BusinessException → 400 中文），"必填"和跨聚合的规则（用户名唯一、门店存在）
 * 在这里。形状问题（字段在不在、status 是不是 0/1）在 controller ——
 * 和 PriceController / PriceAppService 的分工一致。
 */
@Service
public class StaffAdminAppService {

    /**
     * BCrypt 的输入上限是 **72 字节**。
     *
     * 为什么要显式拦：超长的密码交给 BCrypt，行为取决于实现 —— 要么抛
     * IllegalArgumentException（被 GlobalExceptionHandler 兜成一句
     * 无用的"参数不正确"，Bug 23 那条通道），要么**静默截断**。
     * 后者更坏：截断意味着前 72 字节相同的两个不同密码会验成同一个账号，
     * 而用户完全不知道自己的密码其实"只有前 72 字节算数"。
     * 两种都不能接受，所以在这里换成一句说得清的话。
     *
     * 注意单位是**字节**不是字符：UTF-8 下一个汉字 3 字节，所以 24 个汉字就到顶了。
     * 列宽管不着这件事（staff.password 是 VARCHAR(255)，存的是 60 字符的哈希）。
     */
    private static final int PASSWORD_MAX_BYTES = 72;

    private final StaffRepository staffRepository;
    private final StoreRepository storeRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StaffTokenRevoker tokenRevoker;

    public StaffAdminAppService(StaffRepository staffRepository,
                                StoreRepository storeRepository,
                                BCryptPasswordEncoder passwordEncoder,
                                StaffTokenRevoker tokenRevoker) {
        this.staffRepository = staffRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenRevoker = tokenRevoker;
    }

    // ═══════════════════════ 查 ═══════════════════════

    /** 员工列表（**含已停用**）—— 停掉的人必须看得见，否则没法把他启用回来 */
    public Result<List<StaffView>> listStaff() {
        // 门店名一次查完做成 map，不在循环里逐个 findById —— 那会变成 N+1 次查询
        Map<Long, String> storeNames = storeRepository.findAll().stream()
                .collect(Collectors.toMap(Store::getId, Store::getName));
        List<StaffView> views = staffRepository.findAll().stream()
                .map(s -> StaffView.from(s, storeNames.get(s.getStoreId())))
                .toList();
        return Result.ok(views);
    }

    /** 员工详情 —— 编辑页按 id 回显 */
    public Result<StaffView> getStaff(Long id) {
        Staff staff = staffRepository.findById(id).orElse(null);
        if (staff == null) {
            // 404 而不是 400：路径上那个资源不存在，和"订单不存在 / 顾客不存在"同一口径
            return Result.fail(404, "员工不存在");
        }
        return Result.ok(toView(staff));
    }

    // ═══════════════════════ 建 ═══════════════════════

    /** 新建员工。新建一律**启用** —— 没有"建出来就是停用的账号"这种需求 */
    public Result<StaffView> createStaff(CreateStaffCommand cmd) {
        if (isBlank(cmd.username())) {
            return Result.fail(400, "用户名不能为空");
        }
        if (isBlank(cmd.password())) {
            return Result.fail(400, "密码不能为空");
        }
        if (isBlank(cmd.name())) {
            return Result.fail(400, "姓名不能为空");
        }
        // 长度：超了抛 BusinessException → 400（而不是撞到 VARCHAR 才报英文 SQL 异常）
        Staff.requireValidUsername(cmd.username());
        Staff.requireValidName(cmd.name());
        Staff.requireValidPhone(cmd.phone());
        Result<Void> passwordTooLong = requireValidPassword(cmd.password());
        if (passwordTooLong != null) {
            return Result.fail(passwordTooLong.code(), passwordTooLong.message());
        }

        StaffRole role = parseRole(cmd.role());
        if (role == null) {
            return Result.fail(400, "角色不能为空");
        }
        // 管理员不隶属门店：传了也强制成 null，与 V3 种子数据（admin 的 store_id 是 NULL）一致。
        // 这里是**忽略**入参而不是报错 —— 前端的角色下拉框一改，门店下拉框里
        // 往往还留着上一次选的值，为此弹一个 400 是拿用户的疏忽惩罚他。
        Long storeId = role == StaffRole.ADMIN ? null : cmd.storeId();
        Result<Void> storeMissing = requireExistingStore(storeId);
        if (storeMissing != null) {
            return Result.fail(storeMissing.code(), storeMissing.message());
        }

        // 用户名先查再报，**不靠"插进去试试、撞唯一键再说"**：那条路上抛出来的
        // 是 DuplicateKeyException + 一段英文 SQL 报文，最后会变成 500
        // （V9 注释明确要求把这类检查提到应用层）。这只是"尽量早地报错"，
        // 不是并发保证 —— 真正的兜底是下面那个 catch
        if (staffRepository.existsByUsername(cmd.username().trim())) {
            return Result.fail(400, "用户名已存在");
        }

        Staff staff = new Staff();
        staff.setUsername(cmd.username().trim());
        staff.setPasswordHash(passwordEncoder.encode(cmd.password()));
        staff.setName(cmd.name().trim());
        staff.setRole(role.getCode());
        staff.setStoreId(storeId);
        staff.setPhone(blankToNull(cmd.phone()));
        staff.setStatus(1);

        try {
            staffRepository.insert(staff);
        } catch (DuplicateKeyException e) {
            // 上面 existsByUsername 已经查过一遍了，能走到这里只有一种可能：
            // 两次请求之间别人抢先建了同名账号。数据库的 uk_username 是最终裁判
            // （而且是大小写不敏感的 —— MySQL 8 + utf8mb4_unicode_ci 下
            // "Admin" 和 "admin" 算重复，existsByUsername 用的是同一套 collation，所以两条路一致）。
            // 消息与前面**逐字相同**：对调用方来说这就是同一件事
            return Result.fail(400, "用户名已存在");
        }
        return Result.ok(toView(staff));
    }

    // ═══════════════════════ 改 ═══════════════════════

    /**
     * 改资料：姓名 / 角色 / 门店 / 手机号。**改不了用户名**（见 UpdateStaffCommand）。
     *
     * role 或 storeId 变了 ⇒ 作废该员工手上所有 token。因为 token 里**签着**
     * role 和 storeId 的旧值：把管理员降成店长，他那张票里还写着 role=0，
     * 不改的话他还能继续管人与店，最长 24 小时。
     */
    public Result<StaffView> updateStaff(Long id, UpdateStaffCommand cmd) {
        Staff staff = staffRepository.findById(id).orElse(null);
        if (staff == null) {
            return Result.fail(404, "员工不存在");
        }
        if (isBlank(cmd.name())) {
            return Result.fail(400, "姓名不能为空");
        }
        Staff.requireValidName(cmd.name());
        Staff.requireValidPhone(cmd.phone());

        StaffRole role = parseRole(cmd.role());
        if (role == null) {
            return Result.fail(400, "角色不能为空");
        }
        Long storeId = role == StaffRole.ADMIN ? null : cmd.storeId();
        Result<Void> storeMissing = requireExistingStore(storeId);
        if (storeMissing != null) {
            return Result.fail(storeMissing.code(), storeMissing.message());
        }

        // 改之前先比：只有 role / storeId 真变了才踢人。
        // 改个错别字（姓名）不该把柜台电脑上的人弹下线 —— 那不是安全要求，是骚扰
        boolean identityChanged = !Objects.equals(staff.getRole(), role.getCode())
                || !Objects.equals(staff.getStoreId(), storeId);

        staff.setName(cmd.name().trim());
        staff.setRole(role.getCode());
        staff.setStoreId(storeId);
        staff.setPhone(blankToNull(cmd.phone()));
        staffRepository.update(staff);

        // 先写库再作废（StaffTokenRevoker 的注释里写了为什么这个顺序不能反）
        if (identityChanged) {
            tokenRevoker.revokeAll(id);
        }
        return Result.ok(toView(staff));
    }

    /**
     * 重置密码。管理员打错一个字之后唯一的补救路径 —— 没有它就只能改库。
     *
     * **一定**作废该员工手上所有 token：改密码的整个语义就是"从前的凭据不算数了"，
     * 不作废等于密码改了但别人的会话还活着。
     * （顺带也是被盗号后的标准处置：改密码 = 全设备下线。）
     */
    public Result<StaffView> resetPassword(Long id, String newPassword) {
        Staff staff = staffRepository.findById(id).orElse(null);
        if (staff == null) {
            return Result.fail(404, "员工不存在");
        }
        if (isBlank(newPassword)) {
            return Result.fail(400, "密码不能为空");
        }
        Result<Void> passwordTooLong = requireValidPassword(newPassword);
        if (passwordTooLong != null) {
            return Result.fail(passwordTooLong.code(), passwordTooLong.message());
        }

        staff.setPasswordHash(passwordEncoder.encode(newPassword));
        staffRepository.update(staff);
        tokenRevoker.revokeAll(id);
        return Result.ok(toView(staff));
    }

    /**
     * 启用 / 停用（软停用：改 status，**不硬删**）。
     *
     * 停用 ⇒ 作废该员工手上所有 token，**同一张票立刻失效**，不用等 24 小时过期。
     * 启用**不**写水位线，也不需要写：停用期间他登录不了（Staff.canLogin 拦着），
     * 所以那个窗口里根本签不出票来，没有"需要作废的东西"。而停用**之前**签的票
     * 早就被那次作废杀掉了 —— 于是"停用 → 启用"之后他必须重新登录，
     * 旧票不会复活（这正是水位线比"布尔停用标记"强的地方，详见 StaffTokenRevoker）。
     *
     * ⚠️ **不设自锁护栏**（2026-09-18 用户明确拍板）：管理员可以停用/降级自己，
     * 也可以把最后一个管理员降成店长。真发生之后只能直连数据库改回来 ——
     * `update staff set status=1 where username='admin'`。
     * 这不是漏了，是明知道后果仍然选择不拦；记在这里免得下一个人以为是 bug。
     */
    public Result<StaffView> updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            return Result.fail(400, "状态只能是 0(停用) 或 1(启用)");
        }
        Staff staff = staffRepository.findById(id).orElse(null);
        if (staff == null) {
            return Result.fail(404, "员工不存在");
        }
        staff.setStatus(status);
        staffRepository.update(staff);

        if (status == 0) {
            tokenRevoker.revokeAll(id);
        }
        return Result.ok(toView(staff));
    }

    // ═══════════════════════ 内部方法 ═══════════════════

    /** 单个员工的出参 —— 顺带把门店名拼上（列表那条路用 map 批量查，见 listStaff） */
    private StaffView toView(Staff staff) {
        String storeName = staff.getStoreId() == null ? null
                : storeRepository.findById(staff.getStoreId()).map(Store::getName).orElse(null);
        return StaffView.from(staff, storeName);
    }

    /**
     * 角色码 → 枚举。非法码交给 StaffRole.fromCode 抛（它抛的是 BusinessException，
     * 带一句"没有这个角色: X"，400）—— **复用那句话，不在这里另造一句**，
     * 否则同一个错误会有两种说法。
     */
    private StaffRole parseRole(Integer role) {
        return role == null ? null : StaffRole.fromCode(role);
    }

    /**
     * 要归店的话，那个店得**真的存在**。
     *
     * 库里**没有外键**（staff.store_id 只是个普通索引，见 V1），所以这条只能应用层拦 ——
     * 不拦就能建出一个 store_id=999 的店长，他的 token 里会签着一个不存在的门店，
     * 而这是那种"平时看不出问题、排查时查到天亮"的脏数据。
     *
     * 用 findById（**不过滤 status**）而不是 findOpenById：把一个店长先挂到
     * 还没开业 / 已停业的店上是正常操作。拿 findOpenById 会把"停业的店"
     * 误判成"不存在的店"，于是你永远改不了一个已停业门店的员工 ——
     * 而那正是他最需要被改的时候。
     *
     * @return null = 通过；非 null = 该回给调用方的失败
     */
    private Result<Void> requireExistingStore(Long storeId) {
        if (storeId == null) {
            return null;   // 不归店是合法状态（管理员必然如此，店长也可以是）
        }
        if (storeRepository.findById(storeId).isEmpty()) {
            // 400 而非 404：路径上的资源（/api/staff/{id}）是存在的，
            // 不合法的是**请求体里引用**的那个 id
            return Result.fail(400, "门店不存在");
        }
        return null;
    }

    /** @return null = 通过 */
    private Result<Void> requireValidPassword(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PASSWORD_MAX_BYTES) {
            return Result.fail(400, "密码过长（最多 " + PASSWORD_MAX_BYTES + " 字节，一个汉字算 3 字节）");
        }
        return null;
    }

    /**
     * 空白一律存 NULL，不存空串。
     *
     * V9 的教训：'' 和 NULL 在 SQL 里是两回事（`WHERE phone IS NULL` 查不到 ''，
     * `WHERE phone = ''` 也查不到 NULL）。用 '' 糊过去等于往库里塞了个假值，
     * 之后所有"有没有手机号"的判断都要写两遍。stores.phone 也是 DEFAULT NULL，
     * 所以 NULL 才是"没有"的表示法。
     */
    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
