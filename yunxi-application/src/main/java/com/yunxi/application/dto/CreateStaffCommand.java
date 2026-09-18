package com.yunxi.application.dto;

/**
 * 新建员工 —— **入参**（应用层的命令对象）。
 *
 * 为什么要有这个类，而不是让 controller 把六个参数一个个传进来：
 * 六个位置参数里有 username / name / phone **三个 String 挨在一起**，
 * 调用点写成 `create(u, n, p, role, storeId)` 时把 name 和 phone 写反
 * 编译器一句话都不会说，症状是"手机号里存着人名"，要到用的时候才发现。
 * 换成 record，字段有名字，传错顺序编译不过。
 *
 * 它是"调用方想建一个员工"这个意图，不是领域里的 Staff：
 * 这里的 password 是**明文**（应用层负责 BCrypt），而 Staff.passwordHash
 * 是哈希。让 controller 直接 new 一个 Staff 传进来，等于让它知道"密码要先哈希"
 * 这件事 —— 那是应用层的职责，也是接口层引用 domain 的又一次破口（§3.1）。
 *
 * 没有 status：新建一律启用。做一个"建出来就是停用的账号"是两步动作
 * （建 + 停），塞进创建请求体只是给前端多一个可以传错的字段。
 */
public record CreateStaffCommand(
        String username,
        String password,     // 明文，应用层负责哈希后落库
        String name,
        Integer role,        // 0=管理员 1=店长（枚举只有这两档）
        Long storeId,        // 可为空：管理员必须为空，店长允许为空
        String phone         // 可为空
) {}
