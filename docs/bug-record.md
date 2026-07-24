# 测试 Bug 记录

> 项目：云洗助手 yunxi-server  
> 测试日期：2026-07-22  
> 测试范围：订单模块四层（common → domain → infrastructure → application → interfaces）

---

## Bug 1：支付报 500 — `save()` 只会 INSERT，不会 UPDATE

### 现象

创建订单成功，调用 `POST /api/orders/1/pay` 时报 500 `Internal Server Error`。

### 复现步骤

1. 创建订单 → 返回 200，id=1，status=PENDING_PAY
2. 支付 `POST /api/orders/1/pay?payMethod=cash&amount=30` → 500

### 根因

`OrderRepositoryImpl.save()` 写死了 `orderMapper.insert()`：

```java
// 问题代码
public void save(Order order) {
    OrderPO po = toOrderPO(order);
    orderMapper.insert(po);   // 不管订单是新建还是已存在，都执行 INSERT
    order.setId(po.getId());
    // ...
}
```

- 创建订单时：`order.getId() == null` → INSERT ✅ 
- 支付时：先从数据库查出已有订单（id=1），修改后调 `save()` → 又执行 INSERT → id=1 已存在，MySQL 主键冲突 → 500

### 修复

判断 `id` 是否为空，空则 INSERT，否则 UPDATE：

```java
boolean isNew = order.getId() == null;
if (isNew) {
    orderMapper.insert(po);
    order.setId(po.getId());
} else {
    orderMapper.update(po);
}
```

同时在 `OrderMapper.java` 中新增 `update` 方法，在 `OrderMapper.xml` 中新增对应的 UPDATE SQL。

### 教训

- `save()` 方法必须同时处理"新建"和"修改"两种情况
- INSERT = 新增一行，UPDATE = 修改已有的一行
- 判断依据：主键 id 是否为 null

---

## Bug 2：订单明细 items 重复插入

### 现象

每调用一次 `save()`，订单明细就被重新插入一次。一个订单操作几次后，`GET /api/orders/1` 返回的 items 有 32 条（实际只有 1 条）。

### 根因

`save()` 里的 `insertBatch` 没有区分新建和更新，每次都会执行：

```java
// 问题代码
if (!order.getItems().isEmpty()) {
    List<OrderItemPO> itemPOs = toOrderItemPOs(order);
    orderItemMapper.insertBatch(itemPOs);  // 每次 save 都插一次
}
```

### 修复

用 `isNew` 变量控制，只在新建时插入明细：

```java
boolean isNew = order.getId() == null;
// ... insert 或 update ...
if (isNew && !order.getItems().isEmpty()) {
    orderItemMapper.insertBatch(itemPOs);
}
```

### 额外踩坑

`order.setId(po.getId())` 之后 id 不再为 null，如果在此之后判断 `order.getId() == null` 会永远为 false。所以先把 `isNew` 存好，后续用这个变量判断。

### 教训

- "新建时才做的事"要提前用一个布尔变量记下来
- 把状态判断放在操作之前，避免操作改变了判断条件

---

## Bug 3：洗后付订单不付钱可以走完全程

### 现象

创建订单 → `pay(0元)` → `next × 4` → 直接到达终态 PICKED_UP。`paidAmount=0`，顾客没付钱就拿走了衣服。

### 复现步骤

1. 创建订单（totalAmount=100）
2. `pay(BALANCE, 0)` — 洗后付占位
3. `next × 3` — 到达 PENDING_PICKUP
4. `next` — 直接跳到 PICKED_UP，paidAmount 仍为 0

### 根因

`updateStatus()` 在跳转到终态时，没有检查钱是否付清：

```java
// 问题代码
case PENDING_PICKUP:     // 5 → 8
    this.status = OrderStatus.PICKED_UP;  // 直接过，不检查
    this.finishTime = LocalDateTime.now();
    break;
```

### 修复

跳终态前校验 `paidAmount >= totalAmount`：

```java
case PENDING_PICKUP:     // 5 → 8
    if (this.paidAmount.compareTo(this.totalAmount) < 0) {
        throw new BusinessException("未付清，请使用洗后付结账");
    }
    this.status = OrderStatus.PICKED_UP;
    this.finishTime = LocalDateTime.now();
    break;

case DELIVERED:          // 7 → 8（网单同样需要校验）
    if (this.paidAmount.compareTo(this.totalAmount) < 0) {
        throw new BusinessException("未付清，请使用洗后付结账");
    }
    this.status = OrderStatus.PICKED_UP;
    this.finishTime = LocalDateTime.now();
    break;
```

### 教训

- 状态机的每一步都要写**前置校验**
- 不能假设调用方会按正确顺序调用——必须在代码里强制约束
- `BigDecimal.compareTo()`：返回 <0 表示小于，=0 表示等于，>0 表示大于

---

## Bug 4：BusinessException 显示为 500（已修复）

### 现象

