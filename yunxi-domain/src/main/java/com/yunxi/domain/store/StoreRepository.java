package com.yunxi.domain.store;

import java.util.List;
import java.util.Optional;

/**
 * 门店仓储。
 *
 * 门店是别人维护的参考数据：员工端建单要选店、顾客端下单也要选店，
 * 2026-09-18 起管理员还能建店/改店/停业（§4.2）。
 *
 * 所以这里的方法**分成两组，语义不要混**：
 *   · findOpen / findOpenById —— "能下单的店"。过滤条件写在 SQL 里，
 *     查得到就是在营业。下单路径只认这两个
 *   · findAll / findById       —— "管理视角的店"，含已停业的。
 *     管理员要把停业的店列出来（不然他没法学着把它重新开起来）
 *
 * 两组并存不是冗余：前者的调用方压根不该看见停业的店，
 * 后者的调用方必须看得见。合成一个再让调用方自己 if status，
 * 就是把这个判断散到每个调用点上 —— 那才是真的两个真相。
 */
public interface StoreRepository {

    /** 营业中的门店（status=1），按 id 升序 —— 就是前端那个"选门店"的下拉框 */
    List<Store> findOpen();

    /**
     * 按 id 查**营业中**的门店，不存在或已停业都返回空。
     *
     * 为什么两种情况合成一个 Optional：对下单的人来说它们没有区别 ——
     * 都是"这店选不了，换一家"。分成两种返回值，调用方最后还是并成同一句提示。
     *
     * 为什么过滤写在 SQL 里而不是查回来在 Java 里判断：判断条件只有一处，
     * 不会出现"查得到、却选不了"的两个真相。
     */
    Optional<Store> findOpenById(Long id);

    /** 全部门店（含停业），按 id 升序 —— 门店管理列表 */
    List<Store> findAll();

    /**
     * 按 id 查门店，**不过滤状态** —— 停业的店也查得到。
     *
     * 与 findOpenById 的分工要看清：
     *   · 下单要问"这店现在能接单吗" → findOpenById（停业 = 空 = 拒单）
     *   · 管理要问"这个 id 存在吗、它现在是什么状态" → findById
     * 拿 findOpenById 去做管理侧的存在性校验，会把"停业的店"误判成"不存在的店"，
     * 于是你永远改不了一个已停业的门店 —— 那正是它最需要被改的时候。
     */
    Optional<Store> findById(Long id);

    /**
     * 新建门店。id 由数据库生成，实现方负责把它回填进入参对象。
     *
     * 为什么是 insert/update 两个方法，而不是一个"id 为空就插、否则更"的 save：
     * 那种 save 的失败模式是"id 传错了就悄悄改了别的行"，而且它把
     * "这次到底是新建还是修改"这个信息从方法名里抹掉了 —— 调用点读起来要猜。
     */
    void insert(Store store);

    /**
     * 按 id 更新门店（名字/地址/电话/状态）。
     *
     * ⚠️ 写的是**全部可写列**：调用方必须先 findById 拿到当前行、在它身上改，
     * 再传进来。直接 new 一个只填了两个字段的 Store 传进来，会把没填的列写空。
     */
    void update(Store store);
}
