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

顺着"还有谁在写中文进库"往下查，**第三处更严重**：

```
staff id=98  username=mgr_disabled  name=åœç”¨åº—é•¿   ← 正确值是「停用店长」
```

`verify-auth.sh` 造这个停用账号时用的是 `mysql -e "…'停用店长'…"` —— **中文放在命令行参数里**（Bug 24 那个形状），而且**连 `--default-character-set=utf8mb4` 都漏了**，两个编码坑一起踩。它连自愈语句都没有，一个 `insert ignore` 之后就没人管了：这个名字从建出来那天就是乱的。

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
2. **整行断言**：准备守卫检查这一行**所有**非 ASCII 列的字节

三处分别落地：

| 脚本 | 数据 | 原先的问题 | 补的守卫 |
|---|---|---|---|
| `verify-orders.sh` | 门店 2（name+address）、`mgr2`（name） | 自愈只写 name；`mgr2` 的名字有自愈但**没断言** | name、address、`mgr2` 的 name 共 3 条 |
| `verify-stores.sh` | 门店 99（name+address） | 自愈只写 name | 补 address（name 那条原本就有） |
| `verify-auth.sh` | `mgr_disabled`（name） | **整条写路径都是坏的**：`-e` 带中文 + 漏 charset，且无自愈 | name 1 条，写路径整体改成 stdin + charset |

第三条的修法和前两条不同：它的问题不在"自愈漏了一列"，而在**写进去的那条路本身会转码**。所以先把 `-e "…中文…"` 改成 `printf | docker exec -i … --default-character-set=utf8mb4`（和 `verify-stores.sh` 的 `dbFile` 同一个手法），再加自愈和守卫 —— 否则守卫会一直正确地失败。

### 验证

不跑整个脚本（会把刚清干净的 110 张订单再造回来），而是**从真脚本里 sed 抽出那几行**执行 —— 抽出来的文本和真脚本逐字一致，所以验的是真代码。三处每一处都做了**正反两次**：

| 抽出跑的段落 | 正跑（自愈+守卫） | 反证（先弄成乱码，只跑守卫） |
|---|---|---|
| `verify-orders.sh` F 段 | 退出码 0，store 2 的 address → `E9AA8CE694B6E794A8` | `[准备失败] mgr2 (staff id=99) 的 name 字节不对`，退出码 1 |
| `verify-stores.sh` 准备段 | 退出码 0，store 99 的 address 与 `printf '%s' … \| od` 逐字节相同 | （name 那条守卫原先就有，未重复反证） |
| `verify-auth.sh` 脚手架 | 退出码 0，staff 98 的 name → `E5819CE794A8E5BA97E995BF` | `[准备失败] mgr_disabled 的名字字节不对`，退出码 1 |

反证这一步不能省：不跑它的话，"守卫通过"可能只是因为守卫写错了、永远为真（Bug 27 就是这么一条）。最后全库扫一遍双重编码特征（`hex(col) like '%C3A%' or like '%E280%'`）—— 零命中。

### 教训

**一条自愈语句的正确性标准，不是"它写了点什么"，而是"它覆盖了这行里所有会被这条写路径弄坏的东西"。** 只修一部分，剩下的那部分就是静默腐烂 —— 它不会以断言失败的形式暴露，只会以"某天有人肉眼看见"的形式暴露。

判断方法很机械，不需要判断力：**把 insert 里所有的非 ASCII 列列出来，逐个问"谁在盯着它"**。答不出来的那些，就是正在烂或者即将烂的那些。

> 同轮还踩到一个**信号读错**：验证时写的是 `bash probe.sh | grep -v Warning; echo $?`，回显 `1` 看着像脚本失败 —— 实际 `$?` 是 `grep` 的（没有匹配行时 grep 退 1），脚本本身是 0。危险的**不是**这个方向，是反过来的那种：`脚本 | grep 某个必然出现的串` 永远回 0，**脚本自己失败多少次都被盖掉**。这个形状和第 26/27 条是同一类，只是这次它长在一个 `;` 后面而不是断言里。

---

## Bug 31：压测器把自己算进了被测对象 —— 一组"稳定"的 TPS 量的是 JVM 冷启动

### 现象

`bench-coupon.sh` 第一版跑通，10 项断言全绿，输出：

| 轮次 | TPS |
|---|---|
| 预热 | 279.27 |
| 测量 | 305.42 |
| 对照 | 303.03 |

三轮几乎一模一样。**当时把它读成了"服务端吞吐很稳定"，差一步就写进文档。** 唯一不对劲的是延迟：`p50=1409611.6ms` 而整轮 `elapsed=1637.1ms` —— 单条请求比整轮还长 26 分钟，自相矛盾到一眼可见。

（那个数本身是第二个 bug：`System.nanoTime()` 返回纳秒，我当微秒打了出来。中间量带着单位到处走、换算点却有两个 —— 人读的那行和机器读的那行 —— 改了一个漏了另一个。）

### 根因

修完单位，延迟变成 p50 1.41s / elapsed 1.64s。分布形状是**所有请求都在整个窗口里飞**，所以并发是真的。但 500 并发下 p50 1.41 秒，比 curl 量的同一个请求（11ms）慢 128 倍。

于是造了个**瞬时响应的对照服务端**（`com.sun.net.httpserver`，20 行，不碰库不碰 Redis，纯虚拟线程 executor），拿 `LoadGen` 打它：

| | N=1 | N=5 | N=10 | N=25 | N=50 |
|---|---|---|---|---|---|
| 打对照服务端（服务端耗时≈0） | 116ms | 161ms | 214ms | 270ms | 500ms |
| 打真实应用 | 103ms | — | — | — | 428ms |

**打一个什么都不做的服务端，比打真实应用还慢。** 也就是说那组 TPS 量的是 `LoadGen` 自己。

真因在参数设计上：第一版用法是 `java LoadGen.java <url> <tokens> [n] [label]`，`bench-coupon.sh` **每轮调一次、开一个新 JVM**。那个"预热轮"只暖到了服务端（同一个 Spring 进程），**客户端三轮全是冷的** —— JIT 没编译、类没加载、连接池是空的。

同一个 JVM 里连跑四轮 N=50，p50 是：

```
344ms → 32ms → 23ms → 58ms
```

**第一轮比第三轮慢 15 倍。** 三轮 TPS 一样，不是因为稳定，是因为**一样冷**。

### 怎么发现的

不是靠更仔细地读输出，是靠**换一个"服务端耗时为 0"的参照物**。

顺序是：先撞见延迟自相矛盾（p50 > elapsed）→ 修单位 → 发现延迟依然不合理 → 换参照物量客户端 → 定位到 JVM 冷启动。

### 修法

`LoadGen` 的参数改成 `<并发数> <标签> <url> <token文件> ...`，**所有轮次在同一个 JVM 里顺序跑完**，共享一个 `HttpClient`（连接池跨轮续上）。`bench-coupon.sh` 相应发 **4 张券**：预热1 / 预热2 / 测量 / 对照。

预热两轮而不是一轮，是因为实测第二轮还没完全收敛。**收敛与否从输出里直接看得到**：

```
预热1 295.71 TPS / p50 1467ms
预热2 845.81 TPS / p50  425ms
测量  872.42 TPS / p50  413ms     ← 与预热2 只差 3%
```

同一套脚本，修完量出来是 872 TPS —— **是修复前的 2.9 倍**。差的这 2.9 倍全是客户端。

### 教训

**"这个数字是从哪台机器、哪个进程、第几轮出来的"，和这个数字本身一样重要。**

它和 Bug 29（往文档里填没跑出来的数字）是同一个病：**"好看"和"真的验过"这两个信号脱钩了**。29 是没跑就填，31 是跑了但量错了对象 —— 共同点是**没有任何环节会去追问"这个数是怎么来的"**。脚本里的假绿下次跑一遍就露馅；数字一旦进了文档，就没有任何东西会去重跑它（这正是 29 那条的原话）。

由此落出两条：

1. **压测的输出必须自带收敛证据**（预热1 / 预热2 / 测量并排打出来）。"我预热了"是声明，"预热2 和测量只差 3%"是证据 —— 后者才是能被 review 的东西。
2. **报多次的中位数，不报最好的一次。** 修好之后跑并发曲线，同一个 N 下 TPS 抖动仍有 ±16%（N=300 三次是 568 / 595 / 234），24 次运行里有 1 次离群到 p50 1171ms。**单次采样足以得出完全错误的结论，而且它不会报错。**
3. **（2026-09-18 补）±16% 这个数本身还是偏乐观了。** 同配置 N=500 跑 6 遍落在 851–1058，**极差 22.3%**。更要命的是这个抖动**比要量的效应还大** —— 见 Bug 32。

---

## Bug 32：另一个方向的冷启动 —— 量具热了，被测对象还是冷的

### 现象

同一天做"虚拟线程 vs 平台线程"的对照。第一次测虚拟线程得到 **443.88 TPS**（N=200），而文档里平台线程在 N=200 的记录是 **649 TPS**。低 32%，看起来是个干净的结论：**虚拟线程更慢**。

**差一步就写进报告了。** 拦下它的不是这个数本身，是它旁边的收敛形状 —— 一组停了，这一组还在爬：

```
基线  （Bug 31 记录的 N=500）：预热1 295.71 → 预热2 845.81 → 测量 872.42   预热2 与测量差 3%
探头  （重启后第一个 N=200） ：预热1 188.60 → 预热2 337.25 → 测量 443.88   预热2 与测量差 32%，还在涨
```

（两行不是同一个 N —— **这里比的不是数值，是形状**：一组收敛了，另一组没有。）

`188 → 337 → 444` 是一条还在往上走的线。**一个还没停下来的数列，不能拿去跟一个停下来的数列比。**

### 根因

`649` 是并发曲线里的 N=200 点（8 个点升序、每点 3 遍，所以是 24 次里的**第 16–18 次**）—— 跑到它的时候，服务端已经被前面 15 轮垫热了。`443.88` 是**后端重启后的第一个 N=200**。

Bug 31 修的是**压测器的 JVM**冷启动 —— 把四轮塞进同一个 JVM。但**服务端自己也在冷启动**，而它的预热逻辑恰好被"同一个 JVM"这个修法给遮住了：`bench-coupon.sh` 的预热轮确实在跑，只是那两轮里**服务端也在同步变热**，而当时我把预热理解成"暖客户端"。

同一个配置接着跑四遍：**775.06、821.61、842.68、846.88**，中位 **832**。所以 443.88 不是"虚拟线程慢"，是**最先跑的那一趟总是最慢的**。冷热之比 **1.87 倍**，比要测的那个效应大一个量级。

### 怎么发现的

判据不是数值大小，是**收敛**：两轮预热之后的那个数，和测量轮还差多少。差 3% 可以采信，差 32% 不行。

这一条和 Bug 31 的教训是同一条，只是换了一层楼：**31 是量具在冷启动，32 是被测对象在冷启动。修完 31 之后，32 这种错误反而更容易犯** —— 因为"我已经修过冷启动了"这句话本身就是个陷阱。

### 修法

1. **协议里加一个显式的丢弃轮**：重启后的第一次跑，跑完就作废，它唯一的作用是把服务端跑热。
2. **基线必须当天重测，不能跟历史数字比。**

第 2 条不是讲究，是必须的。重测基线（N=200）得到 **740 / 750 / 755 / 776，中位 752**，再比虚拟线程的 **832** 是 **+10.6%**。**不重测基线的话，我会把 +10.6% 报成 +28%（拿 832 比历史那个 649）。**

### 教训

1. **"预热"必须说清楚预热的是谁。** 这条链上有三个东西会冷启动：压测器 JVM、被测服务、数据库和连接池。Bug 31 只锁住了第一个，而那句话听起来像三个都锁住了。
2. **跨会话的数字不能当基线。** 基线不是"上次量过的那个数"，是"同一场实验里、同一台机器上、同样热度的那个数"。所以对照实验的真实成本是 **组数 × 2**，每组都得配一次当天重测的基线 —— 算成本的时候容易漏掉这一半。
3. **抖动比效应大时，结论只能是"测不出来"。** 修完 32 再回头看那组对照：三个配置的范围**互相重叠**（基线 764–858、虚拟线程 762–915、虚拟线程+Druid50 851–1058）。所以 Druid 20→50 的 +5% 既不能确认也不能排除。**"测不出来"和"没有差别"是两句话，报告里不能混。**

---

## Bug 33：三个单测 ERROR —— 断言错了机制，不是代码错了

### 现象

管理员接口的新代码写完，第一次跑 `./mvnw -o test`，三个新测试**报 ERROR 而不是 FAIL**：

```
StaffAdminAppServiceTest$Create .lengthLimits   BusinessException: 姓名不能超过 20 个字
StaffAdminAppServiceTest$Create .roleValidation BusinessException: 没有这个角色: 5
StoreAdminAppServiceTest$Create .lengthLimits   BusinessException: 门店名称不能超过 50 个字
```

异常是从测试方法里**逃出来**的 —— 也就是说，那条校验根本没有把结果交回来，它是**抛**的。

### 根因

同一个 HTTP 400，在这个项目里有**两条完全不同的返回路径**：

| 规则 | 在哪 | 怎么返回 |
|---|---|---|
| 必填、跨聚合（"角色不能为空"、"门店不存在"） | 应用服务 | `return Result.fail(400, "...")` |
| 长度上限（`requireValidXxx` 一类） | **domain 的静态方法** | **抛 `BusinessException`** |

抛出去的那条由 `GlobalExceptionHandler` 接住转成 400 —— **对 HTTP 来说两条路径完全一样**，所以写测试时很容易以为它们也是一回事。

我照着应用服务里"必填校验"的写法去断长度校验（`assertThat(service.createStaff(...).message())`）—— 而长度校验压根不经过 `Result`。**被测的是我以为的机制，不是真的机制。**

**这不是生产代码的 bug。** 两条路径各自都是对的，分工也和 `CustomerAppService` 一致（长度上限只有 domain 一个家）。错的是测试。

### 修法

三处改成 `assertThatThrownBy(...).hasMessage(...)`，并把 `roleValidation` **拆成两条** —— 因为它其实横跨两条路径：

- `nullRole` —— 角色为空是**必填**规则，走 `Result.fail(400, "角色不能为空")`，用原来的写法断
- `illegalRole` —— 角色值非法（`5`）是 `StaffRole.fromCode` **抛**的，用 `assertThatThrownBy`

拆开之后两条测试各钉一条路径；合在一起写的话，无论怎么写都只有一半是对的。

### 教训

1. **"这个 400 是哪条路径发出来的"必须逐个确认。** 端点和状态码相同不代表机制相同 —— **只有消息原文能区分它们**。这也是既有 E2E 一律连 `message` 一起断的原因（"闸门的台词也是被测的"）。
2. **ERROR 比 FAIL 值钱。** FAIL 说明断言看见了不相符的值；ERROR 说明**契约没对上**（这里：该返回的东西抛出来了）。看到 ERROR 要先去问机制，而不是先去改断言的值。
3. **这轮是少见的"红得准"**：三条 ERROR 的消息原文（`姓名不能超过 20 个字`）**恰好就是我要验的那句话本身** —— 说明校验在跑，只是我的断言接错了地方。如果红出来的是一句无关的话，那才是真问题。对照 Bug 21/22：那两次也是红的，但红的是别的原因。

