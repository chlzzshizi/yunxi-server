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
# 查库读真相。
# --default-character-set=utf8mb4 不能删：mysql 命令行默认按 latin1 收发，
# 读中文整串变 ?????、写中文存成双重编码，而**双重编码在读的时候会抵消回去**
# —— "列表里不含某个中文"这类断言会因此无条件通过（见 README 的第三个坑）
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }

echo "########## 准备：登录取 token ##########"

ADMIN_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')" token)

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"manager","password":"admin123"}')" token)

# 顾客注册 → 已注册则登录，返回 token。
# 姓名用 ASCII：Git Bash 会把 shell 里的中文按 GBK 发出去，后端按 UTF-8 解析会 400
# （这是脚本的锅不是后端的；中文经文件投递的用例见 B1/G1）
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

CUST1=$(login_or_register CustomerA 13900000001)
CUST2=$(login_or_register CustomerB 13900000002)

# 顾客 id（从 orders 建单需要 customerId）—— 直接查库拿，避免再调接口
CUST1_ID=$(db "select id from customers where phone='13900000001';")
CUST2_ID=$(db "select id from customers where phone='13900000002';")

# 脚手架自检（**Bug 38** 的规矩："就绪"必须是断言，不能是台词）。
# 原先这里印的是三张票的前 20 个字符：票面值进了 CI 的日志产物，而票空着 /
# id 是空串时照样往下跑 —— 后面每条断言报的都是"越权没挡住""状态机坏了"，
# 真因"脚手架没拿到票"一条都看不见。
# 现在：票判非空、id 判**形状**（不是有无）—— 本脚本的 db() 吞掉 stderr，
# MySQL 一挂它返回空串，而空串照样能拼出 {"customerId":,} 这种畸形 JSON。
# 印长度不印票面值。
BAD=""
[ -z "$ADMIN_T" ] && BAD="$BAD admin票空"
[ -z "$MGR_T" ]   && BAD="$BAD manager票空"
[ -z "$CUST1" ]   && BAD="$BAD cust1票空"
[ -z "$CUST2" ]   && BAD="$BAD cust2票空"
case "$CUST1_ID" in ''|*[!0-9]*) BAD="$BAD cust1Id($CUST1_ID)不是数字";; esac
case "$CUST2_ID" in ''|*[!0-9]*) BAD="$BAD cust2Id($CUST2_ID)不是数字";; esac
if [ -n "$BAD" ]; then
  echo "==> [准备失败] 脚手架没就绪：$BAD"
  echo "             票长 admin=${#ADMIN_T} manager=${#MGR_T} cust1=${#CUST1} cust2=${#CUST2}"
  echo "             后端是否在 8081？13900000001 / 13900000002 能否注册或登录？"
  exit 1
fi
echo "  脚手架就绪（票长 admin=${#ADMIN_T} manager=${#MGR_T} cust1=${#CUST1} cust2=${#CUST2}）"
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

# ── A6：无店店长建门店单（**Bug 36**）──
#
# 这号是**用管理员的接口建的**，不是 SQL：Bug 36 的要点正是"没有门店的店长是一条
# 走得通的正路"（`CreateStaffRequest.storeId` 上写着「店长允许为空」）。若用 SQL 造一个
# 别处根本造不出来的状态、再断言它被拦住，证的是自己写下的前提，不是产品行为。
# 第二次跑这条会 400「用户名已存在」—— 那是**预期**的（建号本身那条断言在
# verify-admin.sh 的 A6），所以这里不看返回值，只看下面那张票
NS_USER=mgrnostore
curl -s -X POST $BASE/api/staff -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_T" \
  -d "{\"username\":\"$NS_USER\",\"password\":\"admin123\",\"name\":\"NoStore\",\"role\":1,\"phone\":\"13700000099\"}" \
  > /dev/null

NS_ID=$(db "select id from staff where username='$NS_USER';")
NS_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$NS_USER\",\"password\":\"admin123\"}")" token)
# 兜底：万一上一轮之后有人改过这号的密码（上面那条建号路再跑只会是 400，改不回来）。
# 这是**脚手架自愈**，不是被测逻辑 —— 走的是管理员重置密码接口（它自己的断言在
# verify-admin.sh 的 F 段）。自愈只修"票拿不到"，不改下面要断言的 store_id
if [ -z "$NS_T" ]; then
  curl -s -X PUT "$BASE/api/staff/$NS_ID/password" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_T" -d '{"password":"admin123"}' > /dev/null
  NS_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
    -d "{\"username\":\"$NS_USER\",\"password\":\"admin123\"}")" token)
