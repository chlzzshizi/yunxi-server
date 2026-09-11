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
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N -e "$1" 2>/dev/null | tr -d '\r'; }

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)
CID=$(db "select id from customers order by id limit 1;")

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
echo "最终状态=$(db "select status from orders where id=$OID;")  （期望 4=待出厂，**只推进一格**）"

OK=0; CONFLICT=0
echo "$R1$R2" | grep -q '"code":200' && OK=1
echo "$R1$R2" | grep -q '"code":409' && CONFLICT=1
if [ $OK -eq 1 ] && [ $CONFLICT -eq 1 ]; then
  echo "==> [OK] 一个成功、一个 409 —— 丢更新被挡住了"
else
  echo "==> [FAIL] 期望恰好 1 个 200 + 1 个 409"
fi
