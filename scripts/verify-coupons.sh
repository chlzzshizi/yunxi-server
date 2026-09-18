#!/bin/bash
# 券-订单抵扣接口验收（真机：MySQL + Redis + 8081）
#
# 证明的是"券和订单接起来到底对不对"：折后价真的落库了吗、券真的烧掉了吗、
# 使用记录三列真的写上了吗、失败的时候订单真的跟着回滚了吗。
#
# 用法：bash scripts/verify-coupons.sh      从任何目录都行
# 前置：docker compose up -d  且后端已在 8081 起着
#
# ⚠️ 两类编码坑（要改这个脚本，先读 verify-stores.sh 的文件头）
#   1. **请求体/命令行参数**里的非 ASCII 会被 MSYS2 那一侧按 GBK 处理，
#      `curl -d '{"name":"中文"}'` 发出去的就是乱码。所以本脚本里：
#      curl 的 body、db() 的 SQL **一律只用 ASCII**。
#      中文券名走 printf 的 \x 转义字节（脚本源码纯 ASCII）+ 文件投递。
#      （grep 的 pattern 是例外：它由 MSYS2 的 coreutils 经手，中文可用 ——
#        下面那些中文断言就是这么写的，和 verify-orders.sh 一样）
#   2. mysql 客户端默认按 latin1 收发，每个 db 调用都必须带
#      --default-character-set=utf8mb4。删掉之后中文两个方向都坏，
#      而且**双重编码在"读"的时候会抵消回去** → 库里的坏数据 SELECT 出来是好的
#      → 断言假绿。所以中文一律**断言 hex 字节**，不看读出来是什么。

BASE=http://localhost:8081
PASS=0; FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="${TMPDIR:-/tmp}/yunxi-e2e"; mkdir -p "$TMP"

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
verdict() {  # verdict "用例名" 0/1 —— 给"多个片段一起判断"的用例用
  if [ "$2" = "1" ]; then
    echo "  [OK]   $1"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1"; FAIL=$((FAIL+1))
  fi
}
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
# 取一个金额字段并归一成 2 位小数。**不断言 "15.00" 这种字面量**：
# BigDecimal 的 scale 会不会被序列化出来（15.00 还是 15.0 还是 15）是实现细节，
# 脚本要断言的是数值本身。字段取不到时打印 MISSING，让它以"断言失败"的样子暴露出来
num() {  # num "<响应>" <字段名>
  awk -v v="$(jqf "$1" "$2")" 'BEGIN{ if (v=="") print "MISSING"; else printf "%.2f", v }'
}
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }

# 「我的券」响应里切出**某一张券**的字段片段：把该券 couponId 之前的内容全删掉，
# 剩下的开头就是它的 name/discount/.../expired。
# 比"整串 grep"精确 —— 整串 grep 分不清那个 true 是哪张券的。
# 找不到就吐 NOTFOUND：原样返回是危险的（下游用 head -1 抓到的第一个字段就成了
# **别的券**的，多半是 false，但赶上"恰好也是 true"就假绿了）
mine_slice() {  # mine_slice "<响应>" <couponId>
  local cut
  cut=$(echo "$1" | sed "s/.*\"couponId\":$2,//")
  if [ "$cut" = "$1" ]; then echo "NOTFOUND"; else echo "$cut"; fi
}

echo "########## 准备 ##########"

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)
# 管理员票：J 段要拿它证明"票是真的、只是角色不对"。种子账号来自 V4（admin/admin123）
ADMIN_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')" token)
# 门店单的 used_staff_id 该等于谁 —— 不写死 2，从库里读，避免"账号 id 恰好是 2"这种隐性前提
MGR_ID=$(db "select id from staff where username='manager';")

# 顾客注册 → 已注册则登录（姓名用 ASCII，见文件头坑 1）
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
CUST1=$(login_or_register CouponCustA 13900000011)
CUST2=$(login_or_register CouponCustB 13900000012)
CUST1_ID=$(db "select id from customers where phone='13900000011';")
CUST2_ID=$(db "select id from customers where phone='13900000012';")

