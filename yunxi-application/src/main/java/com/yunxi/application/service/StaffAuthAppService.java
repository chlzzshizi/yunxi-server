package com.yunxi.application.service;

import com.yunxi.application.dto.StaffIdentity;
import com.yunxi.common.Result;
import com.yunxi.domain.staff.Staff;
import com.yunxi.domain.staff.StaffRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 员工认证应用服务 —— 登录。
 *
 * 这段逻辑原来长在 AuthController 里，直接拿 StaffMapper 查库。
 * 后果是：换成 CLI 或定时任务要复用登录，只能复制粘贴；而且想测"停用账号
 * 该不该放行"就必须起 Spring 加真数据库。搬到这里之后，
 * StaffAuthAppServiceTest 用 Mockito 造个假仓储就能把所有分支跑完。
 *
 * 不负责签 token：token 是"这套 HTTP 接口怎么携带身份"的约定，
 * 交给 interfaces 层的 JwtUtil。这里只说"凭据对不对、这人是谁"。
 */
@Service
public class StaffAuthAppService {

    private final StaffRepository staffRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public StaffAuthAppService(StaffRepository staffRepository,
                               BCryptPasswordEncoder passwordEncoder) {
        this.staffRepository = staffRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 登录。
     *
     * 三种失败刻意分成两类消息：
     *   - "用户名不存在"和"密码错误"回**同一句** —— 消息只要有差别，
     *     就等于告诉别人"这个用户名是存在的"，可以拿来枚举账号
     *   - "账号被停用"单独一句 —— 这是账号状态问题，跟密码对不对无关，
     *     混进上面那句会让人反复重试密码
     */
    public Result<StaffIdentity> login(String username, String password) {
        Staff staff = staffRepository.findByUsername(username).orElse(null);
        if (staff == null) {
            return Result.fail(401, "用户名或密码错误");
        }
        // 停用的判断在账号自己身上（Staff.canLogin），不在这里写 status == 0
        if (!staff.canLogin()) {
            return Result.fail(403, "账号已被停用");
        }
        if (!passwordEncoder.matches(password, staff.getPasswordHash())) {
            return Result.fail(401, "用户名或密码错误");
        }
        return Result.ok(new StaffIdentity(staff.getId(), staff.getUsername(),
                staff.getRole(), staff.getStoreId()));
    }
}
