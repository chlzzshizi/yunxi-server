# 测试 Bug 记录

> 项目：云洗助手 yunxi-server  
> 起始日期：2026-07-22（**持续追加**，Bug 条目按发现顺序编号）  
> 测试范围：最初只有订单模块四层（common → domain → infrastructure → application → interfaces），
> 现已覆盖双身份鉴权、券域高并发、定价域、订单状态机与各层的真机 E2E

> **两条维护约定**（2026-09-11 立）
>
> 1. **口径变更会让旧条目失真，但不改写历史**：状态机重编号、权限模型调整这类改动之后，
>    旧 Bug 条目的描述会变得不对。处理方式是在**条目内加 `⚠️ 日期 修订` 说明块**，
>    写清楚"哪部分作废、现在是什么"，而不是回头改掉当初写的事实。
> 2. **汇总表与"测试通过的功能"要跟着追加**：这两节是给"只读一节的人"看的，
>    落下了它们，整份档案就不能独立读了。

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

## Bug 14：favicon.ico 请求刷 ERROR —— 兜底异常处理把"资源不存在"当成 500

> 类型说明：这是全局异常处理的**覆盖范围设计缺陷**（日志噪音型），不是业务功能缺陷。业务接口全部正常，问题在于"兜底网撒得太大，把正常情况也捞进来当服务器错误"。

### 现象

- 打开 `http://localhost:8081/doc.html`（Knife4j）后，应用控制台连刷：

```text
ERROR 22688 --- [nio-8081-exec-2] c.y.i.handler.GlobalExceptionHandler : 服务器内部错误
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource favicon.ico.
```

- 每次浏览器打开/刷新页面，就多一条 ERROR + 二十多行完整堆栈，真出问题时日志被淹没

### 复现步骤

1. 浏览器访问 `doc.html`（或任何页面）
2. 看应用控制台 → 每条请求刷一条 ERROR 堆栈
3. 堆栈特征：`NoResourceFoundException`，从 `ResourceHttpRequestHandler` 抛出

### 根因

两层原因叠加：

- **浏览器自动请求 favicon.ico**：浏览器打开任何页面都会自动向服务器要站点图标，这是正常行为，项目里没这个文件而已
- **静态资源处理器抛异常**：`/favicon.ico` 不在 `/api/**` 下（JWT 拦截器只管 API），请求落到 Spring 的 `ResourceHttpRequestHandler`，找不到文件就抛 `NoResourceFoundException` —— 语义是"资源不存在"（404 场景），**不是服务器错误**
- **兜底网撒得太大**：`GlobalExceptionHandler` 只有 `@ExceptionHandler(Exception.class)` 一个兜底，把 `NoResourceFoundException` 也接住了 → `log.error("服务器内部错误", e)` + 返回 code 500

关键认知：**"文件不存在"是客户端侧的 404 情况，被"一切异常皆 500"的兜底误判成了服务器故障**。

### 修复

在兜底 catch-all 之前加一个**更具体的 handler**。Spring 的匹配规则是"多个 handler 都能匹配时，最具体的生效"，所以它自动抢在 `Exception` 兜底前面，兜底一行不用改：

```java
/** 静态资源不存在（浏览器自动请求 favicon.ico 等）→ 返回 404，不视为服务器错误 */
@ExceptionHandler(NoResourceFoundException.class)
public Result<Void> handleNoResource(NoResourceFoundException e) {
    log.warn("资源不存在: {}", e.getResourcePath());
    return Result.fail(404, "资源不存在");
}
```

效果：刷新页面不再刷 ERROR，最多一条 WARN。

### 教训

- catch-all（`Exception → 500`）必须搭配精确异常的专用处理，否则**正常情况也会被当服务器错误打 ERROR**——真故障会被噪音淹没
- 异常要分级：资源不存在是 WARN（404 语义），未预期异常才是 ERROR（500 语义），日志级别本身就是诊断信息
- 兜底不需要"缩小"——加特定 handler 让 Spring 按最具体优先匹配即可，兜底仍兜住其余所有异常

---

## Bug 15：网单洗后付订单卡死在"已送达"——两条规则组合出无出路状态

> 类型说明：这是**状态机路径缺口**（死态型），由订单模块静态审计（docs/order-audit-2026-09-10.md）发现，不是运行时报错——恰恰因为不报错、不违反任何单条规则，才隐蔽：每一步操作都被"正确"拒绝了，订单只是永远到不了终点。

### 现象

网单洗后付（pay 0 元）订单：

1. 员工按流程推进：2→3→4→6（派送中）→7（已送达）
2. 此时点"推进"——被拦："未付清，请使用洗后付结账"（正确）
3. 点"洗后付结账"——也被拦："当前状态不允许洗后付结账: 状态=7"（看似也对）
4. **两条路都断，订单永久停在 7**

### 根因

两条各自合理的规则，组合后形成死区：

| 规则 | 来源 | 单独看 |
|---|---|---|
| 走终态 8 前必须付清 | Bug 3 的修复（`updateStatus` 7→8 校验 `paidAmount >= totalAmount`） | ✅ 正确 |
| finalPay 只允许状态 5/6 | 设计文档 §5.4 原文 | ✅ 看似合理 |

问题出在**顺序假设**：文档流程图把"finalPay(6→8)"和"updateStatus(6→7)→updateStatus(7→8)"画成两条并列路径，隐含"洗后付要在 6 态结账"。但状态机是**操作序列**，没有任何机制阻止员工先 6→7 —— 一旦先推进，7 态就落在两条规则的夹缝里。

### 修复

`finalPay` 允许状态 7（配送是物流事实，不应被付款状态阻断；门店单 5、网单 6/7 结账都合理）：

```java
if (this.status != OrderStatus.PENDING_PICKUP     // 5 门店待取件
        && this.status != OrderStatus.DELIVERING  // 6 网单派送中
        && this.status != OrderStatus.DELIVERED) { // 7 网单已送达（本次修复）
    throw new BusinessException("当前状态不允许洗后付结账: 状态=" + this.status.getCode());
}
```

同步更新：设计文档 §5.2/§5.3/§5.4（含修复记录）；回归测试 `OrderTest.DeadlockRegression` 锁定。

> ⚠️ **2026-09-11 修订（本条的"修复"部分已作废，但结论反而更好了）**
>
> 状态机**连号 1–7** 之后，这个死区**从结构上消失了**——不是靠"多放开一个状态"绕过去的。
>
> 原因：旧编号里网单路径是 `…4→6(派送中)→7(已送达)→8(已取件)`，**7 是中间态**，
> 于是出现了"7 态既不能 next（未付清）又不能 finalPay（限 5/6）"的夹缝。
> 新编号把"已送达/已取件"合并成唯一的终态 `7 已完成`，网单路径变成 `1→2→3→4→6→7`，
> **6 是最后一个可操作的状态，而 finalPay 恰好允许 5/6** —— 夹缝没有地方可长。
>
> 所以 `finalPay` 才能**收缩回只允许 5/6**（旧修复是放开到 5/6/7），
> 代码里的三行 `if` 变回两行；`6→7` 补上付清校验（见 `Order.requirePaidOff`）。
> 回归测试同时改名：`DeadlockRegression` → `NoDeadlockRegression`（用例改为验 6→7 这一跳）。
>
> **这才是修状态机 bug 的正确姿势**：多放开一个状态是**打补丁**，
> 简化状态集合消除夹缝才是**改设计**。前者永远留着"还有别的夹缝吗"这个问题。

### 教训

- **状态机验收要查"状态 × 操作"全矩阵**，不能只走合法路径：每个状态点上每个操作，要么合法推进、要么明确拒绝——不存在"全被拒绝"的死态
- **规则组合比单条规则危险**：两条"看起来正确"的校验合在一起可能互相锁死。审查时要把校验放进同一张表里交叉看
- **文档里的流程图会覆盖分支顺序**：画成"两条并列路径"的操作，实际执行是有先后组合的，流程分支要用矩阵穷举验证
- 单测覆盖要包含"**合法操作的非法顺序**"（先 6→7 再结账），不只是"非法操作"（跳级、终态推进）

---

## Bug 16：任何人拿任何 token 都能操作任何订单——接口只认订单 id，不认操作人

> 类型说明：这是**横向越权**（IDOR，Insecure Direct Object Reference）：接口用"资源 id"当唯一输入，却没校验"请求者是否有权碰这个资源"。由订单模块静态审计（docs/order-audit-2026-09-10.md）发现。

### 现象

改前的订单接口签名是：

```java
public Result<Void> nextStatus(@PathVariable Long id)          // 只认 id
public Result<Order> getOrder(@PathVariable Long id)            // 只认 id
public Result<Void> pay(Long id, PayMethod m, BigDecimal amt)   // 只认 id
```

后果（都是真机可复现的）：

| 谁能做什么 | 应该 | 改前实际 |
|---|---|---|
| 顾客 token 推进订单状态 | 拒绝 | **成功推进别人的订单** |
| 顾客 token 替订单付款 | 拒绝 | **成功** |
| 顾客查看别人的订单详情 | 拒绝 | **成功** |
| A 门店员工操作 B 门店订单 | 拒绝 | **成功** |

### 根因

身份信息在 `JwtInterceptor` 里已经解析好并放进了 request attribute，但订单控制器**一个都没取**——token 被用来"过闸机"（鉴权），但过了闸机之后没人再看你是谁（授权）。**认证 ≠ 授权**：验证了"你是合法用户"不等于"这件事你有权做"。

### 修复

1. 控制器从 token（而非请求体）提取身份并传入应用层：

```java
private Long requireStaff(HttpServletRequest http) {
    if (!"staff".equals(http.getAttribute("type"))) {
        throw new BusinessException(401, "请使用员工账号操作");
    }
    return (Long) http.getAttribute("staffId");
}
```

2. 归属校验放应用层（可单元测试）：

```java
if ("customer".equals(requesterType)) {
    if (!order.getCustomerId().equals(requesterId)) throw new BusinessException(403, "无权查看该订单");
} else {
    if (!requesterStoreId.equals(order.getStoreId())) throw new BusinessException(403, "无权查看其他门店的订单");
}
```

3. 列表查询同理：员工按 token 里的 storeId 筛、顾客按 customerId 筛，**前端传不了也改不了**。

### 验收（真机）

| 用例 | 结果 |
|---|---|
| 顾客 token 调 next / pay / final-pay | ✅ 401 请使用员工账号操作 |
| 顾客 A 查顾客 B 的订单 | ✅ 403 |
| 二店店长查一店的订单 | ✅ 403 |
| 二店店长列表 | ✅ total=0（看不到一店数据） |