# 折前价从库里读，不写死 15.00：脚本要验的是"折扣算得对"，
# 不是"价目表还是上次那个数"。价目表被改了不该让这个脚本假红
PRICE=$(db "select price from clothes_prices where category_id=11 and wash_type_id=1;")
QTY=2
# 用 2 件是为了让折后价正好落在整数分上（15.00×2×0.5=15.00），
# 脚本不去复刻 BigDecimal 的 HALF_UP —— 那是单测的活，这里只验端到端形状
BASE_AMT=$(awk -v p="$PRICE" -v q="$QTY" 'BEGIN{printf "%.2f", p*q}')
PAY_AMT=$(awk -v b="$BASE_AMT" 'BEGIN{printf "%.2f", b*0.5}')
SAVE_AMT=$(awk -v b="$BASE_AMT" -v a="$PAY_AMT" 'BEGIN{printf "%.2f", b-a}')

# 准备阶段失败要立刻喊停：否则后面每条断言报的都是"券不属于这位顾客"，
# 把一次登录失败误诊成抵扣逻辑坏了（Bug 22 的教训）。
# 判据分三种（**Bug 38** 的规矩：准备段自检要判**形状**，不是"有没有"）：
#   · 票：非空即可 —— 票空了下游发出去的是 "Authorization: Bearer "，
#     回来的是 401，看着像"越权/身份没传对"
#   · id：必须**是数字** —— db() 把 stderr 丢了，MySQL 一挂它返回空串，
#     而空串拼进 URL/SQL 不报错，只会让断言错得莫名其妙
#   · 单价：小数，case 那套判不了，交给 awk 校验"数字，最多一个小数点"
BAD=""
for v in MGR_T ADMIN_T CUST1 CUST2; do
  [ -z "${!v}" ] && BAD="$BAD $v(票空)"
done
for v in MGR_ID CUST1_ID CUST2_ID; do
  case "${!v}" in ''|*[!0-9]*) BAD="$BAD $v('${!v}')不是数字";; esac
done
if ! awk -v v="$PRICE" 'BEGIN{exit !(v ~ /^[0-9]+(\.[0-9]+)?$/)}'; then
  BAD="$BAD PRICE('$PRICE')不是数字"
fi
if [ -n "$BAD" ]; then
  echo "==> [准备失败] 前置条件不成立，停止：$BAD"
  echo "             票长 manager=${#MGR_T} custA=${#CUST1} custB=${#CUST2}"
  echo "             后端是否在 8081？manager/admin123 能否登录？"
  exit 1
fi
echo "  店长 staff id=$MGR_ID   顾客A id=$CUST1_ID   顾客B id=$CUST2_ID"
echo "  衬衫普洗单价=$PRICE  →  折前 $BASE_AMT / 5折后 $PAY_AMT / 抵扣 $SAVE_AMT"

NOW_START=$(date -d '-1 hour' +%Y-%m-%dT%H:%M:%S)
NOW_END=$(date -d '+1 day'  +%Y-%m-%dT%H:%M:%S)
mk_body() {  # mk_body <ASCII 名称> <输出文件>
  printf '{"name":"%s","discount":0.50,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
    "$1" "$NOW_START" "$NOW_END" > "$2"
}
# 发券走 API（真实路径：字段校验 + Redis 库存预热都是真的），
# 只有"时间到了"这一步快进 —— 券状态平时由每分钟一次的 CouponStatusTask 推进，
# 验收脚本等不起那一分钟
new_coupon() {  # new_coupon <请求体文件> → 券 id
  jqf "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
        -H "Authorization: Bearer $MGR_T" --data-binary @"$1")" id
}

mk_body "CPN-E2E-ONLINE" "$TMP/c1.json"     # 网单用
mk_body "CPN-E2E-STORE"  "$TMP/c2.json"     # 门店单用
mk_body "CPN-E2E-RACE"   "$TMP/c3.json"     # 并发用