---

## Bug 34：「同秒内重新登录会被判成旧票」—— 计划里把方向写反了

### 现象

计划 §三 把 `iat` 的秒精度写成了"**偏放行**"：停用与签发落在同一秒时，那张票会被**放行**，窗口 < 1 秒。

写 `verify-admin.sh` 的 B 段（停用 → 立刻启用 → 重新登录）时要按秒推演一遍，推完发现方向是**反的**：同一秒内签发的票会被**拒绝**。

### 根因

两个时间戳的精度不一样，而且取整方向不利：

- JWT 的 `iat` 是 NumericDate，**秒**；jjwt 从 `Date` 转过去是**向下取整**（`getTime()/1000*1000`）。
- 水位线 `auth:staff:invalidAfter:<id>` 存的是**毫秒**当前时间（`System.currentTimeMillis()`）。

判据是**严格小于**：`iat < 水位线 → 401`。

于是同一自然秒里：停用在 `…500ms` 写下水位线；用户在 `…900ms` 重新登录拿到新票，它的 `iat` 被截断成 `…000` —— `…000 < …500`，**判为失效**。

用户看到的是「**账号已被停用或权限已变更，请重新登录**」，而这时账号**是启用状态**。**报错指向了一个不存在的原因** —— 和 Bug 20 同一个形状。

窗口 ≤1 秒、自愈（过了这一秒再登就正常），但**真实的触发路径很短**：管理员停错了人、马上改回来，那个人在同一秒里登录。

### 修法

**判断逻辑本轮不动。** 要"修"只能把水位线也降到秒级 —— 那是把 1 秒的误拒换成 1 秒的误放（把该拦的票放进来），**换一个方向而已，不是改善**。

- `verify-admin.sh` 的 B 段写成**显式的 `sleep 1`**，注释标明这是**已知边界**，不是"等 Redis 落盘"；
- 写进文档当已知边界。

### 教训

**"方向偏哪边"这种话，说出来之前必须按最小单位推演一次。**

我在计划里写"偏放行"是想当然的：`iat` 和当前时间"差不多"听起来像更宽松。实际是两个取整叠在一起 —— **`iat` 向下取整、水位线取当前时刻，两个方向合起来是偏拒绝**。

这和 Bug 29/31/32 是同一族：**写在文档里的断言，没有任何东西会去重跑它**。这次的拦截者是 `verify-admin.sh` 里那句 `sleep 1` —— **因为写脚本必须比写计划更较真**：计划里错一句方向只是错一句话；脚本里错一句方向，会让断言在错误的时刻失败，而跑的人只会以为代码坏了。

---

## Bug 35：`"$(cmd "/path "$VAR")"` —— bash 报的错离现场 200 行

### 现象

```
$ bash -n scripts/verify-admin.sh
scripts/verify-admin.sh: line 638: unexpected EOF while looking for matching `)'
```

638 是文件的**最后一行**。真正的错在 **B6b** —— 差着两百多行，中间每一行都是无辜的。

### 根因

写成了：

```bash
check "B6b ..." "$(getJson "/api/staff "$T_A")"
```

路径两侧那对引号**看起来是配平的**（一共四个 `"`，正好两对），但在 bash 的解析里不是：`$T_A` 前面那个 `"` 打开的不是"路径的结束引号"，它把**后面的** `"` 当成了自己的闭合，闭合关系整体错开一位。结果那个 `)` 落在了一个没闭合的字符串里 —— 而 **`$()` 里引号中的 `)` 不结束命令替换**。

最小复现（三个都在 Git Bash 里真跑了）：

```bash
T=x
echo "$(echo "/api/staff "$T")"     # 报 EOF —— 这就是坏的那个
echo "$(echo /api/staff "$T")"      # 正常
echo "$(echo "x)y" )"               # 正常：引号里的 ) 不终止 $()
```

### 怎么发现的

值得记的是**三种手法只有第三种管用**：

1. 先写了个 Python 的引号扫描器 → **误判**。它按"嵌套引号会闭合"来数，和 bash 不是同一套规则 —— **用一个更简单的模型去模拟解析器，得到的是模型的错，不是文件的错。**
2. 拿 `bash -n` 对文件**前缀**做二分 → 在第一个多行函数定义处就误报（前缀会把函数体截断，截出假的"不配平"）。**二分要求"截断之后仍然合法"，这个文件不满足。**
3. 换成**引号栈**扫描器：遇 `$(` 压栈（记下**行号**），遇 `)` 弹栈，报栈里剩下什么：

```
line 329: ) sits inside an open quote at $( depth 1
UNBALANCED at EOF: state=N stack=[('D', 329)]
```

一次定位。**它并没有比前两个更聪明，它只是把 bash 真正在维护的那个状态原样抄了一遍。**

### 修法

去掉路径两侧的引号（`$T_A` 自己带着引号，够了）：

```bash
check "B6b ..." "$(getJson /api/staff "$T_A")"
```

### 教训

1. **多余的引号比缺的引号更坏。** 缺的当场报错、报在那一行；多的只在特定的嵌套形状下错，而且报在别处。
2. **解析器的报错位置不是错误位置。** `EOF` / `超时` / `栈溢出` 这类判据全都只告诉你"它在哪里放弃"，不告诉你"你哪里写错了"。定位靠**缩小范围**（要满足前提）或**把解析器的状态补出来**（引号栈），不靠读报错行。
3. 这和 Bug 32 是同一条：**报出来的那个数/那个位置不携带定位信息** —— 32 是"这个 TPS 不告诉你是谁冷的"，35 是"这个行号不告诉你是谁没配上"。

---

## Bug 36：`store_id=NULL` 的店长建不了一单，报的还是句假话（**本轮发现，未修**）

### 现象

这一轮允许建出"**没有门店的店长**"（`role=1` + `storeId=NULL`）。库里允许（列可空）、门店又不做数据隔离（2026-09-10 口径），所以这是个**合法状态**。

这样的账号能登录、能读价目表、能读门店列表 —— 但**他建门店单时会收到 401「登录信息已升级，请重新登录」**。

**重登一万次也没用**：他的 token 本来就不带门店，重新登也不会带上。

### 根因

`OrderController.requireStaffStore`（`OrderController.java:217-223`）在 staff token 的 `storeId` 为 null 时抛那个 401。而它上面那段注释（**209 行起**）自己写着一个全称断言：

> 谁还可能走到"拿不到 storeId"这一支：**只剩旧 token**（签发时没有 storeId claim）。

**这句话在我这轮之后就是错的了。** 那个分支现在多了一种落点 —— 而且不是"旧"的，是**刚签发的、以后也永远没有门店的**票。401 的文案（"登录信息已升级，请重新登录"）是为"旧 token"写的，套在无店店长身上就是**把"这个身份按设计就没有门店"说成了"你的票过期了"** —— Bug 20 的现场。

### 为什么没修

修法取决于口径，而口径是用户的。三个方向都说得通：

1. 建号时对 `role=1` 强制 `storeId` 非空 —— 那就没有"无店店长"这种身份了；
2. 让那句话**分身份说**：旧 token → 保持原话；无店店长 → "你还未归属门店，请让管理员先指派"；
3. 允许无店店长做订单之外的读写（即现状），只把话说对。

**本轮按已批准的计划实现（不拦），把这个发现摆出来。** 它连带要求把那段注释的第 209 行一起改 —— 那句"只剩旧 token"现在是假的。

### 教训

**两个各自都对的判断，组合起来会生成一个假成因。**

- `createStaff` 放开 `storeId`，是按"门店不做数据隔离"这条口径 —— 对；
- `requireStaffStore` 报 401，是按"只有旧 token 会落到这里"这条不变量 —— 当时也对。

单看谁都没错，撞在一起就变成"系统告诉这个人他的票据过期了，而票是刚发的"。**放开门槛的时候，要把"开了门之后他会走到哪个分支"走一遍。**

可操作的形状：**代码注释里的"只剩…"、"一定是…"是一种全称断言** —— 加功能时要把它当不变量拿去验。这次是读注释读出来的，不是测出来的，因为**没有任何断言会去看注释**。

这和 §4.2 那句"他被授权做的事，恰好是没实现的那部分"是同一个盲区：**权限是按端点分头设计的，没有人在端点之间走过一趟。**

---

## Bug 37：五条假红 —— 探针选在了"被测对象本来就进不去的门"上

### 现象

`verify-admin.sh` 第一次真机运行：**119 通过 / 5 失败**。5 条红的原文一模一样：

```
[FAIL] A2b 用它查自己的详情 → 200
       期望含: "code":200
       实际: {"code":403,"message":"员工与门店管理只对管理员开放，请使用管理员账号"}

[FAIL] A2c 详情里用户名对得上
[FAIL] A4e 改姓名**不**踢人：A2 那张票现在还能用
[FAIL] B1 停用**之前**，这张票是在用的（对照组）
[FAIL] B6c 启用后重新登录拿到的新票能用
```

形状像"新接口大面积坏了" —— 连"建号之后能查到自己的详情"这种最基本的事都过不去。

### 根因

**5 条红全是脚本自己的错。** 它们拿的是**店长的票**（`$T_A`），打的是 `/api/staff/...` —— 而那个前缀**整个归管理员**（含读接口），这是设计里写明的（E1 断的就是"店长 `GET /api/staff` → 403"）。

**脚本自己跟自己打架**：E 段说"这个前缀店长必须 403"，A/B 段却拿店长的票指望 200。

更要命的是这 5 条**各自的意图都是对的**，错的只是探针：

| 断言 | 想证的事 | 探针错在哪 |
|---|---|---|
| A2b/A2c | 建出来的号**读得回来** | 该用**有权的**那张票（管理员） |
| A4e | 改姓名不踢人 ⇒ **这张票还活着** | 该选一个**店长进得去**的接口 |
| B1 | 停用之前票是在用的（对照组） | 同上 |
| B6c | 启用后重新登录的新票能用 | 同上 |

"这张票还活着"这件事，在 `/api/staff` 上**永远证不出来**：那张票**好的时候**，这个前缀回的是 403（闸门挡的），跟"票废了"的 401 刺眼程度一样。**探针选在了"不管被测对象是好是坏、结果都不能用"的地方。**

### 怎么发现的

不是靠读脚本，是靠**三条互相咬合的证据**：

1. 5 条的失败原文**一字不差都是闸门那句话** —— 不是 500、不是空响应、不是"员工不存在"。一句有信息量的错误代码，一次性把范围缩到了一个分支上。
2. 5 条用的是**同一个探针**，而且是"**5 条一起红、另外 119 条全绿**"。
3. 回头 grep 店长票的**全部**调用点，看到 E1 断言同一 URL 必须 403 —— **自相矛盾当场成立**。

对照：**同一件事在 D 段写对了。** D5/D6c/D6d 也要证"这张票还活着"，探针用的是 `/api/stores`（任何有效 token 都能读）→ 那三条一直绿。**同一件事一段写对、一段写错**，所以这 5 条红不像笔误，像"功能坏了"。

### 修法

只改探针，**生产代码一行没动**：

- A2b/A2c → 换**管理员**的票（要证的是"详情读得回来"，那就得用有权的票）
- A4e / B1 / B6c → 换到 **`/api/orders`**（店长进得去）。顺带让 **B1↔B4 变成同一条 URL 的前后对照**：停用前 200、停用后 401，同一个请求里只有"票"这一个变量
- D2/D8 → 标签写的是"**顾客**的选店列表"，探针却用店长票 → 换成顾客票。这两条**一直是绿的**，所以没被红暴露出来 —— **同一个毛病待在绿的那一侧**（原来验的是店长的读权限，不是顾客的下单路径）
- B3/B3b **故意不动**：留在 `/api/staff` 上是对的 —— 店长的票在那条 URL 上"票好=403、票废=401"，所以拿到 401 只可能来自作废检查，顺手钉住了 `JwtInterceptor:74` 声称的**作废检查排在角色闸门之前**

复跑：**124 / 124**。断言总数与分组一个字没变（A36/B13/C9/D32/E20/F8/G6）。

### 教训

1. **假红比假绿更阴。** 假绿让你放过一个 bug；**假红会让你去改一段本来是对的代码，把红"修"掉。** 这次要是没回头看 E1，最顺手的"修法"就是放开 `/api/staff` 的读权限 —— 一刀删掉 E1，5 条红立刻变绿，功能上开了个口子还全程有"测试通过"背书。
2. **探针必须选在"被测的那个变量是唯一变量"的地方。** "证明这张票是好的"要求探针走一条**任何好票都能通过**的路；拿一条"好票也过不去"的路当探针，测出来的永远是探针自己的毛病。这和 Bug 27 是同一个形状 —— 那次失败原因被换成了**状态检查**，这次被换成了**闸门**，**都是"红在了别的原因上"**。
3. **一条断言的"意图"和它的"探针"是两件事，要分开审。** 这 5 条的意图全对、标签全对，只有 URL 是错的。机械做法：把每条"证明票/权限是好的"断言拎出来，问一句 **"这个身份在这条 URL 上本来能通过吗"**。
4. 连带一条：**同一件事在同一个脚本里出现两种写法时，其中一种大概率是错的。** D 段对、A/B 段错不是巧合，是写的时候没回头对口径。

---

## Bug 38：一句无条件的"就绪" —— 空票把"脚手架没拿到顾客"报成了"鉴权坏了"

### 现象

2026-09-18 全量重跑（九个脚本，为验证 `JwtInterceptor` 那两处新逻辑），`verify-price-write.sh` 拿到 **28 通过 / 1 失败**，唯一那条红：

```
[FAIL] A1 顾客 token 改价 → 401 请使用员工账号
       期望含: 请使用员工账号操作
       实际: {"code":401,"message":"未登录","data":null}
```

「未登录」是**拦截器**的台词（`JwtInterceptor:35`），A1 期望的是**控制器**的台词（`PriceController.requireStaff`）。**请求根本没走到控制器。**

这次重跑的背景让这条红格外刺眼：那天刚给拦截器加了**两条新逻辑**（失效水位线、反方向闸门），**它本来就在射程内**。最自然的读法是"改坏了"。

### 根因

**票是空的。** 准备段只 login、不建档：

```bash
CUST_T=$(jqf "$(curl -s -X POST .../api/auth/customer/login \
  -d '{"phone":"13900000091","password":"123456"}')" token)
echo "  manager/customer token 就绪"        # ← 无条件印的，票是空的也印
```

`13900000091` **不是本脚本建的**，是 `verify-price.sh` 建的。而通配符顺序里 `verify-price-write` 排在 `verify-price` **前面**（`'-'`(0x2D) < `'.'`(0x2E)）—— **建号的还没跑，用号的先跑了**。