> ⚠️ **2026-09-10 口径修订**：后两行（跨店 403 / total=0）已按设计文档 §4.2 作废——所有店长可管理所有门店的订单，门店只是地理位置，不是权限边界。现在跨店查单是 200、列表能看到。本节其余结论（顾客侧归属校验、"身份只来自 token"）仍然成立；IDOR 通道虽然不再有越权后果，但"身份只从 token 取"这条规则保留。

### 教训

- **每个接口都要问两遍**：你是谁（认证）＋ 你能不能碰这个 id（授权）。只做前者等于没锁门
- **身份只能来自 token**，绝不能来自请求体——请求体是攻击者完全控制的数据
- 授权规则写在**应用层**（`OrderAppService`），控制器只做身份提取：这样规则能用单测锁住（`OrderAppServiceTest.GetOrder` 6 个用例）
- **越权漏洞不会报错**：它表现为"功能正常"，只有专门去试才会发现。别等 `@Valid` 之类的东西救你

---

## Bug 17：`OrderStatus.fromCode` 抛 `IllegalAccessError`——全局异常处理器接不住

> 类型说明：**异常类型选错**。飘在异常体系之外的 `Error`，从所有 `catch (Exception)` 的网里漏出去。

### 现象

`OrderStatus.fromCode(99)` 不返回 400，而是让请求变成一个非 JSON 的 500 页面（Tomcat 的错误页）。

### 根因

```java
// 改前
throw new IllegalAccessError("没有这个状态:" + code);   // ← Error 的子类
```

两个问题叠在一起：

1. **`IllegalAccessError` 是 `Error` 不是 `Exception`** —— 全局处理器写的是 `@ExceptionHandler(Exception.class)`，接不住 `Error`，异常直接漏到 Servlet 容器
2. **语义完全不对** —— `IllegalAccessError` 是 JVM 的类访问权限错误（比如一个类试图访问它没权限的成员），和"状态码不存在"毫无关系

同一个项目里 `OrderSource.fromCode` / `PayMethod.fromCode` 用的都是 `IllegalArgumentException`，只有这一个写错了——**三个同类方法，一个手滑**。

### 修复

```java
throw new IllegalArgumentException("没有这个状态:" + code);   // 与另两个枚举一致
```

### 教训

- **`Error` 不要用来表达业务错误**：`Error` 是 JVM 级别的（OOM、栈溢出、类加载失败），业务代码抛 `Exception` 的子类
- **同类方法要长得一样**：三个 `fromCode`，两个用 `IllegalArgumentException`、一个用 `IllegalAccessError`，这种不一致本身就是 bug 的温床。写新方法时先照抄邻居
- 兜底 `@ExceptionHandler(Exception.class)` 有天然盲区（`Error`、`Throwable`），别以为"有兜底就万事大吉"
- 这个 bug 的真机验收用例是 `?status=99` → 400，属于"参数校验"那批，跟越权一起验的

---

## Bug 18：订单号用秒级时间戳，同一秒两笔单直接撞唯一索引

> 类型说明：**标识符生成策略**缺陷。撞库概率不是"理论上"的，同秒并发下单必然发生。

### 现象

两笔订单在同一秒创建时，`orders.uk_order_no` 唯一索引拦下第二笔，用户看到 500。

### 根因

```java
// 改前
String orderNo = "YX" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
```

`yyyyMMddHHmmss` 精度只到秒。门店高峰期收银台连续开单、或者网单被脚本批量提交，"同一秒"不但可能，而且是常态。数据库有 `uk_order_no` 唯一索引（这是对的，是最后防线），于是第二笔直接插不进去。

### 修复

两层防护：

1. **生成策略**：加 4 位随机数，把同秒碰撞概率从"必然"降到 1/10000

```java
static String generateOrderNo() {
    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    int rand = ThreadLocalRandom.current().nextInt(10000);
    return "YX" + ts + String.format("%04d", rand);
}
```

2. **撞了就重试**：捕获 `DuplicateKeyException`，换个号重来（最多 3 次，连撞 3 次说明生成策略有毛病，报错让人看见）

```java
try {
    orderRepository.save(order);
    return Result.ok(order);
} catch (DuplicateKeyException e) {
    log.warn("订单号冲突（第 {} 次），换号重试: {}", attempt, order.getOrderNo());
}
```

### 验收

- 真机：订单号形如 `YX202609102129391728`（YX + 18 位数字）
- 单测：撞一次后重试成功（`verify(save, times(2))`）；连撞 3 次报"订单号生成冲突"

### 教训

- **唯一索引不是敌人，是安全网**：它拦住的是"你的生成策略有洞"这个事实。正确反应是修策略 + 加重试，不是去掉索引
- **"同一秒"比你想的常见**：秒级精度的标识符在任何有并发的系统里都会撞；时间戳精度要和业务写入速率匹配
- **重试必须有上限**：无上限重试 = 死循环；上限 + 明确报错，让问题暴露而不是被吞掉
- 这也是为什么"业务错误"和"技术错误"要分开：撞号是技术问题（重试可解），不该让用户看到 500

---

## Bug 19：读-改-写丢更新——两个员工同时点"推进"，同一单被推两格

> 类型说明：**并发竞态**（lost update，丢更新）。单机单线程永远复现不了，所以最容易"测试全绿"。

### 现象

两个员工同时点同一单的"推进"：

```
员工A: 读到 status=3 → 内存改 4 → UPDATE status=4 WHERE id=10
员工B: 读到 status=3 → 内存改 4 → UPDATE status=4 WHERE id=10   ← 覆盖A，且两人都以为推进了一格
```

结果：订单状态和每个人以为的都不一致；连续点两次可能一次推进两格（3→5），跳过"待出厂"。

### 根因

应用层的状态操作是标准的**读-改-写**三步，中间有时间窗：

```java
Order order = orderRepository.findById(orderId)...;   // 读
order.updateStatus();                                  // 改（内存）
orderRepository.save(order);                           // 写（无条件 UPDATE ... WHERE id=?）
```

`save()` 的 UPDATE 只按 `id` 定位，**不带任何状态条件**——数据库无法知道"我读到的状态是不是已经过期"。这不是"概率低所以算了"的问题：门店多端（收银台 + 店长手机）同时操作是常态。

### 修复

用 **CAS（Compare-And-Swap）**：UPDATE 带上"操作前读到的状态"作为条件，让数据库原子地判断"状态是否还是我读到的那个"。

```xml
UPDATE orders SET status = #{po.status}, ...
WHERE id = #{po.id}
  AND status = #{expectedStatus}      <!-- 关键：状态必须仍是操作前那个 -->
```

```java
private void saveStatusChange(Order order, OrderStatus beforeStatus, Long operatorStaffId) {
    order.recordOperator(operatorStaffId);
    if (!orderRepository.updateStatusCas(order, beforeStatus)) {
        throw new BusinessException(409, "订单状态已被其他人变更，请刷新后重试");
    }
}
```

受影响行数 0 = 有人抢先了 → 409 让用户刷新，而不是覆盖对方。

### 验收（真机，确定性复现）

并发竞态通常"碰运气"才能触发，这里用**行锁把时间窗从微秒拉长到秒**，做到确定性复现：

```bash
# 另开一个 MySQL 会话，把订单行锁住 3 秒
BEGIN; SELECT id FROM orders WHERE id=$OID FOR UPDATE; SELECT SLEEP(3); COMMIT;
# 期间并发发两个 next 请求：两个都读到 status=3，都卡在 UPDATE 上等锁
```

| 结果 | 值 |
|---|---|
| 请求1 | `{"code":200}` |
| 请求2 | `{"code":409,"message":"订单状态已被其他人变更，请刷新后重试"}` |
| 最终 DB 状态 | 4（**只推进一格**，不是 5） |

脚本：`C:\tmp\verify-race.sh`；单测：`OrderAppServiceTest.Concurrency`（2 个用例）

### 教训

- **"读-改-写"是并发 bug 的标准模板**：只要代码里有"查出来 → 改一改 → 存回去"，就要问一句"这中间别人改了怎么办"
- **乐观锁 vs 悲观锁**：这里用乐观锁（不加锁，靠 WHERE 条件发现冲突），适合冲突不频繁的场景；如果这单每秒被点一千次，就该换成悲观锁（`SELECT ... FOR UPDATE`）或串行化队列
- **状态机 + 并发 = 必须 CAS**：状态推进天然是"基于当前状态做决策"，决策依据过期了，决策就是错的
- **单测能验逻辑，验不了竞态**：`updateStatusCas` 返回 false 是 Mock 出来的。真正的证据是上面那个行锁实验——"数据库层面确实拦住了"
- 返回 **409 Conflict** 而不是 400：这不是用户参数错，是并发冲突，语义不同，前端处理方式也不同（提示刷新而非改输入）

---

## Bug 20：管理员被当成"token 过期"，报错指向一个重登也解决不了的方向

> 类型说明：**两种不同的 null 被合并成一种处理**。这类 bug 不崩、不报错，只是把人往错误的方向指引。

### 现象

用 `admin/admin123` 登录（返回 200，token 是刚签发的），紧接着调用订单接口：

```json
{"code":401,"message":"登录信息已升级，请重新登录","data":null}
```

重新登录一百次，结果一模一样。

### 根因

`storeId` 为 `null` 有**两种完全不同的原因**，代码只按其中一种处理：

| 原因 | 是否重登可解 | 应有的响应 |
|---|---|---|
| 旧 token 缺 `storeId` claim（字段是后加的） | ✅ 重登即可 | 401 登录信息已升级 |
| 管理员 `staff.store_id` 按设计就是 NULL | ❌ 重登永远还是 NULL | 403 说清"管理员不隶属门店" |

表结构注释写得很清楚：`store_id BIGINT DEFAULT NULL COMMENT '所属门店，管理员为 NULL'`——**这是设计如此**，不是数据缺失。但代码里只有一个 `if (storeId == null) → 401`，把管理员也归进了"旧 token"。

而 token 里其实**有区分依据**：`role` claim（0=管理员 1=店长）早就在了，控制器却没看。

### 修复

按角色区分，报错要指向真正可行的方向：

```java
private Long requireStaffStore(HttpServletRequest http) {
    Long storeId = (Long) http.getAttribute("storeId");
    if (storeId != null) return storeId;
    Integer role = (Integer) http.getAttribute("role");
    if (role != null && role == 0) {
        throw new BusinessException(403, "管理员账号不隶属门店，订单操作请使用店长账号");
    }
    throw new BusinessException(401, "登录信息已升级，请重新登录");   // 只留给旧 token
}
```

