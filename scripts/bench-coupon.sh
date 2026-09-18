#!/bin/bash
# 抢券接口压测（真机：MySQL + Redis + 8081）
#
# 证明的是"高并发"这四个字里**性能**的那一半。八个 verify-* 脚本证明的是
# 正确性（不超卖、去重、回滚），但一个都没测过吞吐 —— 全仓库检索
# TPS|QPS|吞吐|响应时间 零命中。这个脚本补的就是它。
#
# 用法：bash scripts/bench-coupon.sh                      从任何目录都行
#       N=1000 STOCK=200 bash scripts/bench-coupon.sh     改并发数和库存
# 前置：docker compose up -d 且后端在 8081 起着（后端怎么起见根 README）
#
# ⚠️ 后端启动时必须压掉 DEBUG 日志，否则你测的是 logback 不是 Redis：
#     cd yunxi-interfaces && ../mvnw spring-boot:run \
#       -Dspring-boot.run.jvmArguments="-Dlogging.level.com.yunxi=warn"
#   application.yml:50 是 logging.level.com.yunxi: debug，而 MyBatis mapper 的
#   包名就在 com.yunxi 下 —— 500 并发时每条 selectById/insert 都会打日志。
#   （走 -D 覆盖，不动 application.yml。）
#
# ⚠️ 四轮**必须挤在同一个 JVM 里**跑（LoadGen 的参数就是这么设计的）。
#   2026-09-18 第一版每轮开一个 JVM，跑出三轮 TPS 279/305/303，
#   当时当成"服务端吞吐稳定"写进了报告 —— 是错的。拿一个瞬时响应的对照
#   服务端一量：打它 N=1 要 116ms，**比打真实应用还慢**。真因是 JVM 冷启动
#   （同一个 JVM 连跑四轮 N=50：p50 = 344ms → 32ms → 23ms → 58ms）。
#   三轮数字一样不是因为稳定，是因为**一样冷**。
#
# ⚠️ 编码：本脚本内的中文是安全的（printf/变量/重定向都是字节安全的，见
#   verify-stores.sh 文件头）。但**断言 LoadGen 的输出必须用 ASCII** ——
#   Java 的 stdout 在这台机器上不是 UTF-8（实测打印中文是乱码），
#   所以 LoadGen 把响应里的中文消息转成了 URL 百分号编码。A7/A8 那两条断言
#   比的是编码后的字节，注释里标了它对应哪句中文。
#
# 脚本会往库里写 N 个顾客和 4 张券（累积型，同 verify-coupons.sh 的约定：
# 每轮新发不复用 id，要干净数据只有 down -v）。

set -u

BASE=http://localhost:8081
PASS=0; FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="${TMPDIR:-/tmp}/yunxi-e2e"; mkdir -p "$TMP/bench"

N=${N:-500}          # 并发数
STOCK=${STOCK:-100}  # 每张券的库存
# 给 LoadGen 的 JVM 加参数用（做配置对比实验时用过），平时是空的。
# 故意不加引号：它是一串空格分隔的 flag，就是要让它按空白拆开
LOADGEN_OPTS=${LOADGEN_OPTS:-}

check() {  # check "用例名" "响应" "期望片段"
  if echo "$2" | grep -q "$3"; then
    echo "  [OK]   $1"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1"; echo "         期望含: $3"; echo "         实际: $2"; FAIL=$((FAIL+1))
  fi
}
checkNot() {  # checkNot "用例名" "响应" "不该出现的片段"
  if echo "$2" | grep -q "$3"; then
    echo "  [FAIL] $1"; echo "         不该出现: $3"; echo "         实际: $2"; FAIL=$((FAIL+1))
  else
    echo "  [OK]   $1"; PASS=$((PASS+1))
  fi
}
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
res() { echo "$1" | grep '^RESULT ' | grep "\"label\":\"$2\""; }   # res "<整段输出>" <轮次标签>
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }
redis_get() { docker exec yunxi-redis redis-cli get "$1" 2>/dev/null | tr -d '\r'; }

echo "########## 前置守卫 ##########"

# 后端在不在。**看 body 不看 HTTP 状态码** —— 本项目 HTTP 恒为 200，
# 连异常都走 GlobalExceptionHandler 转成 Result.fail（没有 @ResponseStatus）。
# 无 token 访问一个受保护的端点会拿到 {"code":401,...}，说明路由和拦截器都在；
# 拿到 404 说明跑的是不带这个端点的旧包（scripts/README.md 记的判据）。
PROBE=$(curl -s -m 5 "$BASE/api/prices")
if ! echo "$PROBE" | grep -q '"code":401'; then
  echo "==> [准备失败] 后端不在预期状态：GET /api/prices 期望 body 含 \"code\":401（未登录）"
  echo "             实际: $PROBE"
  echo "    起后端：cd yunxi-interfaces && ../mvnw spring-boot:run \\"
  echo "              -Dspring-boot.run.jvmArguments=\"-Dlogging.level.com.yunxi=warn\""
  exit 1
