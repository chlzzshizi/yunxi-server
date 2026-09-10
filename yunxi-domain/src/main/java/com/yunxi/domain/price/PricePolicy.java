package com.yunxi.domain.price;

import com.yunxi.common.BusinessException;

import java.math.BigDecimal;

/**
 * 定价规则 —— 「精洗价 = 普洗价 + 20」这条业务规则**唯一**的家。
 *
 * 为什么单独起一个类，而不是把这行加法散在 Service 里：
 * 同一个数字至少会被三处问到 —— 写价格时派生精洗、页面展示、以后"改普洗价
 * 要不要联动精洗"。写成常量 + 静态方法后，将来调价只改一处；
 * 而且它不依赖 Spring / 数据库，测试可以直接调（见 PricePolicyTest）。
 *
 * 洗涤方式 id 用常量指代是安全的：V1 建表时就固定插入 1/2/3，设计文档 §4.5
 * 明确"不可新增或删除"。如果哪天真允许自定义，这里要改成按名字查库。
 */
public final class PricePolicy {

    /** 普洗 —— 各品类的基准价 */
    public static final long PLAIN = 1L;
    /** 精洗 —— 价格由普洗派生，不手工填 */
    public static final long REFINED = 2L;
    /** 单熨 —— 独立定价 */
    public static final long IRON = 3L;

    /** 精洗相对普洗的加价 */
    public static final BigDecimal REFINED_EXTRA = new BigDecimal("20");

    private PricePolicy() {}    // 纯规则类，不给实例化

    /**
     * 精洗价 = 普洗价 + 20。
     *
     * 不做四舍五入：两个两位小数的数相加，结果最多两位小数，
     * 多写一次 setScale 只会让人怀疑"这里是不是真会产生第三位"。
     */
    public static BigDecimal deriveRefined(BigDecimal plainPrice) {
        return plainPrice.add(REFINED_EXTRA);
    }

    /** 是不是我们认识的三种洗涤方式之一 */
    public static void requireKnownWashType(Long washTypeId) {
        if (washTypeId == null
                || (washTypeId != PLAIN && washTypeId != REFINED && washTypeId != IRON)) {
            throw new BusinessException("没有这个洗涤方式: " + washTypeId);
        }
    }
}