一并解决"没有可用账号"的问题：V3 只种了 admin（无门店），**没有任何店长账号，订单接口谁都调不了**。新增 `V4__seed_store_manager.sql`：1 号演示门店 + `manager/admin123`（role=1，store_id=1）。

### 验收（真机）

| 用例 | 结果 |
|---|---|
| admin 查订单列表 | ✅ 403 管理员账号不隶属门店 |
| manager 建单/支付/推进/查列表 | ✅ 全部正常（token 里有 storeId=1） |

> ⚠️ **2026-09-10 口径修订**：admin 的订单权限已提升为"与店长同级"（查/支付/推进/分拣上架都不限门店、不再 403）。唯一保留的拒绝是**建门店单** → 403"管理员账号不隶属门店，建门店单请使用店长账号"（订单要有"物理的店"：取件地址、分拣上架的位置，管理员没有店可归）。Bug 20 的教训（null 有歧义、报错要指向一个可行动作）不变。
>
> ⚠️⚠️ **2026-09-11 再次修订（本条的上述内容已作废）**：用户拍板把管理员权限**收回**——管理员只增删改查店长/员工、管理门店、系统设置，**不参与订单操作、也不能改价**（`/api/orders/**` 一律 403，定价写接口只认店长）；"分拣/上架"概念整体删除。Bug 20 的教训（null 有歧义、报错要指向一个可行动作）依然成立。
>
> ✅ **代码已同步**（2026-09-11，提交 `e250d07`）：闸门落在 `JwtInterceptor.checkRoleGate`，按 URL 前缀收口 —— 管理员 `/api/orders/**` 与 `PUT /api/prices/**` 双双 403。真机验收见 `verify-orders.sh` A1/A1b、`verify-pricing-authority.sh` A2/A3。
>
> 另：`OrderController.requireStaffStore` 里那条"`role == 0` → 403 请用店长账号"的分支**已删除**——闸门前置后它永远走不到。这正是本条（Bug 20）最讽刺的地方：**当初专门为管理员写的分支，最后被一道更靠前的规则变成了死代码**。它的历史价值是暴露了"`null` 有歧义"，但代码本身不该留。

### 教训

- **`null` 是有歧义的**："没这个字段"和"这个字段本来就是空"是两件事，合并处理就会给出误导性的错误
- **错误信息要指向一个可行的动作**：说"请重新登录"之前，先确认"重新登录真的能解决吗"。不能解决的，就是把人往岔路上引
- **种子数据和代码是一体的**：写完"管理员不能操作订单"，才发现没有人能操作订单——**验收账号要跟功能一起设计**，否则功能写完也验不了
- token 里已有的信息（`role`）要主动用起来，别再多查一次库

---

## Bug 21：订单 E2E 脚本在"后端算价"之后就红了，一直没人重跑

> 类型说明：**验收缺口**（脚本失效型），不是程序缺陷。程序是对的，错的是断言脚本还停在旧世界——这类问题最容易蒙混过关，因为红的那份脚本根本没人跑。

### 现象

2026-09-10 口径修订时重跑 `C:\tmp\verify-orders.sh`，B1 起全红：

```json
{"code":400,"message":"第 1 条明细的衣物分类或洗涤方式不存在（分类 1 / 洗涤方式 1）","data":null}
```

脚本里的分类 id 是 `1`/`2`/`3`——V6 种子之后它们是**父分类**，价格只挂在叶子上（11-42）；请求体里还留着 `unitPrice`（后端算价后这个字段已经不存在了）。也就是说：**从第 3 步"后端算价"落地那一刻起，这份脚本就不可能通过**，但当时的验收门只跑了新写的 `verify-pricing-authority.sh`。

### 根因

三层原因叠加：

1. **算价改的是共享行为**：建单从"前端传单价"变成"后端查价目表"，所有建单类断言从此都依赖价目表里的 id 和价格——但验收门只列了新脚本
2. **脚本里的 id 是魔数**：`categoryId: 1` 在 V6 之前指"衬衫"，V6 之后指"上衣（父）"，脚本里没有一行注释说明这些 id 从哪来
3. **没有回归触发器**：`C:\tmp\verify-*.sh` 靠手跑，没有任何机制提醒"改了 X 就要重跑 Y"

### 修复

1. 分类 id 改用 V6 叶子（衬衫 11 / 裤装 12），并在脚本里注明出处
2. 删掉请求体里已作废的 `unitPrice`（"老前端多传也不报错"这条用例留在定价脚本里，那里才是它的家）
3. `E2 总数 >= 3` 的正则 `"total":[3-9]` 改成否定断言（`checkNot "total":0`）——否定断言对总数增长免疫

### 教训

- **改了共享行为，要把"受影响的全部脚本"列出来重跑**，不是只跑新写的那份。判断依据：这个改动让哪些既有断言的前提变了（这次的答案是"所有生成金额的断言"）
- **脚本里的魔数要写出处**（"11 = V6 种子里衬衫的叶子分类"）——不然它会在下一次迁移里悄悄失效，而失效的样子是"400 分类不存在"，看着像程序 bug
- **没被自动跑的脚本 ≈ 没验收**：真机脚本得写进每一步的验收清单（已补进计划文件）
- 也要分清**"断言过期"和"实现回归"**：这次红灯是脚本自己旧了，不是新代码把老功能改坏——先做这个判断再动手，方向错了两边都白修

---

## Bug 22：同一个"脚本失效"又犯一次——`verify-race.sh` 把准备失败伪装成并发失灵

> 类型说明：**Bug 21 的漏网之鱼**。根因一模一样（后端算价之后，脚本还在用父分类 id 建单），
> 但这次的**表现形态坏得多**：它不报"分类不存在"，而是报"并发保护没生效"。

### 现象

2026-09-11 按新口径补跑验收时，`C:\tmp\verify-race.sh` 输出：

```
订单 id=  操作前状态=  （期望 3=洗涤中）
持锁 3 秒 + 并发两个 next ...
请求1: {"code":404,"message":"资源不存在","data":null}
请求2: {"code":404,"message":"资源不存在","data":null}
最终状态=  （期望 4=待出厂，**只推进一格**）
==> [FAIL] 期望恰好 1 个 200 + 1 个 409
```

最后那一行是**假的**——CAS 好得很。真相是：

脚本第 24 行用 `categoryId: 1` 建单，而 `1` 是**父分类**（上衣），价目表里没有它，
后端算价直接 400 → 订单没建出来 → `OID` 是空字符串 → 两个请求打的是
`/api/orders//next`（注意中间那个空 id）→ 双双 404。

### 根因

三层叠加：

1. **和 Bug 21 同一个根因**：`categoryId: 1` 在 V6 之前是"衬衫"，V6 之后是"上衣（父）"；
   `pay` 的金额也还停在 `25.00`（衬衫普洗是 15.00）
2. **Bug 21 的教训写了，但没照做**：那条教训原文是"改了共享行为，要把**受影响的全部脚本**
   列出来重跑"，而当时的修复只改了 `verify-orders.sh` 一个文件——`C:\tmp` 下实际上有 **5 个**脚本
3. **失败信号被张冠李戴**：脚本不做前置校验，`OID` 为空也照跑不误，
   于是"我没准备好"被报成了"被测对象坏了"

> **第 3 条才是真正值钱的**。第 2 条只是遗漏，第 3 条是**把排查方向指反了**：
> 看到"[FAIL] 期望 1 个 200 + 1 个 409"，第一反应一定是去翻 `OrderAppService` 的 CAS 代码——
> 而那里的代码一行都没错。

### 修复

```bash
# ① 分类改用叶子（11=衬衫）；丢掉已作废的 unitPrice
-d "{\"source\":1,\"customerId\":$CID,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}"

# ② 金额改成价目表算出来的全额（pay 只收"全额或 0"）
curl -s -X POST "$BASE/api/orders/$OID/pay?payMethod=cash&amount=15.00" ...

# ③ 新增准备阶段守卫：拿不到订单 id、或状态没到 3，立刻停并打印原始响应
if [ -z "$OID" ]; then
  echo "==> [准备失败] 建单没成功，拿不到订单 id。响应：$ORDER"; exit 1
fi
if [ "$STATUS" != "3" ]; then
  echo "==> [准备失败] 订单没推进到 3，竞态用例的前置条件不成立"; exit 1
fi
```

修复后：一个 200、一个 409，最终状态 4——**只推进一格**。

### 教训

- **写在"教训"里的动作，要当场落成清单**：Bug 21 的教训正确且具体，但它是散文，不是 checklist。
  正确的收尾动作是当场列出 `C:\tmp\verify-*.sh` **全部 5 个文件**，逐个判断"这个改动让它的前提变了没有"。
  **结论正确 + 没有执行 = 没修**
- **测试脚本必须自己准备前置条件，并在准备失败时立刻喊停**：
  一个分不清"前置不成立"和"断言不成立"的脚本，比没有测试更耗人——
  它会在你快要相信自己的时候，把你引到错误的代码上去
- **红灯先问"我的前提还成立吗"，再问"被测对象坏了吗"**：脚本里的魔数（分类 id、金额）
  是最容易悄悄失效的前提，而它们的失效不报"脚本错了"
- **`404 资源不存在` 出现在一个"资源一定存在"的用例里，本身就是强信号**：
  两个请求同时 404，说明请求根本没落到那单上（`/api/orders//next` 里 id 是空的）。
  这个线索当时就在屏幕上，只是被最后一行的 `[FAIL]` 盖住了

---

## Bug 23：BCrypt 的英文原文被原样回给前端——`rawPassword cannot be null`

> 类型说明：**两个独立成因叠在一起**。只修任何一个，现象都会消失，但都只是消失了一半
> （详见「修复」里那句话）。发现它纯属意外：是 Bug 22 那次整改顺手挂的探针照出来的。

### 现象

2026-09-11 给认证模块写验收网（`verify-auth.sh`）时，末尾挂了几条"探针"——
只打印、不断言，目的是"装修前先看清楚毛坯长什么样"。当时照出来的是：

```
D1 员工登录缺 password：
   {"code":400,"message":"rawPassword cannot be null","data":null}
D3 顾客登录缺 password：
   {"code":400,"message":"rawPassword cannot be null","data":null}
```

一个全中文的系统里蹦出一句英文，而且这句话交代得很清楚：**我们用的哪个密码库、
它内部哪个参数是 null**。攻击者不用猜，服务端自己招了。

这条当时**原样留着没改**（改动会污染"纯搬家"的验证），记在提交信息里作为遗留。

