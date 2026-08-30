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

## Bug 8：Application 层引用不到 Infrastructure 的 Mapper

### 现象

`CouponAppService`（在 yunxi-application）里 import `CouponMapper` 和 `CouponGrabMapper`（在 yunxi-infrastructure），编译提示 `程序包com.yunxi.infrastructure.persistence.mapper不存在`。

### 根因

`yunxi-application/pom.xml` 只依赖了 `yunxi-common` 和 `yunxi-domain`，没有依赖 `yunxi-infrastructure`。Mapper 都在 infrastructure 模块，所以找不到。

### 修复

在 `yunxi-application/pom.xml` 中添加 `yunxi-infrastructure` 依赖。

### 教训

- 哪个模块用了别的模块的类，就要在 pom.xml 里声明依赖
- OrderAppService 没遇到这个问题，因为 OrderRepository 在 domain 层（application 依赖了 domain）
- Coupon 没建 domain 层直接调了 infrastructure 的 Mapper，暴露了依赖缺失

---

## Bug 9：Mapper XML 文件名带前导空格

### 现象

`yunxi-infrastructure/src/main/resources/mapper/` 目录下出现两个**带前导空格**的文件名：

```bash
"mapper/ CouponMapper.xml"       # 注意 / 后面有一个空格
"mapper/ CouponGrabMapper.xml"
```

当前 `mybatis.mapper-locations: classpath:mapper/**/*.xml` 的通配符恰好能匹配到它们，Windows 上能正常加载——**但这是侥幸**。

### 根因

创建文件时误输入了前导空格。MyBatis 的 `**/*.xml` 通配符不区分文件名细节，把带空格的文件也扫进去了。

### 隐患

- Linux 文件系统对文件名严格区分，同名的 `CouponMapper.xml` 和 ` CouponMapper.xml` 是两个不同文件
- 任何按文件名精确引用/查找的场景（部署脚本、CI、团队协作、IDE 搜索）都会找不到文件
- "恰好能跑"不等于"正确"——问题只是被通配符掩盖了

### 修复

用 `git mv` 重命名，去掉前导空格：

```bash
git mv "mapper/ CouponMapper.xml" "mapper/CouponMapper.xml"
git mv "mapper/ CouponGrabMapper.xml" "mapper/CouponGrabMapper.xml"
```

### 教训

- 文件名不允许有前导空格——检查文件时留意 `ls` 输出里路径中的异常空格
- 依赖通配符加载的文件，命名错误会被掩盖，要主动核对实际文件名
- Windows 能跑 ≠ Linux 能跑，跨平台部署前检查这类隐性差异

---

## Bug 10：抢完库存"加回"存在竞态，库存键恢复不到 0

### 现象

`grabCoupon` 在 DECR 返回负数时执行 `increment` 加回。库存只剩最后 1 份时两个顾客并发抢：

```
顾客A: DECR → -1 → 加回 → 0
顾客B: DECR → -2 → 加回 → -1   ← 库存键最终是 -1，恢复不到 0
```

库存键的值从此脏掉，后续补货、活动重开时剩余量计算错误。

### 根因

- `decrement` 和 `increment` 是 Redis 上两条独立命令，中间可以插入其他请求，**"先减后加"不是原子操作**
- "抢完加回"的思路本身是错的：加回的数量无法精确匹配（A、B 都以为自己只多减了 1）

### 修复

去掉加回逻辑。`remaining < 0` 直接返回"已抢完"，库存键保留负数（语义：超出 N 人想抢），下次发券时 `SET` 覆盖即可：

```java
if (remaining == null || remaining < 0) {
    // 已抢完。不加回：并发下"减了再加"不是原子操作，加不回来；
    // 库存键保留负数表示"超出多少人想抢"，下次发券 SET 覆盖即可
    return Result.fail(400, "已抢完");
}
```

### 教训

- 需要"多步原子"时单条命令不够：要么 Lua 脚本合成一步，要么重新设计数据语义
- 不要用"出错后补偿"来弥补并发错误——补偿操作本身也有竞态

---

## Bug 11：并发重复抢返回 500，且 Redis 留下脏标记

### 现象

同一顾客快速点两次"抢券"：

1. 两个请求都通过了 `isMember` 检查（Redis 里还没有标记）
2. 两个请求都 DECR 成功，库存扣了 2
3. 第一次 `insert` 成功，第二次撞 `uk_coupon_customer` 唯一键 → 抛异常 → 全局异常兜底返回 **500**
4. 更糟：原代码先 `SADD` 后 `insert`，撞键时用户**已被标记在 Redis 集合里，但数据库没有记录**——该用户永远无法再抢

### 根因

- `isMember → DECR → SADD → insert` 四步非原子，Redis Set 判重只是"快速路径"，不是权威数据源
- 写操作顺序错误：先写缓存（SADD）后写数据库（insert），数据库失败时缓存留下脏标记

### 修复

调整顺序：**先写数据库（权威数据源，唯一键兜底），成功后再标记 Redis**，撞键时优雅返回并还回库存：

```java
try {
    couponGrabMapper.insert(grab);          // uk_coupon_customer 兜底
} catch (DuplicateKeyException e) {
    redisTemplate.opsForValue().increment(stockKey);   // 还回多扣的库存
    return Result.fail(400, "你已经抢过了");
}
redisTemplate.opsForSet().add(grabbedKey, customerId.toString());
```

### 教训

- 强一致需求（判重）的权威数据源是数据库唯一键，Redis 只能做快速路径
- 缓存和数据库的写顺序原则：**先权威，后缓存**——缓存可以重建，数据库不能脏

---

## Bug 12：命令行无法编译 — 环境未配置 + Maven Wrapper 未落地