号不在库里时，链条是这样断的：

1. login → `{"code":401,"message":"手机号或密码错误"}`，**响应里没有 `token` 字段** → `jqf` 取到空串
2. `-H "Authorization: Bearer "`（空值）→ 服务端看到的是"**没有 Authorization 头**"
3. `JwtInterceptor:35` 抛 401「未登录」
4. A1 要的那句话永远等不到 —— 而它**是能等到的**（见下）

### 怎么发现的

**先证对照再断失败**，四条证据：

1. 全项目只有一处抛「未登录」：`grep -rn '未登录' --include=*.java` → `JwtInterceptor:35`，一条。
2. 拿**真的**顾客票打同一条 URL → `{"code":401,"message":"请使用员工账号操作"}` —— **A1 的期望够得着，控制器没坏、拦截器也没坏**。
3. 把变量置空复现 `-H "Authorization: Bearer $EMPTY"` → `{"code":401,"message":"未登录"}` —— **与失败原文一字不差**。
4. 库里那行的 `create_time` = **21:32:57**，而本脚本那轮在 **21:32:56** 就写完了日志 —— **号是"用完之后"才被建出来的**（被排在后面的 `verify-price.sh` 建的）。

> 那个号**为什么**当时不在库里，**我没查出来**（脚本里没有任何一处删 `13900000091`；压测清理删的是 16 位号，它是 11 位）。但这不影响修法：**脚手架不该依赖另一个脚本留下的历史残留**，哪怕那份残留平时一直都在。

### 修法

两处，**断言一条没动**：

1. **换用兄弟脚本早就在用的 `login_or_register`**（`verify-orders.sh:54` / `verify-coupons.sh:77` 各有一份**逐字相同**的实现）。它先 register（新号直接给 token、"有号没密码"的老号就地激活），拿不到再 login（已存在且有密码的号走这条）。三份复制用 `md5sum` 对过，一致。
2. **准备段加自检**：两张票只要有一张是空的就 `exit 1`，并印**长度**而不是票面值（空/非空一眼可判，也不把票写进 CI 的日志产物）。

**修完当场复跑：29 / 29 全绿。** 这一跑走的是"register 回 400 该手机号已注册 → 转 login"那条支路（脚手架号还在）；"号不存在 → register 200 直接拿票"那条支路在修的时候单独打过 —— 用一个一次性号码把四个分支全验了一遍，验完即删。**两条支路都有实测**，不是"另一条看着没问题"。

### 教训

1. **"就绪"必须是断言，不能是台词。** 原句 `echo "  manager/customer token 就绪"` 是**无条件**印的：票在手里、票是空串、票是 `undefined`，它都印同一句。这是 Bug 29 那个形状又换了个地方 —— **验证动作确实发生过（login 真调了），但它没验它声称的那件事（票到底拿到没有）**。修法是加**判据**，不是加日志。
2. **脚手架由别的脚本创建 = 依赖"跑的顺序"，而那个顺序是通配符定的、没人会去读。** 更坏的是这个依赖平时**看不见**：库里那个号是历史残留时，脚本一直是绿的（所以它绿了这么多轮）。**"一直是绿的"不等于"自己站得住"** —— 换台干净机器、或者别人清过一次库，它立刻塌。
3. **报错指错方向的代价，在这一轮特别大**：`{"code":401,"message":"未登录"}` 会把人引向"JWT 拦截器坏了"，而那天**刚改过拦截器** —— 顺手的结论就是"我改坏了"。真正救场的只有一条：**拿一张真票打同一条 URL**。同一天这个形状踩了两次（本 bug + 401 当路由探针那条证伪），都是"**先拿对照组，再下结论**"。
4. 与 **Bug 37** 是一对：37 是"探针选在了被测对象进不去的门上"（假红），38 是"**准备段的前提根本没成立**"（也是假红，只是红在了更前面一层）。**两次都不是业务代码坏了，而两次的第一反应都指向业务代码。**

### 同一形状的第二处（同日发现，**未爆**，一并修了）

`verify-pricing-authority.sh` 的准备段有**一模一样的三个缺陷**：顾客票只 `login` 没有 register 兜底、`CUST_ID` 直接查库不校验、末尾一句无条件的 `echo "manager token 就绪…"`。

**它为什么一直没爆**：通配符顺序里 `verify-price.sh` 排在它**前面**（`"price." < "pricing"`：`e`(0x65) < `i`(0x69)），那个号**连跑时总在**。它绿是因为**别人留下的残留总在**，不是因为自己站得住 —— 和上面那条同一个病。

**如果爆了会是什么样**（这次没等它爆，照着代码推的）：空票 → A1 收到「未登录」；`CUST_ID` 空 → 请求体退化成 `{"customerId":,...}` 畸形 JSON → B/C/D/E 全红，报的全是"算价坏了""事务坏了"。最阴的是 **F 段会假绿**：F1 断言"不是 200"、F2/F3 断言"前后计数没变" —— 建单压根没发出去时，这三条**无条件成立**。

**修法**与上面同（换 `login_or_register`，四份拷贝 `md5sum` 一致；准备段加自检），**多一条**：`CUST_ID` 判的是**形状不是有无** ——

```bash
case "$CUST_ID" in ''|*[!0-9]*) BAD="$BAD customerId($CUST_ID)不是数字";; esac
```

因为本脚本的 `db()` **故意不吞 stderr**（它要让人看得见 SQL 报错），MySQL 一挂它返回的就是 docker 的报错文本 —— **非空，但根本不是 id**，只判非空照样放进畸形 JSON。**"非空"不等于"可用"**，这是上一节那条教训的下一格。

**怎么验的**：把守卫从真文件里 `sed` 抠出来（防止手抄走样）跑八种输入 —— 全齐 / 三张票各空一次 / id 空 / id 是 docker 报错 / id 两行 / id 带尾随空格 —— **只有"全齐"放行，其余七种全部 `exit 1`**；再取真文件的前 **87 行**端到端跑一遍：三张票 **197 / 179 / 188** 字符、`id = 15229`。**A–H 的断言一条没动，条数仍是 24。** 改完复跑整个脚本：**24 / 24 全绿**。

> 顺带：验的过程中我自己先踩了一个 —— 把守卫当**命令行参数**传给 `bash -c`，中文按 GBK 发出去，报错都不给就把脚本吃了。**凡是带中文的东西，一律走文件或 stdin**（同 `verify-stores.sh` 文件头那条）。

---

## Bug 39：把"别人先跑过"当成了自己的前提 —— CI 在**第一个**脚本就红

### 现象

2026-09-18 修完 Bug 38 之后要推上去看 CI 的真实结论，先把顺序理了一遍：

- CI 的循环用通配符 `scripts/verify-*.sh`（`.github/workflows/ci.yml:154`），按字典序，**`verify-admin.sh` 排第一**；
- 而 `verify-admin.sh` 的准备段要求 `stores` 里 **id=2 和 id=99 已经存在**（99 还必须是 `status=0` 的停业店）；
- **这两个 id 都不是迁移种的**：V4 只种了 id=1（云洗中央门店）。id=2 由 `verify-orders.sh` 的 F 段建，id=99 由 `verify-stores.sh` 的准备段建 —— **两个脚本都排在 admin 后面**。

于是同一份脚本有两种命运：

| 环境 | 结果 |
|---|---|
| 本机九连跑 | 一直绿 —— 靠的是**上一轮跑完留下的残渣** |
| CI（`docker run` 全新库，无卷） | 2/99 不存在 → `exit 1` → **e2e job 在第一个脚本就红，后面八个一次都没跑过** |

### 根因

**把"执行顺序"当成了"本脚本的前提"。** 顺序是通配符定的、是调度者的偶然；前提才是脚本自己的事。

这与 **Bug 38**（脚手架由 `verify-price.sh` 建）、**Bug 41**（race 借别人的顾客）是**同一个形状** —— 一天之内量出三处。三处都不是业务代码坏了，而是"这份脚本自己站不住，一直靠别人扶着"。

### 修复

自己把前提种出来（`scripts/verify-admin.sh:150-173`）：

```sql
insert ignore into stores (id,name,address,phone,status)
  values (99,'云洗停业测试店','杭州市余杭区测试路 1 号','0571-00000000',0);
update stores set name='云洗停业测试店', address='杭州市余杭区测试路 1 号' where id=99;
insert ignore into stores (id,name,address) values (2,'二号门店','验收用');
update stores set name='二号门店', address='验收用' where id=2;
```

- 写法照 `verify-stores.sh` 的现成那份（含**整行写回**的自愈，见 Bug 30）
- **保留** `id=1` 必须存在的硬断言：那是 V4 种的真前提，D 段的读接口全指着它
- 种完立刻自证字节（名字/地址被转码就当场停，见 Bug 22/30 的教训）

**代价说清楚**：这换掉了"顺便检测别人有没有把脚手架弄坏"。而那层检测本来就名不副实 —— 循环里的 **id=2，本脚本一条断言都不读**（全脚本 grep，它只出现在原来那条守卫里），守的是一条自己不用的前提。换来的是"单跑任何一个都成立"，后者才是本仓库的规矩（六份 `login_or_register` 拷贝就是为它存在的）。

### 验证 ✅（2026-09-18 深夜实测）

不等 CI —— 在本机直接造出"这两个脚手架不存在"的条件，按上面那条教训办事：

```bash
delete from stores where id in (2,99);   # 模拟全新库（不动 id=1）
bash scripts/verify-admin.sh             # 单跑，不靠任何别的脚本先跑
```

结果：**退出码 0，118 / 118 全绿**；跑完再查库，`id=2`（status=1）与 `id=99`（`云洗停业测试店`，status=0）都在 —— **是它自己 `insert ignore` 补出来的**，不是残渣。修法在先红过一次的同一处成立：这条路的失败模式（全新库 → 第一个脚本 exit 1）已经不可能再复现。

### 教训

**判断"这是不是前提"，方法是机械的：把脚本单独丢进一个空库跑一遍。** 绿了才算自备。

---

## Bug 40：六个脚本红了也不退非零 —— 假绿从**断言层**搬到了**汇总层**

### 现象

CI 判成败的方式是 `if bash "$s"`（`.github/workflows/ci.yml:157`）。而九个脚本里：

| 结尾 | 脚本 |
|---|---|
| `[ $FAIL -eq 0 ]`（正确） | `verify-admin.sh` / `verify-auth.sh` / `verify-stores.sh` |
| `echo "================"`（**永远退 0**） | `verify-coupons.sh` / `verify-orders.sh` / `verify-price.sh` / `verify-price-write.sh` / `verify-pricing-authority.sh` |
| 连 `FAIL` 变量都没有 | `verify-race.sh`（两条失败分支只 `echo`，不计数） |

后果：**断言红成一片，CI 照样打 OK。** 汇总行里那串「通过 X 项，失败 Y 项」还会照印，但**没有任何东西在读它** —— `mark=OK` 对它们不是"通过"，是台词。

### 根因

**Bug 38 的同一个病，换了一层**：38 是"准备段印了一句无条件的就绪"，40 是"**汇总层没有人在读结论**"。断言一个不少地跑了、也照实打印了，缺的是把它们**变成退出码**的最后一行。

### 修复

- 那五个脚本各补一行 `[ $FAIL -eq 0 ]`（三个一直对的脚本本来就有，照抄）
- `verify-race.sh` 补 `FAIL` 计数（两条失败分支 `FAIL=$((FAIL+1))`）+ 同样的结尾
- 每个脚本在这行上面写了注释，指明「**Bug 40** 之前本脚本最后一行是 echo」

### 验证

**不能"看着像"就算数** —— 要真让它红一次，确认它退出非零。✅ **2026-09-18 深夜已执行**，三个探针（都在 `/tmp` 的**副本**上做，仓库里的脚本一个字节没动）：

| 探针 | 怎么造的假红 | 结果 |
|---|---|---|
| `verify-coupons.sh`（补过 `[ $FAIL -eq 0 ]` 的六个之一） | 把 B1 的期望片段 `"code":200` 改成 `"code":999` | 退出码 **1**；印「通过 30 项，失败 1 项」；`[FAIL]` 一行，指名 B1 |
| `verify-race.sh`（原先**连 `FAIL` 变量都没有**的那个） | 把"最终状态=4"的期望改成 5 | 退出码 **1**；印「通过 1 项，失败 1 项」；并发那条 200/409 仍 `[OK]` —— 说明**失败计数是分开算的**，不是一红全红 |
| `verify-orders.sh` 的准备段（Bug 38 的修法） | 把 `BASE` 指到没人听的端口 | 退出码 **1**；`==> [准备失败]` + `票长 admin=0 manager=0 cust1=0 cust2=0`（印的是**长度**，不是票） |

三个都红了、都退了非零。**"绿"这句话现在有据可依：不是没人报错，是有人会喊。**

### 教训

一个检查要成立，得**两件事同时存在**：检查本身，和**读检查结论的东西**。

- `echo "  [FAIL] …"` 是给人看的；`exit 1` 才是给机器看的。
- 这条也适用于 CI 自己：那句 `if bash "$s"` 才是判据，日志里的"通过/失败"不是。
- **和 Bug 29/31/32/34 同族**：那一族是"文档里的断言没人会去重跑"，这一族是"脚本里的断言没人会去读"。

---

## Bug 41：`verify-race.sh` 借别人的顾客建单 —— 空库必塌，且"只推进一格"只是打印

### 现象

```bash
CID=$(db "select id from customers order by id limit 1;")   # 原 :29
```

随手取库里 **id 最小**的顾客来建单。两个后果：

1. **空库/清过库时返回空串** → 建单失败 → 脚本按准备失败退出 1。它能活到今天，是因为通配符里它排**第 8**，前面七个脚本早就造了顾客 —— 又一个"靠别人扶着"（同 Bug 38/39）。
2. **建的是谁的单一无所知**：取到哪个顾客完全取决于库里有什么。

同一脚本还有第二处（原 `:69`）：

```bash
echo "最终状态=$(db "select status from orders where id=$OID;")  （期望 4=待出厂，**只推进一格**）"
```

**这句是 `echo`，旁边没有任何断言。** CAS 真失灵（两个请求都 200、状态被推到 5）时，这行会照印"只推进一格"。

### 修复

- 补第 **6** 份 `login_or_register`（与另外五份逐字一致，`md5 5d383e42ede9…`）自己建顾客；建完判**形状**（票非空 + id 是数字），不合格当场 `exit 1`
- 把"只推进一格"从 `echo` 升成真断言（`PASS`/`FAIL` 计数），结尾 `[ $FAIL -eq 0 ]`

### 教训

**"能看到"不等于"被断言"。** 那行打印存在了很久、每次都正确 —— **正因为每次都对，没人发现它其实不是断言**。这一类要靠"读脚本时问一句：这句话错了的话，谁会红？"来查（同 Bug 27 的发现手法）。

---

## Bug 42：`POST /api/coupons` 没有身份判断 —— **顾客能自己发券**（2026-09-18 发现，**当晚已修 A**）

