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

---

## 测试通过的功能

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