fi

if [ "$(docker exec yunxi-redis redis-cli ping 2>/dev/null | tr -d '\r')" != "PONG" ]; then
  echo "==> [准备失败] Redis 不通：docker exec yunxi-redis redis-cli ping 没回 PONG"
  exit 1
fi

# 虚拟线程要 JDK 21。PATH 上的 java 在这台机器上是 1.8，所以优先认 JAVA_HOME
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"; JAVA="${JAVA:-java}"
JVER=$("$JAVA" -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
if [ "${JVER:-0}" -lt 21 ]; then
  echo "==> [准备失败] LoadGen 要 JDK 21+（虚拟线程），当前 $JAVA 是 $JVER"
  echo "    PATH 上的 java 通常是 1.8；把 JAVA_HOME 指到 jdk21"
  exit 1
fi
echo "  后端在、Redis 在、java=$JVER（$JAVA）"

echo
echo "########## 准备：$N 个不同顾客 ##########"
# 手机号 = 139 + 10 位 epoch + 3 位序号 = 16 字符（VARCHAR(20) 够）。
# 带 epoch 是为了同一个库上重复跑不撞 uk_phone。
# 后端对手机号**没有格式校验**（CustomerAuthAppService 只判非空），所以这样能过。
RUN_TAG=$(date +%s)
for ((i = 0; i < N; i++)); do
  printf '139%s%03d\n' "$RUN_TAG" "$i"
done > "$TMP/bench/phones.txt"

# 注册 → 已注册则登录（同 verify-coupons.sh:77-92）。
# BCrypt 一次约 100ms，串行 500 次要 50 秒，所以分批并发拿。
# 姓名用 ASCII：这个字段后端其实不读（register 只取 phone/password），
# 但命令行参数里的非 ASCII 会被 MSYS2 转 GBK —— 见 verify-stores.sh 文件头坑 1
BATCH=20
i=0
while read -r phone; do
  # 先把下标钉进 idx 再 fork：子 shell 是 fork 那一刻的快照，而 i 紧接着就自增了，
  # 直接在子 shell 里用 $i 是在赌 fork 和自增的先后
  idx=$i
  (
    # </dev/null：子 shell 不能继承 while 的 stdin，否则可能把还没读的行吃掉
    name="BenchCust$idx"
    resp=$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
      -d "{\"name\":\"$name\",\"phone\":\"$phone\",\"password\":\"123456\"}") </dev/null
    tok=$(jqf "$resp" token)
    if [ -z "$tok" ]; then
      resp=$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
        -d "{\"phone\":\"$phone\",\"password\":\"123456\"}") </dev/null
      tok=$(jqf "$resp" token)
    fi
    echo "$tok" > "$TMP/bench/tok-$idx.txt"
  ) </dev/null &
  i=$((i + 1))
  if [ $((i % BATCH)) -eq 0 ]; then wait; fi
done < "$TMP/bench/phones.txt"
wait

for ((j = 0; j < N; j++)); do cat "$TMP/bench/tok-$j.txt" 2>/dev/null; done > "$TMP/bench/tokens.txt"
# grep -c 无论如何都会打印一个数字（0 行时也打 0）—— 所以**不要**再跟 || echo 0，
# 那会拼出 "0\n0"，下一行的 -lt 直接报 integer expression expected
GOT=$(grep -c '^ey' "$TMP/bench/tokens.txt")
if [ "$GOT" -lt "$N" ]; then
  echo "==> [准备失败] 只拿到 $GOT 个 token，需要 $N 个。先看注册接口是不是在报错："
  curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
    -d '{"phone":"13900000000","password":"123456"}'
  echo
  exit 1
fi
CUST1=$(head -1 "$TMP/bench/tokens.txt")
echo "  拿到 $GOT 个顾客 token"

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)
if [ -z "$MGR_T" ]; then echo "==> [准备失败] 店长登录没拿到 token（manager/admin123）"; exit 1; fi