> **状态：已修（候选修法 A）**，验证见条目末尾「修复 ✅」。B 仍留在原地，是更稳的那条路。

### 复现步骤（**修复前**的行为）

> ⚠️ 下面第 2 步那句 `→ 200，券建出来了` 是**读代码推出来的**，不是实测的 —— 见文末「修复 ✅」里那段 ⚠️。
> 第 4 步（自己抢、自己用）没走到过。**修复后第 2 步应是 401**，实测见 J1 / J1a。

```bash
# 1. 一张普通的顾客票（注册即登录）
CUST_T=$(curl -s -X POST localhost:8081/api/auth/customer/register \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13900000099","password":"123456"}' \
  | grep -o '"token":"[^"]*' | cut -d'"' -f4)

# 2. 用它发券：discount 0.01 = 一折，库存随便填，开抢时间填过去
curl -s -X POST localhost:8081/api/coupons -H "Authorization: Bearer $CUST_T" \
  -H 'Content-Type: application/json' \
  -d '{"name":"自产券","discount":0.01,"totalStock":999999,
       "startTime":"2020-01-01T00:00:00","endTime":"2030-01-01T00:00:00"}'
# → 200，券建出来了，status=1（未开始）

# 3. 等下一次整分：CouponStatusTask 每分钟把到点的券推进成"进行中"
# 4. 用**同一个顾客**抢它（POST /api/coupons/{id}/grab）→ 下单时用掉 → 一折结算
```

### 证据链（三处，本轮逐条核过）

1. **`CouponController.java:27-36`** —— `createCoupon` 上**没有任何身份判断**。对比**同一个文件**里的 `grab`（:50）和 `myCoupons`（:68）：那两个都判了 `!"customer".equals(type)`。
2. **`JwtInterceptor.java:108`** —— `checkRoleGate` 遇到非 staff **直接 `return`**："顾客能用哪些接口由各 Controller 自己判断"（那条注释原文）。
3. **`WebMvcConfig.java:31-38`** —— 拦截器覆盖 `/api/**`，只排除 `/api/auth/**` 与文档路径 → `/api/coupons` **是被拦截的**，所以"至少要一张有效票"成立；但**任何一张有效票都行**（店长的、顾客的都行）。

### 影响面

不是"数据被改坏"，是**直接的钱**：

- 券能被**自己**抢（`grabCoupon` 只认"顾客 + 没抢过 + 有库存"），自己用（下单抵扣）
- `CouponAppService.createCoupon`（`:53-60`）**零校验**：只做 `status=1` + insert + Redis 预热库存。`discount` **没有范围检查** —— 不只是"能低到 0.01"，负数和 `>1` 也能塞进去
- 定时任务每分钟跑一次，所以"填过去的开始时间"最多 60 秒就生效

### 候选修法（A 当晚已被采用，见文末「修复 ✅」；B 仍是更稳的那条路）

- **A（最小改动）**：`CouponController.createCoupon` 加一句与 `grab` 同形的身份判断（`!"staff".equals(type)` → 401）。规则留在"店长发券"这句话旁边。
- **B（更稳）**：把"发券只归员工"**上移到 `JwtInterceptor.checkRoleGate`**，按 URL 前缀收口 —— 与"管理员专区"同一类规则。A 的问题是**每个新写接口都要记得加一句**，而这个洞正是"记得加"漏掉的那一个（`JwtInterceptor` 的注释里恰好写着"一处收口，不会漏"，见 `:95-100`）。

### 顺带记一笔分层

`CouponController` 是全仓**唯一**一个 `import com.yunxi.infrastructure.…` 的 interfaces 文件 —— 出参直接用 `CouponPO`（`grep -rn 'import com.yunxi.infrastructure' yunxi-interfaces/src/main` 只有这一行）。2026-09-11 那轮整改的口径是"interfaces 不得引用 domain，出参走 application DTO"，这里是**同一个毛病朝 infrastructure 方向**。

### 教训

**身份判断要写在收口处，而不是每个接口各自写一遍。** 同一条规则被摊薄成 N 份时，漏掉的那一份不会报错 —— 它会**静悄悄地什么都不做**（这个接口连"我是谁"都不问）。

### 修复 ✅（2026-09-18 后半夜，用户拍板"三个洞一起修"）

**改了哪一行**：`CouponController.createCoupon` 第一句加身份闸，与同文件 `grab` / `myCoupons` 同形（方向相反）：

```java
if (!"staff".equals(http.getAttribute("type"))) {
    return Result.fail(401, "请使用员工账号操作");   // 与其余五个 controller 的员工闸同一句话
}
```

**同时补上了折扣率的范围校验**（`CouponAppService.createCoupon`，`0 < discount <= 1`）—— 但**这两件事挡的不是同一个东西**，别记混：

| 闸 | 挡的是 | 挡不住 |
|---|---|---|
| Controller 身份闸 | **一折券**（`0.01` 落在 `(0,1]` 里，范围校验放行），也就是**直接的钱** | 员工自己发一折券（那属于权限管理，不是这个洞） |
| AppService 范围校验 | 建了一张**永远用不掉**的券（`Order.applyCoupon` 到用券时才抛"折扣率不合法"，那时报错的是顾客、填错的是店长） | 任何身份问题 |

**三条证据**（这条 bug 上一轮是**读代码**读出来的，本轮给了它真机证据）：

1. **修复前**：`checkRoleGate` 对非 staff 直接 return（证据链第 2 条）+ `createCoupon` 无判断（第 1 条）→ 顾客票能走到 `insert`。这是**代码走读的结论**。
2. **修复后**（`verify-coupons.sh` J1，实测）：顾客票 `POST /api/coupons` → `{"code":401,"message":"请使用员工账号操作"}`，且 J1a 查库**没有这张券** —— 拒绝发生在 `insert` / Redis 预热之前，不是"先建了再报错"。
3. **这句话只可能来自这一行**：`grep -rn '请使用员工账号操作' yunxi-*/src/main/java` 有 8 处，但**能应 `POST /api/coupons` 的只有 `CouponController:43`**（其余六个各自守着自己的端点，`OrderAppService:473` 只在建单路径上）。加上第 2 条里那条断言**已被证明会红**（把期望值改错 → `[FAIL] J1` + 退 1），所以"401"不是拦截器或别的层顺手给的。

> ⚠️ **一次没做的事，写清楚**：我本想再做一次"把闸注掉 → 看 J1 变绿"的反向探针（那会是"这个洞原先真的存在"的直接复现），**没有做** —— 那要求在生产代码里临时拆掉一个鉴权闸，被本会话的权限策略拦下，**拦得对**。所以上面第 1 条至今仍是**读代码**的结论，不是一次观测到的越权。**要把它变成观测事实，得由人来决定**：拿一张顾客票打旧版本，或临时注掉那一行 —— 两条路都要有人明确点头。文档里不把这句写成"复现过"。

### 拍板与第四道闸（2026-09-19：**管理员不发券**）

修 A 时留下一个**口径问题**，当时明确标成"待拍板"没有擅自定：`createCoupon` 的判据是 `type == "staff"`，**管理员也是 staff** —— 于是顾客发不了券了，**管理员能发**。这与 §4.2"管理员只管人与店、不碰订单"的意思相冲（发券 → 抢券 → 下单核销，整条是顾客侧业务），但"管理员该不该能看到这个页面"是产品决定，不是 bug。**2026-09-19 用户拍板：管理员不发券。**

**闸落在哪里**：`JwtInterceptor.checkRoleGate` 的**方向二**（"管理员不参与日常经营 ⇒ 拦管理员"），与订单 / 定价写 / 顾客那三条并排 —— 也就是候选修法 **B 的那个收口位置**（A 仍在 controller 里挡顾客，两条闸分工不变，见下表）。

```java
if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/coupons".equals(uri)) {
    throw new BusinessException(403, "管理员不参与发券，请使用店长账号");
}
```

**判据形状是刻意的**：`POST` **且**路径**精确等于** `/api/coupons`。两侧各防一个具体的错法：

| 若写成 | 会错在哪 |
|---|---|
| 只按方法拦（不看路径） | 抢券 `POST /api/coupons/{id}/grab` 也被截 → 管理员收到"不参与发券"，而那句话是**错的**（他不是不该发券，是没有顾客账号）—— controller 那句「请使用顾客账号登录」才对 |
| 只按前缀拦（不看方法） | `GET /api/coupons`（可用券列表）被顺手收掉。读**本次没动**，与 `/api/prices` 同形："看得见，不能写"；要连读一起收是另一个决定，没人拍过 |

**证据**：

- 单测：`JwtInterceptorTest.AdminStaysOut.couponIssueBlocked` —— 管理员 `POST /api/coupons` → 403 那句话；`POST /api/coupons/12/grab` 与 `GET /api/coupons` **放行**（就是上表那两条边界，各钉一条）。另在`managerKeepsWorking`（店长本职）里补了一条 `POST /api/coupons` 放行 —— **拦谁的规则要配一条"不拦谁"**，否则把判据写成 `return`（谁都拦）也全绿
- 真机：`verify-coupons.sh` **J4**（管理员票发券 → 403「管理员不参与发券」，票是真的、只是角色不对）、**J4a**（被拒之后库里没有这张券 —— 闸在拦截器里，早于 controller 与 insert）、**J4b 对照**（同一时刻、同一端点、同一种请求体换成店长 → 拿到数字 id）。J4b 用**另一个券名**（`CPN-E2E-CTRL`）：`CPN-E2E-ADMIN` 必须永远 0 行，J4a 那条 `^0$` 重跑才成立（同一个名字会让对照每跑一次多一行，第二次跑就红 —— 那又是"本机与 CI 分家"）
- 计数：`verify-coupons.sh` 36 → **39**，九个脚本 389 → **392**；单测 interfaces 52 → **53**（全量 394 → **395**）

**三条闸的分工（别记混）**：

| 闸 | 位置 | 挡谁 | 报什么 |
|---|---|---|---|
| 身份闸（Bug 42 修 A） | `CouponController.createCoupon` | 顾客（非 staff） | 401 请使用员工账号操作 |
| 角色闸（本次） | `JwtInterceptor.checkRoleGate` 方向二 | 管理员（staff 里的 role=0） | 403 管理员不参与发券，请使用店长账号 |
| 折扣率校验（Bug 42 同批） | `CouponAppService.createCoupon` | 任何角色填的越界折扣 | 400 折扣率必须大于 0 且不超过 1 |

> **这次没走"只记录不修"**：它不是新洞 —— 洞（顾客发券）当天就修了；这一条是把当时**明确悬置的口径**补上，属于"拍板后落地"。拍板之前的行为**没有变过**：顾客一直发不了券，管理员一直能发。所以本条目**不新开 Bug 编号**。

---

## Bug 43：空明细的订单能白洗到终态 7 —— **一分钱没收**（2026-09-18 发现，当晚已修）

### 怎么发现的

不是线上撞见的，是**补单测时被逼出来的**：给 `Order` 的状态机补"合法操作的非法顺序"那批用例，
写到"一路推到 7"时得先造一张订单，顺手想用 `List.of()` 当明细（最省事的构造），
结果发现**这条最省事的构造本身就是一条能造成收入损失的路径**。

### 四个环节分开看都没错，连起来一分钱不收

| 环节 | 代码 | 为什么"没错" |
|---|---|---|
| 构造 | `Order(...)` `calcTotalAmount()` | 空明细求和 = `reduce` 的**单位元 0** → `totalAmount = 0`。没有一条规则说"总额不能是 0" |
| 支付 | `pay(method, 0)` | 判据是"**等于全额** 或 **等于 0**"—— `0` 两条都满足 |
| 终态闸 | `requirePaidOff()` | 问的是"付得**够不够**"：`paidAmount(0) < totalAmount(0)` 为**假** → 放行 |
| 收尾 | `finish()` | 只看状态，不看金额 |

于是：**建单 → `pay(wechat, 0)` → `updateStatus()` × 4 → 状态 7「已完成」，`paidAmount=0`。**

### 当时唯一挡着它的是什么

`OrderController.java:50-52` 的一句 `if (items == null || items.isEmpty()) throw ...`。
**这是唯一的一道闸，而且它在最外层。** 域层（`Order` 自己）没有任何第二道 ——
与 `fillExpressNo` 的长度校验、`fillOrderInfo` 的地址校验**同一个理由**：
订单自己的不变量，换定时任务 / 后台脚本 / 第二个前端进来就绕过去了。

### 修复 ✅（2026-09-18 后半夜）

`Order` 构造器第一句：

```java
if (items == null || items.isEmpty()) {
    throw new BusinessException("订单至少要有一条明细");   // 与 OrderController 那句用词一致
}
```

- **用词照抄 Controller**：同一个错误不该有两种说法（否则前端按文案分支时会漏掉一条）
- **`null` 与空表共用一句话**：拆成两处写，迟早变成"一个是 400、一个是 500"
- **闸设在构造，不设在 `calcTotalAmount`**：越早越好，且报错位置就指着"你没给明细"

### 验证 ✅

- `OrderTest.EmptyItemsRejected` 3 条（空表抛 / `null` 抛 / **对照：有一条明细仍然建得出来** —— 防"把闸做成永远拒"）
- `./mvnw -o -B test` 全绿（domain 94 条）
- ⚠️ **这道闸的边界（顺手量出来的，写清楚免得下一个人以为"补完就全平了"）**：
  `quantity=0` 的明细（"有明细、但 0 件"）算出来的总额**还是 0**，能走通同一串环节 ——
  而域层的这道闸**不管它**。它现在只被 `OrderController.java:60-62`（"第 N 条明细数量必须为正整数"）挡着，
  也就是**和修之前一模一样：唯一的闸在最外层**。域构造器的 `quantity` 是 `int`，负数也收得下
  （负总额会在 `pay` 那里被"不能为负数"拦下，走不远）。
  **本轮没往域层补这一条**，因为"数量必须为正"属于**订单的不变量**还是**接口的参数校验**，两种都说得通 ——
  **这要人来定，不由我顺手加一道闸改变口径。**

### 教训

**"每个环节都合法"不等于"这条路径合法"。** 四个环节各自看都问对了问题，
但**没有人问总额本身可不可能为 0** —— 金额校验散在四处时，缺的那一处不会报错，
它会让你白洗一件衣服。补单测的价值有时候不在于覆盖，而在于**逼你把最省事的那条构造写出来**。

---

## Bug 44：`source == null` 的订单被**静默当成网单**（2026-09-18 发现，当晚已修）

### 缺陷

`Order.updateStatus()` 在 4 态（洗后待出厂）按来源分叉，判据写的是：

```java
if (this.source == OrderSource.STORE) { ... } else { this.status = DELIVERING; }   // 网单那支
```

`else` 而不是 `else if (source == ONLINE)` —— **`null` 落进"网单"那一支**。
一张来源不明的订单被当成网单：之后 `fillExpressNo` 会要求它录快递单号（门店单才不寄快递），
状态文案也是网单那套。

### 为什么这个比抛异常难查