fi
# 准备守卫（Bug 38 的规矩：就绪是断言不是台词）。第二条不能省：哪天有人给这号分了门店，
# A6 会退化成一句"403 没出现"的**假红**，而真因（他已经不是无店店长了）一个字都看不见
if [ -z "$NS_T" ] || [ -z "$NS_ID" ]; then
  echo "==> [准备失败] 无店店长 $NS_USER 没就绪（票长 ${#NS_T}，id='$NS_ID'）"; exit 1
fi
NS_STORE=$(db "select coalesce(store_id,'NULL') from staff where id=$NS_ID;")
if [ "$NS_STORE" != "NULL" ]; then
  echo "==> [准备失败] $NS_USER (id=$NS_ID) 的 store_id='$NS_STORE'，不是 NULL"
  echo "              A6 要测的是「没有门店的店长」，这号已经不是了（谁给他分了门店？）"; exit 1
fi
echo "  无店店长 $NS_USER id=$NS_ID 票长=${#NS_T} store_id=NULL"

NS_ORDER=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
  -H "Authorization: Bearer $NS_T" \
  -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}")
# 2026-09-19 拍板前，这里报的是 **401「登录信息已升级，请重新登录」** ——
# 对一个按设计就没有门店的账号，"重新登录"是一件**没有用**的动作（Bug 20 的形状：
# 把 A 说成了 B）。改成 403 的理由有二：说清是什么事、以及 401 在本项目里
# 到处都意味着"重新登录"，客户端会照着去做那件没用的事
check "A6 无店店长建门店单 → 403（仍然拒绝，但说的是真话）" "$NS_ORDER" '"code":403'
check "A6b 文案说清了是什么事、该找谁" "$NS_ORDER" "账号还没有归属门店，无法开单，请联系管理员分配门店"
# checkNot 是必须的另一半：A6b 只查"说了什么"，有人加回一句"请重新登录"照样全绿。
# 这条钉的是**这种病**（指向做不到的动作），不是这一句话的措辞
checkNot "A6c 这句里不许再出现「重新登录」—— 那正是 Bug 36 的病（假成因）" \
  "$NS_ORDER" "重新登录"
# 对照组。少了它，"403 是因为票坏了/号停用了"和"403 是因为他没门店"在脚本里长得一模一样：
# 同一张票读别的接口是 200，被拒的才**只有**这个动作（门店单要有物理的店）
check "A6d 对照：同一张无店店长的票读价目表 → 200（账号本身是好的）" \
  "$(curl -s "$BASE/api/prices" -H "Authorization: Bearer $NS_T")" '"code":200'

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

# C2b 与 C2 只差一个符号：判据写 `<= 0` 还是 `== 0`，这条能分开。
# 负数是另一件事，不是 0 的重复 —— 单价永远是正的，负数量是唯一能把明细
# 做成负数的手法，能和其他明细**对冲**把总额压低（少付钱还判付清）。
#
# 说明分工：这条证的是**最外层那道闸**（OrderController:59 的 400 文案）。
# 过了 controller 之后是域层还是应用层拦的，脚本看不出来 —— 那两道由单测钉
# （OrderTest.NonPositiveQuantityRejected / OrderAppServiceTest.rejectNonPositiveQuantity），
# 它们证的是"换定时任务、脚本、第二个前端这些入口进来同样绕不掉"。
check "C2b 明细数量为负数 → 400（判据是 <= 0，不是 == 0）" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" -H "Authorization: Bearer $MGR_T" \
     -d "{\"source\":1,\"customerId\":$CUST1_ID,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":-1}]}")" \
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

