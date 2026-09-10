package com.yunxi.application.dto;

import com.yunxi.domain.price.ClothesCategory;

/**
 * 衣物分类的**出参**（一级 + 叶子都在里面，前端按 parentId 组树）。
 *
 * leaf 是把领域判断（{@code ClothesCategory.isLeaf()}）搬出来给前端：
 * "有 parentId 才是叶子、叶子才能定价"是规则，规则本体在领域对象里，
 * 这里只是搬运 —— 前端因此不用自己写一遍 parentId != null。
 */
public record CategoryView(
        Long id,
        String name,
        String icon,
        Long parentId,
        int sortOrder,
        boolean leaf
) {

    public static CategoryView from(ClothesCategory category) {
        return new CategoryView(
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getParentId(),
                category.getSortOrder(),
                category.isLeaf());
    }
}
