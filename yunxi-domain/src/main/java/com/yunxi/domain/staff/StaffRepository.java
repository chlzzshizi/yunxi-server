package com.yunxi.domain.staff;

import java.util.List;
import java.util.Optional;

/**
 * 员工仓储 —— 由 infrastructure 用 MyBatis 实现。
 *
 * 接口定义在 domain：谁需要什么数据，由需要的人说了算；
 * infrastructure 只负责"怎么取"。这样 application 才能只依赖这个接口，
 * 单元测试里用 Mockito 造个假实现就能测登录规则，不用起 Spring 也不用连库。
 */
public interface StaffRepository {

    /**
     * 按用户名查员工（登录用）。
     *
     * 查不到返回空 Optional，而不是 null —— "没这个人"是正常结果，不是异常。
     */
    Optional<Staff> findByUsername(String username);

    /** 按 id 查员工；查不到返回空 */
    Optional<Staff> findById(Long id);

    /** 全部员工（含已停用），按 id 升序 —— 员工管理列表 */
    List<Staff> findAll();

    /**
     * 新建员工。id 由数据库生成，实现方负责把它回填进入参对象。
     */
    void insert(Staff staff);

    /**
     * 按 id 更新员工。
     *
     * ⚠️ 写的是**全部可写列**（姓名/角色/门店/手机/状态/密码）：
     * 调用方必须先 findById 拿到当前行、在它身上改，再传进来。
     * 直接 new 一个只填了两个字段的 Staff 传进来，会把密码和角色写空。
     *
     * username 不在可写列里 —— 见 StaffController 类注释（改用户名 = 换账号）。
     */
    void update(Staff staff);

    /**
     * 这个用户名是不是已经被占了。
     *
     * 为什么不靠"插进去试试、撞唯一键再说"：那条路上抛出来的是
     * DuplicateKeyException + 一段英文 SQL 报文，最后会变成 500（V9 的教训）。
     * 先查再报，调用方才能回一句中文的 400。
     *
     * 注意这只是**尽量早地报错**，不是并发保证 —— 两个人同时注册同一个用户名，
     * 仍然要靠数据库的 uk_username 兜底。两者是"早报错"和"最终一致"的分工，
     * 不是二选一。
     */
    boolean existsByUsername(String username);
}