DB_STATUS=$(db "select status from orders where id=$ORDER1_ID;")
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
# 所以那行乱码永远不会自愈 —— 只能靠下面的 update 把它写回正确字节。
# 这两句话是 2026-09-12 查库时才发现的：F 段一直全绿，因为它只看订单不看店名
#
# 2026-09-13 补：那句 update 原先只写 name，而这行 insert 里有**两个**中文列 ——
# 于是 address 的乱码从 09-12 一直留到 09-13（在门店列表里肉眼可见）。
# 根因一句话：**自愈只修了"有断言盯着的那一列"** —— name 有 verify-stores 的
# 守卫看着，address 没人看，所以它乱着也没人喊。修法是两件事一起做：整行写回 + 整行断言
STORE2_NAME="二号门店"
STORE2_ADDR="验收用"
printf '%s\n' "insert ignore into stores (id,name,address) values (2,'$STORE2_NAME','$STORE2_ADDR');
update stores set name='$STORE2_NAME', address='$STORE2_ADDR' where id=2;
insert ignore into staff (id,username,password,name,role,store_id,status)
values (99,'mgr2','\$2a\$10\$fEzKJTH469Zd9GB0CKMLseS/iFVndCGene.WQiQ53Q/isi2yZa5oS','二店店长',1,2,1);
update staff set name='二店店长' where id=99;" > "$TMP/scaffold-orders.sql"
docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi \
       --default-character-set=utf8mb4 < "$TMP/scaffold-orders.sql"

# 准备守卫（Bug 22 教训：前置条件不成立就立刻停，别让它跑成后面某个断言失败）。
# 注意这两条**不是**在验上面那句 update 写对了没 —— 它刚写完，读出来当然是对的。
# 它们验的是**写进去的那条路**（printf → 文件 → stdin → mysql 客户端）有没有在哪
# 一段被转码。少了它们，同一个坑会以"这次换了一列"的形式再犯一次：
# 乱码本身不报错、不影响 F 段的任何断言，只会安安静静躺在库里等人肉眼撞见
S2_NAME=$(db "select name from stores where id=2;")
if [ "$S2_NAME" != "$STORE2_NAME" ]; then
  echo "==> [准备失败] 二店 id=2 的 name 字节不对"
  echo "              期望 '$STORE2_NAME'"
  echo "              实际 '$S2_NAME'（乱码说明插入时被转码了）"; exit 1
fi
S2_ADDR=$(db "select address from stores where id=2;")
if [ "$S2_ADDR" != "$STORE2_ADDR" ]; then
  echo "==> [准备失败] 二店 id=2 的 address 字节不对"
  echo "              期望 '$STORE2_ADDR'"
  echo "              实际 '$S2_ADDR'（乱码说明插入时被转码了）"; exit 1
fi
# 二店店长同理。这条原先没有：name 有自愈语句所以一直是好的，但**没有断言** ——
# "有自愈、没断言"离"没自愈、没断言"只差一步，那一步就是某次改动顺手删掉 update
S99_NAME=$(db "select name from staff where id=99;")
if [ "$S99_NAME" != "二店店长" ]; then
  echo "==> [准备失败] mgr2 (staff id=99) 的 name 字节不对"
  echo "              期望 '二店店长'"
  echo "              实际 '$S99_NAME'（乱码说明插入时被转码了）"; exit 1
fi

MGR2_RESP=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"mgr2","password":"admin123"}')
MGR2_T=$(jqf "$MGR2_RESP" token)
echo "  mgr2 token: ${MGR2_T:0:20}..."

check "F1 二店店长查一店订单 → 200（跨店可查）" \
  "$(curl -s "$BASE/api/orders/$ORDER1_ID" -H "Authorization: Bearer $MGR2_T")" '"code":200'

# 用订单号而不是 id 判断"列表里有这一单"：id 是数字，会误匹配到别的 id 前缀
ORDER1_NO=$(db "select order_no from orders where id=$ORDER1_ID;")
check "F2 二店店长列表里有一店的单（不再按店过滤）" \
  "$(curl -s "$BASE/api/orders?page=1&pageSize=20" -H "Authorization: Bearer $MGR2_T")" \
  "\"orderNo\":\"$ORDER1_NO\""

echo
echo "########## G. 数据完整性 ##########"
# "袖口有污渍" 的 UTF-8 首字节是 E8A296（袖）；控制台显示 ????? 只是 Git Bash 编码，
# 数据库里存的必须是真 UTF-8 字节，否则就是真存坏了
check "G1 中文备注没存坏（查真实字节而不是看控制台乱码）" \
  "$(db "select hex(remark) from orders where id=$ORDER1_ID;")" \
  "E8A296"

echo
echo "########## H. 顾客在线支付（后端算金额，顾客传不了）##########"