# 中文券名：源码里只有 ASCII 的 \x 转义，真 UTF-8 字节由 printf 生成。
# 期望字节同时写成 hex 常量 —— 断言时拿它比对**两边**（库里的、接口返回的），
# 而不是拿库里读出来的去比接口返回的：那样双重编码会自己抵消，永远绿
CN_NAME=$(printf '\xe5\xbc\x80\xe4\xb8\x9a\xe4\xba\x94\xe6\x8a\x98\xe5\x88\xb8')   # 开业五折券
CN_NAME_HEX=E5BC80E4B89AE4BA94E68A98E588B8
printf '{"name":"%s","discount":0.50,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
  "$CN_NAME" "$NOW_START" "$NOW_END" > "$TMP/c4.json"

CPN1=$(new_coupon "$TMP/c1.json")
CPN2=$(new_coupon "$TMP/c2.json")
CPN3=$(new_coupon "$TMP/c3.json")
CPN4=$(new_coupon "$TMP/c4.json")
for v in CPN1 CPN2 CPN3 CPN4; do
  if [ -z "${!v}" ]; then echo "==> [准备失败] $v 为空，发券没成功，停止"; exit 1; fi
done
db "update coupons set status=2 where id in ($CPN1,$CPN2,$CPN3,$CPN4);" > /dev/null
echo "  四张 5 折券 id：$CPN1(网单) $CPN2(门店单) $CPN3(并发) $CPN4(中文名/过期)"

# 抢券：顾客A 把四张都抢下来
for cp in $CPN1 $CPN2 $CPN3 $CPN4; do
  G=$(curl -s -X POST "$BASE/api/coupons/$cp/grab" -H "Authorization: Bearer $CUST1")
  if ! echo "$G" | grep -q '"code":200'; then
    echo "==> [准备失败] 抢券 $cp 没成功：$G"; exit 1
  fi
done
echo "  顾客A 已把四张券都抢到手"

# 建单请求体（含中文的地方一概不写，见文件头坑 1）
online_body() {  # online_body <couponId 或 "" >
  local c=$1
  printf '{"source":2,"storeId":1,"deliveryAddress":"Hangzhou Xihu Rd 100","items":[{"categoryId":11,"washTypeId":1,"quantity":%s}]%s}' \
    "$QTY" "$([ -n "$c" ] && echo ",\"couponId\":$c")"
}

echo
echo "########## A. 券的归属（建单前的友好校验）##########"

# 顾客B 用顾客A 的券。这一条**不是防越权的**（两种来源都拦不住冒用），
# 它买的是"让错误指对方向"：没有它，请求会一路走到 CAS 报 409「该优惠券已被使用」，
# 把"这张券不是你的"说成"这张券被人用过了"，把人引去查错方向
check "A1 顾客B 拿顾客A 的券下网单 → 400 该优惠券不属于这位顾客" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
     -H "Authorization: Bearer $CUST2" -d "$(online_body $CPN1)")" \
  "该优惠券不属于这位顾客"

check "A1b 被拒之后这张券没被动过（used 还是 0）" \
  "$(db "select used from coupon_grabs where coupon_id=$CPN1 and customer_id=$CUST1_ID;")" "^0$"

check "A1c 被拒之后一张订单都没落库（此刻还没有人成功用过 CPN1）" \
  "$(db "select count(*) from orders where coupon_id=$CPN1;")" "^0$"

check "A2 不存在的券 → 400 优惠券不存在" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
     -H "Authorization: Bearer $CUST1" -d "$(online_body 999999)")" \
  "优惠券不存在"

echo
echo "########## B. 网单用券：折后价落库 + 使用记录 ##########"

B1=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
  -H "Authorization: Bearer $CUST1" -d "$(online_body $CPN1)")
check "B1 顾客用券下网单 → 200" "$B1" '"code":200'
# total_amount 存的是**折后应付**：收银台和 finalPay 都拿它当"付清"的基准，
# 存折前价会让顾客用券之后还被要求付全款
check "B1a totalAmount = 折后 $PAY_AMT（不是折前的 $BASE_AMT）" "$(num "$B1" totalAmount)" "^$PAY_AMT$"
check "B1b discountAmount = 省下 $SAVE_AMT" "$(num "$B1" discountAmount)" "^$SAVE_AMT$"
check "B1c 订单上挂住了 couponId=$CPN1" "$B1" "\"couponId\":$CPN1,"
B1_ID=$(jqf "$B1" id)
if [ -z "$B1_ID" ]; then echo "==> [准备失败] 网单没建出来：$B1"; exit 1; fi

