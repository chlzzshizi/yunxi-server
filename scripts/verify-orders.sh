#!/bin/bash
# 订单模块接口验收（真机：MySQL + Redis + 8081）
#
# 用法：bash scripts/verify-orders.sh     从任何目录都行
# 前置：docker compose up -d  且后端已在 8081 起着
BASE=http://localhost:8081
PASS=0; FAIL=0

# 路径一律相对脚本自身。脚本原住在 C:\tmp，写死 /c/tmp 就等于把
# "这份脚本必须待在某个特定目录"变成一条隐性前提 —— 搬进仓库后必须去掉
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="${TMPDIR:-/tmp}/yunxi-e2e"; mkdir -p "$TMP"

# 断言：期望字符串出现在响应里
check() {  # check "用例名" "响应" "期望片段"
  if echo "$2" | grep -q "$3"; then
    echo "  [OK]   $1"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1"; echo "         期望含: $3"; echo "         实际: $2"; FAIL=$((FAIL+1))
  fi
}
# 断言：不该出现的片段（"总数不是 0"这类否定判断用它）
checkNot() {  # checkNot "用例名" "响应" "不该出现的片段"
  if echo "$2" | grep -q "$3"; then
    echo "  [FAIL] $1"; echo "         不该出现: $3"; echo "         实际: $2"; FAIL=$((FAIL+1))
  else
    echo "  [OK]   $1"; PASS=$((PASS+1))
  fi
}
# 取 JSON 字段（取**第一个**匹配：订单 JSON 里明细也有 id 字段，贪婪匹配会取错）
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

echo "########## 准备：登录取 token ##########"

ADMIN=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')
ADMIN_T=$(jqf "$ADMIN" token)
echo "  admin  token: ${ADMIN_T:0:20}..."

MGR=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"manager","password":"admin123"}')
MGR_T=$(jqf "$MGR" token)
echo "  manager token: ${MGR_T:0:20}..."

# 顾客注册 → 已注册则登录，返回 token。
# 姓名用 ASCII：Git Bash 会把 shell 里的中文按 GBK 发出去，后端按 UTF-8 解析会 400
# （这是脚本的锅不是后端的；中文经文件投递的用例见 B1/G1）
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

CUST1=$(login_or_register CustomerA 13900000001)
CUST2=$(login_or_register CustomerB 13900000002)
echo "  cust1  token: ${CUST1:0:20}..."
echo "  cust2  token: ${CUST2:0:20}..."

# 顾客 id（从 orders 建单需要 customerId）—— 直接查库拿，避免再调接口
CUST1_ID=$(docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N --default-character-set=utf8mb4 -e \
  "select id from customers where phone='13900000001';" 2>/dev/null | tr -d '\r')
CUST2_ID=$(docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N --default-character-set=utf8mb4 -e \
  "select id from customers where phone='13900000002';" 2>/dev/null | tr -d '\r')
echo "  cust1 id=$CUST1_ID  cust2 id=$CUST2_ID"

echo
echo "########## A. 身份与越权 ##########"

# 2026-09-11 口径（推翻 09-10 的"权限≥店长"）：管理员只管人与店，
# /api/orders/** 一律 403 —— **含读接口**（放开读只会让"他到底能不能管订单"重新变模糊）。
# 闸门在 JwtInterceptor.checkRoleGate，按 URL 前缀收口
check "A1 admin 查订单列表 → 403（管理员不参与订单操作）" \
  "$(curl -s "$BASE/api/orders" -H "Authorization: Bearer $ADMIN_T")" "管理员不参与订单操作"

# 写接口同样到不了 Controller —— 管理员连"建单"这个动作都做不了
check "A1b admin 建门店单 → 403（写接口也被同一道闸门挡下）" \
  "$(curl -s -X POST $BASE/api/orders -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $ADMIN_T" \
     -d "{\"customerId\":$CUST1_ID,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}")" \
  "管理员不参与订单操作"

check "A2 无 token 查订单 → 401" \
  "$(curl -s "$BASE/api/orders")" "未登录"

check "A3 顾客 token 推进订单 → 401 员工账号" \
  "$(curl -s -X POST "$BASE/api/orders/1/next" -H "Authorization: Bearer $CUST1")" \
  "请使用员工账号操作"

check "A4 顾客 token 支付 → 401" \
  "$(curl -s -X POST "$BASE/api/orders/1/pay?payMethod=cash&amount=30.00" \
     -H "Authorization: Bearer $CUST1")" "请使用员工账号操作"

