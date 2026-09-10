package com.yunxi.common;

import java.util.List;

/**
 * 分页结果 —— 列表接口统一返回这个，前端不用自己算有没有下一页。
 *
 * 注意 totalPages 是**组件**不是普通方法：record 的方法不会进 JSON
 * （Jackson 只序列化组件），写成方法的话前端根本收不到这个字段。
 *
 * @param list       当前页数据
 * @param total      符合条件的总条数
 * @param page       当前页码（从 1 开始，面向人）
 * @param pageSize   每页条数
 * @param totalPages 总页数（向上取整，没数据时是 0）
 */
public record PageResult<T>(List<T> list, long total, int page, int pageSize, int totalPages) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        return new PageResult<>(list, total, page, pageSize, calcTotalPages(total, pageSize));
    }

    private static int calcTotalPages(long total, int pageSize) {
        return pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
    }
}