**不报错、不留痕。** 它安静地走错一支，一路走到"录快递单号"那一步才会反着报出来
（`只有网单可以录入快递单号: 状态=…`）—— 那时排查的人对着的是**另一句话**，
而真因（来源为空）早在三步之前。

它是全流程**唯一**一个按来源分叉的状态点 —— 也就是说，这是 `source` 唯一被"用"的地方，
所以它也成了唯一能发现"来源没填"的地方。

### 修复 ✅（2026-09-18 后半夜）

分叉前先问一句：

```java
if (this.source == null) {
    throw new BusinessException("订单缺少来源，无法判断后续流程");
}
```

**闸只设在分叉点，不在更早的地方**：2→3、3→4 两步照常推进。
提前拦会让一张"来源还没填上的在途订单"在无关的状态上炸，报错位置指不到真因。

### 验证 ✅

`OrderTest.NullSourceRejected`：推到 4 → 抛那句话 → **抛完之后状态还是 4**
（没被顺手写成 5、也没写成 6 —— 这是"断言异常"和"断言没有副作用"两件事）。

### 教训

**`if (A) … else …` 是二值的，而字段是三值的**（`STORE` / `ONLINE` / `null`）。
判据写成 `if (== STORE) … else`，等于**替 `null` 做了决定**，而且做得无声无息。
"安静地走错一支"永远比抛异常贵：抛异常当场有人查，走错一支要等它撞上另一条规则的边界才露头。
**遇到 `else` 结尾的分叉，问一句：剩下的那些值，我真的一视同仁吗？**

---

## Bug 45：`mvnw` 没有可执行位 —— CI 从落地那天起**一次都没跑起来过**（2026-09-19 发现，当天已修）

### 现象

CI 装好之后，界面上的形状一直是"红"。两次 run、两个 job（单元测试 / 真机验收）全红，
而红的位置都在**第一步**：

```
./mvnw -o -q install -DskipTests
bash: ./mvnw: Permission denied
```

### 根因

**这个仓库在 Windows 上开发，而 `core.fileMode=false`。** 在这种仓库里
`git add` 收不到文件模式的变化，所以 `mvnw` 在 git 索引里存的一直是 `100644`。
本地怎么都试不出来：Git Bash 里 `./mvnw` 不校验这个位，`bash mvnw` 更不校验 ——
**Windows 上这件事没有观察点**。

修法（关键：`chmod +x` 在这里没用，要改的是 **git 索引**，不是磁盘上的文件）：

```bash
git update-index --chmod=+x mvnw
```

### 影响面：这不是"红"，这是"没跑过"

在此之前，"CI 全绿"和"CI 全红"这两句话**一次都不成立** —— 它从来没走到过
能绿的任何一步。两者的界面表现完全一样（都是一行红叉），但排查方向相反：

- 红 ⇒ 去读失败的那一步
- **从没跑过 ⇒ 先问"它到底跑到过哪一步"**

### 连带修正 Bug 39

Bug 39 那条推断——"CI 全新库 → `verify-admin.sh` 在**第一个脚本**就 `exit 1`，
后面八个一次没跑过"——**是一次没被观测过的推断**（当时 CI 连 `./mvnw` 都过不去，
它根本到不了脚本那一步）。这个推断本身说对了一件事（那份脚本当时确实借了别人的前提），
但"CI 会红在这里"这句话在 2026-09-19 之前**没有任何证据**。

真的跑起来之后（run #3/#4，全新库、无卷）实际发生的是：

| 以为会怎样 | 实际怎样 |
|---|---|
| `verify-admin.sh` 在准备段 `exit 1` | ✅ **走过了**准备段（Bug 39 的 `insert ignore` 修法在真·空库上成立） |
| 后面八个一次没跑过 | ✅ **八个全绿**（空库、`docker run` 全新实例） |
| — | ❌ `verify-admin.sh` 红了，但红在 C4b / D5 两条断言上 —— 那是另一个 bug（**Bug 46**） |

### 验证 ✅

run #3（提交 `0c2a682`）：**单元测试 job 转绿**（全新 Linux runner，含 JaCoCo），
真机验收 job 的后端能在全新库上起得来 —— 这是这个 CI 第一次跑到"被测系统"这一层。

### 教训

**"红"和"从来没跑过"在界面上长得一模一样。** 一个从没有任何一次成功记录的 CI，
先要问的不是"哪里红了"，是"**它到底跑到过哪一步**"。
与 Bug 29/31 同族：**信号的来路决定它可不可信** —— 这里连"信号"本身都还没产生。

---

## Bug 46：同一个自然秒内重新登录，新票被判成旧票 —— **CI 找到的，本地绿靠的是运气**（2026-09-19 发现，当天已修）

### 现象

`mvnw` 修好、CI 真的跑到脚本层之后，第一次能读到红在哪（靠新加的 `::error::`
annotation，见下面"读不出来的红"那段）：

```
[FAIL] C4b 换一张**新票**：他现在只是店长 → 403
[FAIL] D5 调岗之前，这张票是在用的（对照组）
       实际: {"code":401,"message":"账号已被停用或权限已变更，请重新登录"}
```

**同一份脚本，在本机 118 / 118 一直绿。**

### 根因：Bug 34 的边界① 真咬了人

`StaffTokenRevoker` 的类注释里写着这条边界，一字不差：

- 写水位线：`System.currentTimeMillis()` —— **毫秒**
- 判据：`issuedAt.getTime() < watermark`，而 JWT 的 `iat` 是**秒**（jjwt 落到整秒）
- ⇒ "作废那次操作"与"紧接着的重新登录"落在**同一个自然秒**里时，
  新票的 `iat` 被截断到该秒起点 → `iat < 水位线` 成立 → **刚签发的票被判成旧的**

偏拒绝方向、窗口 < 1 秒、下一秒自动恢复 —— 当初把它记为"已知边界 + 脚本里 `sleep 1`"
（当时只有 B6c 那一处写了 `sleep 1`）。

### 为什么只有 CI 撞上：不是环境不同，是**本地那次恰好躲过了**

窗口的成立条件是：**"作废"与"重新登录"落在同一个自然秒里**。这一串中间夹着两次
`db()`（`docker exec mysql`，几百毫秒一次），所以那一次登录落在作废后的第几毫秒，
决定它是撞上窗口还是跨过去 —— 而这取决于作废发生在那一秒的什么位置。

| | 事实 | 是什么 |
|---|---|---|
| 本机九连跑 | 118 / 118 反复绿 | ✅ **实测** |
| CI（全新库） | 同一份脚本 116 / 2 | ✅ **实测**（annotation 读到的） |
| 本机 · 把那两次 `docker exec` 去掉 | **可靠地**落在同一秒 → 401（探针连跑 3 次都成立） | ✅ **实测** —— 所以"快"这一侧确实是窗口成立的条件 |
| 本机 · 中间夹一次 `docker exec redis-cli get` | 那一次登录跨到了下一秒 | ⚠️ **一次样本**，方向一致但**不成统计** |
| "本机因为慢才几乎总能跨秒" | — | ⚠️ **推断**：与上面几条方向一致，但**"慢多少、躲过的概率多大"没有量**。别拿它当结论用 |

所以能确定的是：**本地那 118/118 不是"验过了"，两个环境跑的不是同一件事**；
"为什么本地躲过、CI 撞上"的**概率**没有量清 —— 结论停在"差异真实存在"就够用了
（本条的处理也不依赖那个概率：`relogin()` 是从构造上排除窗口，不是调概率）。

这条对"本地过、CI 挂"是个反直觉的方向：直觉总以为慢的环境更容易抖，
而这里**快的那一侧才把它暴露出来**。

### 修复（脚本层）

`relogin()` 助手（`sleep 1` → 重新登录），**五处**调用点统一走它：
B6c / C4b / D5 / D6d / E14。E16 故意不动 —— 那条只断言登录**响应**，
登录本身不受水位线拦。规则只住一个地方，不再各写各的（原先只有 B6c 有 `sleep 1`）。

⚠️ **生产代码一个字没动**：水位线仍然是"毫秒 vs 秒 + 严格小于"。
脚本绕开的是这个窗口，**不是修掉了它** —— 修它要改判据方向或把 `iat` 提到毫秒，
那是口径问题，得人拍板（与 Bug 34 的处置一致）。

### 证据（不是"我猜是时间问题"）

新增 `scripts/probe-watermark-race.sh`：**把窗口主动造出来**，然后从
**票上的 `iat`** 和 **Redis 里的水位线**取数核对（不拿 shell 的钟判 —— 见下）：

| 验的是什么 | 结果 |
|---|---|
| 作废与签发落在**同一秒**（对齐秒边界后立刻作废+登录） | ✅ 13 / 13，连跑三次，每次都是**第 1 次对齐就成功** |
| 这张同秒新签的票打 `/api/orders` | ✅ **401**，话术正是 `JwtInterceptor.java:75` 那句（证明死因是水位线，不是别的 401） |
| 隔出那一秒再登录 | ✅ 200；同一张票连打两次结果不变（**不是抖动**） |
| 杀的是"签发时刻"不是"某一张票" | ✅ 两张刚才还好用的票，在新一轮作废后一起变 401 |
| 改完重跑 `verify-admin.sh` | ✅ **118 / 118，exit 0，43 秒** |

**探针自己踩过的两个坑**（都写进了它的头注释，第一版是我判错的）：

- ❌ 用 `date +%s` 判"是不是同一秒"：登录这一趟往返在这台机器上要几百毫秒，
  等读到 `date` 秒已经翻过去了，而票上的 `iat` 还停在上一秒 ——
  于是把一次**判对了的 401** 报成了红。判据只能从**被测对象**（票 + Redis）取。
- ❌ 在"作废"和"登录"之间夹一次取水位线（`docker exec redis-cli get`）：
  几百毫秒，够把窗口整段吃掉 —— 那次登录真的跨到了下一秒，成了 200。

### 教训

**"本地绿 + CI 红"的第一个问题不是"哪边错了"，是"这两次跑的是不是同一件事"。**
时序敏感的断言在这两个环境下**不是同一件事** —— 而"本地绿"三个字会把这件事盖住。

第二件：**修掉脚本里的 flake ≠ 修掉缺陷。** `sleep 1` 让脚本不再撞窗口，
但窗口还在生产代码里（有意保留，方向偏保守）。**什么时候能把 `sleep 1` 拿掉，
以探针为准** —— 甲段变绿（同秒也放行）那天，就是它被真正修掉的那天。

### 顺带：那个"读不出来的红"

`verify-admin.sh` 在 CI 上红的那两次，外面只能看到一个
`Process completed with exit code 1` —— 想知道红在哪一条，得下载那一步的日志，
而 `GET /actions/jobs/{id}/logs` **要仓库 admin 权限**（匿名 API 直接回
`Must have admin rights to Repository.`）。改法是让工作流自己把 `[FAIL]` 那几行
发成 `::error::` —— 它变成**公开可读的 check-run annotation**，人和工具都能取。
Bug 46 的诊断就是靠它拿到的（第一条 annotation 就把 C4b / D5 和那句 401 顶了出来）。

---

## Bug 47：unit job 红了一次，**根因未定** —— 只把"下次能读出什么"补上（2026-09-19 发现，当天只修了可读性）

> **状态：未定位。** 这不是"已知原因的偶发"，是**读不到现场**。下面每一条都标了实测/未做。

### 事实（实测，只有这些）

| 项 | 事实 |
|---|---|
| 哪个提交 | **`f7979f4`** —— 只改了两个 markdown（`docs/bug-record.md` / `scripts/README.md`），**零代码改动** |
| 哪个 job 红 | `单元测试`（`./mvnw test` 这一步 `failure`）；**同一个 run 的 `真机验收（九个脚本）` 是绿的** |
| 同一份代码上一次跑 | 父提交 `34bddb0`：unit + e2e **两个 job 都绿**（16:46:11–16:46:52 UTC） |
| 本机 | 连跑 **7 遍** `./mvnw -o -B test`，**全绿**（6 遍是专门为查它跑的） |

### 为什么查不下去 —— 这条 bug 真正记的是这件事

**现场读不到**：这一步的日志要仓库 admin 权限（匿名 API 回
`Must have admin rights to Repository.`，403），check-run annotation 里只有一句
`Process completed with exit code 1`。于是连**两种可能性都分不开**：

- 测试断言红了 → 查代码 / 测试
- 依赖、插件、Maven 缓存没拉下来 → 查缓存与网络

**这两种的排查方向完全相反**，而当时的界面把它们显示成同一个东西。
这正是 **Bug 45 的同族**：**"红"和"不知道红在哪"长得一样。**

### 已做的（只有一件，但方向是对的）

~~Rerun 那次 run~~（要仓库写权限的 token，本轮没有）→ 改为**把 unit job 也做成会说话的**：

- `./mvnw -B test` 收进 `/tmp/unit.log`；失败时抓 surefire 的失败行
  （`<<< FAILURE!` / `[ERROR] Tests run:` / `[ERROR] Failed to execute goal`），
  一条条 `::error` 顶成公开可读的 annotation；一条都没抓到就**兜底**抓 `^\[ERROR\]`
- 失败时上传 `surefire-reports` artifact（expected/actual 全文，annotation 放不下）
- 管道与 e2e 那步同形（`%`→`%25`、换行→`%0A`），并用**假日志**验过两条路都抓得到

### 还没做的 / 不成立的说法

- **根因仍是未知**。下一次 unit job 红了，annotation 里就会有测试名 —— 那时才谈得上修。
- **"本机复现不出来"不等于它不存在**：CI 是 UTC / Linux / 干净缓存，本机是 +08:00 / Windows / 暖缓存。
  本机 7 遍绿只能说明"本机这个配置下没抓到"，不能说明"测试是好的"。
- 没有把这一个 job 的失败算进"CI 是不是绿的"总账 —— 总账看 workflow 结论。

### 补记（2026-09-19）：CI **先停用**，这条通道暂时没人走

用户拍板「**我先不用 ci，github 不能稳定连接**」。对上面这一节的影响，逐条写清：

- **"下一次 unit job 红了，annotation 里就会有测试名"这句暂时兑现不了。** 通道本身已经写进
  `ci.yml` 并用假日志验过，但要等下一次推送、下一次真跑才谈得上。
  **Bug 47 的根因因此仍未定，这一笔不结案** —— 停用不是"解决了"，是"暂时不去撞它"。
- **判定改回本机**：九个脚本 + `./mvnw -o -B test`，数字**和环境一起写**。
  本机这一侧是 Windows / 热库 / 慢机器 —— 恰好是 Bug 46「本地绿靠运气」的那一侧。
- **`ci.yml` 没删也没改**：不是"CI 不要了"，是"暂时不靠它"。哪天连得上，它扫的还是这九个
  `scripts/verify-*.sh`，留下的历史 run 也还在。
