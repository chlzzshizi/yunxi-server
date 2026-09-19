# 云洗助手（yunxi-server）

多门店洗衣管理系统的全栈实现。后端 DDD 分层（5 模块）+ JWT 双身份鉴权 + 订单状态机（CAS 乐观锁）+ 优惠券高并发抢购；前端 Vue 3，员工端与顾客端两套人格。

实现过程中的 **48 条踩坑记录**在 [`docs/bug-record.md`](docs/bug-record.md) —— 那份文档比这份 README 更能说明这个项目做了什么。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21、Spring Boot 3.5.0 |
| 持久化 | MySQL 8（Flyway 迁移）、MyBatis、Druid 连接池 |
| 缓存 | Redis 7（抢券库存 + 登录黑名单） |
| 鉴权 | JWT，员工 / 顾客两套身份，`JwtInterceptor` 统一把守 |
| 前端 | Vue 3 `<script setup>` + Vue Router 4 + Bootstrap 5（CDN）+ Vite |
| 接口文档 | springdoc + Knife4j（开关与路径见 `yunxi-interfaces/src/main/resources/application.yml`） |

## 快速开始

前置：JDK 21、Docker、Node 18+。

### 1. 起中间件（MySQL + Redis）

```bash
docker compose up -d
```

> **必须在 Git Bash 里跑**（PowerShell 里的 `bash` 指向 WSL，那里既没有 docker 命令也连不上宿主机的 localhost 代理）。
>
> `up -d` 保留数据；`docker compose down -v` 会**连数据卷一起删掉**，下次启动重新跑迁移。

### 2. 起后端（:8081）

**IntelliJ**：直接跑 `yunxi-interfaces` 里的 `com.yunxi.YunxiApplication`。

**命令行**：

```bash
./mvnw -o -q install -DskipTests          # 先在仓库根目录装一遍
cd yunxi-interfaces && ../mvnw spring-boot:run
```

> 第一步不能省。直接从 `yunxi-interfaces` 跑 `spring-boot:run` 会报
> `ClassNotFoundException: StaffAuthAppService` —— 它要的兄弟模块没进本地仓库。
> IntelliJ 自己解析 reactor，不需要这一步。
>
> `mvnw` 认 `JAVA_HOME`（本项目开发机上是 `D:\JDK\jdk21`），**不看 PATH 上的 `java`** ——
> 那上面很可能还是 1.8。

**Flyway 在应用启动时执行迁移**，所以：**改了迁移文件必须重启后端**，否则库还停在旧版本，症状是"代码明明改了却报 `Unknown column`"。

### 3. 起前端（:5173）

```bash
cd yunxi-web
npm install
npm run dev
```

打开 **`http://localhost:5173`**，**不要用 `127.0.0.1:5173`** —— Vite 开发服务器只绑了 `[::1]`，`127.0.0.1` 连不上。`/api` 由 Vite 代理到 `localhost:8081`。

## 演示账号

| 账号 | 密码 | 能做什么 |
|---|---|---|
| `manager` | `admin123` | **店长**（store_id=1）。订单全流程、定价改价、发券 —— 演示主用这个 |
| `admin` | `admin123` | **管理员**。按设计只负责"人与店"：**员工管理**与**门店管理** —— 后端 2026-09-18 实现，前端页面 2026-09-19 补上（`/staff/admin/staff`、`/staff/admin/stores`，登录后自动落到前者的页面上）。订单与定价写接口对他是 403，那是有意口径（2026-09-11 起） |
| 顾客 | — | 顾客端**自行注册**（手机号 + 密码，注册即登录）。库里已有的顾客是脚本冒烟时建的，密码没有记录，别去猜 |

顾客端和员工端是**两套人格**，token 存在不同的 localStorage 槽里，互不挤占，可以同时登录。数据库里的密码是 BCrypt 哈希，上面两个是本地开发的演示凭据。

## 项目结构

```
yunxi-common          枚举与工具，不依赖任何模块
yunxi-domain          聚合根、领域规则（状态机、精洗派生、折扣率校验）
yunxi-infrastructure  MyBatis、PO、Flyway 迁移
yunxi-application     应用服务，编排与事务
yunxi-interfaces      Controller、DTO、拦截器、全局异常处理
yunxi-web             前端（独立 npm 工程）
```

依赖方向是**硬约束**：

```
interfaces → application → domain ← infrastructure
```

`interfaces` 不得引用 `domain` 对象，出参一律走 application 层 DTO；`domain` 不依赖 Spring 和 MyBatis。**券模块是唯一的例外**（没有 domain 层，`CouponPO` 直接进应用层），这条偏离是记在案的主动简化。

## 验收

**单元测试**（Mockito，不碰 Spring 也不碰数据库）：

```bash
./mvnw -o test
```

**真机接口脚本**（要后端在 8081 起着 + 容器在跑；从仓库根目录跑，顺序随意）：

```bash
bash scripts/verify-orders.sh
```

