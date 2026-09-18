#!/bin/bash
# 并发保护（CAS）真机验证 —— 确定性复现 409，不靠"碰运气并发"
#
# 原理：
#   1. 另开一个 MySQL 会话，把订单行 SELECT ... FOR UPDATE 锁住 3 秒
#   2. 同时发两个 next 请求：两个都读完（读快照不阻塞，都看到 status=3），
#      然后都卡在 UPDATE 上等锁
#   3. 锁一放，第一个 UPDATE 命中 status=3 → 推进成功；
#      第二个 UPDATE 的 WHERE status=3 此时已不成立 → 影响 0 行 → 409
#   这就是"读-改-写"竞态的真实样子，只不过我们把时间窗从微秒拉长到了秒。
#
# 用法：bash scripts/verify-race.sh      从任何目录都行
# 前置：docker compose up -d  且后端已在 8081 起着

BASE=http://localhost:8081
MYSQL="docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi"

# 中间产物写临时目录，不写死在 C:\tmp —— 脚本原住在那个目录，搬进仓库后
# 写死路径就等于"换个地方跑就崩"，而且 /c/tmp 这个 MSYS 写法在 Linux 上根本不存在
TMP="${TMPDIR:-/tmp}/yunxi-e2e"; mkdir -p "$TMP"
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
# --default-character-set=utf8mb4：mysql 命令行默认按 latin1 收发，
# 读中文会整串变 ?????、写中文会存成双重编码 —— 说明见 verify-stores.sh 文件头
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)

# 顾客注册 → 已注册则登录，返回 token。
# 姓名用 ASCII：Git Bash 会把 shell 里的中文按 GBK 发出去，后端按 UTF-8 解析会 400
# （这是脚本的锅不是后端的；中文经文件投递的用例见 verify-stores.sh 的 C1）
# 函数体在本仓库有**六份拷贝**（彼此逐字一致）：verify-orders.sh /
# verify-coupons.sh / verify-price.sh / verify-price-write.sh /
# verify-pricing-authority.sh / verify-race.sh —— 就是下面这一个函数。
# 脚本之间不互相 source：六份拷贝是故意的，要的就是"单跑任何一个都成立"。
# md5（从 `login_or_register() {` 到收尾的 `}`）= 5d383e42ede9
# （复核命令见 scripts/README.md 的"六份拷贝"一节；改任何一份都要同步改六份）
login_or_register() {
  local name=$1 phone=$2 resp token
  resp=$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"phone\":\"$phone\",\"password\":\"123456\"}")
  token=$(jqf "$resp" token)
  if [ -z "$token" ]; then
    resp=$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
      -d "{\"phone\":\"$phone\",\"password\":\"123456\"}")
    token=$(jqf "$resp" token)
  fi
  echo "$token"
}

# 顾客脚手架：**自己建**（**Bug 41** 的修法）。
# 原写法是 `select id from customers order by id limit 1` —— 随手取库里 id 最小的那行，
# 而本脚本**一句建档语句都没有**。于是：
#   · 九连跑能过，纯粹因为它排第 8 位，前面七个脚本已经建过顾客了
#   · 单跑在空库上必塌（好在是下面那条守卫的**诚实**失败，不是假绿）
# 这和 Bug 38 / Bug 39 是**同一个形状**：把"执行顺序"当成了"本脚本的前提"。
# 顺带把"借一个别人的顾客"换成"用自己的" —— 订单挂在谁名下不再取决于谁先跑过。
# 号段 13900000021 归本脚本（91=定价三兄弟、01/02=orders、11/12=coupons）
CUST_T=$(login_or_register RaceCust 13900000021)
CID=$(db "select id from customers where phone='13900000021';")
# id 判**形状**不判有无：MySQL 一挂，db() 这个吞 stderr 的写法会返回空串，
# 而空串照样能拼出 {"customerId":,} 这种畸形 JSON（Bug 38/39 的教训）
case "$CID" in
  ''|*[!0-9]*)
    echo "==> [准备失败] 顾客脚手架没就绪：票长 ${#CUST_T}、id='$CID'"
    echo "             后端在 8081 吗？13900000021 能不能注册/登录？"; exit 1;;
esac