- 代价照实记：**"全新库能不能一次跑通"这一侧现在没有第二个环境在守。**
  脚本准备段已按 Bug 39/41 改成"缺了自己建"，但那是**在本机这份跑了几十轮的库上**验的，
  不是空库上验的 —— 这正是当初要 CI 的理由（Bug 29 那句"文档里的假绿没有任何东西会去重跑它"）。

---

## 汇总

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
| 30 | 验收 | 自愈只修一半（**三处**） | 脚手架 insert 里的中文列有多个，自愈 update 只补了**有断言盯着的那一列** —— 两个门店的 address、`mgr_disabled` 的 name 因此烂了四天没人喊；`verify-auth.sh` 那处更狠：`-e` 带中文 + 漏 charset，整条写路径都会转码。修法：整行写回 + 整行断言（写路径坏的要先修路径）。判断法是机械的——**把 insert 里的非 ASCII 列逐个问"谁在盯着它"** |
| 31 | 验收 | 压测器在量自己（**假绿换到了性能上**） | 每轮开一个新 JVM，把 JVM 冷启动算进了请求延迟，跑出一组"三轮都很稳定" 的 279 / 305 / 303 TPS —— 稳定是因为三轮**一样冷**。换个"服务端耗时≈0"的对照服务端才现形：拿 LoadGen 打它比打真实应用还慢（N=1 要 116ms）。同一个 JVM 连跑四轮 p50 是 344→32→23→58ms，**第一轮比第三轮慢 15 倍**。修法是所有轮次挤进一个 JVM + 输出自带收敛证据。和 29 同病：**"好看"和"真的验过"脱钩** |
| 32 | 验收 | 冷启动的**另一半**（修完 31 才暴露出来） | 31 只锁住了量具的 JVM，**被测服务自己也在冷启动** —— 而"同一个 JVM 跑四轮"这个修法恰好把服务端的预热过程遮住了。重启后第一个 N=200 量出 443.88 TPS，对比文档里的 649，**差一步就得出"虚拟线程更慢"**。拦下它的不是数值而是收敛形状：`188 → 337 → 444` 是条还在爬的线，而基线是 `296 → 846 → 872`。同配置热起来中位 **832**，冷热差 **1.87 倍**。修法：协议加一个显式丢弃轮 + **基线必须当天重测**（不重测会把 +10.6% 报成 +28%）。连带结论：三个配置的抖动范围互相重叠（±22.3%），**比要量的效应还大 —— 只能报"测不出来"，不能报"没差别"** |
| 33 | 单测 | 断言错了**机制**（ERROR 不是 FAIL） | 同一个 400 有两条返回路径：必填/跨聚合走 `Result.fail(400)`，**长度上限是 domain 抛 `BusinessException`**。测试照抄了前者的写法，被测的是"我以为的机制"。三处改成 `assertThatThrownBy`，并把横跨两条路径的 `roleValidation` 拆成两条 |
| 34 | Interfaces | 已知边界（**计划里方向写反了**） | 水位线存**毫秒**、`iat` 只到**秒**且**向下**取整 → 同一自然秒内重新登录会被判成旧票，报的还是「账号已被停用」（账号是启用的）。计划里写的是"偏放行"，实际**偏拒绝**。修法：不换方向，写成显式 `sleep 1` + 文档记为已知边界。**和 29/31/32 同族：文档里的断言没人会去重跑它** |
| 35 | 验收 | 脚本语法（**报错位置离现场 200 行**） | `"$(cmd "/path "$VAR")"` 引号错位，`)` 落进未闭合的字符串，bash 一路吃到 EOF 才报"括号不配平"。最小复现三行；**只有第三种排查手法（把 bash 的引号栈原样抄一遍）管用**，前两种（Python 扫描器 / 前缀二分）都误判 |
| 36 | Interfaces | 组合出的**假成因**（本轮发现，**未修**） | 允许建 `store_id=NULL` 的店长（合法状态）× `requireStaffStore` 的 401「登录信息已升级」→ 无店店长建单收到一句假话。**它上面那段注释写的"只剩旧 token 会落在这里"因此变成假的**。修法取决于口径，等用户拍板 |
| 37 | 验收 | 假红（**5 条，同一个探针错**） | 脚本自己打架：E 段断言"店长打 `/api/staff` 必须 403"，A2b/A2c/A4e/B1/B6c 却拿**店长的票**指望 200 → 5 条一致回闸门那句话，看着像新接口全坏。**改的是探针、代码零改动**，复跑 124/124。同一个毛病还藏在 D2/D8 的**绿**里（标签写"顾客"、探针用店长票）|
| 38 | 验收 | 假红 + **报错指错方向**（脚手架由**别人**建） | `verify-price-write.sh` 的顾客脚手架是 `verify-price.sh` 建的，而通配符顺序里它排**后面**（`-` < `.`）→ 号不在时只 login 拿到**空票** → `Authorization: Bearer `（空值）→ 拦截器回「未登录」→ A1 报成"鉴权坏了"，而真因是"没拿到票"。准备段那句"就绪"是**无条件**印的假话。改用兄弟脚本逐字相同的 `login_or_register` + 空票即 `exit 1`。**同一形状还有第二处**：`verify-pricing-authority.sh` 同日一并修（未爆，见本节末） |
| 39 | 验收 | 前提借自别的脚本（**"CI 首跑必红"是推断，没观测过**） | `verify-admin.sh` 要求 `stores` 2/99 已存在，而它们由排在**后面**的两个脚本建（V4 只种了 id=1）→ 本机靠残渣一直绿，当时推断"CI 全新库会在**第一个脚本**就 `exit 1`"。**2026-09-19 更正**：那时 CI 连 `./mvnw` 都过不去（Bug 45），这句没有任何证据；真跑起来之后，修法在空库上成立、后面八个也全绿。修法：自己 `insert ignore` 补齐 + 保留 id=1 的硬断言。**判据是机械的：单独丢进空库跑一遍** |
| 40 | 验收 + CI | 假绿（**换到了汇总层**） | 六个脚本最后一行是 `echo`（`verify-race.sh` 连 `FAIL` 变量都没有）→ 断言红成一片，`if bash "$s"` 照样打 OK，汇总行照印但**没人在读它**。38 是"准备段印假话"，40 是"**结论没人读**"。修法：六处补 `[ $FAIL -eq 0 ]` + race 补计数 |
| 41 | 验收 | 前提借自别的脚本 + **打印当断言** | `verify-race.sh` 取库里第一个顾客建单（空库必塌；九连跑靠排第 8）；"最终状态=4，只推进一格"那句是 `echo` —— CAS 真失灵也照印。修法：第 6 份 `login_or_register` 自建 + 打印升成断言 |
| 42 | Interfaces | 越权（发现当晚已修 A） | `POST /api/coupons` 上没有任何身份判断（同文件的 `grab`/`myCoupons` 都判了）→ **顾客 token 能自己发券**：`discount:0.01`（一折）、库存随意、开抢时间填过去，等定时任务推进后自己抢、自己用。`CouponAppService.createCoupon` 里折扣率**零校验**（负数/>1 也能塞）。修法 A 已落地（Controller 身份闸）+ 折扣率范围（两半挡的不是同一件事），`verify-coupons.sh` J 段 5 条实测。**2026-09-19 追加**：修 A 时留下一处**悬置口径** —— 判据是 `type == "staff"`，所以**管理员也能发券**；用户当天拍板"**管理员不发券**"，`checkRoleGate` 方向二加第四道闸（403「管理员不参与发券」），三条闸的分工见条目内「拍板与第四道闸」 |
| 43 | Domain | 状态机 + 金额缺口（**发现当晚已修**） | 空明细 → 总额 0 → `pay(0)` 同时满足"等于全额"和"等于 0" → `requirePaidOff` 问的是"够不够"，`0 < 0` 为假 → **白洗到终态 7，一分钱没收**。四个环节分开看都"没错"。原先唯一的闸在 `OrderController`（最外层，换入口就绕过）→ 已把闸补进 `Order` 构造器。**边界：`quantity=0` 仍是同一条路，本轮没动**（口径要人定） |
| 44 | Domain | 分叉判据漏了 `null`（**发现当晚已修**） | `updateStatus` 在 4 态按来源分叉，写成 `if (== STORE) … else …` → **`source=null` 静默落进"网单"那支**：之后会被要求录快递单号、文案也是网单那套，**不报错不留痕**。这是 `source` 全流程唯一被用到的地方，所以也是唯一能发现"来源没填"的地方 → 已改成先问一句"来源呢"，抛 `订单缺少来源，无法判断后续流程`，状态停在 4 |
| 45 | CI + 环境 | **"红"其实是"从来没跑过"** | `mvnw` 在 git 索引里是 `100644`（Windows `core.fileMode=false` → `git add` 收不到模式变化）→ Linux runner 上 `./mvnw` 直接 `Permission denied`。CI 从落地起两个 job、两次 run 全红，而红在**第一步** —— 所以"CI 全绿/全红"这两句话一次都没成立过。修法 `git update-index --chmod=+x`（`chmod +x` 没用：要改的是索引不是磁盘）。**教训：红和没跑过在界面上长得一样** |
| 46 | 验收 + 环境 | 本地绿靠运气（**CI 找到的**） | `verify-admin.sh` 在 CI 上 **116 / 2**（C4b、D5），回的都是 401「账号已被停用或权限已变更」；**同一份脚本本机 118/118 一直绿**。根因是 Bug 34 的边界①（`iat` 秒 vs 水位线毫秒，判据 `<`）：窗口只在"作废与重新登录落在同一自然秒"时成立，而中间夹着两次 `db()` —— 两个环境跑的不是同一件事。**"本机为什么躲过"的机制方向一致但概率没量清**，结论停在"差异真实存在"（修法也不依赖它） |
| 47 | CI + 环境 | **红得读不出来**（根因未定） | `f7979f4`（**只改了两个 markdown**）的 unit job 红了，而同一份代码的上一跑两个 job 全绿；`./mvnw test` 这一步的日志要仓库 admin 权限（匿名 403），annotation 里只有 `exit code 1` → **连"是断言红了"还是"依赖没拉下来"都分不出**（两种可能排查方向相反）。修的是**可读性**：unit job 失败时把 surefire 失败行顶成公开可读的 annotation + 传 `surefire-reports` artifact。本机连跑 7 遍全绿 = **本机没抓到，不等于不存在**。**当天晚些 CI 先停用**（GitHub 连不稳，用户拍板）→ 这条通道暂时不会被动用，**根因未结案** |

---

## 测试通过的功能

### 2026-09-19 拍板"管理员不发券" —— `POST /api/coupons` 加第四道闸

> 这一轮**动了生产代码**：`JwtInterceptor.checkRoleGate` 的方向二多一条。理由不是发现了新洞
> （洞在 Bug 42 当天就修了），是把当时**明确悬置的口径**补上 —— **管理员到底能不能发券**。
> 详细判决与三条闸的分工写在 **Bug 42 的「拍板与第四道闸」**一节；这里只记这一轮的账。

**改了什么**：`checkRoleGate` 拦管理员那三条（订单 / 定价写 / 顾客）旁边加第四条 ——
`POST` 且路径**精确等于** `/api/coupons` → 403「管理员不参与发券，请使用店长账号」。
两侧边界是刻意的：抢券 `POST /{id}/grab`（顾客的动作，该由 controller 回「请使用顾客账号登录」）
和**读**（`GET /api/coupons`、`/mine`）**都不动** —— 与 `/api/prices` 同形："看得见，不能写"。

**账（全部实测；改了 `JwtInterceptor` ⇒ 按仓库规矩九个脚本全量重跑）**：

| 验的是什么 | 结果 |
|---|---|
| 单测 | ✅ **395 / 395**（`./mvnw -o -B test` BUILD SUCCESS，先删 `*/target/surefire-reports`）。common 12 / domain 94 / application 236 不动，interfaces 52 → **53**（`couponIssueBlocked` 一条；另在 `managerKeepsWorking` 里补一句"店长发券放行"） |
| 九个脚本全量重跑 | ✅ **392 / 392，九个退出码全 0**（admin 118 / auth 29 / **coupons 36 → 39** / orders 69 / price-write 29 / price 26 / pricing-authority 24 / race 2 / stores 56）。每个脚本"自印的通过数"与独立数出的 `[OK]` 标记数逐个相等。⚠️ 这是**本机**的数（见 Bug 46 那句教训：说"全绿"要连环境一起说） |
| 新断言**会不会红** | ✅ 在 `/tmp` 副本上把 J4 的期望值改错 → `[FAIL] J4`、**退 1**、38 / 1。**仓库里的脚本没被改** |
| 这句 403 只可能来自新那一行 | ✅ `grep -rn '管理员不参与发券'` 全仓只有四处：生产代码 `JwtInterceptor:166`、单测一条、脚本两条。`verify-coupons.sh` J4 断言的就是它 |
| 后端重启后才是新代码 | ✅ `install -DskipTests` → 杀掉 8081 上的旧进程 → `spring-boot:run` → 探测到 401 才开跑 |

**新增的三条真机断言**（`verify-coupons.sh` J 段）：**J4** 管理员票发券 → 403；
**J4a** 被拒之后库里没有这张券（闸在拦截器里，早于 controller 与 insert）；
**J4b 对照** 同一时刻、同一端点、同一种请求体换成店长 → 拿到数字 id。
J4b 用**另一个券名**（`CPN-E2E-CTRL`）：`CPN-E2E-ADMIN` 必须永远 0 行，J4a 那条 `^0$` 重跑才成立。

**CI 那一侧（已跑完，实测）**：`34bddb0` 的 workflow **两个 job 全绿** ——
「单元测试」与「真机验收（九个脚本）」都是 `success`（真机验收 16:46:11 → 16:47:44 UTC）。
也就是 **395 项单测与 392 条脚本断言在 CI 的"全新库 + Linux + 快机器"上也是绿的** ——
那正是 Bug 46 暴露问题的那一侧。结论来自 `GET /actions/runs`（`head_sha` = `34bddb0`、
`conclusion` = `success`）与 `GET /actions/runs/{id}/jobs`；**job 日志没读**（要 admin 权限）。

### 2026-09-19 CI 第一次真跑 —— 两个 bug 都出在"验"这一层（Bug 45 / 46）

> 这一轮**没有动生产代码**。改的三个文件全是测试/CI 资产：
> `.github/workflows/ci.yml`（失败时发 annotation）、`mvnw`（索引里的模式位）、
> `scripts/verify-admin.sh`（`relogin()`）+ 新增 `scripts/probe-watermark-race.sh`。

**先纠正一句话**：在这一天之前，"CI 是红的"这个说法**不成立** —— 它一次都没跑起来过
（Bug 45：`mvnw` 在 git 索引里是可执行位缺失，Linux 上第一步就 `Permission denied`）。
下面是按提交记录下来的、真的发生过的四步：