### 根因

两层，缺一不可：

1. **处理器回显了框架异常的原文** —— `GlobalExceptionHandler`：
   ```java
   @ExceptionHandler(IllegalArgumentException.class)
   public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
       return Result.fail(400, e.getMessage());   // ← 原文出网
   }
   ```
2. **业务消息也搭这条管道** —— 4 个枚举的 `fromCode` 都拿 `IllegalArgumentException`
   当"给用户看的话"的载体（`"没有这个状态: 99"`）。
   所以第 1 条**不能**直接改成泛化消息：一改，`verify-orders.sh` 里那 3 条
   断言（没有这个状态 / 来源 / 洗涤方式）全红。

`IllegalArgumentException` 是 **JDK 的公共类型**，Spring 内部在抛、BCrypt 也在抛。
处理器拿到它，**没有任何办法区分**"这句是我们要讲给用户听的话"和"这句是框架的内部报错"。
于是 BCrypt 那句框架异常，就顺着"给用户看的话"这条管道出网了。

> 换个说法：**不是处理器写错了，是把两种消息塞进了同一根管子**。
> 管道本身没坏，坏在谁都能往里灌。

### 修复

三步，**每一步都只解决一半问题**：

1. **枚举改抛 `BusinessException`**（`OrderStatus` / `OrderSource` / `PayMethod` / `StaffRole`）——
   把业务消息从公共类型上挪走。消息**一个字不改**，`verify-orders.sh` 那 3 条断言照旧通过：
   改的是类型，不是文案。改完这根管道里只剩框架噪音，才敢动第 2 步
2. **处理器不再回显原文**：
   ```java
   log.warn("参数错误: {}", e.getMessage());   // 原文进日志：日志是给自己排查用的
   return Result.fail(400, "参数不正确");       // 出网的只有泛化消息
   ```
3. **登录服务补空白守卫**（`StaffAuthAppService` / `CustomerAuthAppService`）：
   ```java
   if (password == null || password.isBlank()) {
       return Result.fail(401, "用户名或密码错误");
   }
   ```
   为什么是 401 而不是 400：**400 同样是泄漏**——它等于用响应码承认"这次请求没带密码字段"。
   401 加上和"密码错"逐字相同的消息，连"传没传这个字段"都分辨不出来。

> **只修第 1 步**：业务消息活了，`rawPassword` 照样泄漏（管道还通着）。
> **只修第 2 步**：泄漏堵住了，但 3 条业务断言全红（管道没了，业务消息也出不去）。
> 这类 bug 值钱就值钱在这——修一半，现场看起来完全正常。

### 加固

- 新增 `GlobalExceptionHandlerTest`（interfaces 层第一个单测，纯函数，`new` 一个就能测）：
  钉住"框架异常不回显 + 业务异常原样透传"这两条**相反**的规矩
- `verify-auth.sh` 的 D 段从"探针"**升格为断言**（4 条 → 10 条）：每条都验两件事——
  回的是哪个中文码/话，以及 `message` 里**有没有英文字母**（框架异常的指纹）
- 单测 82 → 88（新增 3 条 handler + 3 条空白密码）

### 教训

- **"要给用户看的消息"必须有自己的异常类型**：一旦和框架共用，就等于把自己要说的话
  和框架的内部噪音混进同一根管子，等框架往里塞一句英文，你连"该不该转发"都判断不了
- **探针不是浪费**：那 4 条 D 段当初只是"拍张照"，照出来的东西反而是那轮最值钱的产出。
  **没有断言的地方也有信息，前提是你肯看一眼**
- **注释里的理由要能指导下一步**：`OrderStatus.fromCode` 上早就写着"抛 BusinessException 而不是
  IllegalArgumentException"，但另外 3 个枚举没跟上——**一个地方想明白了，不等于四个地方都改对了**
- **修完把"当时为什么这么写"钉进测试**：`GlobalExceptionHandlerTest` 里那句
  `doesNotContain("rawPassword")` 才是这条 bug 的墓碑，光断言"等于参数不正确"挡不住下次

---

## Bug 24：Git Bash → MySQL 这条链上有**两个独立的**编码陷阱，第二个会让"不存在"类断言无条件通过

> 类型说明：**两个坑，根因完全不同，症状却长得很像**（都是"中文坏了"）。
> 第一个是 MSYS2 转命令行参数，第二个是 mysql 客户端自己的默认字符集 ——
> 一开始我把第二个也归给了 MSYS2，方向错了半天。

### 现象

`verify-stores.sh` 从写出来那天起就跑不了：**每一个带中文的请求体都返回**
`{"code":400,"message":"请求体格式不正确"}` —— 而这条报错完全指不到编码上。

同一时期，用 `db()` 查库时中文显示成 `?????`，改用 `hex()` 却能看出字节"差不多对"。

### 根因

**坑一：MSYS2 会转换命令行参数里的非 ASCII。** Git Bash 把参数交给原生 exe
（`curl.exe`、`docker.exe`）之前，会按当前 Windows 代码页（中文系统 = GBK/CP936）
转一遍。实测：

```
curl -d '{"username":"中文测试"}'      发出 23 字节
curl --data-binary @文件               发出 27 字节
```

差的 4 字节，正是「中文测试」在 UTF-8（12 字节）与 GBK（8 字节）下的差。
服务端 Jackson 收到的是 GBK 字节、不是合法 UTF-8，直接 400。

**判据：只含 ASCII 的参数随便传；只要有一个字节 >127，就得走文件或 stdin。**
bash 内部（`printf`、变量、重定向）是字节安全的 —— MSYS2 只转**跨进程的参数**。

**坑二：mysql 命令行默认按 `latin1` 收发。** 这条和 MSYS2 无关，是客户端自己的默认值
（`character_set_client` / `character_set_results` 都是 latin1）。两个方向都坏：

- **写**：UTF-8 字节被当 latin1 解读、再转存进 utf8mb4 → **双重编码**
  （实测存进去的是 `C3A4C2BAE28098…`）
- **读**：真 UTF-8 的中文转不成 latin1 → 整串变成 `?????`

### 最阴的一点

**双重编码在读的时候会抵消回去**：存的是乱的，`SELECT` 出来却是好的。

于是"列表里不含这个店名"这类断言**无条件通过** —— 库里那个名字本来就写不出正确的那六个字，
怎么断言都找不到它。`verify-stores.sh` 的 `A3`（"列表不含停业店"）当时就是这么假绿的：
它不但在验证一个根本没生效的过滤，而且**即使过滤真的坏了它也照样会过**。

### 修复

- 带请求体的调用一律走 `postJson`/`putJson`（落文件 + `--data-binary @`）
- 带中文的 SQL 走 `dbFile`（`docker exec -i` + 重定向走 stdin）
- **七个脚本全部**的 `db()` 和内联 `docker exec` 补上 `--default-character-set=utf8mb4`
- 造完中文数据要**断言库里的字节**（`A3c` + 准备阶段守卫），不能只看"读出来是对的"

### 教训

- **"读出来是对的"不能证明"存进去是对的"。** 双重编码是可逆的，
  这让最自然的验证手段（查出来看一眼）恰好失效
- **同一个症状可能有几个独立成因，修好一个不代表找对了。** 我把两个坑都归给 MSYS2 时，
  `db()` 的修复看起来"生效了"（读方向确实被我改对了），但写方向的解释一直是错的 ——
  错的解释会让人下一次在错误的地方找问题
- **参数里的非 ASCII 是跨工具的通病**，不止 curl：`docker exec mysql -e "…中文…"` 一样中招，
  而且它是**写**路径，坏得很安静（见 Bug 25）

---

## Bug 25：`verify-stores.sh` 从未跑通过，而 `verify-orders.sh` 用同一手法在库里存了乱码——**两个脚本都是绿的**

> 和 Bug 21/22 同类（脚本失效），但这次不是"改了行为没重跑"，
> 而是**脚本从第一天起就没有真正验证过它声称的东西**。

### 现象

`verify-stores.sh` 第一版把中文都写在 `curl -d` 里（坑一），所以它从写出来当天就是坏的。
但它被当成"新脚本还没调试"，一直没跑 —— 也就一直没人发现它**根本没验证过任何东西**。

同一时期 `verify-orders.sh` **一直是全绿的**（38/38）。直到这次逐个查库才发现：

```
id=2   äºŒå·é—¨åº—   C3A4C2BAC592C3A5C28FC2B7C3A9E28094C2A8C3A5C2BAE28094
id=99  云洗停业测试店   E4BA91E6B497E5819CE4B89AE6B58BE8AF95E5BA97
```

id=2 是 `verify-orders.sh` 的 F 段建的脚手架，**店名是双重编码的乱码**，存进去很久了。
而且那句 `insert ignore` 意味着**这一行永远不会自愈** —— 后面每一次运行都跳过它。

### 根因

F 段用 `docker exec mysql -e "…'二号门店'…"` 建脚手架，中文走命令行参数 → 坑一 → 双重编码入库。

而它全绿，是因为**没有任何断言看店名**：F 段测的是"二店店长能看到一店的单"，
店名叫什么都不影响。乱码就这么躺在库里，只有主动去查才看得见。

### 修复

- F 段的 SQL 改走 stdin（`docker exec -i` + 重定向），并补一句自愈 `update`
  把已经存坏的那行写回正确字节
- `verify-stores.sh` 的整个请求路径改走文件（`postJson`/`putJson`）
- 两处都补上"造完数据验证字节"的守卫

修复后实测：`hex(name)` = `E4BA8CE58FB7E997A8E5BA97`（二号门店），id=99 也是对的。

### 教训

- **"脚本全绿"和"脚本在验证"是两件事。** 一个断言不看的东西，
  在它坏掉时可以无限期地保持绿色
- **`insert ignore` + 坏数据 = 永久坏数据。** 自愈语句必须显式写，
  不能指望"重跑一遍就好了" —— 重跑恰恰是它不会好的原因
- **看到"这个脚本从来没跑通过"时，顺手问一句：那它旁边那个一直绿的脚本呢？**
  同一时期、同一手法写的东西，往往是**一起坏的**，只是一个红得明显、一个绿得可疑

---

## Bug 26：`sed` 补的那个换行 + 子串断言 = 一次还没跑就已经成立的"假绿"

### 现象

**没有任何现象。** `verify-coupons.sh` 的 I2 是用来证明"中文券名读出去没坏"的字节断言，
它在脚本第一次运行之前就已经**注定会通过**——无论被断言的东西对不对。

### 它是怎么被发现的