九个脚本分别证明什么、断言数是多少、跑之前要注意哪些坑，见 [`scripts/README.md`](scripts/README.md)。**脚本会往库里写数据**，要干净数据只能 `down -v` 重建（哪些脚手架必须留着，那份 README 里有清单）。

## 性能

抢券接口（`POST /api/coupons/{id}/grab`）的压测结果。压测器是 JDK 21 虚拟线程自写的（`scripts/loadgen/LoadGen.java`，零依赖），编排在 `scripts/bench-coupon.sh` —— **跑法、断言和必须一起读的边界见 [`scripts/README.md`](scripts/README.md)**。

**500 人抢 100 张券**（两遍）：**872 / 777 TPS**，p50 413 / 497 ms，p99 536 / 617 ms，**0 超卖**。

**并发曲线**（每点 3 遍取中位，共 24 次运行）：

| 并发 | 10 | 20 | 25 | 50 | 100 | 200 | 300 | 500 |
|---|---|---|---|---|---|---|---|---|
| TPS | 320 | 434 | 525 | 594 | **670** | 649 | 568 | 733 |
| p50 | 19 ms | 29 ms | 32 ms | 53 ms | 93 ms | 188 ms | 350 ms | 538 ms |

**拐点在 100 并发左右**：10→100 吞吐翻倍，100→500 只涨 9%。过了拐点 `p50 ≈ N 毫秒` —— 加并发买到的基本是排队时间，不是吞吐。**24 次运行全部不超卖**，包括一次机器打嗝的离群（TPS 掉到 234、p50 冲到 1171 ms）依然 0 超卖：性能抖动和正确性完全解耦。

曲线原本只是**推断**出两个瓶颈候选。2026-09-18 补了真正的对照实验（N=500，每点 3–6 遍取中位）：**Tomcat `max-threads=200` 实测排除** —— 换成虚拟线程后 200 个线程那道闸整个消失，吞吐纹丝不动（885 vs 857，范围完全重叠）；**Druid `max-active=20` 测不出来** —— 池子开到 50 只有 +5%，落在噪声里。剩下的最可能是 12 核本身，但**这是推断不是实测**。**反过来读也成立：测不出来 ≠ 没差别 —— 这台机器的抖动（最大 ±22%）比想测的效应还大。**

⚠️ 读这些数字要带上边界：压测端与服务端**同机 12 核**（压测器、Spring Boot、MySQL、Redis 抢同一批核，测出来的是**下限**）；Tomcat / Druid 全是默认配置；DEBUG 日志已用 `-Dlogging.level.com.yunxi=warn` 压掉；**单次采样在这个规模下抖动最大到 ±22%**（原先写的 ±16% 偏乐观），要报就报多次的中位数。还有一条没消掉的：两条启动路径都会加 `-XX:TieredStopAtLevel=1`，**这些数字全是 C1-only 编译下测的**，偏低多少没人量过。

> 顺带一提：第一版压测器每轮开一个新 JVM，把 JVM 冷启动算进了请求延迟，跑出过一组"三轮都很稳定" 的 279 / 305 / 303 TPS —— 假的。真因是三轮一样冷。记在 [`docs/bug-record.md`](docs/bug-record.md) Bug 31。
>
> 同一个病在**服务端**又犯了一次：重启后第一次跑 443.88 TPS、热起来中位 832，**同一份配置差 1.87 倍**。差一点就把"虚拟线程更慢"写进报告。Bug 32。

## 几个容易踩的地方

- **HTTP 状态码恒为 200**，成功失败一律看响应体里的 `code`。前端 `request.js` 统一解包，业务代码只分支 `body.code`。
- **同一个概念有多套表示**：枚举在**返回体**里是名字（`"PENDING_PAY"`），在**查询参数**里是数字（`?status=2`），支付方式在参数里是小写码（`?payMethod=cash`）。前端最容易在这里翻车 —— Bug 28 就是这么来的：`?payMethod=undefined` 发出去了，`npm run build` 全绿，到后端才 400。
- **列表接口的 `items` 恒为 `[]`**，只有详情返回真明细；`OrderItemView` 没有 `subtotal`，前端自己 `unitPrice × quantity`。
- **`coupons.discount` 是比率不是百分比**（`0.50` = 五折）。
- **409 = 别人刚改过这一单**（CAS 冲突），处理方式是**重新读取**，不是重试。
- **门店不做数据隔离**：所有店长可以管理所有门店的订单，`store_id` 只是地理位置。
- **网单的 `store_id` 可以是 NULL**（顾客可以不指定门店），展示时要兜底。

## 文档

- [`docs/bug-record.md`](docs/bug-record.md) —— 48 条 bug 的现象 / 根因 / 修法，以及每轮"测试通过的功能"清单
- [`scripts/README.md`](scripts/README.md) —— 验收脚本怎么跑、证明了什么、有哪些巨坑
- 设计文档《云洗助手重构设计文档》**不在本仓库内**，是独立维护的规格说明