| 时点 | 单元测试 job | 真机验收 job |
|---|---|---|
| `mvnw` 修好之前（两次 run） | ❌ 第一步 `./mvnw` 就 Permission denied | ❌ 同上 —— **被测系统一次没起来过**，脚本一条没跑 |
| `0c2a682`（`mvnw` 的可执行位） | ✅ 绿（全新 Linux runner，与本地同一条 `./mvnw -o -B test` 命令） | ❌ 后端在**全新库**上起来了、脚本在跑了 —— 但红在哪**读不出来**（日志要仓库 admin 权限） |
| `2267dcf`（红了就发 annotation） | ✅ 绿 | ❌ `verify-admin.sh` **116 / 2**（C4b、D5）—— 第一条 annotation 就把"红在哪两条"顶出来了 |
| `a764786`（`relogin()` 修好） | ⚠️ 未单独读 | ✅ **通过**（整条 workflow 的结论） |

**`a764786` 这一行是怎么读到的**（值得记一笔，因为差点读不到）：GitHub 匿名 API
每小时只给 **60 次**，本机走代理时出口是**共享**节点（`140.245.98.123`），
配额被用满 → 全是 **403**，而 `403` 和网络不通（`000`）在命令行上**长得一样**，
我按网络问题白等了一轮。真正管用的是**工作流徽章**：

```bash
curl -s -x http://127.0.0.1:7897 \
  "https://github.com/chlzzshizi/yunxi-server/actions/workflows/ci.yml/badge.svg?branch=main"
# → "passing"
```

徽章**不占 API 配额**，给的是"main 上最新一次运行"的结论。在此之前 main 上
只有过失败的运行（`2267dcf` 的 116 / 2、更早的 `mvnw` Permission denied），
所以这个 `passing` 只可能来自 `a764786` —— 但**它只有结论、没有细节**：
哪一步、跑了多少条，等 API 配额恢复后另读（本节不填没读到的数字）。

**`0c2a682` / `2267dcf` 顺带证实的（跑出来的，不是推的）**：

| 证了什么 | 怎么证的 |
|---|---|
| 单测在**第二条路上**也绿 | 全新 Linux runner（不是本机、不是 Windows），与本地同一条命令。数字以本地实测的 **394** 为准（CI 那步的完整日志要 admin 权限才读得到） |
| 迁移链在**空库**上从头跑到尾 | `docker run` 全新实例、无卷 —— 后端起来了，接口有响应 |
| **Bug 39 的修法在真·空库上成立** | `verify-admin.sh` 走过了准备段（没有 `exit 1`）—— 这是那条修法第一次在"没有残渣的库"上被试 |
| "借别人前提"那一族到此为止 | 其余八个脚本在**同一个空库**上全绿（Bug 38 / 39 / 41 说的都是这件事） |

**唯一的红**（`verify-admin.sh` 116 / 2）查出来是 **Bug 46**：不是脚本写错了，
是"作废水位线的同一秒窗口"—— 本机跑不出来**只因为本机慢**。
修法（`relogin()`，`sleep 1`）改完在本机重跑：**118 / 118，exit 0，43 秒**，
并另配了一个能**主动复现**那个窗口的探针（`probe-watermark-race.sh`，13 / 13，连跑三次稳定）。

**九个脚本在本机（改完 `relogin` 之后）重跑**：✅ **389 / 389，九个退出码全 0**
（admin 118 / auth 29 / coupons 36 / orders 69 / price-write 29 / price 26 /
pricing-authority 24 / race 2 / stores 56 —— 每条与 `relogin` 之前**一致**，
说明这次改的是时序、没动断言数）。**但这个数字仍旧是"本机"的**：
同一批脚本在 CI 上是什么样，以下一条 CI 结论为准（CI 的机器快，正是 Bug 46 的那一侧）。

### 2026-09-18 晚 测试金字塔扳正（单测补全 → 脚本变薄 → 准备段自证）

> 本节是**当天第三轮**，排在"抢券压测"（上午）和"管理员接口"（下午）之后。
> 那一节末尾的"另外八个既有脚本 277 / 277 ✅ 实测"是本轮**之前**的记录 —— 本节改了脚本本身，所以那行数字的来源没变、**总数变了**。

**数字的来路**（按 Bug 29 立的规矩，没跑过的一律标 ⚠️）：

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测（common 12 + domain 94 + application 236 + interfaces 52） | **394** | ✅ **实测** —— 基线 **216 → 388**（晚）**→ 394**（后半夜，+6：domain +4 −2、application +4）。`./mvnw -o -B test` BUILD SUCCESS，0 failures / 0 errors / 0 skipped |
| 九个验收脚本的断言总数 | **389** | ✅ **实测**（2026-09-18 后半夜全量重跑）：**389 / 389，九个退出码全 0**，且**每个脚本"自印的通过数"与独立数出的 `[OK]` 标记数逐个相等**。变薄后 376 → 补密码 8 条 → 384 → 补发券闸 5 条 → 389 |
| 其中本轮删掉的 | **26** | 变薄前 **402 → 376**（调用式 397→371；5 条手写断言没动）。逐条点名清单在 `scripts/README.md` |
| JaCoCo 报告 | —— | ✅ 已接入（`prepare-agent` + `report`，**不设 `check` 门槛**：目标是看见洞，不是设 KPI） |

**本轮做的三件事**（另有一条**补记**，写在这三件之后 —— 它记的是初稿里的一处假绿）：

1. **单测补全**（§7 的账）：领域层补 `Staff.canLogin` 默认拒绝、`Store` 长度 + emoji、`ClothesCategory.isLeaf`、`Order.applyCoupon` 恰好 1.0、`ClothesPrice` 负数、`OrderItem` 未定价分支、三个枚举的 `fromCode`；应用层补 `updateStaff` / `updateStore` 两条**整段未测**的校验路径、`CouponAppService`（整个类原先没有测试文件）、`StaffTokenRevoker`；接口层补 `adminOnly` 闸门、`preHandle` 三道 401、`JwtUtil`、`GlobalExceptionHandler` 四条通道、DTO 字段集合的**反射钉**。
   - 两条**只钉现状 + 上报、不写会红的测试**：空明细 → `totalAmount=0` → `pay(0)` 满足"等于 0" → 白洗到终态 7（域层没有第二道闸，当前只被 `OrderController` 挡着）；`updateStatus` 的 `source == null` 静默当网单走 6。
     → **这两条当晚已修**（用户拍板）。当时钉缺口的那两个 Nested 已翻面成"钉补上的闸"：`EmptyItemsRejected` / `NullSourceRejected`，见 **Bug 43 / 44** 与下面「2026-09-18 后半夜」一节。
2. **脚本变薄**：按"这条断言需要'世界'吗"把**边界密度**搬进单测，脚本每条规则留**一条代表性探针**。**搬走 ≠ 删掉** —— 26 条删除全部点名，且每条都在单测里有家。五个脚本**一条没动**（orders / price / price-write / pricing-authority / race）：它们的断言要么是计划明文豁免的 DTO 绑定探针，要么是那条规则唯一的探针。
3. **准备段自证**（Bug 38 的扫尾）：九个脚本统一 `==> [准备失败]` 前缀、票非空 + **id 判形状**（不是有无）、印长度不印票面值、手机号判 11 位数字。两处**故意**的例外写在脚本里：`verify-auth.sh` 不在准备段取票（它的被测对象就是登录本身）、`verify-admin.sh`/`verify-stores.sh` 不用 `login_or_register`（号每次现拼，兜底分支是死的）。

> **补记（当晚稍后）：本节初稿里有一处假绿，被自己抓住了。**
>
> 计划第 1 步点名了「`OrderStatus` / `OrderSource` / `PayMethod.fromCode` 未识别码」，上面第 1 条我照着计划把它写成了"已完成"。写完重读、逐条对计划点名时卡住了：**这句话指向哪个文件？** 想不起来 —— 因为没这个文件。`yunxi-common` 当时**连 `src/test` 目录都没有**，全仓没有任何测试碰过这三个 `fromCode`。
> 这与 **Bug 29** 是同一个形状（数字是数出来的，不是跑出来的），只是这次假绿长在**记录**里而不是设计文档里，而且没有第三个人会去重跑它 —— 靠"我说不出它在哪"发现。**假绿不挑地方，写在哪儿就在哪儿骗人。**
>
> 修法（当晚补完并**实测**）：`yunxi-common/pom.xml` 加 `spring-boot-starter-test`（test 作用域，抄 `yunxi-domain` 那份），新建 `yunxi-common/src/test/java/com/yunxi/common/enums/EnumCodeTest.java` **12 条** —— 码值往返、`OrderStatus` 就是 1..7 连号（**落库契约**，V8 迁移那次教训的哨兵）、历史码 8 已消失、0/负数/越界码抛 `BusinessException` 且消息是那句原文、默认码值 400、`PayMethod` 四个码值 `cash/wechat/alipay/balance`（同时活在前端与 `orders.pay_method` 列里）、大小写敏感、`null` 不 NPE。
> `./mvnw -o -B test` → common 12 / domain 92 / application 232 / interfaces 52 = **388**，BUILD SUCCESS。376 → 388 的差额就是这 12 条。
>
> 测试里留了一处**诚实的标注**，值得记下来：`PayMethod.fromCode(null)` 那条**没有活调用点能传进 null**（三个调用点都是 `@RequestParam String`，Spring 自己先回 400），所以它钉的是**防御性质**不是活路径 —— 注释里写明了，没把它说成"防住了一次线上事故"。**把防御性断言说成实战功绩，是另一种假绿。**
> 至此只剩 `yunxi-infrastructure` 一个模块没有测试，那是 §7 明文规定的（Mapper 不写单测）。

**本轮顺带量出来的三个洞（原样记着，修没修见下）**：

- **Bug 42**：`POST /api/coupons` 越权 —— 顾客能自己发券（见条目）。→ **当晚已修**（修法 A，见 Bug 42 条目末尾「修复 ✅」与「2026-09-18 后半夜」一节）
- **`PUT /api/staff/{id}/password` 的成功路径从没被任何脚本跑通过**：只有 E5 碰过它的 403、原 A10d 碰过它的 404。200 → 新密码能登、旧密码不能登、旧票当场作废这条链，当时只有应用层单测兜着。→ **当日深夜已补**（`verify-admin.sh` E14–E19，8 条）
- **「`CouponAppServiceTest` 缺『已用的券不在 `/mine`』」—— 这条是我报错了**：判据在 `CouponGrabMapper.xml:64` 的 `AND g.used = 0` 里，而那个 mapper 在单测里是 **mock 掉的**。在单测里补它等于先 stub 一个已经滤好的列表、再断言它没被改动 —— **断言的是自己写的那行 `when(...)`，规则一个字都没碰到**。它本来就该住在脚本里（`verify-coupons.sh` 的 H4 用同一把刀切三张券），**这个洞不存在**；已在 `CouponAppServiceTest.MyCoupons` 里写下这段理由，防下一个人"顺手补一条"

**验收（2026-09-18 深夜，实测；此前的 ⚠️ 全部落地）**：

| 验的是什么 | 结果 |
|---|---|
| 九个脚本全量重跑 | ✅ **384 / 384，九个退出码全 0**（静态数 376 与实测逐脚本吻合）—— 这是**当晚**的数；后半夜又补了 5 条（发券闸）→ 现在的数是 **389**，见下一节 |
| Bug 39 的修法（`delete from stores where id in (2,99)` 后**单跑** `verify-admin.sh`） | ✅ 脚本自己 `insert ignore` 补齐后 **118 / 118 全绿**（不是 `exit 1`） |
| Bug 40 的修法（**真让它红一次**） | ✅ 三个探针：coupons 翻一个期望值 → 退 **1**（通过 30 失败 1）；race 把期望状态改成 5 → 退 **1**（失败计数 1，它原先连 `FAIL` 变量都没有）；把 `BASE` 指到死端口 → 准备段 `==> [准备失败]` + 票长全 0 + 退 **1**。**探针跑在 `/tmp` 的副本上，仓库里的脚本没被改** |
| JaCoCo 报告（看洞） | ✅ 已看：application 行 99.8% / 分支 99.0%；domain 分支 97.8%、**行 59.8%**；interfaces 行 37.6%（controller 整层 0% —— 那层按判据归脚本）；common 行 68.7%（`StaffRole` 12 行"未覆盖"是**跨模块假象**：它在 `yunxi-application` 的测试里被跑到，而各模块报告只统计自己模块的测试） |

**仍未做**（本节的时点：2026-09-18 深夜）：设计文档 §7 验证状态块（**要另行确认授权**，有"不许擅自改"的规矩）—— 三个生产代码的洞**当天后半夜已全部修掉**，见下一节。

### 2026-09-18 后半夜 修三个生产代码的洞（Bug 42 / 43 / 44）

> 用户拍板"三个一起修"、"推，让 CI 说话"。这一节是**动生产代码**的那一轮 ——
> 上面那节末尾的"仍未做"清单里，三条一次性清空。

**改了什么**（四处，全在 `main`，全部有实测支撑）：

| 洞 | 改动 | 一句话理由 |
|---|---|---|
| Bug 42（一半） | `CouponController.createCoupon` 加身份闸（非 staff → 401） | 挡住**直接的钱**：顾客票发一折券再自己抢自己用 |
| Bug 42（另一半） | `CouponAppService.createCoupon` 加 `0 < discount <= 1` | 挡住**建得出来却永远用不掉**的券（`Order.applyCoupon` 到用券时才抛，报错的人跟填错的人不是同一个） |
| Bug 43 | `Order` 构造器：空明细当场抛 | 订单自己的不变量，换任何入口进来都绕不掉 |
| Bug 44 | `Order.updateStatus`：`source == null` 当场抛 | 全流程唯一按来源分叉的点，`else` 不能替 `null` 做决定 |

**验证（全部实测，不是推的）**：

| 验的是什么 | 结果 |
|---|---|
| 单测 | ✅ **394 / 394**（`./mvnw -o -B test` BUILD SUCCESS）。domain 92 → **94**（−2 条钉缺口的 +4 条钉闸的）、application 232 → **236**（折扣率 4 条）、common 12 / interfaces 52 不动 |
| 九个脚本全量重跑 | ✅ **389 / 389，九个退出码全 0**（coupons 31 → **36**，新增 J 段 5 条） |
| 新断言**会不会红** | ✅ 在 `/tmp` 副本上把 J1 的期望值改错 → `[FAIL] J1`、**退 1**、35 / 1。**仓库里的脚本没被改** |
| 后端重启后才是新代码 | ✅ `install -DskipTests` → 杀掉 8081 上的旧进程 → `spring-boot:run` → 探测到 200 才开跑（**不吃旧进程的绿**） |

**顺手量出来的一处计数陷阱**（不是 bug，但会让数字说谎）：`target/surefire-reports/` 里
**上一次跑的 XML 不会被清掉**，换了测试类名之后旧的还在 —— 直接 `grep tests=` 求和会
把已经删掉的用例**算进来**（本轮实测：不清理时 domain 报 96，清理后重跑是 **94**）。
本仓库所有"单测 N 项"的数字，都必须**先删 `*/target/surefire-reports` 再跑**。
这和 Bug 29/31 是同一族：**数字的来路决定它可不可信。**

