package com.yunxi.domain.price;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 衣物分类层级单元测试 —— 纯领域，不依赖 Spring / 数据库 / Mock。
 *
 * 补测之前这个类 45 条指令一条都没被执行过（2026-09-18 接上 JaCoCo 后报 0%），
 * 是域层漏得最干净的一个。它只有一条规则 —— isLeaf()，
 * 而这条规则**确实在守东西**（不是摆设）：
 *   · PriceAppService.savePrices:59 判的就是它 ——"只能给二级（叶子）分类设置价格"
 *   · CategoryView:28 把它的结果当 leaf 字段发给前端，前端按它决定置不置灰
 * 所以它一旦判反，给"上衣"定价会放行，前端也会把一级分类画成可点。
 * 而在此之前，改坏它没有任何测试会红。
 *
 * 口径（见 ClothesCategory 的类注释）：层级只有两层，
 * parentId 为 null 是一级分类（"上衣"），有 parentId 的是叶子（"衬衫"）。
 * 注意这个方向和常见的树写法是**反的** —— 常见写法是"有孩子才不是叶子"，
 * 这里是"有父亲才是叶子"。钉在这里免得下次按习惯读反。
 *
 * 刻意**不测** getter/setter：它们没有规则，测了只是刷数字。
 */
class ClothesCategoryTest {

    /** 造一个分类，只设参与判定的那个字段（name 随手填，只为让对象像个真分类） */
    private ClothesCategory withParent(Long parentId) {
        ClothesCategory category = new ClothesCategory();
        category.setName("衬衫");
        category.setParentId(parentId);
        return category;
    }

    // ════════════════ 是不是叶子 ════════════════

    @Nested
    @DisplayName("isLeaf：判据是 parentId != null")
    class IsLeaf {

        @Test
        @DisplayName("parentId = null → 一级分类，不是叶子（上衣不能定价）")
        void nullIsNotLeaf() {
            assertThat(withParent(null).isLeaf()).isFalse();
        }

        @Test
        @DisplayName("parentId = 1 → 二级分类，是叶子（衬衫才能定价）")
        void withParentIsLeaf() {
            assertThat(withParent(1L).isLeaf()).isTrue();
        }

        @Test
        @DisplayName("parentId 指向谁不影响判定 —— 只看非不非空")
        void anyParentCounts() {
            // 判据里没有"父分类真的存在吗"这一步。父存不存在是数据完整性的活
            // （外键 / 应用层），不是这条规则的活 —— 把它混进来会让这个纯判断
            // 依赖上仓储，域层也就没法脱开数据库直接测了
            assertThat(withParent(1L).isLeaf()).isTrue();
            assertThat(withParent(999L).isLeaf()).isTrue();
        }

        @Test
        @DisplayName("parentId = 0L → 当成有父，是叶子（0 不是 null）")
        void zeroIsTreatedAsHavingParent() {
            // 钉的是当前行为，不是"这是对的"。
            // 0 当哨兵值在这套代码里是有先例的（Redis 库存键用负数表示超发），
            // 真有人拿 0 表示"没有父分类"塞进来，一级分类就会被当成叶子放行定价。
            // 这一条的存在意义：那次改动会在这里变红，
            // 而不是静默多出一批"可以定价的上衣"
            assertThat(withParent(0L).isLeaf()).isTrue();
        }
    }
}