NOW_START=$(date -d '-1 hour' +%Y-%m-%dT%H:%M:%S)
NOW_END=$(date -d '+1 day'  +%Y-%m-%dT%H:%M:%S)
# 发券走 API：字段校验 + Redis 库存预热（CouponAppService.createCoupon 里那句
# SET coupon:stock:{id}）都是真的。只有"时间到了"快进 —— 券状态平时由每分钟一次
# 的 CouponStatusTask 推进，压测等不起那一分钟
mk_body() {  # mk_body <ASCII 名称> <输出文件>
  printf '{"name":"%s","discount":0.50,"totalStock":%s,"startTime":"%s","endTime":"%s"}' \
    "$1" "$STOCK" "$NOW_START" "$NOW_END" > "$2"
}
new_coupon() {  # new_coupon <请求体文件> → 券 id
  jqf "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
        -H "Authorization: Bearer $MGR_T" --data-binary @"$1")" id
}
mk_body "BENCH-WARMUP1" "$TMP/bench/w1.json"
mk_body "BENCH-WARMUP2" "$TMP/bench/w2.json"
mk_body "BENCH-MEASURE" "$TMP/bench/meas.json"
mk_body "BENCH-CONTROL" "$TMP/bench/ctrl.json"
CPN_W1=$(new_coupon "$TMP/bench/w1.json")
CPN_W2=$(new_coupon "$TMP/bench/w2.json")
CPN_M=$(new_coupon "$TMP/bench/meas.json")
CPN_C=$(new_coupon "$TMP/bench/ctrl.json")
for v in CPN_W1 CPN_W2 CPN_M CPN_C; do
  if [ -z "${!v}" ]; then echo "==> [准备失败] $v 为空，发券没成功，停止"; exit 1; fi
done
db "update coupons set status=2 where id in ($CPN_W1,$CPN_W2,$CPN_M,$CPN_C);" > /dev/null
# 每张券的库存键此时都是 $STOCK（createCoupon 预热写的），四张互不干扰
echo "  四张券：预热1=$CPN_W1 预热2=$CPN_W2 测量=$CPN_M 对照=$CPN_C  每张库存=$STOCK"

echo
echo "########## 四轮爆发（同一个 JVM，前两轮是预热）##########"
# 预热**两轮**不是保险起见：实测同一 JVM 连跑，第一轮 p50 344ms、第二轮 32ms、
# 第三轮 23ms，第二轮还没完全收敛。两轮之后测量轮才是热的。
#
# 第 3 轮（control）故意**不用** N 个不同顾客，而是同一个顾客的 token 重复 N 次。
# 它证明的是另一件事：去重挡得住。Redis Set + uk_coupon_customer 两道闸，
# 结果必须恰好 1 个成功 —— 如果这轮成功数 > 1，说明两处去重都漏了
for ((i = 0; i < N; i++)); do echo "$CUST1"; done > "$TMP/bench/one-token.txt"

OUT=$("$JAVA" $LOADGEN_OPTS "$SCRIPT_DIR/loadgen/LoadGen.java" "$N" \
  warmup1 "$BASE/api/coupons/$CPN_W1/grab" "$TMP/bench/tokens.txt" \
  warmup2 "$BASE/api/coupons/$CPN_W2/grab" "$TMP/bench/tokens.txt" \
  measure "$BASE/api/coupons/$CPN_M/grab"  "$TMP/bench/tokens.txt" \
  control "$BASE/api/coupons/$CPN_C/grab"  "$TMP/bench/one-token.txt" 2>&1)
echo "$OUT"

echo
echo "########## 断言 ##########"
RES_W1=$(res "$OUT" warmup1)
RES_W2=$(res "$OUT" warmup2)
RES_M=$(res "$OUT" measure)
RES_C=$(res "$OUT" control)
for v in RES_W1 RES_W2 RES_M RES_C; do
  if [ -z "${!v}" ]; then
    echo "==> [准备失败] $v 没拿到，LoadGen 没跑成功，停止"
    echo "  （n=$N 但 token 文件不够行，或后端中途挂了，都会是这样）"
    exit 1
  fi
done

echo "--- 测量轮：不超卖 ---"
# 恰好等于库存：多了是超卖，少了是漏发 —— 两个方向都要卡死
check "A1 成功数恰好等于库存 $STOCK（多了是超卖，少了是漏发）" \
  "$(jqf "$RES_M" code200)" "^$STOCK$"
check "A2 失败数 = $N - $STOCK = $((N - STOCK))" \
  "$(jqf "$RES_M" code400)" "^$((N - STOCK))$"
check "A3 没有请求在传输层就挂了（err=0 说明 $N 个连接全部拿到了 HTTP 响应）" \
  "$(jqf "$RES_M" err)" "^0$"
check "A4 库里 coupon_grabs 恰好 $STOCK 行" \
  "$(db "select count(*) from coupon_grabs where coupon_id=$CPN_M;")" "^$STOCK$"
check "A5 这 $STOCK 行属于 $STOCK 个不同顾客（没有一人中两次）" \
  "$(db "select count(distinct customer_id) from coupon_grabs where coupon_id=$CPN_M;")" "^$STOCK$"

