package com.yunxi.domain.store;

import java.util.List;
import java.util.Optional;

/**
 * 门店仓储 —— **只读**。
 *
 * 门店是别人维护的参考数据：员工端建单要选店、顾客端下单也要选店，
 * 但没有任何一个接口会去改门店（§4.2：管门店是管理员的事，还没做）。
 * 所以这里只有查，没有 save —— 接口订得越窄，实现就越没有走样的空间。
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
}