check "B2 库里 total_amount|discount_amount|coupon_id 三列都对" \
  "$(db "select concat_ws('|',total_amount,discount_amount,coupon_id) from orders where id=$B1_ID;")" \
  "$PAY_AMT|$SAVE_AMT|$CPN1"

# 使用记录三列一次断言完：used=1（核销了）、used_order_id 指向这一单（不是别的单）、
# used_time 非空（时间由数据库 NOW() 写入）、used_staff_id 为 **NULL**（网单是顾客自助）
check "B3 使用记录：used=1 / used_order_id 指向本单 / used_time 有值 / used_staff_id 为 NULL" \
  "$(db "select concat_ws('|',used,if(used_time is null,'NULL','SET'),ifnull(used_order_id,'NULL'),ifnull(used_staff_id,'NULL')) from coupon_grabs where coupon_id=$CPN1 and customer_id=$CUST1_ID;")" \
  "1|SET|$B1_ID|NULL"

# 原先这里有一条 B4「用掉的券从『我的券』里消失」（单独查一次 CPN1）。
# 删掉的理由是它被 H4 **严格包含**：H4 用同一把刀切三张券（CPN1/CPN2/CPN3），
# 而 B4 只是其中最弱的那一份 —— 同一属性、同一端点，两份拷贝没有第二种可能的结果

echo
echo "########## C. 门店单用券（2026-09-12 放开门店单用券）##########"

C1=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
  -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":$QTY}],\"couponId\":$CPN2}")
check "C1 门店单带顾客自己的券 → 200" "$C1" '"code":200'
check "C1a 门店单同样算折后价 $PAY_AMT（券的算法不分线上线下）" "$(num "$C1" totalAmount)" "^$PAY_AMT$"
C1_ID=$(jqf "$C1" id)
if [ -z "$C1_ID" ]; then echo "==> [准备失败] 门店单没建出来：$C1"; exit 1; fi

# 这一条是放开门店单用券的**唯一兜底**：记录不等于授权，它拦不住员工替顾客烧券，
# 只能让这件事事后可查 —— 而"可查"的全部重量就压在这一列上
check "C2 used_staff_id = 建单员工（门店单的券是谁烧的，落到了具体的人头上）" \
  "$(db "select concat_ws('|',used,ifnull(used_order_id,'NULL'),ifnull(used_staff_id,'NULL')) from coupon_grabs where coupon_id=$CPN2 and customer_id=$CUST1_ID;")" \
  "1|$C1_ID|$MGR_ID"

echo
echo "########## D. 付清以折后金额为准 ##########"

check "D1 按折前价 $BASE_AMT 收钱 → 400 支付金额不正确（收银台不能要顾客付全款）" \
  "$(curl -s -X POST "$BASE/api/orders/$C1_ID/pay?payMethod=cash&amount=$BASE_AMT" \
     -H "Authorization: Bearer $MGR_T")" "支付金额不正确"

check "D2 按折后价 $PAY_AMT 收钱 → 200" \
  "$(curl -s -X POST "$BASE/api/orders/$C1_ID/pay?payMethod=cash&amount=$PAY_AMT" \
     -H "Authorization: Bearer $MGR_T")" '"code":200'

check "D3 库里 paid_amount = $PAY_AMT，状态推进到 2" \
  "$(db "select concat_ws('|',paid_amount,status) from orders where id=$C1_ID;")" "$PAY_AMT|2"

echo
echo "########## E. 重复用券（顺序重放）##########"

check "E1 同一张券再用一次 → 400 该优惠券已被使用（友好校验先拦下）" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
     -H "Authorization: Bearer $CUST1" -d "$(online_body $CPN1)")" \
  "该优惠券已被使用"