洗后付订单在 PENDING_PICKUP 点 `next`，后端正确阻止了操作，但前端看到的是：

```json
{
  "status": 500,
  "error": "Internal Server Error"
}
```

而不是预期的：

```json
{
  "code": 400,
  "message": "未付清，请使用洗后付结账",
  "data": null
}
```

### 根因

没有配置全局异常处理器。`BusinessException` 抛出来后，Spring 默认把它当作未处理异常，统一返回 500。

### 修复

在 `yunxi-interfaces` 模块新建 `GlobalExceptionHandler.java`，用 `@RestControllerAdvice` 拦截异常：

- `BusinessException` → `Result.fail(e.getCode(), e.getMessage())`
- `IllegalArgumentException` → `Result.fail(400, msg)`
- `Exception`（兜底）→ `Result.fail(500, "服务器内部错误")`

### 效果

```json
{"code": 400, "message": "当前状态不允许推进: 状态=8", "data": null}
```

### 教训

- `@RestControllerAdvice` 是全局异常拦截网，不需要在每个 Controller 写 try-catch
- `@ExceptionHandler` 按异常类型匹配，越具体的越优先匹配

---

## Bug 5：登录密码验证失败 — BCrypt 哈希不正确

### 现象

登录接口返回 `401 用户名或密码错误`，但数据库里有 admin 用户，密码也是对的。

### 根因

V3 迁移文件里的 BCrypt 哈希值是手写的，不是用 `BCryptPasswordEncoder` 生成的。BCrypt 每个哈希自带随机盐值，手工写的哈希无法被正确验证。

### 修复

临时在 AuthController 构造方法里打印 `passwordEncoder.encode("admin123")`，用 Spring 自带的 BCrypt 生成正确哈希，更新数据库和 V3 迁移文件。

### 教训

- BCrypt 哈希不能用随机字符串冒充——必须用 `BCryptPasswordEncoder.encode()` 生成
- V3 迁移文件要和实际生成的哈希保持一致

---

## Bug 6：BCryptPasswordEncoder 标红 — 依赖未传递

### 现象

AuthController 里 `BCryptPasswordEncoder` 标红，编译不过。

### 根因

`spring-security-crypto` 依赖只写在了 `yunxi-infrastructure/pom.xml`，但 AuthController 在 `yunxi-interfaces` 模块。Maven 的依赖传递没有把这个包传过去。

### 修复

在 `yunxi-interfaces/pom.xml` 里直接加了一条 `spring-security-crypto` 依赖。

### 教训

- 哪个模块用了某个 jar 包里的类，就在哪个模块的 pom.xml 里声明
- 不要依赖 Maven 的间接传递——不总是有效

---

## Bug 7：Flyway 校验码不匹配 — 已执行的迁移文件不能改

### 现象

修改 V3 迁移文件后重启，报 `Migration checksum mismatch for migration version 3`，应用启动失败。

### 根因

V3 迁移文件修改前已经被 Flyway 执行过一次，`flyway_schema_history` 表存了旧的校验码。文件内容改后新旧校验码不一致，Flyway 拒绝启动——保护数据库不被意外覆盖。

### 修复

```sql
DROP DATABASE yunxi;
CREATE DATABASE yunxi;
```

删库重建，让 Flyway 重新执行所有迁移，校验码全部重新记录。

### 教训

- 已执行过的迁移文件**不能改**
- 要改表结构或数据 → 新建 V4、V5 迁移文件
- 只有空库可以随意改旧文件 + 删库重建

---

## 汇总

| Bug | 层 | 类型 | 一句话 |
|---|---|---|---|
| 1 | Infrastructure | INSERT/UPDATE 未区分 | `save()` 对新老数据都执行 INSERT |
| 2 | Infrastructure | 新建/更新逻辑未隔离 | 明细每次 `save()` 都被重复插入 |
| 3 | Domain | 状态机缺前置校验 | 终态跳转前没检查是否付清 |
| 4 | Interfaces | 异常未正确转换 | BusinessException 被当作 500 返回 |
| 5 | Infrastructure | 数据错误 | BCrypt 哈希是手写的，验不过 |
| 6 | Infrastructure | 依赖缺失 | BCrypt 包未在调用模块声明 |
| 7 | Infrastructure | 迁移校验 | 已执行的 Flyway 文件被修改 |

---

## 测试通过的功能

| 功能 | 状态 |
|---|---|
| 创建订单 | ✅ |
| 查询订单 | ✅ |
| 支付（先付） | ✅ |
| 支付（洗后付占位 0 元） | ✅ |
| 状态推进（门店单 2→3→4→5→8） | ✅ |
| 洗后付结账（5→8 补齐全款） | ✅ |
| 终态不可再推进 | ✅ |
| 洗后付未付清阻止跳终态 | ✅ |
| items 不重复插入 | ✅ |
| 员工登录 + 获取 Token | ✅ |
| 不带 Token 被拦截（401） | ✅ |
| 带 Token 正常访问 | ✅ |