脚本写完了但后端还没重启（V10 迁移没执行，跑了也是白跑），于是先在**脱机**状态下把脚本里
的几个 shell 助手（`mine_slice` / `num` / 券名字节提取）单独拎出来，用**构造的 JSON** 喂一遍。
I2 的那条流水线吐出来的是：

```
got  = E5BC80E4B89AE4BA94E68A98E588B80A
want = E5BC80E4B89AE4BA94E68A98E588B8
                              ^^^ 多了一个字节
```

### 根因（两个成因叠加，缺一个就没事）

1. **`sed` 会给自己的输出补一个换行。** 那 0A 不是数据的一部分，是 `sed` 加的；
   `od -An -tx1` 照单全收，hex 串末尾就多出 `0A`。
2. **`check` 是子串匹配**（`grep -q "$pattern"`）。
   `E5BC80…E588B8` 是 `E5BC80…E588B80A` 的子串，所以**多一条尾巴照样匹配**。

### 为什么它比 Bug 24/25 更值得记一笔

Bug 24 和 Bug 25 是"跑起来之后绿得不对"，这个是**跑之前就已经绿了**——
它一次都不会红，也不会在日志里留下任何可疑痕迹。如果等到第一次真机跑，
看到的会是 32/32 全绿，而 I2 什么都没验证。

### 修法

- 比对前 `tr -d '\n'`，把 `sed` 补的那个字节去掉；
- 断言**加锚**：`^…$`，让"多一个字节"真的会失败。
  验证方式是**反证**：故意给正确结果加一个 `0A`，锚定后必须失败——
  同一句话在修复前是"通过"的。

### 顺带改掉的一处同类问题

`mine_slice()` 原本"找不到这张券就原样返回整串"，下游再 `head -1` 取第一个 `expired`——
那取到的是**别的券**的字段。多数情况会碰巧是 `false` 而暴露出来，
但赶上"恰好也是 true"就又假绿了。改成找不到时吐 `NOTFOUND`。

### 教训

**脱机验脚本（把助手函数拎出来喂构造数据）应该在写完之后、跑真机之前做。**
这一步的成本是几分钟，换来的是"脚本第一次运行的结果是可信的"——
否则第一次运行只能证明"它跑得通"，证明不了"它验得对"。

---

## Bug 27：快递单号的四条校验断言全排在"该失败的原因"之后 —— 又是一条注定全绿的假绿

### 现象

`verify-orders.sh` 新增的 I 段（快递单号）里，我把"顾客 token → 401""少参数 → 400""超 50 字 → 400""空白串 → 400"四条断言放在了 `I4 再推进 6→7` **之后**。脚本跑起来会全绿。

### 根因

那四条坏输入**本来就都会失败**——只是失败在另一句消息上。`Order.fillExpressNo` 的检查顺序是**来源 → 状态 → 空值 → 长度**，而 `I4` 已经把这张单推到了 7 态（已完成）。于是不管请求里带的是顾客 token、还是 51 个字、还是根本没有 `expressNo` 参数：

- 参数层面的失败（缺参 → 400、顾客 token → 401）在 controller/interceptor 就被拦下，**结论碰巧是对的**；
- 参数层面过了的（超长、空白串）会走到领域层，然后被**状态检查**拦下 —— 报的是 `只有派送中的订单可以录入快递单号`。

而 `check` 是**子串**匹配，`"不能超过"` 和 `"不能为空"` 这两条期望片段压根不会出现，本该红。**真正会红的是"缺参"和"顾客 token"两条**，它们能过是因为校验发生在状态之前。也就是说：长度校验到底写没写、空白串拦没拦，**这个顺序下一条都看不出来**——四条断言里两条假绿、两条真绿但验错了对象，合起来读起来像"四条全过 = 校验齐了"。

这和 Bug 24/25/26 是同一个病：**断言问的不是"它为什么失败"，而是"它失败了没有"。** 只要被测对象在**任何**路径上都会失败，这种断言就恒真。

### 它是怎么被发现的

不是跑出来的——是**写完之后重读**发现的。当时正在检查"注释里说的检查顺序"和"断言排列的顺序"对不对得上，读到 `I4` 把单推到 7 态，而后面四条还在用这张单，立刻就觉得不对劲。**脱机重读之所以能抓到，是因为这一次我问的是"这几条如果失败，会报哪句话"**——上一轮（Bug 26）问的是"这条断言有没有可能通过"，问对了问题才抓得到。

### 修法

把四条坏输入全部搬到 `I4` 之前，让它们在**唯一一个正确的原因**上失败：

```
I1~I3  正常录入（6 态）
I4~I7  四条坏输入 ← 必须在这里，单还在 6 态
        顾客 token 401 / 缺参 400 / 超长 400 / 空白串 400
I7b    四次坏输入之后库里还是原来那个单号（没有半截写入）
I8     6→7 完成
I9     完成后再补录 → 400 "派送中"（这时"派送中"才是**唯一**可能的原因）
I10    门店单 → 400 "只有网单"（来源检查在状态之前，也钉住了检查顺序）
```

并在段落里写了一句注释说明**为什么顺序不能动**（"顺序反了的话，长度校验有没有写、员工校验有没有生效，全都看不出来 —— 一条全绿的假绿"）。

`I9` / `I10` 反过来变成了"只有这一个原因"的强断言：7 态下任何坏输入都报"派送中"，所以这两条必须用**参数完全合法**的请求去打，报出"派送中"就说明确实是状态拦下的。

### 教训

**一串"坏输入应该被拒"的断言，必须放在被测对象处于"除了这一条、没有别的理由会拒绝"的状态上。** 换句话说：断言的前置状态要**排除掉所有其他失败原因**，否则你验的是最后一道防线，不是你以为的那道。

已有一条同类纪律（README 的两条硬要求）只管"准备失败"；这一条管的是**反过来的**——准备"太成功"（状态被推过了头），同样会让断言失去意义。

---

## Bug 28：下拉框存的是参数值，代码却拿它去查一张按**枚举名**建的字典 —— `?payMethod=undefined`

### 现象

前端员工订单详情页的「确认收款」和「洗后付结账」，两个下拉框都失效：选「现金」还是选「微信」没有任何区别，请求一律以

```
{"code":400,"message":"没有这个支付方式"}
```

打回。而 `npm run build` **全绿**，页面渲染得好好的——下拉框里有三个选项、能选、按钮能点。

### 根因

`utils/format.js` 里有一张把枚举名翻成参数码的表：

```js
export const PAY_METHOD_CODE = { CASH: 'cash', WECHAT: 'wechat', ALIPAY: 'alipay', BALANCE: 'balance' }
```

而模板里的下拉框，`value` 写的是**参数码**：

```html
<select v-model="payMethod">
  <option value="cash">现金</option>      <!-- 值已经是 'cash' 了 -->
```

于是提交时 `PAY_METHOD_CODE[payMethod.value]` 实际是 `PAY_METHOD_CODE['cash']` —— **键不存在，返回 `undefined`**。到这里还没有任何东西会报错：JS 里用不存在的键索引一个字面量对象完全合法。

真正把它变成"一个发得出去、只是内容是废话的请求"的是下一步：`new URLSearchParams({ payMethod: undefined })` **不会**抛异常，也**不会**省略这个参数，它老老实实序列化成字符串

```
?payMethod=undefined&amount=30
```

后端拿 `"undefined"` 去查支付方式，回一句"没有这个支付方式"。**整条链上没有任何一环会失败，除了最后那一环**——而那句话读起来像"后端不认识这个支付方式"，一点都不像"前端查表查空了"。

### 它是怎么被发现的

不是跑出来的（这一轮前端一次都没跑过），是**重读 `format.js` 的导出和它的调用点**时发现的。当时问的是这么一句话：

> 这张表的**键**，和 select 里的**值**，是同一套东西吗？

`PAY_METHOD_TEXT` 的键是枚举名（它要拿 `order.payMethod` 去查，那是 `"CASH"`）；`PAY_METHOD_CODE` 的键**也**是枚举名（因为它是照着上面那张抄的）——**但它的调用点喂进去的是参数码**。表和调用点对同一个概念各用了一套表示，中间那次转换就必然是空的。

这个问法和 Bug 27 同源：**不问"它跑通了没有"，问"这两个东西是不是同一个东西"。** 27 问的是"断言的前置状态和它要验的原因对不对得上"，28 问的是"表的键和查表用的键对不对得上"。

### 修法

**删掉那张反查表**，而不是"把它的键改对"。改成一张和顾客侧同款的选项表：

```js
/** 柜台能选的支付方式。`code` **直接就是要发出去的参数值**（`?payMethod=cash`），
 *  中间**不设**"把枚举名翻成小写码"的查表 —— 那种表的键一旦写错（用枚举名 CASH
 *  去查小写码 'cash'），查不到只会得到 undefined，然后静悄悄发一个
 *  `?payMethod=undefined` 出去：前端一路绿灯，到后端才 400 */
export const STAFF_PAY_OPTIONS = [
  { code: 'cash', text: '现金' },
  { code: 'wechat', text: '微信' },
  { code: 'alipay', text: '支付宝' },
]
```

select `v-for` 这张表、v-model 直接持有**参数值本身**，提交时原样透传：

```html
<select v-model="payMethod">
  <option v-for="m in STAFF_PAY_OPTIONS" :key="m.code" :value="m.code">{{ m.text }}</option>
</select>
```

```js
const onPay = () => run(() => payOrder(orderId, payMethod.value, Number(payAmount.value)), '收款已记录')
```

`PAY_METHOD_TEXT`（枚举名 → 中文）保留，它只管**显示返回值**，职责单一。两边从此各自只有一套表示，"转换"这件事不存在了，也就没有"转换写错"这个位置。

### 教训

**同一个概念有两套表示（枚举名 / 参数码）时，"中间转换"是个纯亏的环节**——它不产生信息，只产生一个写错的机会，而且写错了不报错、只发一个 `undefined` 出去。能删就删：让表单的 value 本身就是参数值，比修对一张映射表更省事。

配套的一条：**`npm run build` 全绿只证明"语法和 import 能解析"，一个字都不证明"这个按钮能用"。** 前面 27 条里大半是"验收脚本假绿"，这一条是"验收脚本根本还没轮到它"——构建通过是最弱的那种绿，别把它当"验证过了"。

---

## Bug 29：文档里写了一个**还没跑出来**的数字 —— 假绿换了个地方

### 现象

改完「网单门店可选」之后，往设计文档 §7 的验证快照里填：

> 单元测试 **172 项**全绿……`verify-orders.sh` **69/69**、`verify-stores.sh` **60/60**……