check "E2 库里 coupon_id=$CPN1 的订单仍然只有 1 张" \
  "$(db "select count(*) from orders where coupon_id=$CPN1;")" "^1$"

echo
echo "########## F. 并发用券：恰好一个 200 + 一个 409，且只留下一张单 ##########"
# 和 verify-race.sh 同一个手法：另开一个 MySQL 会话把 coupon_grabs 那一行锁 3 秒，
# 把 CAS 的竞态窗口从微秒拉长到秒。两个请求都读到 used=0（普通 SELECT 走快照不阻塞），
# 然后都卡在 UPDATE 上；锁一放，一个命中 1 行、一个命中 0 行 → 409 → 事务回滚
# —— 这里同时验了两件事：CAS 挡得住，且被挡下的那单**真的没落库**（券和单不会只成一半）
echo "  持锁 3 秒 + 并发两个带同一张券的建单请求 ..."
docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi --default-character-set=utf8mb4 \
  -e "BEGIN; SELECT id FROM coupon_grabs WHERE coupon_id=$CPN3 AND customer_id=$CUST1_ID FOR UPDATE; SELECT SLEEP(3); COMMIT;" \
  > /dev/null 2>&1 &
sleep 1
curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $CUST1" \
  -d "$(online_body $CPN3)" > "$TMP/coupon-race-1.json" &
curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $CUST1" \
  -d "$(online_body $CPN3)" > "$TMP/coupon-race-2.json" &
wait
R1=$(cat "$TMP/coupon-race-1.json"); R2=$(cat "$TMP/coupon-race-2.json")

OK=0; CONFLICT=0
echo "$R1$R2" | grep -q '"code":200' && OK=1
echo "$R1$R2" | grep -q '"code":409' && CONFLICT=1
verdict "F1 恰好一个 200 + 一个 409 —— 第二张单被 CAS 挡下" "$(( OK * CONFLICT ))"
echo "         请求1: $R1"
echo "         请求2: $R2"

check "F2 库里 coupon_id=$CPN3 的订单只有 1 张（409 那单**回滚了**，不是只报了个错）" \
  "$(db "select count(*) from orders where coupon_id=$CPN3;")" "^1$"

SURVIVOR=$(db "select ifnull(max(id),0) from orders where coupon_id=$CPN3;")
check "F3 券的 used_order_id 指向那张活下来的单（回滚掉的那张没在券上留痕）" \
  "$(db "select ifnull(used_order_id,'NULL') from coupon_grabs where coupon_id=$CPN3 and customer_id=$CUST1_ID;")" \
  "^$SURVIVOR$"

echo
echo "########## G. 过期券 ##########"
# 把截止时间改到过去 —— 模拟"时间过去了"。不去复刻"发一张出生就过期的券"
# 那种现实里不会出现的状态
db "update coupons set end_time=date_sub(now(), interval 1 day) where id=$CPN4;" > /dev/null

check "G1 过期券下单 → 400 该优惠券已过期" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
     -H "Authorization: Bearer $CUST1" -d "$(online_body $CPN4)")" \
  "该优惠券已过期"

check "G2 被拒的订单没留下痕迹（券还是未使用）" \
  "$(db "select used from coupon_grabs where coupon_id=$CPN4 and customer_id=$CUST1_ID;")" "^0$"

echo
echo "########## H. GET /api/coupons/mine ##########"

# 这一段才第一次查「我的券」：H2 要看的正是 CPN4 **过期之后**的样子
# （G 段刚把它的 end_time 改到过去）
MINE=$(curl -s "$BASE/api/coupons/mine" -H "Authorization: Bearer $CUST1")
check "H1 顾客查我的券 → 200" "$MINE" '"code":200'

check "H2 过期的券还在列表里，只是标了 expired（置灰而不是消失）" \
  "$(mine_slice "$MINE" "$CPN4" | grep -o '"expired":[a-z]*' | head -1)" '"expired":true'

check "H3 未使用的券（CPN4）在列表里" "$MINE" "\"couponId\":$CPN4,"