**这一轮**同时把三处"只有脚本能验"的东西留在了原地，没往单测里塞假测试：
`CouponController` 的身份闸（单测里 mock 掉的就是 service，没有 controller 这一层，
它的家是 `verify-coupons.sh` J1）、以及 J1 那句"顾客能不能发券"（**读代码 + 修复后 401 的正向证据**，
反向复现**没做** —— 理由写在 Bug 42 条目里那段 ⚠️）。

> **补记（2026-09-19）**：上表那一行「九个脚本全量重跑 ✅ **389 / 389**」是**本机**的数字，
> 写成那样容易被读成"任何环境下都绿"。第二天 CI 第一次真跑到脚本层（全新库），
> 同一批脚本里 **`verify-admin.sh` 是红的**（116 / 2，见 Bug 46）—— 其余八个绿。
> 所以那句话的完整版是"**在本机（有上一轮残渣、且机器慢）** 389 / 389"。
> 这正是 Bug 29 立的那条规矩的另一种形态：数字要连**它的来路**一起写。

### 2026-09-18 管理员接口（员工管理 + 门店管理）

> 这一节排在"抢券压测"前面：**两轮同一天，管理员接口在后**（压测那轮是上午，业务代码零改动；
> 这轮动了 `JwtInterceptor`，所以那节末尾"没重跑的七个脚本"那张账**本轮起作废**）。

**先说清楚数字的来路**：下表里**每一项都标了是"实测"还是"尚未重跑"** ——
按 Bug 29 立的规矩，没跑过的写 ⚠️，**不填预期值**。

| 层次 | 项数 | 结果 |
|---|---|---|
| 单测（domain 49 + application 164 + interfaces 3） | **216** | ✅ **实测** —— 基线 172 + 新增 44（`StaffAdminAppServiceTest` 32 + `StoreAdminAppServiceTest` 12），0 失败 0 错误 |
| `bash scripts/verify-admin.sh` | **124** | ✅ **实测** —— 首跑 **119 / 5**，那 5 条红**全是脚本自己的探针错**（**Bug 37**，只改探针、生产代码零改动）→ 复跑 **124 / 124**。断言总数与分组一个字没变 |
| 另外**八个**既有脚本 | **277** | ✅ **实测（2026-09-18 全量重跑，21:31–21:34）**——`verify-orders` **69** / `verify-stores` **60** / `verify-auth` **36** / `verify-coupons` **32** / `verify-price` **26** / `verify-pricing-authority` **24** / `verify-race` **1**（一 200 一 409，只推进一格）**全部 0 失败**；`verify-price-write` **29**（重跑那遍 28 / 29）—— 唯一那条红是**脚手架没拿到顾客票**（**Bug 38**，与 `JwtInterceptor` 无关），按 `scripts/README.md:184` 的规矩它本来就在射程内，所以**当场按"先证对照再断失败"查了个底**。修法只动准备段（换 `login_or_register` + 空票即 `exit 1`），**断言一条没改** → **改完当场复跑 29 / 29，本行 277 / 277 全绿** |

本轮改动：

- **`/api/staff` 六个端点**（建 / 列表 / 详情 / 改资料 / 重置密码 / 启停）+
  **`/api/stores` 三个写端点** + **`GET /api/stores/all`**。设计文档 §4.2 只有"增删改查店长/员工、
  管理门店"一句话和两个前缀名，**没有端点、没有请求体、没有校验规则、没有错误文案** ——
  这轮把这些空白补成了明确口径（待回填进文档）
- **「员工」就是店长**（2026-09-18 用户明确）：`staff` 表只有 `role=0`（管理员）/ `role=1`（店长），
  `StaffRole` 枚举里**没有第三档**。"新建一个员工" = 建一个 `role=1` 的账号
- **"删"只做软停用**（`status=0`），不做硬删 —— 用户名因此**永久占用**，
  这是 `verify-admin.sh` 用带时间戳的账号名的原因（固定名字第 2 次跑必撞"重名 → 400"那条断言）
- **停用 / 降级 / 改门店 / 重置密码都让已签发的 token 失效**，四者用同一个原语：
  `auth:staff:invalidAfter:<id>` = 毫秒水位线，TTL = `jwt.expiration`。
  **用户只说了"停用"，另外三种是我补的** —— 它们属于同一个洞（token 里的 `role`/`storeId` 会变旧），
  只做停用等于留半个后门：把越权的管理员降级，他的票还能带着 `role=0` 活 24 小时
  - 失效文案选的是「账号已被停用**或权限已变更**，请重新登录」——
    只写"已被停用"的话，降级和改密的人会看到一句错话（Bug 20 的教训）
  - 代价：staff 请求的 Redis 从 1 次 GET 变 2 次（拦截器本来每次就要查一次黑名单，所以是 +1）
  - 已知边界见 **Bug 34**
- **`GET /api/stores` 一个字节没动**，含停业的列表另开 `/api/stores/all` ——
  老接口的语义是"能下单的店"，顾客下单页和员工建单页都指着它，`verify-stores.sh` 的 A2/A3 也钉着它。
  写接口按**方法**分（`GET` 放行、写拦），因为 `verify-stores.sh:286` 的 **B9b** 硬钉着
  "管理员读门店列表必须 200"
- **无自锁护栏是故意的**（用户拍板）：管理员可以停用/降级自己，也可以降级最后一个管理员 ——
  锁死了就 `down -v` 重建。**这是一个已知的、故意的缺口，不是漏了**
- **没有 V12 迁移。** `stores.status` 和 `staff` 的全部列 V1 就建好了，这轮纯粹是把已有的列接上接口
- **CI 从八个脚本变九个**（`.github/workflows/ci.yml`）：循环用的是 `scripts/verify-*.sh` 通配符，
  **新脚本不用改循环**，只有计数和措辞要人工跟着改

`scripts/verify-admin.sh` 的七组（静态计数 A36 / B13 / C9 / D32 / E20 / F8 / G6）。最值钱的几条：

- **B 段**（停用即失效）：**先证明这张票能用**再停用 —— 否则测的是"它本来就不能用"。
  停用后同一张票立刻 401、**重新启用不会复活旧票**、再次登录得 403「账号已被停用」
- **C 段**（降级即失效）靠两个状态码把两件事分开：同一张票在降级后是 **401**（票据失效），
  重新登录拿到的票打 `/api/staff` 是 **403**（身份不够）。**两句话不同，断言就必须不同**
- **E 段**（反方向闸门）九次越权尝试之后**逐项断言库里没有任何实际变更**（门店名/状态没动、
  没有多出账号、密码没被重置）—— 只断 403 的话，"报了错但真改了"照样能过
- **D 段**成对断言：门店停业后从 `GET /api/stores` **消失**，但仍在 `/all` 里 ——
  只断"消失"的话，把店整个删掉也能过

脚本头记了三条复现雷区：①中文请求体走 `postJson`/`putJson`；②**含中文的 SQL 必须走 `dbQ`（stdin）
而不是 `db()`（命令行参数）** —— `docker` 是原生 Windows exe，MSYS2 会把参数转成 GBK，
**静默返回空**，那是一发假绿；③准备段失败必须 `exit 1`。第 ② 条是 Bug 24 的同族新成员，
已写进 `scripts/README.md`。脚本本身是**累积式**的、不硬删：每跑一次留 2 行 `status=0` 的 staff
+ 1 行 `status=0` 的门店。

**首跑还顺手证伪了仓库里的一句断言。** 动手前想确认"你重启的这份有没有本轮的代码"，
第一版探针是"打新路由、无 token，回 401 就说明路由在" —— 结果**对照组把我自己证伪了**：
拿一个绝对不存在的路径 `GET /api/nonexistent-xyz` 去打，也回
`{"code":401,"message":"未登录"}`。原因是 `/api/**` 全被拦截器罩着，未匹配的路径落到静态资源
处理器上、照样过拦截器。所以 **401 不携带"这条路由存在吗"的信息**。

`ci.yml` 的"等后端就绪"那一步原先注释里写着这个 401 "一次证明三件事：**路由在**、拦截器在、
JSON 序列化在" —— 前两件成立，**第三件是假的**。已在该文件里更正（那段注释本身也是个
"探针以为自己证明了什么"的例子，和 **Bug 37** 同一个形状）。真正有效的探针是带一张
**有效 token** 去打，看它回 200 还是"资源不存在" —— 后来就是这么确认后端是新包的。

### 2026-09-18 抢券压测（本轮**没改业务代码**）

本轮唯一的产出是**度量**：新增 `scripts/loadgen/LoadGen.java` 与 `scripts/bench-coupon.sh`，
其余改动全在文档（根 `README.md` / `scripts/README.md` / 本文档 / 设计文档 §11）。
**生产代码一行没动** —— 所以下表里"没重跑"的那几项是有依据的没重跑，不是漏了。

| 验收门 | 结果 | 说明 |
|---|---|---|
| `bash scripts/verify-coupons.sh` | **32/32** | **跑完 24 轮压测之后**重跑。压测每轮都往券域写数据（新发 4 张券 + N 个顾客），这一条就是专门验它没把券域搞脏的 |
| `./mvnw -o test` | **172 项全绿** | domain 49 + application 120 + interfaces 3，`BUILD SUCCESS`。与 09-13 记的基线 **172 一致**，本轮既没加测试也没欠测试 |
| `bash scripts/bench-coupon.sh`（N=500 / STOCK=100） | **10/10**，两遍 | A1–A7 + A8(`checkNot`) + C1/C2。数字见根 `README.md` 的「性能」一节 |
| 并发曲线 8 点 × 3 遍 = **24 次运行** | **0 超卖** | 含一次机器离群（TPS 234 / p50 1171ms），依然 0 超卖 |
| 对照实验 3 配置 × 3–6 遍 = **13 次运行**（N=500） | **0 超卖** | 同上，26 次运行累计全部 `ok == STOCK`、`fail == N - STOCK` |

#### 补做：虚拟线程 / 连接池的对照实验

上一轮曲线**只是推断**出"Tomcat `max-threads=200` 和 Druid `max-active=20` 不是瓶颈"—— 依据是"参数大小对不上"。那是推理，不是实测。本轮补了真对照（N=500，每点 3–6 遍取中位，`STOCK=250`）：

| 配置 | 轮次 | 中位 TPS | 范围 | p50 中位 |
|---|---|---|---|---|
| A 平台线程 / Druid 20（基线） | 3 | 857 | 764–858 | 427 ms |
| B **虚拟线程** / Druid 20 | 4 | 885 | 762–915 | 325 ms |
| C 虚拟线程 / **Druid 50** | 6 | 929 | 851–1058 | 347 ms |

**先证明两件事真的生效了**，否则就是空跑：

- 虚拟线程：`jcmd <pid> Thread.print` 里 `http-nio-8081-exec-*` **归零**，只剩 `Acceptor` / `Poller`，平台线程总数约 44。**200 个线程那道闸整个消失了。**
- Druid 50：Druid 不在 INFO 打初始化配置，所以从 MySQL 侧取 `Max_used_connections` 高水位 —— `FLUSH STATUS` 清零后跑完是 **51**。顺带这一手**反证了之前一直是 20**：清零前那个值是 **21**（20 条连接 + 1 条命令行自己的）。

**结论，按证据强度分三档：**

1. **Tomcat `max-threads=200` —— 排除（有因果）。** B 组把线程闸拆了，吞吐纹丝不动（885 vs 857，两组范围完全重叠）。**闸拆了流量不变，它就不是墙。**
2. **Druid `max-active=20` —— 测不出来。** +5.0%，落在噪声里。**是"这台机器分辨不出 5%"，不是"两者没差别"。**
3. **12 核 —— 仍然是推断，没变。** 它没法靠"改个参数再跑"证伪。

**唯一稳定跑出方向来的信号是 p50**：427 → 325 ms（虚拟线程）。**而且三个配置的范围互相重叠** —— 这台机器的抖动（最大 ±22.3%）**比要量的效应还大**。所以本轮的诚实结论是：**Tomcat 线程数不是瓶颈（实锤），Druid 是不是瓶颈这台机器判不了。**

**顺带发现一个没改的优化点**：`CouponAppService.grabCoupon` 第一步是无条件的 `couponMapper.selectById(couponId)`，**每个请求都走一次** —— 500 并发 = 500 次 select + 约 250 次 insert ≈ **750 次 DB 往返**，其中约 1/3 是替那 250 个注定拿 400 的请求白查的。券活动是极热的小数据，缓存它就能砍掉这部分。**本轮只发现没改。**

**这一轮也是 Bug 32 的现场**：第一次量虚拟线程得到 443.88 TPS（对比文档里的 649，差点报成"虚拟线程更慢"），同一天重测基线才拦下来。**过程写在 Bug 32 里，那是本轮比 TPS 数字更值钱的产出。**

**没重跑的七个脚本**（`verify-orders` / `verify-price-write` / `verify-pricing-authority` /
`verify-race` / `verify-stores` / `verify-auth` 等）：它们的射程是业务代码，本轮业务代码零改动；
压测只调 `POST /api/coupons/{id}/grab`，射程内只有券域。**这是判断，不是实测** ——
下次任何人动了业务代码，这张表就整体作废。

**当场复认的一条既有口径**：无 token 调 `/api/prices` 和 `/api/orders`，**HTTP 状态码都是 200**，
`code` 才是 401（`{"code":401,"message":"未登录","data":null}`）。这正是 README 里"HTTP 恒为 200"
那一条 —— 所以压测脚本的守卫必须 grep 响应体，看状态码会一路绿到底。

**压测留下的脏数据**（两轮，都是实测的删除行数）：第一轮压测攒下 **5665 个**压测顾客
（`139` 开头 16 位）、**113 张** `BENCH-*` 券、连带的抢券记录 **6297 条**；
补做的对照实验又添了一批，第二轮清掉的是 **9500 个顾客 + 100 张 `BENCH-*` 券 + 14275 条抢券记录**。
它们**不影响任何断言**（压测每轮自己发新券、自己造人，券域测试也是自己建自己的数据）。
**两轮都已清理完毕**，清完都重跑了 `verify-coupons.sh` **32/32** 和 `verify-auth.sh` **36/36**；
终态回到 4 个顾客 / 12 张券 / 8 条抢券记录 / 6 张订单。

> 清理本身挖出一处**差点误伤**：库里存在 11 位的 139 号段顾客
> `13900000011` / `13900000012`，它们是 `verify-coupons.sh` 的**顾客A**，
> 3 张订单和 4 条抢券记录全挂在它名下。**判据只写 `like '139%'` 就会把它俩删掉**，
> 而且删完当轮脚本照样绿（下轮会重新注册）—— 要等下次跑 F 段真去用券时才炸。
> 靠 `length(phone) = 16` 躲开。这次是**删之前查出来**的，没变成 bug，
> 所以只修了文档（`scripts/README.md` 补了完整的三表清理步骤 + 四个交叉检查），
> 不为它新增 bug 条目 —— 但**近失和真失的根因是同一个**，
> 记住"139 号段不止一种形状"比记住一个编号有用。

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