# 网单必须有配送地址（领域规则）—— 地址用 ASCII：Git Bash 会把 shell 里的中文
# 按 GBK 发出去，后端按 UTF-8 解析，中文地址经 -d 必然变乱码（文件头那段）
NEW_ONLINE() {  # NEW_ONLINE <顾客 token> → 打印订单 id
  local resp
  resp=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
    -H "Authorization: Bearer $1" \
    -d '{"source":2,"storeId":1,"deliveryAddress":"Hangzhou Xihu Rd 100","items":[{"categoryId":11,"washTypeId":1,"quantity":1}]}')
  if ! echo "$resp" | grep -q '"code":200'; then
    echo "==> [准备失败] 网单没建出来：$resp" >&2; exit 1
  fi
  jqf "$resp" id
}

H1_ID=$(NEW_ONLINE "$CUST1")
H2_ID=$(NEW_ONLINE "$CUST2")
echo "  顾客A 的网单 id=$H1_ID   顾客B 的网单 id=$H2_ID"

check "H1 顾客付自己的单 → 200（金额由后端从订单上取，没有 amount 参数）" \
  "$(curl -s -X POST "$BASE/api/orders/$H1_ID/online-pay?payMethod=wechat" \
     -H "Authorization: Bearer $CUST1")" '"code":200'

# 一次断言完三个字段：状态进到 2、支付方式落上、钱数对
# （衬衫普洗 15.00 × 1 件，没券，所以支付额就是 15.00）
check "H2 库里 status=2 / pay_method=wechat / paid_amount=15.00" \
  "$(db "select concat_ws('|',status,pay_method,paid_amount) from orders where id=$H1_ID;")" \
  "2|wechat|15.00"

# 连点两次：第二次读到的已经是"已支付"，领域层直接拦下（400）。
# 真正的并发撞车在下面那段 —— 那两个请求都会读到 1 态，只能靠 CAS 分胜负
check "H3 连点两次 → 第二次被领域状态机拦下（不允许支付）" \
  "$(curl -s -X POST "$BASE/api/orders/$H1_ID/online-pay?payMethod=wechat" \
     -H "Authorization: Bearer $CUST1")" "不允许支付"

check "H3b 重复点击没有把 paid_amount 变成两倍" \
  "$(db "select paid_amount from orders where id=$H1_ID;")" "15.00"

check "H4 付别人的单 → 403 无权支付该订单（这单存在，只是不是他的）" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay?payMethod=wechat" \
     -H "Authorization: Bearer $CUST1")" "无权支付该订单"

check "H4b 被拒之后顾客B 的单没被动过（还是 1 态、没付款）" \
  "$(db "select concat_ws('|',status,ifnull(pay_method,'NULL'),paid_amount) from orders where id=$H2_ID;")" \
  "1|NULL|0.00"

# 403 排在支付方式校验之前：别人的单不配知道"这单能不能用现金"，
# 换句话这一条同时也钉住了这两道校验的先后
check "H4c 连自己都不是这单的主人时，报的是无权而不是支付方式" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay?payMethod=cash" \
     -H "Authorization: Bearer $CUST1")" "无权支付该订单"

check "H5 员工 token 调在线支付 → 401 请使用顾客账号登录（这是顾客自助的端点）" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay?payMethod=wechat" \
     -H "Authorization: Bearer $MGR_T")" "请使用顾客账号登录"

check "H6 无 token → 401 未登录" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay?payMethod=wechat")" "未登录"

check "H7 现金 → 400（顾客在手机上点不出柜台动作）" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay?payMethod=cash" \
     -H "Authorization: Bearer $CUST2")" "只支持微信或支付宝"

check "H8 支付方式压根不存在 → 400（枚举转换拦住）" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay?payMethod=bitcoin" \
     -H "Authorization: Bearer $CUST2")" "没有这个支付方式"

check "H9 少了 payMethod 参数 → 400，不是 500" \
  "$(curl -s -X POST "$BASE/api/orders/$H2_ID/online-pay" \
     -H "Authorization: Bearer $CUST2")" "缺少参数"

check "H9b 不存在的订单 → 404（顺序：先认出人，再说单子不在）" \
  "$(curl -s -X POST "$BASE/api/orders/999999/online-pay?payMethod=wechat" \
     -H "Authorization: Bearer $CUST2")" '"code":404'

check "H9c 一串坏输入下来，顾客B 的单还在 1 态没动过" \
  "$(db "select concat_ws('|',status,ifnull(pay_method,'NULL'),paid_amount) from orders where id=$H2_ID;")" \
  "1|NULL|0.00"