echo "--- 测量轮：扣减行为符合设计 ---"
# 这一条是全项目**第一次有断言去证明那句注释**。CouponAppService.java:88-91 写着
# "不加回：并发下减了再加不是原子操作；库存键保留负数表示超出多少人想抢"。
# $N 个不同顾客全部通过 isMember 检查（Set 一开始是空的），所以恰好发生 $N 次
# DECR：$STOCK - $N。落点不是这个数，就说明"保留负数"那条设计没按注释走。
check "A6 库存键停在 $((STOCK - N))（=$STOCK-$N，验证「减了不回补」的设计）" \
  "$(redis_get "coupon:stock:$CPN_M")" "^$((STOCK - N))$"

echo "--- 测量轮：400 的原因是「抢完」而不是「重复抢」---"
# 光看 code 计数区分不出这两种 400，而它们的含义完全相反：
# "已抢完"是库存真的发完了（正常），"你已经抢过了"意味着去重误伤了不同顾客（严重）。
# 下面两串是中文经 URL 百分号编码后的字节，LoadGen 输出里就是这么写的：
#   %E5%B7%B2%E6%8A%A2%E5%AE%8C                          = 已抢完
#   %E4%BD%A0%E5%B7%B2%E7%BB%8F%E6%8A%A2%E8%BF%87%E4%BA%86  = 你已经抢过了
# （Java 的 stdout 在这台机器上不是 UTF-8，所以走编码而不是直接打中文）
MSGS_M=$(echo "$OUT" | grep '^MSG measure ')
check "A7 $((N - STOCK)) 条 400 全是「已抢完」" \
  "$MSGS_M" "^MSG measure code400 %E5%B7%B2%E6%8A%A2%E5%AE%8C $((N - STOCK))$"
checkNot "A8 没有一条 400 是「你已经抢过了」（$N 个不同顾客不可能重复抢）" \
  "$MSGS_M" "%E4%BD%A0%E5%B7%B2%E7%BB%8F%E6%8A%A2%E8%BF%87%E4%BA%86"

echo "--- 对照轮：去重挡得住 ---"
check "C1 同一顾客并发 $N 次，恰好成功 1 次" "$(jqf "$RES_C" code200)" "^1$"
check "C2 库里 coupon_grabs 只有 1 行" \
  "$(db "select count(*) from coupon_grabs where coupon_id=$CPN_C;")" "^1$"
# C 轮的库存键**故意不断言**：被 Set 挡下的请求不 DECR、撞唯一键的又 INCR 还回，
# 所以"通过 isMember 的请求数"本身不确定，落点不唯一。硬断言会变成一个
# 随并发抖动而假红的用例 —— 假红的用例比没有用例更坏，它会教人忽略红色

echo
echo "########## 结果 ##########"
# 四轮并排。前两轮和测量轮的差距就是"预热够不够"的证据 ——
# 差距大说明 JIT 还没热透，那个测量值不能采信，要加预热轮重跑
printf '  %-10s %12s %12s %12s\n' 轮次 TPS P99_us p50_us
for pair in "预热1:RES_W1" "预热2:RES_W2" "测量:RES_M" "对照:RES_C"; do
  lbl=${pair%%:*}; var=${pair##*:}; line=${!var}
  printf '  %-10s %12s %12s %12s\n' "$lbl" "$(jqf "$line" tps)" \
    "$(jqf "$line" p99_us)" "$(jqf "$line" p50_us)"
done
echo
echo "  测量轮原始数据：$RES_M"
echo "  测量轮 400 的构成：$(echo "$MSGS_M" | grep -c 'code400') 种 message"
echo
echo "  ⚠️ 数字的边界（写进报告时必须带上）："
# 不用 nproc：Git Bash 里不一定有，Windows 自己会给 NUMBER_OF_PROCESSORS
echo "     · 压测端和服务端同机 ${NUMBER_OF_PROCESSORS:-?} 核，LoadGen 的 JVM 和 Spring Boot、"
echo "       MySQL、Redis 抢同一批核 —— 测出来的是**下限**"
echo "     · Tomcat 默认 max-threads=200（application.yml 没配线程池），"
echo "       $N 并发里有 $((N > 200 ? N - 200 : 0)) 个在 accept 队列排队"
echo "     · Druid max-active=20，每次 selectById 都要抢连接"
echo "     · DEBUG 日志必须已关（见脚本头），否则测的是 logback"
echo "     · JIT 已由前两轮预热吸收（这是同一个 JVM 里的第 3 轮），"
echo "       判断依据就是上面前两行和测量行的差距"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