PASS=0; FAIL=0

# 建单 → 支付 → 推进到 3（洗后付拦不到，先付全额）
#
# ⚠️ 分类必须用**叶子**（11=衬衫）：1-4 是父分类，价目表里没有它们，
#    后端算价会直接 400 —— 那样订单根本建不出来，OID 就是空的。
#    （2026-09-10 后端算价改造后本脚本曾静默失效，见 bug-record）
ORDER=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MGR_T" \
  -d "{\"source\":1,\"customerId\":$CID,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}")
OID=$(jqf "$ORDER" id)

# 准备阶段失败要立刻喊停。否则 OID 为空 → 两个请求都打 /api/orders//next → 404 →
# 最后报出来的是"[FAIL] 期望恰好 1 个 200 + 1 个 409"，把**准备失败误诊成并发失败**
if [ -z "$OID" ]; then
  echo "==> [准备失败] 建单没成功，拿不到订单 id。响应：$ORDER"; exit 1
fi

# 金额必须是价目表算出来的**全额**（衬衫普洗 15.00）：pay 只收"全额或 0"
curl -s -X POST "$BASE/api/orders/$OID/pay?payMethod=cash&amount=15.00" -H "Authorization: Bearer $MGR_T" > /dev/null
curl -s -X POST "$BASE/api/orders/$OID/next" -H "Authorization: Bearer $MGR_T" > /dev/null

STATUS=$(db "select status from orders where id=$OID;")
echo "订单 id=$OID  操作前状态=$STATUS  （期望 3=洗涤中）"
if [ "$STATUS" != "3" ]; then
  echo "==> [准备失败] 订单没推进到 3（支付或 next 被拒），竞态用例的前置条件不成立"; exit 1
fi

echo "持锁 3 秒 + 并发两个 next ..."
$MYSQL -e "BEGIN; SELECT id FROM orders WHERE id=$OID FOR UPDATE; SELECT SLEEP(3); COMMIT;" > /dev/null 2>&1 &
sleep 1
curl -s -X POST "$BASE/api/orders/$OID/next" -H "Authorization: Bearer $MGR_T" > "$TMP/race-1.json" &
curl -s -X POST "$BASE/api/orders/$OID/next" -H "Authorization: Bearer $MGR_T" > "$TMP/race-2.json" &
wait

R1=$(cat "$TMP/race-1.json"); R2=$(cat "$TMP/race-2.json")
echo
echo "请求1: $R1"
echo "请求2: $R2"
echo
# "只推进一格"**必须是一条断言，不能是打印**。原写法把它写在括号里就完了，
# 意思是：万一两个请求都成功（丢更新没被挡住），只要下面那条碰巧过，
# "状态被推进了两格"这件事**没有任何东西在守**。
# 和 Bug 38 是同一个形状：**把结论印出来，当成验过了**。
STATUS_AFTER=$(db "select status from orders where id=$OID;")
if [ "$STATUS_AFTER" = "4" ]; then
  echo "  [OK]   最终状态=4（待出厂）—— 只推进了一格"; PASS=$((PASS+1))
else
  echo "  [FAIL] 最终状态=$STATUS_AFTER，期望 4（待出厂）"
  echo "         推进两格 = 两个 next 都生效了，丢更新没被挡住"; FAIL=$((FAIL+1))
fi

OK=0; CONFLICT=0
echo "$R1$R2" | grep -q '"code":200' && OK=1
echo "$R1$R2" | grep -q '"code":409' && CONFLICT=1
if [ $OK -eq 1 ] && [ $CONFLICT -eq 1 ]; then
  echo "  [OK]   恰好一个 200、一个 409 —— CAS 挡住了第二次写"; PASS=$((PASS+1))
else
  echo "  [FAIL] 期望恰好 1 个 200 + 1 个 409"; FAIL=$((FAIL+1))
fi

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"

# 退出码就是断言结果（**Bug 40** 的修法）。原写法连 FAIL 变量都没有 ——
# 并发那条断言红了也只印一行 [FAIL]，脚本照样退 0，CI 打 OK。
# 这一步原本被注释成"不报性能数字"，但它报的是**正确性**断言，该失败就得失败。
[ $FAIL -eq 0 ]