echo
echo "########## H·并发：两个支付同时到 → 恰好一个 200 + 一个 409 ##########"
# 和 verify-race.sh 同一个手法：行锁把读-改-写的窗口从微秒拉长到秒。
# 两个请求都会读到 1 态、都会在内存里支付成功，最后卡在同一条
# UPDATE ... WHERE status = 1 上；锁一放，一个命中 1 行、一个 0 行 → 409。
# 没有 CAS 的话这里会是两个 200，而钱只收了一次 —— 也就是"订单显示付了两遍"
#
# **顺序重放拿不到 409**：第二次读到的已经是 2 态，在领域层就被拦成 400 了（H3）。
# 409 只在两个请求并行、都读到 1 态时才出现，所以这一条必须真并发才验得到
# （变量名从 H3_ID 让开：H3 是上面那条连点用例，别被覆盖成别的单）
RACE_ID=$(NEW_ONLINE "$CUST1")
echo "  持锁 3 秒 + 并发两个 online-pay（订单 id=$RACE_ID）..."
docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi --default-character-set=utf8mb4 \
  -e "BEGIN; SELECT id FROM orders WHERE id=$RACE_ID FOR UPDATE; SELECT SLEEP(3); COMMIT;" \
  > /dev/null 2>&1 &
sleep 1
curl -s -X POST "$BASE/api/orders/$RACE_ID/online-pay?payMethod=wechat" \
  -H "Authorization: Bearer $CUST1" > "$TMP/pay-race-1.json" &
curl -s -X POST "$BASE/api/orders/$RACE_ID/online-pay?payMethod=alipay" \
  -H "Authorization: Bearer $CUST1" > "$TMP/pay-race-2.json" &
wait
P1=$(cat "$TMP/pay-race-1.json"); P2=$(cat "$TMP/pay-race-2.json")
echo "         请求1: $P1"
echo "         请求2: $P2"

POK=0; PCONFLICT=0
echo "$P1$P2" | grep -q '"code":200' && POK=1
echo "$P1$P2" | grep -q '"code":409' && PCONFLICT=1
if [ $POK -eq 1 ] && [ $PCONFLICT -eq 1 ]; then
  echo "  [OK]   H10 恰好一个 200 + 一个 409 —— 第二次支付被 CAS 挡下"; PASS=$((PASS+1))
else
  echo "  [FAIL] H10 期望恰好 1 个 200 + 1 个 409"; FAIL=$((FAIL+1))
fi

# 只断言"有一个 409"是不够的：报错之后如果事务没回滚干净、或者赢的那个把钱记了两遍，
# 这条也能过。真正要证的是**钱只收了一次**
check "H10b 状态只推进到 2、钱只收了一次（409 那条真的回滚了）" \
  "$(db "select concat_ws('|',status,paid_amount) from orders where id=$RACE_ID;")" "2|15.00"

echo
echo "########## I. 快递单号（仅网单·派送中）##########"

# 造一张走到派送中（6 态）的网单：已付 → 推进 2→3→4→6
I1_ID=$(NEW_ONLINE "$CUST1")
curl -s -X POST "$BASE/api/orders/$I1_ID/online-pay?payMethod=wechat" -H "Authorization: Bearer $CUST1" > /dev/null
for _ in 1 2 3; do
  curl -s -X POST "$BASE/api/orders/$I1_ID/next" -H "Authorization: Bearer $MGR_T" > /dev/null
done
I1_STATUS=$(db "select status from orders where id=$I1_ID;")
echo "  网单 id=$I1_ID 推到状态=$I1_STATUS（期望 6=派送中）"
if [ "$I1_STATUS" != "6" ]; then
  echo "==> [准备失败] 网单没走到 6 态，快递单号的用例前置不成立"; exit 1
fi

check "I1 派送中录入单号 → 200" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/express?expressNo=SF1234567890" \
     -H "Authorization: Bearer $MGR_T")" '"code":200'

check "I2 单号真的落库了（证明走的是那条 CAS 语句，不是内存里改改）" \
  "$(db "select express_no from orders where id=$I1_ID;")" "SF1234567890"

check "I3 录入单号**不推进状态**，仍在 6 态" \
  "$(db "select status from orders where id=$I1_ID;")" "6"