# 三张已消费的券必须都不在 —— 一张一张判，"漏了哪张"才看得出来
GONE=1
for cp in $CPN1 $CPN2 $CPN3; do
  echo "$MINE" | grep -q "\"couponId\":$cp," && GONE=0
done
verdict "H4 三张已消费的券（$CPN1/$CPN2/$CPN3）都不在「我的券」里" "$GONE"

check "H5 员工 token 查我的券 → 401 请使用顾客账号登录" \
  "$(curl -s "$BASE/api/coupons/mine" -H "Authorization: Bearer $MGR_T")" "请使用顾客账号登录"

check "H6 无 token → 401 未登录" "$(curl -s "$BASE/api/coupons/mine")" "未登录"

echo
echo "########## I. 中文券名没存坏 ##########"
# 断言的是**期望字节**，不是"库 vs 接口"（那样双重编码会抵消，永远绿）。
# 开=E5BC80 业=E4B89A 五=E4BA94 折=E68A98 券=E588B8
#
# 两处 `tr -d '\n'` 不是多余的：sed 会给输出补一个换行，那个 0A 会被 od
# 当成第 16 个字节，hex 就变成 `…E588B8` + `0A`。而 check 是**子串**匹配，
# 多出来的尾巴照样能匹配上 —— 断言会以一个错误的理由变绿。
# 去掉之后再比对，比的就真是"一模一样"了
check "I1 库里券名是干净的 UTF-8（断言 hex，不看控制台）" \
  "$(db "select hex(name) from coupons where id=$CPN4;" | tr -d '\n')" \
  "^$CN_NAME_HEX\$"

check "I2 接口返回的券名是同一串字节（读出去也没坏）" \
  "$(mine_slice "$MINE" "$CPN4" | grep -o '"name":"[^"]*"' | head -1 | sed 's/^"name":"//; s/"$//' \
     | tr -d '\n' | od -An -tx1 | tr -d ' \n' | tr 'a-f' 'A-F')" \
  "^$CN_NAME_HEX\$"

echo
echo "########## J. 发券的两道闸（2026-09-18 Bug 42）##########"
# 这一段是本脚本**第一次以顾客身份**碰 /api/coupons、也是第一次拿越界的折扣率发券。
# 两条都是 2026-09-18 才补的闸，补之前各是一条能直接变成钱的路：
#   · 顾客票 POST /api/coupons → **200**。JwtInterceptor 遇到非 staff 直接 return
#     （"顾客能用哪些接口由各 Controller 自己判断"），而 createCoupon 忘了判断 ——
#     于是任何一张有效票都能给自己发券，折扣低到 0.01 都行；60 秒后定时任务
#     把它推成"进行中"，抢下来下单抵扣。**不是脏数据，是收入**
#   · discount 越界（0 / 负数 / >1）→ **200**。券建得出来、Redis 预热好、
#     顾客抢到手，一路到下单才被 Order.applyCoupon 抛"折扣率不合法"——
#     那时报错的是顾客，填错的是店长，两个人隔着好几步
#
# 这一段的对照组就是上面的准备段：同样是店长、同样的 /api/coupons，
# 0.50 的四张券**建得出来**（CPN1~CPN4 都拿到了 id）。所以下面这几条 400/401
# 不是"这个端点坏了"，是闸在按折扣率和身份分人

printf '{"name":"CPN-E2E-HACK","discount":0.01,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
  "$NOW_START" "$NOW_END" > "$TMP/hack.json"
check "J1 顾客 token 发券 → 401 请使用员工账号操作（一折券也不例外，票有效也没用）" \
  "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
     -H "Authorization: Bearer $CUST1" --data-binary @"$TMP/hack.json")" \
  "请使用员工账号操作"

check "J1a 被拒之后库里没有这张券（不是「先 insert 再回 401」）" \
  "$(db "select count(*) from coupons where name='CPN-E2E-HACK';")" "^0$"

printf '{"name":"CPN-E2E-BADDISC","discount":1.01,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
  "$NOW_START" "$NOW_END" > "$TMP/baddisc.json"