check "A5 顾客 token 结账 → 401" \
  "$(curl -s -X POST "$BASE/api/orders/1/final-pay?payMethod=cash" \
     -H "Authorization: Bearer $CUST1")" "请使用员工账号操作"

echo
echo "########## B. 创建订单 ##########"

# 含中文的请求体走文件：payload-order-cn.json 是 Write 工具写的真 UTF-8，
# 用 sed 只替换 ASCII 占位符，中文字节原样不动
sed "s/__CID__/$CUST1_ID/" "$SCRIPT_DIR/payload-order-cn.json" > "$TMP/order-cn.json"
ORDER1=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MGR_T" \
  --data-binary @"$TMP/order-cn.json")
check "B1 店长创建门店单 → 200，总额 30.00" "$ORDER1" '"code":200'
check "B1 订单号格式 YX+18位数字" "$ORDER1" '"orderNo":"YX[0-9]\{18\}"'
check "B1 操作人已留痕 staffId=2" "$ORDER1" '"staffId":2'
ORDER1_ID=$(jqf "$ORDER1" id)
echo "         新订单 id=$ORDER1_ID"

# 再建两单（验分页）
# 分类 id 用 V6 种子里的**叶子**分类（11=衬衫 12=裤装…）：1-4 是父分类，价目表里没有它们
curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
  -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[{\"categoryId\":12,\"washTypeId\":1,\"quantity\":1}]}" > /dev/null
ORDER3=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
  -d "{\"source\":1,\"customerId\":$CUST2_ID,\"items\":[{\"categoryId\":11,\"washTypeId\":2,\"quantity\":1}]}")
check "B2 第二单正常创建" "$ORDER3" '"code":200'
ORDER3_ID=$(jqf "$ORDER3" id)
echo "         顾客B 的订单 id=$ORDER3_ID"

echo
echo "########## C. 参数校验（400 而不是 500）##########"

check "C1 空明细 → 400" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[]}")" "至少需要一条明细"

check "C2 明细数量为 0 → 400" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":0}]}")" \
  "数量必须为正整数"

check "C3 明细缺 categoryId → 400" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[{\"washTypeId\":1,\"quantity\":1,\"unitPrice\":15.00}]}")" \
  "缺少衣物分类或洗涤方式"

check "C4 门店单缺 customerId → 400" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d '{"source":1,"items":[{"categoryId":11,"washTypeId":1,"quantity":1}]}')" \
  "必须指定顾客"

check "C5 非法 source=99 → 400" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d '{"source":99,"customerId":1,"items":[{"categoryId":11,"washTypeId":1,"quantity":1}]}')" \
  "没有这个来源"

check "C6 请求体 JSON 语法错 → 400 不是 500" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d '{"source":1,')" "请求体格式不正确"

check "C7 分页 page=abc → 400 不是 500" \
  "$(curl -s "$BASE/api/orders?page=abc" -H "Authorization: Bearer $MGR_T")" "参数格式不正确"

check "C8 非法 status=99 → 400" \
  "$(curl -s "$BASE/api/orders?status=99" -H "Authorization: Bearer $MGR_T")" "没有这个状态"

check "C9 支付金额不足 → 业务拦截" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/pay?payMethod=cash&amount=20.00" \
     -H "Authorization: Bearer $MGR_T")" "支付金额不正确"

check "C10 支付金额为负 → 拦截" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/pay?payMethod=cash&amount=-5.00" \
     -H "Authorization: Bearer $MGR_T")" "不能为空或负数"

check "C11 不存在的订单 → 404" \
  "$(curl -s "$BASE/api/orders/999999" -H "Authorization: Bearer $MGR_T")" '"code":404'

echo
echo "########## D. 支付与状态机 ##########"

check "D1 先付全额 30.00 → 200" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/pay?payMethod=cash&amount=30.00" \
     -H "Authorization: Bearer $MGR_T")" '"code":200'

check "D2 已支付再支付 → 拦截" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/pay?payMethod=cash&amount=30.00" \
     -H "Authorization: Bearer $MGR_T")" "不允许支付"

check "D3 推进 2→3 → 200" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/next" -H "Authorization: Bearer $MGR_T")" '"code":200'

DB_STATUS=$(docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N --default-character-set=utf8mb4 -e \
  "select status from orders where id=$ORDER1_ID;" 2>/dev/null | tr -d '\r')
check "D4 数据库状态真的变成 3（CAS 的 SQL 生效）" "$DB_STATUS" "3"