# ── 下面四条必须紧挨着 I3、在 6→7 之前跑完 ──
# 它们在别的状态上也会失败，而且失败消息**恰好就是期望的那句**：
# 后面 I5 把单推到 7 态之后，任何坏输入都会报"只有派送中"。
# 顺序反了的话，长度校验有没有写、员工校验有没有生效，全都看不出来 —— 一条全绿的假绿
check "I4 顾客 token 录单号 → 401 请使用员工账号操作" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/express?expressNo=SF999" \
     -H "Authorization: Bearer $CUST1")" "请使用员工账号操作"

check "I5 少了 expressNo 参数 → 400，不是 500" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/express" \
     -H "Authorization: Bearer $MGR_T")" "缺少参数"

# express_no 是 VARCHAR(50)：没有这道长度校验的话，51 个字会一路走到 UPDATE
# 才被 MySQL 弹回来（DataTooLong 英文异常 → 500），而不是一句给用户看的话
check "I6 超过 50 个字符 → 400（不是让 MySQL 抛 DataTooLong 变 500）" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/express?expressNo=XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX" \
     -H "Authorization: Bearer $MGR_T")" "不能超过"

check "I7 空白串 → 400（前端把 \"   \" 原样提交是最常见的坏输入）" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/express?expressNo=%20%20%20" \
     -H "Authorization: Bearer $MGR_T")" "不能为空"

check "I7b 四次坏输入之后库里还是原来那个单号（没有半截写入）" \
  "$(db "select express_no from orders where id=$I1_ID;")" "SF1234567890"

check "I8 再推进 6→7 → 200（网单已付清，放行）" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/next" -H "Authorization: Bearer $MGR_T")" '"code":200'

check "I9 已完成的单再补录 → 400 派送中（事后补票会让运单和订单状态对不上）" \
  "$(curl -s -X POST "$BASE/api/orders/$I1_ID/express?expressNo=SF999" \
     -H "Authorization: Bearer $MGR_T")" "派送中"

# ORDER1 是门店单（已到 7 态）—— 来源检查在状态检查之前，所以报的是"只有网单"。
# 这一条也钉住了检查顺序：反过来就会报"派送中"，而那句在 7 态下没有信息量
check "I10 门店单录单号 → 400 只有网单（衣服在店里等顾客来取）" \
  "$(curl -s -X POST "$BASE/api/orders/$ORDER1_ID/express?expressNo=SF999" \
     -H "Authorization: Bearer $MGR_T")" "只有网单"

echo
echo "########## J. 网单门店可选（2026-09-13 口径）##########"
# 新口径：顾客**可以不选**门店，不选就存 NULL。所以这一节是成对的"两半" ——
# 少了 J1（null 放行），J3 那个 400 可能来自"没传门店"而不是"店是坏的"；
# 少了 J3，把 if 条件写成永假也能让 J1 通过（校验整个被删掉没人发现）

# storeId 显式传 null，而不是省略这个键：两者对后端等价，但显式写出来
# 才是"我确实没选"，也让这条用例的意图在脚本里自解释
J_NULL=$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
  -H "Authorization: Bearer $CUST1" \
  -d '{"source":2,"storeId":null,"deliveryAddress":"Hangzhou Xihu Rd 200","items":[{"categoryId":11,"washTypeId":1,"quantity":1}]}')
J_NULL_ID=$(jqf "$J_NULL" id)

check "J1 网单不指定门店 → 200（在线顾客本来就没有门店归属）" "$J_NULL" '"code":200'
check "J2 库里 store_id 是 NULL，订单本身照常落库（15.00，1 态待支付）" \
  "$(db "select concat_ws('|',ifnull(store_id,'NULL'),source,status,total_amount) from orders where id=$J_NULL_ID;")" \
  "NULL|2|1|15.00"

# J1 的反证：校验只是改成"给了才校"，没有被整个删掉
check "J3 指定一个不存在/已停业的门店（999）→ 400" \
  "$(curl -s -X POST $BASE/api/orders -H "Content-Type: application/json" \
     -H "Authorization: Bearer $CUST1" \
     -d '{"source":2,"storeId":999,"deliveryAddress":"Hangzhou Xihu Rd 300","items":[{"categoryId":11,"washTypeId":1,"quantity":1}]}')" \
  "门店不存在或已停业"

# 门店单不受影响：它的门店取自店长 token，永远有值（口径变更只动了网单那一支）
check "J4 门店单的 store_id 仍然落在店长自己那家店（1）" \
  "$(db "select ifnull(store_id,'NULL') from orders where id=$ORDER1_ID;")" "1"

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
