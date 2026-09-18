package com.yunxi.interfaces.dto;

/**
 * 启用/停用 —— 员工与门店**共用**这一个请求体。
 *
 * 两者的字段、取值、语义完全一样（1=启用/营业，0=停用/停业），所以共用一个类；
 * 不同的是副作用：停用员工会作废他手上所有 token，停用门店不会
 * （详见 StaffAdminAppService.updateStatus 和 StoreAdminAppService.updateStatus）。
 * 那个差别由各自的 URL 说清楚，不需要两个只有类名不同的 record。
 *
 * 没有 id：改谁由 URL 路径参数决定。
 */
public record UpdateStatusRequest(
        Integer status
) {}