check "D5 推进 3→4 → 200" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/next" -H "Authorization: Bearer $MGR_T")" '"code":200'

check "D6 推进 4→5 门店单走 5 → 200" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/next" -H "Authorization: Bearer $MGR_T")" '"code":200'

check "D7 已付清，5→7 终态（门店单 1→2→3→4→5→7）→ 200" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/next" -H "Authorization: Bearer $MGR_T")" '"code":200'

check "D8 终态不可再推进 → 拦截" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/next" -H "Authorization: Bearer $MGR_T")" "不允许推进"

echo
echo "########## E. 列表分页与归属（顾客限本人；员工不限门店）##########"

LIST=$(curl -s "$BASE/api/orders?page=1&pageSize=2" -H "Authorization: Bearer $MGR_T")
check "E1 店长列表 page=1&size=2 → 200" "$LIST" '"code":200'
checkNot "E2 列表非空（total 不为 0）" "$LIST" '"total":0'
check "E3 totalPages 字段存在" "$LIST" '"totalPages"'

check "E4 status=2 筛选（已支付的单）" \
  "$(curl -s "$BASE/api/orders?status=2&page=1&pageSize=20" -H "Authorization: Bearer $MGR_T")" '"code":200'

check "E5 顾客查自己的订单 → 看到自己那单" \
  "$(curl -s "$BASE/api/orders?page=1&pageSize=20" -H "Authorization: Bearer $CUST1")" "\"customerId\":$CUST1_ID"

check "E6 顾客A 查顾客B 的订单详情 → 403" \
  "$(curl -s "$BASE/api/orders/$ORDER3_ID" -H "Authorization: Bearer $CUST1")" '"code":403'

echo
echo "########## F. 跨店不隔离：所有店长管所有订单（§4.2）##########"
# 造第二个门店 + 店长（直接用 SQL，属于验收脚手架）
#
# SQL 走 stdin 而不是 -e：`-e "…二号门店…"` 里的中文会先被 MSYS2 转成 GBK
# （命令行参数跨进程边界就会转），再被 latin1 的 mysql 客户端转一道 ——
# 库里存下来的是**双重编码的乱码**。insert ignore 遇到已存在的 id 什么都不做，
# 所以那行乱码永远不会自愈 —— 只能靠下面那句 update 把它写回正确字节。
# 这两句话是 2026-09-12 查库时才发现的：F 段一直全绿，因为它只看订单不看店名
printf '%s\n' "insert ignore into stores (id,name,address) values (2,'二号门店','验收用');
update stores set name='二号门店' where id=2;
insert ignore into staff (id,username,password,name,role,store_id,status)
values (99,'mgr2','\$2a\$10\$fEzKJTH469Zd9GB0CKMLseS/iFVndCGene.WQiQ53Q/isi2yZa5oS','二店店长',1,2,1);
update staff set name='二店店长' where id=99;" > "$TMP/scaffold-orders.sql"
docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi \
       --default-character-set=utf8mb4 < "$TMP/scaffold-orders.sql"

MGR2_RESP=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"mgr2","password":"admin123"}')
MGR2_T=$(jqf "$MGR2_RESP" token)
echo "  mgr2 token: ${MGR2_T:0:20}..."

check "F1 二店店长查一店订单 → 200（跨店可查）" \
  "$(curl -s "$BASE/api/orders/$ORDER1_ID" -H "Authorization: Bearer $MGR2_T")" '"code":200'

# 用订单号而不是 id 判断"列表里有这一单"：id 是数字，会误匹配到别的 id 前缀
ORDER1_NO=$(docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N --default-character-set=utf8mb4 -e \
  "select order_no from orders where id=$ORDER1_ID;" 2>/dev/null | tr -d '\r')
check "F2 二店店长列表里有一店的单（不再按店过滤）" \
  "$(curl -s "$BASE/api/orders?page=1&pageSize=20" -H "Authorization: Bearer $MGR2_T")" \
  "\"orderNo\":\"$ORDER1_NO\""

echo
echo "########## G. 数据完整性 ##########"
# "袖口有污渍" 的 UTF-8 首字节是 E8A296（袖）；控制台显示 ????? 只是 Git Bash 编码，
# 数据库里存的必须是真 UTF-8 字节，否则就是真存坏了
check "G1 中文备注没存坏（查真实字节而不是看控制台乱码）" \
  "$(docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N --default-character-set=utf8mb4 -e \
     "select hex(remark) from orders where id=$ORDER1_ID;" 2>/dev/null)" \
  "E8A296"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