**这些数字当时一个都没跑过。** J 段（网单门店可选那 4 条断言）刚写完还没执行，后端也还没重启到带 V11 的那一版。69 是数出来的（65 + 4），不是跑出来的。

### 根因

不是笔误，是**同一台机器、同一套动机**在另一个表面上重演了一遍。前面 28 条几乎全是"验收脚本假绿"：断言注定通过、失败原因被换掉、脚本根本没跑。它们的共同点是**"绿"这个信号与"真的验过"脱钩**。这次脱钩的不是脚本，是**文档**：

- 脚本里的假绿，下次跑一遍就会露馅；
- 文档里的假绿**没有任何东西会去重跑它**。它会被当成事实读、被当成基线引用，然后在下一轮变成"上次是 69 啊怎么现在 65"的排查起点。

触发它的是一个很自然的动作：**文档要写得完整，而完整性看起来像是"把空填上"**。数字有了、格子填满了，文档就"完成"了——至于数字是不是量出来的，写的时候没人在问这个问题。

### 怎么发现的

写完重读了一遍那句话，卡在"我是什么时候跑的 J 段"上——想不起来，因为没跑过。于是把那一段换成明确写着"尚未重跑"的 ⚠️ 块，并在里面写清为什么留空：

> 等重启后端、跑完再回填 —— 写了没跑过的数字，就是这份文档自己记了几十遍的那种假绿。

跑完八个脚本之后才回填成真数字。

### 教训

**"数字从哪来"要跟着数字一起留下。** 这一条比"别忘了跑"更可操作：忘了跑是记性问题，而"这个 69 是数出来的还是跑出来的"是一个能在写的时候当场问、也只需要问一句的问题。

落成一条规则（已写进设计文档 §7）：**数字没跑出来之前，那一节写"尚未重跑"，不写预期值。** 空着是诚实的，填一个算出来的数不是——**读者无法区分两者，这正是它的危险之处。**

补一句和 Bug 26/27 的关系：那两条是"跑之前就已经绿了"，这条是"没跑就说绿了"，**三条都不是跑出来的错，都是写出来的**。验收侧的 bug 大多不长在运行期，长在"我要写一个看起来完整的交付物"这个动作里。

---

## Bug 30：自愈语句只修了**一半**的中文列 —— 乱码在库里躺了四天

### 现象

两家脚手架门店的 `address` 在库里是**双重编码的乱码**，而它们的 `name` 是好的：

```
id=2   name=二号门店        address=éªŒæ”¶ç”¨        ← 正确值是「验收用」
id=99  name=云洗停业测试店   address=æ­å·žå¸‚ä½™æ­åŒºæµ‹è¯•è·¯ 1 å·   ← 正确值是「杭州市余杭区测试路 1 号」
id=1   name=云洗中央门店     address=浙江省杭州市西湖区文一西路 100 号   ← 迁移种子，一直是好的
```

用户在前端的门店下拉框里**肉眼**撞见的。八轮脚本全绿、单测 172 全绿，它照样烂着。

### 根因

两个脚本造脚手架门店时都是这个形状：

```sql
insert ignore into stores (id,name,address) values (2,'二号门店','验收用');
update stores set name='二号门店' where id=2;   -- ← 只补了 name
```

`insert ignore` 遇到已存在的 id 什么都不做（这正是 Bug 24 那轮留下的乱码能活下来的原因），所以**修复只能靠那句 update**。而 insert 里的中文列有**两个**，update 只写了**一个**。

于是 `name` 每次跑都被写回正确字节，`address` 一次都没被碰过。区别不在 SQL 写法，在**谁盯着它**：

- `name` 有 `verify-stores.sh` 的准备守卫 + A3c 两条断言盯着，烂了会立刻喊停；
- `address` **一条断言都没有** —— 乱着不报错、不影响任何 check，安静地躺着。

F 段（`verify-orders.sh`，用这家店造跨店场景）连店名都不看，只看订单 —— 所以"脚本全绿"和"库里有乱码"**同时为真，两件事不冲突**。

### 修法

成对做两件事，缺一不可：

1. **整行写回**：`update stores set name='…', address='…' where id=N`
2. **整行断言**：准备守卫检查这一行**所有**非 ASCII 列的字节（两个脚本都补上了 address 那条）

### 验证

不跑整个脚本（会把刚清干净的 110 张订单再造回来），而是**从真脚本里 sed 抽出那几行**执行 —— 抽出来的文本和真脚本逐字一致，所以验的是真代码：

- 抽出 F 段脚手架 → 退出码 0，store 2 的 address 变成 `E9AA8CE694B6E794A8`（「验收用」的正确字节）；
- 抽出准备段 → 退出码 0，store 99 的 address 十六进制与 `printf '%s' "杭州市余杭区测试路 1 号" | od` 的输出逐字节相同；
- **反证**：把 store 2 的 address 人为改成乱码，只跑守卫那几行 → `==> [准备失败] 二店 id=2 的 address 字节不对 … 实际 'éªŒæ”¶ç”¨'`，退出码 1。不跑这条反证的话，"守卫通过"可能只是因为守卫写错了永远为真（Bug 27）。

### 教训

**一条自愈语句的正确性标准，不是"它写了点什么"，而是"它覆盖了这行里所有会被这条写路径弄坏的东西"。** 只修一部分，剩下的那部分就是静默腐烂 —— 它不会以断言失败的形式暴露，只会以"某天有人肉眼看见"的形式暴露。

判断方法很机械，不需要判断力：**把 insert 里所有的非 ASCII 列列出来，逐个问"谁在盯着它"**。答不出来的那些，就是正在烂或者即将烂的那些。

> 同轮还踩到一个**信号读错**：验证时写的是 `bash probe.sh | grep -v Warning; echo $?`，回显 `1` 看着像脚本失败 —— 实际 `$?` 是 `grep` 的（没有匹配行时 grep 退 1），脚本本身是 0。危险的**不是**这个方向，是反过来的那种：`脚本 | grep 某个必然出现的串` 永远回 0，**脚本自己失败多少次都被盖掉**。这个形状和第 26/27 条是同一类，只是这次它长在一个 `;` 后面而不是断言里。

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
| 14 | Interfaces | 异常处理缺陷 | 兜底 catch-all 把 favicon.ico"资源不存在"当 500 打 ERROR 刷屏 |
| 15 | Domain | 状态机路径缺口 | 网单洗后付先推进到 7 后：next 拦未付清、finalPay 限 5/6，订单永久卡死（**2026-09-11 状态机连号后，该死区已从结构上消除**） |
| 16 | Interfaces | 横向越权 IDOR | 订单接口只认订单 id 不认操作人：任意 token 可操作任意订单 |
| 17 | Common | 异常类型选错 | `OrderStatus.fromCode` 抛 `IllegalAccessError`（Error），全局处理器接不住 → 非 JSON 500 |
| 18 | Application | 标识符生成缺陷 | 订单号用秒级时间戳，同秒两笔必撞 `uk_order_no` |
| 19 | Application | 并发竞态 | 读-改-写丢更新：并发推进同一单会覆盖对方状态，需 CAS |
| 20 | Interfaces | 错误信息误导 | 管理员 storeId 为 NULL（设计如此）被当成"旧 token 需重登"，403 报成了 401 |
| 21 | 验收 | 脚本失效未重跑 | 后端算价后 `verify-orders.sh` 就红了（分类 id 还是 V6 之前的父分类、unitPrice 已作废），到口径修订才被发现 |
| 22 | 验收 | 脚本失效**被误诊** | Bug 21 的漏网之鱼：`verify-race.sh` 同样用父分类建单，但报出来的是"CAS 失灵"（两个 404 被读成并发失败），把排查方向指反 |
| 23 | Interfaces + Common | 信息泄漏 | 业务消息借 JDK 公共异常类型 + 处理器回显原文：BCrypt 的 `rawPassword cannot be null` 直接回给前端（**两个成因，修一半就正常，所以难发现**） |
| 24 | 验收 + 环境 | 编码（**两个独立成因**） | Git Bash 把非 ASCII 参数转成 GBK；mysql 客户端默认按 latin1 收发。后者写进去是双重编码，而**读的时候会抵消回去** —— "不存在"类断言因此无条件通过 |
| 25 | 验收 | 脚本从未运行 + 假绿 | `verify-stores.sh` 从写出来当天就是坏的（中文请求体全 400）；同期 `verify-orders.sh` 全绿，却用同一手法在库里存了个乱码店名 —— 因为没有任何断言看它 |
| 26 | 验收 | 假绿（**跑之前就已经绿了**） | `sed` 输出的尾部补了个 `0A`，而子串断言不在乎多一条尾巴 —— 字节断言在脚本第一次运行之前就注定通过。靠脱机喂构造数据发现，靠加锚 + 反证修掉 |
| 27 | 验收 | 假绿（**失败原因被换掉了**） | 快递单号的四条坏输入断言排在"订单已完成"之后：它们确实会失败，但失败在状态检查上，长度/空白校验写没写完全看不出来。靠"重读时问一句它会报哪句话"发现 |
| 28 | 前端 | 两套表示之间的转换写空 | `PAY_METHOD_CODE` 的键是枚举名 `CASH`，select 的值是参数码 `cash`，`PAY_METHOD_CODE['cash']` 是 `undefined`，而 `URLSearchParams` 会把它序列化成字面量 `?payMethod=undefined` 发出去。构建全绿、后端才 400。靠"表的键和查询用的键是不是同一套"发现，靠**删掉那张表**修掉 |
| 29 | 文档 | 假绿（**换了个表面**） | 把还没跑过的 `69/69`「八脚本全绿」填进了设计文档 §7 —— 数字是数出来的不是跑出来的。脚本里的假绿下次跑就露馅，**文档里的假绿没有任何东西会去重跑它**。靠"我是什么时候跑的"想不起来发现，修法是留 ⚠️「尚未重跑」，跑完再回填 |
| 30 | 验收 | 自愈只修一半 | 两个脚本的脚手架 insert 里有两个中文列（name/address），自愈 update 只补了 name —— 因为 **name 有断言盯着、address 没有**。两个门店的地址乱码因此在库里躺了四天，八轮脚本全绿也照样烂（F 段根本不看店名）。修法：整行写回 + 整行断言。判断法是机械的——**把 insert 里的非 ASCII 列逐个问"谁在盯着它"** |

---

## 测试通过的功能

### 2026-09-13 券-订单抵扣（步骤 6）