> 类型说明：这是工程/环境问题，不是程序运行时缺陷。程序本身没出错，是"工具链不可复现 + 设计计划未落地"。记录价值在教训。

### 现象

- 命令行执行 `mvn compile` 报 `mvn: command not found`
- 系统 PATH 上的 java 是 1.8.0_131（项目要求 JDK 21）
- 设计文档第九节明确写了"Maven Wrapper（锁定版本）"，但项目里一直没有 `mvnw`
- 结果：只有 IDEA 内置环境能编译，脱离 IDE 命令行一碰就挂

### 根因

- 环境变量未配置：Maven 只存在于 IDEA 内置路径（`plugins/maven/lib/maven3`），不在系统 PATH；`JAVA_HOME` 未指向 jdk21
- 设计文档写了 Wrapper 计划，实现阶段没有回头核对落地

### 修复

1. 用 IDEA 内置 Maven 生成 Wrapper：`mvn wrapper:wrapper -Dmaven=3.9.9`
2. 项目新增 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`，锁定 Maven 3.9.9
3. 验证：`./mvnw -version` 自动下载 3.9.9 到 `~/.m2/wrapper/dists`，编译正常

### 教训

- 设计文档写了的配置项，做完功能要回头核对是否落地——计划不执行等于没计划
- 工具链要能脱离 IDE 复现：否则换机器、上 CI、命令行打包全部直接挂
- "现在能跑"不等于"环境正确"——这次是环境侥幸，和 Bug 9（文件名侥幸）是同一类问题

---

## Bug 13：Knife4j 空白无接口分组 — springdoc 2.6.0 与 Spring Boot 3.5 版本不兼容

> 类型说明：这是依赖版本冲突（运行期错误），不是业务逻辑缺陷。编译期完全正常，运行时才炸——此类问题排查最隐蔽。

### 现象

- 打开 `http://localhost:8081/doc.html`，左侧"文档管理 → default 分组"下**没有任何接口**，之前能看到的"认证接口 / 订单接口 / 折扣券接口"分组全部消失
- 直接请求 `GET /v3/api-docs`，返回的是全局异常处理器的兜底格式：

```json
{"code":500,"message":"服务器内部错误","data":null}
```

- 诡异点：`/v3/api-docs/swagger-config` 返回 200，`doc.html` 也能打开（页面框架正常），唯独接口列表是空的
- 业务接口（登录、发券、抢券）全部正常——只有文档生成受影响

### 复现步骤

1. 启动应用（Spring Boot 3.5.0 + springdoc 2.6.0）
2. 浏览器访问 `doc.html` → 分组空白
3. `curl http://localhost:8081/v3/api-docs` → 返回 `{"code":500,...}`（注意：HTTP 状态码是 200，内容才是 500）

### 根因

查看应用日志，全局异常处理器记录的真实堆栈第一行：

```text
java.lang.NoSuchMethodError: 'void org.springframework.web.method.ControllerAdviceBean.<init>(java.lang.Object)'
```

- `ControllerAdviceBean` 是 **Spring Framework** 的类，运行环境加载的是 Spring Framework 6.2.7（Spring Boot 3.5 自带）
- **springdoc 2.6.0** 是按 Spring Framework 6.1 编译的，它调用 `ControllerAdviceBean(Object)` 这个**单参构造函数**——该构造函数在 Spring 6.2 中被**删除**了
- springdoc 生成文档时要扫描 `@RestControllerAdvice` 处理器（GlobalExceptionHandler），一调用这个构造函数就抛 `NoSuchMethodError`，文档生成中断
- `NoSuchMethodError` 是**运行期错误**：编译时类和方法都"存在过"，编译器检查不出来，所以 `mvn compile` 一路绿灯，只有运行时才炸

### 修复

升级 springdoc 到兼容 Spring Boot 3.5 的版本线（2.8.x），修改 `pom.xml`：

```xml
<!-- 修改前 -->
<springdoc.version>2.6.0</springdoc.version>

<!-- 修改后：2.8.x 是 Boot 3.4/3.5 的最低兼容版本；2.8.13 + Knife4j 4.4.0 + Boot 3.5.6 有社区实测成功案例 -->
<springdoc.version>2.8.13</springdoc.version>
```

验证：全新实例上 `/v3/api-docs` 返回全部 9 个接口（订单 5 + 券 2 + 认证 2），登录接口正常。

### 教训

- **`NoSuchMethodError` / `NoClassDefFoundError` = 版本冲突的典型信号**：某个库按旧版本的类签名编译，运行时加载到的新版本把该方法/类删了。编译期永远检查不到，只能看运行时堆栈
- **升级依赖，不要降级框架**：框架（Boot）升级后要同步升级配套库，而不是把框架降回去迁就旧库；改 pom 前先查该库与当前 Boot 版本的兼容矩阵
- **排查时永远看响应体内容，状态码会撒谎**：`Result` 统一包装让业务失败也返回 HTTP 200，只看状态码会把 500 误判为"正常"
- 文档类问题先验证 `/v3/api-docs` 的**内容**（tags/paths 是否为空），而不是只看它是否 200

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
| 8 | Application | 依赖缺失 | Application 未依赖 Infrastructure |
| 9 | Infrastructure | 文件名错误 | Mapper XML 文件名带前导空格 |
| 10 | Application | 并发竞态 | 抢完库存"加回"不是原子操作，库存键恢复不到 0 |
| 11 | Application | 并发竞态 | 重复抢撞唯一键返回 500，且 Redis 留下脏标记 |
| 12 | 环境 | 工具链不可复现 | Maven 不在 PATH、java 1.8、Wrapper 未落地 |
| 13 | 依赖 | 版本冲突 | springdoc 2.6.0 与 Boot 3.5 不兼容，Knife4j 空白无接口 |

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