check "J2 店长发 1.01 折的券 → 400 折扣率必须大于 0 且不超过 1" \
  "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
     -H "Authorization: Bearer $MGR_T" --data-binary @"$TMP/baddisc.json")" \
  "折扣率必须大于 0 且不超过 1"

check "J2a 越界的券没落库（拒绝发生在 insert / Redis 预热之前）" \
  "$(db "select count(*) from coupons where name='CPN-E2E-BADDISC';")" "^0$"

# 0 与负数共用一句判据（`<= 0`）。只探 0 的话，把判据改成 `== 0` 仍然全绿，
# 而负折扣会让 total_amount 变成负数 —— 顾客下单反倒"欠"店里钱
printf '{"name":"CPN-E2E-NEG","discount":-0.50,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
  "$NOW_START" "$NOW_END" > "$TMP/negdisc.json"
check "J3 负折扣（-0.50）→ 400 同一句话（判据是 <= 0，不是 == 0）" \
  "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
     -H "Authorization: Bearer $MGR_T" --data-binary @"$TMP/negdisc.json")" \
  "折扣率必须大于 0 且不超过 1"

# J4/J4a 是 2026-09-19 拍板"管理员不发券"之后补的（Bug 42 当时留下的口径问题）。
# 闸在 JwtInterceptor.checkRoleGate 的**方向二**（拦管理员那三条的旁边），
# 判据 = POST + 路径精确等于 /api/coupons —— 所以 J1 那条顾客 401 不受影响
# （顾客在拦截器里直接放行，拦他的是 controller），抢券也是。
# 措辞与订单/顾客那几句同形："不参与……请使用店长账号"。
printf '{"name":"CPN-E2E-ADMIN","discount":0.50,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
  "$NOW_START" "$NOW_END" > "$TMP/admin.json"
check "J4 管理员 token 发券 → 403 管理员不参与发券（票是真的，角色不对）" \
  "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
     -H "Authorization: Bearer $ADMIN_T" --data-binary @"$TMP/admin.json")" \
  "管理员不参与发券"

check "J4a 被拒之后库里没有这张券（闸在拦截器里，早于 controller 和 insert）" \
  "$(db "select count(*) from coupons where name='CPN-E2E-ADMIN';")" "^0$"

# 对照：同一时刻、同一个端点、同一种请求体，只是换成店长 —— 发得出来。
# 没有这条，J4 在"发券整个功能塌了"的情况下照样绿（Bug 29 的形状：验的不是它声称的事）。
# 对照用**另一个券名**：CPN-E2E-ADMIN 必须永远保持 0 行，上面那条 ^0$ 重跑才成立
# （用同一个名字的话，对照每跑一次就多一行，第二次跑 J4a 必红 —— 本机与 CI 又要分家）
printf '{"name":"CPN-E2E-CTRL","discount":0.50,"totalStock":100,"startTime":"%s","endTime":"%s"}' \
  "$NOW_START" "$NOW_END" > "$TMP/ctrl.json"
CTRL_ID=$(jqf "$(curl -s -X POST $BASE/api/coupons -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MGR_T" --data-binary @"$TMP/ctrl.json")" id)
case "$CTRL_ID" in ''|*[!0-9]*) CTRL_OK=0;; *) CTRL_OK=1;; esac
verdict "J4b 对照：同样请求体换成店长 → 拿到数字 id（'$CTRL_ID'）—— J4 的 403 是分人，不是端点坏了" "$CTRL_OK"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"

# 退出码就是断言结果 —— CI 用 `if bash "$s"` 判成败（.github/workflows/ci.yml:157），
# 而在 **Bug 40** 之前本脚本最后一行是 echo：**永远退 0**。断言红成一片，
# CI 照样打 OK（汇总行里那串"通过 X 项，失败 Y 项"还会照印，但 job 不会失败）——
# 假绿从"断言层"搬到了"汇总层"，而这一层没有任何断言在看着它。
# admin / auth / stores 三个一直是对的（它们本来就有这一行），这行是照它们补的。
[ $FAIL -eq 0 ]
