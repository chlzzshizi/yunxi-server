package com.yunxi.application.dto;

import com.yunxi.common.enums.StaffRole;
import com.yunxi.domain.staff.Staff;

/**
 * 员工的**出参**（员工管理列表 / 详情 / 建改之后的回显）。
 *
 * **没有 password / passwordHash 字段** —— 和 StaffIdentity、CustomerView 同款：
 * 不是"记得别填"，是**类型上就没有这个位置**，谁想漏都漏不了。
 * （Staff 里那个字段叫 passwordHash，这里连一个能承接它的槽都没有。）
 *
 * role 是**枚举**而不是 0/1 —— 和 OrderView 里 OrderStatus / PayMethod 一样。
 * README 那条口径：枚举在**返回体**里是名字（"ADMIN" / "MANAGER"），
 * 只在**请求参数**里是数字。前端拿到 "MANAGER" 不用回去翻表，
 * 而"0 是不是管理员"这个问题每次都要查一遍。
 *
 * storeName 是**拼进来的**，不是 staff 表上的列：列表页要显示
 * "张店长 · 云洗中央门店"，只给 storeId 的话前端还得再拉一次门店列表自己映射。
 * 门店已停业时它照样有值（查的是 findAll 不是 findOpen）—— 停业门店的员工
 * 不该在列表里变成一个没有门店名的人。
 */
public record StaffView(
        Long id,
        String username,
        String name,
        StaffRole role,
        Long storeId,
        String storeName,
        String phone,
        Integer status
) {

    /**
     * @param storeName 调用方查好传进来的门店名；门店不存在或员工没归店时为 null
     *                  （staff.store_id 上没有外键，查不到是可能的，不是异常）
     */
    public static StaffView from(Staff staff, String storeName) {
        return new StaffView(
                staff.getId(),
                staff.getUsername(),
                staff.getName(),
                toRole(staff.getRole()),
                staff.getStoreId(),
                storeName,
                staff.getPhone(),
                staff.getStatus());
    }

    /**
     * Integer 码 → 枚举。
     *
     * null 放行（返回 null）而不是抛：读接口不该因为一行的 role 是空的
     * 就整个列表 400。而**越界的码照样让 StaffRole.fromCode 抛** ——
     * 那是"库里的 role 不是任何已知角色"，是数据坏了，必须响，
     * 不能悄悄显示成一个前端看不懂的空值。
     */
    private static StaffRole toRole(Integer code) {
        return code == null ? null : StaffRole.fromCode(code);
    }
}