**8 个脚本全部重跑** —— 本轮改的是 `OrderAppService.createOrder`，而**建单是每个造数据的
脚本都要走的门**（`verify-orders` / `verify-price-write` / `verify-pricing-authority` /
`verify-race` / `verify-stores` 五个脚本里所有"先建单、再断言"的段落全在射程内）：

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测（domain 43 + application 106 + interfaces 3） | 152 | ✅ 基线 139 + 新增 13（domain +6、application +7） |
| `verify-coupons.sh` | 32 | ✅ **新脚本**，见下 |
| `verify-stores.sh` | 60 | ✅ |
| `verify-auth.sh` | 36 | ✅ |
| `verify-orders.sh` | 38 | ✅ |
| `verify-price-write.sh` | 29 | ✅ |
| `verify-price.sh` | 26 | ✅ |
| `verify-pricing-authority.sh` | 24 | ✅ |
| `verify-race.sh` | 1 | ✅ 一 200 一 409，只推进一格 |

本轮改动：

- **券-订单抵扣（§5.8）**：`total_amount` 存**折后应付**，`discount_amount` 是**相减**出来的
  （不是把折扣率再算一遍——两个数必须对得上）。存折后价的意义不只是展示：
  `pay` / `updateStatus` / `finalPay` 三处"付清没"比的都是它，存折前价会让用券的顾客
  在收银台被要求付全款
- **券必须属于这张单的顾客**：`coupon_grabs.customer_id == order.customer_id`。
  这条**不是防越权的**（两种来源都拦不住冒用），它买的是**让错误指对方向**——
  没有它，请求会一路走到 CAS 报 409「该优惠券已被使用」，把"这张券不是你的"
  说成"这张券被人用过了"，把人引去查错方向
- **核销 CAS 放在落库之后**，两个独立理由：①订单号冲突的重试循环内部会吞异常，
  先核销会在三次撞号后白白烧掉顾客的券；②`used_order_id` 要等订单拿到自增 id 才写得出
- **门店单也能用券**（2026-09-12 用户拍板，设计文档已同步）+ **V10 使用记录三列**
  （`used_time` / `used_order_id` / `used_staff_id`）。这是放开门店单用券的**唯一兜底**：
  **记录不等于授权**，它拦不住员工替顾客烧券，只能让这件事事后可查
- **`GET /api/coupons/mine`**：只含未使用，过期的**置灰而不是消失**（"我抢的券去哪了"
  比"这里本来就没有东西"好回答）
- **`discount_amount` 必须是 `BigDecimal.ZERO` 而不是 null**：列是 `NOT NULL DEFAULT 0.00`，
  留 null 会让**每一张不带券的订单**都插不进去——而不带券的是绝大多数

E2E 里最值钱的一条是 **F 段**：和 `verify-race.sh` 同一个手法（行锁拉长窗口）让两个带
同一张券的建单请求并发，断言"恰好一个 200 + 一个 409"**并且库里只有 1 张单**——
后者才是在证"CAS 失败时订单真回滚了"，只验 409 的话，一个"报了错但订单留在库里"的
半截状态照样能过。

> 同轮修掉两个自伤：①编辑 `OrderPO.java` 时连带删掉了 `private BigDecimal paidAmount;`——
> 编译立刻报错，没有流出去；②**Bug 26**（`sed` 的换行 + 子串断言 → 断言在脚本第一次
> 运行之前就注定通过），靠"后端还没重启，先把脚本助手拎出来喂构造数据"发现。

### 2026-09-13 顾客在线支付 + 快递单号（步骤 7）

**8 个脚本全部重跑** —— 本轮改了 `Order.java`（新增 `fillExpressNo`）与
`OrderAppService.java`（新增 `onlinePay` / `fillExpressNo`，并改了 `saveStatusChange`），
`verify-orders.sh` 是主战场，其余 7 个是回归：

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测（domain 49 + application 119 + interfaces 3） | **171** | ✅ 基线 152 + 新增 19（domain `ExpressNo` +6、application `OnlinePay` +9 / `ExpressNo` +4） |
| `verify-orders.sh` | **65** | ✅ 基线 38 + H/I 两段 **27** |
| `verify-stores.sh` | 60 | ✅ |
| `verify-auth.sh` | 36 | ✅ |
| `verify-coupons.sh` | 32 | ✅ |
| `verify-price-write.sh` | 29 | ✅ |
| `verify-price.sh` | 26 | ✅ |
| `verify-pricing-authority.sh` | 24 | ✅ |
| `verify-race.sh` | 1 | ✅ 一 200 一 409，只推进一格 |

本轮改动：

- **顾客在线支付 `POST /api/orders/{id}/online-pay`**（§5.2 / §6.4）：**没有 amount 参数**，
  金额由后端从订单上取（`total_amount` 已是折后应付）。校验顺序 **401 → 404 → 403 → 400**，
  其中 **403 排在"支付方式"之前**是刻意的——先认领属关系再谈业务规则，倒过来会把
  "这单不能用现金"漏给一个不相干的人
- **快递单号 `POST /api/orders/{id}/express`**（§5.4）：仅网单、仅 status=6，**不推进状态**，
  但走同一条 CAS（一个员工录单号、另一个同时推进 → 后到的拿 409，而不是把单号写到已完成的单上）
- **`express_no` 的 50 字上限补在领域层**：列是 `VARCHAR(50)`，不拦的话 51 个字会一路走到
  UPDATE 才被 MySQL 弹回来（`DataTooLong` 英文异常 → 500）。按 **code point** 数而不是
  `length()`——后者数 UTF-16 码元，一个 emoji 占 2，会在边界上放行超长的串
- **`saveStatusChange` 加 null 防护**：`onlinePay` 是第一个传 `operatorStaffId = null` 的调用方，
  而无条件 `recordOperator(null)` 会把 `staff_id` 这一列**抹掉**——网单上本来就是 NULL 看不出，
  **门店单上那是建单员工**，"谁经手的"这条线索就没了。不报错、不违反约束、只悄悄少一个字段，
  靠写的时候多问一句才发现；回归测试钉在 `OrderAppServiceTest.OnlinePay#doesNotWipeStaffId`

E2E 里最值钱的两条：

- **H10**：持锁 3 秒 + 并发两个 `online-pay` → **恰好一个 200 + 一个 409**，
  且断言库里 `status|paid_amount = 2|15.00`。**顺序重放拿不到 409**——第二次读到的
  已是 2 态，在领域层就被拦成 400 了；409 只在两个请求并行、都读到 1 态时才出现，
  所以这条必须真并发才验得到。
- **I10**：用一张**已到 7 态的门店单**录单号，必须报「只有网单」。这条同时钉住了
  `fillExpressNo` 内部"来源检查在状态检查之前"的顺序——反过来的话会报「派送中」，
  而那句话在 7 态下没有信息量。

> 本轮**在跑之前**先修掉了一个自伤的假绿：**Bug 27**（四条"坏输入该被拒"的断言排在
> "订单已完成"之后 → 失败原因被换成了状态检查，长度/空白校验写没写完全看不出来）。
> 因为脚本静态数出来是 65 而文档里要写这个数字，所以在跑之前逐条核了一遍。
>
> 另有一处**环境误判**差点发生：跑之前查迁移版本用了 `select max(version) from
> flyway_schema_history`，它回 `9` 而库里有 V10——`version` 是 VARCHAR，`max` 走字符串
> 比较，`'9' > '10'`。当时差点把"V10 已执行"读成"后端跑的是旧包"。已把 README 里的
> 建议查法改成 `group_concat(... order by installed_rank)`，并补了一条更直接的判据
> （新端点会不会回答）。

### 2026-09-13 网单门店可选（口径变更，V11）

用户口径：**网单顾客可以不选门店**，配送地址仍然必填。选门店从"必填"降为"选填"，
`orders.store_id` 相应放开为可空（迁移 **V11**）。

**8 个脚本全部重跑** —— 本轮改的是 `OrderController.createOrder` 与
`OrderAppService.createOrder`，**建单又是每个造数据的脚本都要走的门**，射程与上一轮相同：

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测（domain 49 + application 120 + interfaces 3） | **172** | ✅ 基线 171 + 新增 1（`OrderAppServiceTest` 的 `nullStoreAllowed`） |
| `verify-orders.sh` | **69** | ✅ 基线 65 + **J 段 4** |
| `verify-stores.sh` | 60 | ✅ |
| `verify-auth.sh` | 36 | ✅ |
| `verify-coupons.sh` | 32 | ✅ |
| `verify-price-write.sh` | 29 | ✅ |
| `verify-price.sh` | 26 | ✅ |
| `verify-pricing-authority.sh` | 24 | ✅ |
| `verify-race.sh` | 1 | ✅ 一 200 一 409，只推进一格 |

本轮改动：

- **V11 迁移把 `orders.store_id` 放开为可空**：列是 V1 建的 `NOT NULL`，"不选"就无处安放。
  门店不做数据隔离（§4.2：所有店长可管理所有订单），所以 `NULL` 不影响任何权限判断
- **`null` = "顾客没选"，不是"挑了个坏店"**——这两个含义必须在判断里分开写：

  ```java
  if (source == OrderSource.ONLINE && storeId != null
          && storeRepository.findOpenById(storeId).isEmpty())
  ```

  混在一起的话，不选门店会被报成「门店不存在或已停业」，一句话把顾客指向错误的方向
  （他会去换个门店重试，而正确做法是什么都不用改）
- **真正的门在 `OrderController`，不在 `OrderAppService`**：动手前我以为只有应用层拦，
  grep 之后才发现在 controller 里（`网单必须指定门店 storeId`）—— **两层都得开**。
  这个判断的顺序值得记：先 grep 出那条 400 的**原文**在哪，再决定动哪几个文件；
  按"应该在哪儿"去改，改完前端还是 400，而代码看着已经对了
- **前端三处**：建单页门店改「选填」+ `<option value="">不指定</option>`；
  两个展示位（员工列表、员工详情）加 `storeId ? '#' + storeId : '-'` 兜底 ——
  不兜底 Vue 会把 `null` 渲染成空串，那一格只剩一个孤零零的 `#`

J 段是**成对的四条**：J1（不传门店 → 200 且库里 `store_id` 是 `NULL`）+ J3（传 `storeId=999`
→ 400）必须同时存在——只留 J1 的话，**把校验整个删掉**也能过；只留 J3 的话，那个 400
可能来自"没传门店"而不是"店是坏的"。J4 是回归哨兵：这次动的正是 `store_id` 的校验，
最容易顺手带歪的就是门店单仍然必须落在店长自己那家店上的那条路径。

单测里那条新用例断言的是 **`verify(storeRepository, never()).findOpenById(any())`**，
比"没抛异常"强：它证明 `null` 是在判断的第一段就短路了，而不是查了一次库、恰好被某个
`Optional.empty()` 放行。在这个用例里两者**结果相同**，但一个是"不指定"，另一个是
"店不存在也放行"。

> 本节数字差点又是一次假绿，而且是在**新地方**：写改动说明时先把 `69` 和"八脚本全绿"
> 填进了设计文档 §7，**那时 J 段还没跑、后端也还没重启**。数字是算出来的，不是跑出来的。
> 已记为 **Bug 29**，并把"没跑出来之前写'尚未重跑'、不写预期值"立进了 §7。

### 2026-09-12 个人中心 + 建单的两处不可信输入

**7 个脚本全部重跑** —— 本轮改了 `Order.java` 与 `OrderAppService.java`，全在射程内：

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测（domain + application + interfaces） | 139 | ✅ 基线 131 + 新增 8 |
| `verify-stores.sh` | 60 | ✅ 含新增 C3/C3b/C4/C5/C5b（见下） |
| `verify-auth.sh` | 36 | ✅ |
| `verify-orders.sh` | 38 | ✅ |
| `verify-pricing-authority.sh` | 24 | ✅ |
| `verify-price.sh` | 26 | ✅ |
| `verify-price-write.sh` | 29 | ✅ |
| `verify-race.sh` | 1 | ✅ 一 200 一 409，只推进一格 |

本轮改动：

- **个人中心 `GET/PUT /api/customers/me`**（顾客自助）：姓名从"注册时填"改为"个人中心里补/改"，
  注册仍然只要手机号 + 密码。`rename`（无条件）与 `fillName`（只在没名字时生效）是**两个方法**，
  选错了是**静默失败**：回 200，但库里一个字没动（§6.4 有对照表，单测也钉着这一点）
- **姓名上限 20 个字**：`customers.name` 是 `VARCHAR(20)` 但从未校验 → 21 个字会一路走到
  INSERT/UPDATE 才被 MySQL 弹回来，用户看到 500 加一串英文。建档、补名字、个人中心改名
  三条写 name 的路径都补上了（用 `codePointCount` 数，emoji 算 1 个字）
- **门店单 `customerId` 回库确认**：设计文档里原先标着"**已知缺口（未修）**"。
  `orders.customer_id` 不是外键，不拦就能建出一张挂在**幽灵顾客**身上的单 ——
  它不报错，但从此所有"按顾客查订单"的地方都会莫名其妙地少一条
- **网单必须有配送地址**：域层规则（`Order.fillOrderInfo`），空白串也算没填；门店单不受影响
- **闸门措辞**：`/api/customers` 前缀下多了 `/me` 之后，"管理员不参与顾客**建档**"成了一句话错话，
  改成"管理员不参与顾客**相关操作**"。E2E 断言的正是这句话 —— **闸门的台词也是被测的**

新增的两组 E2E 用例都带**对照组**（`C4` 门店单不填地址 → 200、`C2` 真门店 → 200），
因为一个孤立的 400 证明不了是"这条规则"在拒绝它。

> 同轮修掉两个验收侧的 bug：**Bug 24**（编码，两个独立成因）与 **Bug 25**（脚本从未跑通 + 假绿数据）。
> 它们不是"顺手发现"的，是**逐个查库核对字节**查出来的。

### 2026-09-11 口径修订② + 步骤 1–4（状态机连号 1–7 / 管理员收权 / N2 分层）

提交 `e250d07`。**5 个脚本首次按新口径全部重跑**：

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测 domain | 26（OrderTest 16 + PricePolicyTest 10） | ✅ |
| 单测 application | 42（OrderAppServiceTest 28 + PriceAppServiceTest 14） | ✅ |
| `verify-orders.sh` | 38 | ✅ 含 A1/A1b 管理员双 403、D7 门店单 `5→7` 终态 |
| `verify-pricing-authority.sh` | 24 | ✅ 含新加 A2/A3：管理员改价 403 **且价目表未被改动** |
| `verify-price.sh` | 26 | ✅ 价目表读模型 + §4.5 样例 + 全表 `精洗 = 普洗 + 20` 不变量 |
| `verify-price-write.sh` | 29 | ✅ 精洗派生 / 拒绝手填 / 羽绒服逃生舱 / 数据还原 |
| `verify-race.sh` | 1 | ✅ 一 200 一 409，只推进一格（先修好了脚本，见 Bug 22） |

口径变化：

- 状态机**连号 1–7**，`7 已完成` 是唯一通用终态（门店单 `1→2→3→4→5→7`、网单 `1→2→3→4→6→7`）；
  **`6→7` 补上付清校验**（原先网单可从"派送中"直接完成而不付钱）；`finalPay` 收缩回 5/6
- **管理员不参与订单操作、也不能改价**：`/api/orders/**` 一律 403（含读接口）、`PUT /api/prices/**` 403。
  闸门在 `JwtInterceptor.checkRoleGate`，按 URL 前缀收口
- `interfaces` 不再引用 `domain`：`grep -rn "com.yunxi.domain" yunxi-interfaces/` 零命中
- V7 删 `sorting`/`shelving` 两表；V8 把历史 `status=8` 迁成 `7`（枚举 code 落库 = 数据契约）

> **流程教训**：本轮之前的验收清单只列了 2 个脚本（`verify-orders.sh` / `verify-pricing-authority.sh`），
> 另外 3 个（`verify-price.sh` / `verify-price-write.sh` / `verify-race.sh`）**长期没人跑**，
> 其中 `verify-race.sh` 已经烂了很久（Bug 22）。
> **从本轮起，`C:\tmp` 下全部 5 个脚本都进验收清单。**

### 2026-09-10 口径修订（所有店长管所有订单）

用户拍板：店长不限门店、分拣/上架不限门店、管理员订单权限 ≥ 店长（设计文档 §4.2 原有口径，§6.4 与代码同步过来）。验收：

| 功能 | 状态 |
|---|---|
| 单测 68 项（domain 26 + application 42） | ✅ |
| `verify-orders.sh` 38 项：A1 admin 查列表 → 200；A1b admin 建门店单 → 403；F1/F2 二店店长跨店查单/列表 → 200 可见 | ✅ |
| `verify-pricing-authority.sh` 22 项不回归 | ✅ |

> 同一次修订作废了下面"第 1 步"小节里的三条断言：二店店长查一店 → 403、二店店长列表 total=0、admin（无门店）→ 403。
>
> ⚠️ **2026-09-11 再次修订（本节 A1 与"管理员权限"部分已作废）**：管理员权限收回，
> **A1 现在应当是 `admin 查订单列表 → 403`**，"管理员订单权限 ≥ 店长"不再成立；
> `verify-pricing-authority.sh` 也从 22 项增到 24 项。本节其余内容（店长不限门店、F1/F2 跨店可见）**仍然有效**。

> ⚠️ **以下小节均为历史记录，状态码用的是当时的旧编号（1–8，门店单终点是 `8 已取件`）**。
> 当前口径以最上面的 2026-09-11 小节为准：**连号 1–7，唯一终态是 `7 已完成`**。
> 历史小节里的 `5→8`、`2→3→4→5→8` 一类写法不再对应现在的状态机，保留原样是为了不篡改当时的验收事实。


### 2026-09-10 订单模块第 1 步（S1.3~S1.5：校验/身份 + 并发保护 + 列表）

真机验收 37 项全通过（脚本 `C:\tmp\verify-orders.sh`），并发专项 1 项（`C:\tmp\verify-race.sh`）：

| 功能 | 状态 |
|---|---|
| 员工 token 建单/支付/推进/查列表 | ✅ |
| 顾客 token 调 next/pay/final-pay → 401 | ✅ |
| 顾客 A 查顾客 B 订单 → 403；二店店长查一店订单 → 403 | ✅ |
| 二店店长列表看不到一店数据（total=0） | ✅ |
| admin（无门店）→ 403 管理员账号不隶属门店，提示用店长账号 | ✅ |
| 无 token → 401 | ✅ |
| 列表分页 page/pageSize/total/totalPages 正确，明细不参与列表查询 | ✅ |
| 状态筛选 `?status=2` | ✅ |
| 空明细 / 数量 0 / 缺分类 / 门店单缺顾客 → 400（可读消息，不是 500） | ✅ |
| 非法 source=99 / status=99 → 400 | ✅ |
| 请求体 JSON 语法错 → 400 请求体格式不正确 | ✅ |
| 分页 page=abc → 400 参数格式不正确 | ✅ |
| 支付金额不足 / 为负 → 业务拦截（金额校验加固后） | ✅ |
| 不存在的订单 → 404 | ✅ |
| 全链路状态推进 1→2→3→4→5→8 + 终态不可再推进 | ✅ |
| 订单号格式 YX+18 位数字，操作人 staffId 落库 | ✅ |
| **并发 CAS**：行锁拉长窗口 → 一 200 一 409，只推进一格 | ✅ |
| 中文备注 UTF-8 落库（hex 校验，非控制台显示） | ✅ |

单测 41 个（domain 16 + application 25）全绿。
> 数字修正（2026-09-10 深夜）：此处一度被改成"42 个（domain 17）"，是把 `target/surefire-reports/`
> 里的 xml 逐个相加时，把类改名 `DeadlockDefect`→`DeadlockRegression` 之前遗留的陈旧报告
> 也算进去了。Surefire 不清理旧文件，那个 xml 是幽灵。以控制台汇总行与源码 `@Test` 数为准：16 + 25 = 41。

### 早期手工验收（订单基础链路，2026-09 初）

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

### 2026-09-09 券收尾 + 顾客 JWT

| 功能 | 状态 |
|---|---|
| 券状态定时推进（1→2→3，次分钟幂等 0+0） | ✅ |
| Redis 库存键丢失懒加载兜底（删键后首抢自动重建，Redis 与 DB 行数一致） | ✅ |
| 顾客注册（注册即登录，uk_phone 兜底并发重复注册） | ✅ |
| 顾客登录（BCrypt 校验；password=NULL 的门店单顾客显式拦截） | ✅ |
| 顾客 token 抢券（真实顾客落库，customerId 从 token 取） | ✅ |
| 员工 token 抢券被拒（401 身份隔离，防横向越权） | ✅ |
| 无 token 请求被拦截（401） | ✅ |
| 重复注册同手机号被拒（400） | ✅ |
| 中文数据 UTF-8 存储验证（HEX E5BCA0E4B889 = 张三） | ✅ |
