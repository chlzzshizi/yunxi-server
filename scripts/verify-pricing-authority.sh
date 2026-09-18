#!/bin/bash
# 第 3 步验收：后端算价 + 事务（真机：MySQL + Redis + 8081）
#
# 三个命题：
#   一、单价只认 clothes_prices —— 请求体里塞 unitPrice:1.00 也不会生效
#   二、不支持的组合 → 400 且能指导用户；价目表缺行 → 400（不是 500）
#   三、一次建单是一个事务 —— 明细写不进去时订单也不能留下
BASE=http://localhost:8081
TMP="${TMPDIR:-/tmp}/yunxi-e2e"; mkdir -p "$TMP"    # 请求体落盘用，不污染仓库
PASS=0; FAIL=0

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
# 直接查库：接口说算成 15 不算，库里是 15 才算。
# 故意**不**吞 stderr —— 藏掉 SQL 报错会让"查询失败"看起来像"0 行"
# --default-character-set=utf8mb4 是必须的：mysql 命令行默认按 latin1 收发，
# 读中文会整串变 ?????、写中文会存成双重编码 —— 完整说明见 verify-stores.sh 文件头
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>&1 \
       | tr -d '\r' | grep -v "password on the command line"; }

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

echo "########## 准备 ##########"
MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)
ADMIN_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')" token)

# 顾客脚手架 13900000091 **不是本脚本建的**，是 verify-price.sh 建的。通配符顺序里
# 它排在本脚本**前面**（"price." < "pricing"：e(0x65) < i(0x69)），所以**九连跑时**它总在。
# 但"总在"靠的是**别人留下的残留**，不是本脚本的前提：清过库、或单跑本脚本，它就不在。
# 那时原写法只 login → 拿到**空票**，$CUST_ID 也会是空串，于是每个请求体退化成
# {"customerId":,...} 这种畸形 JSON —— A/B/C/D/E 会红成一片"算价坏了、事务坏了"，
# 而 F 段还会**假绿**（F1 断言"不是 200"、F2/F3 断言"前后没变"：建单压根没发出去，
# 三条都无条件成立）。这是 **Bug 38** 的同一个形状，只是这次会从 B 段开始爆。
CUST_T=$(login_or_register PricingAuthorityCust 13900000091)
CUST_ID=$(db "select id from customers where phone='13900000091';")

# 脚手架自检（**Bug 38** 的规矩："就绪"必须是断言，不能是台词 —— 原先这里就是一句
# 无条件的 echo，票空着、id 空着也照印"就绪"）。
# 印长度不印票面值：空/非空一眼可判，也不把票写进 CI 的日志产物。
# CUST_ID **判的是形状不是有无**：本脚本的 db() 故意不吞 stderr（见上面的注释），
# MySQL 一挂它返回的就是 docker 的报错文本 —— 非空，但根本不是 id，照样打成畸形 JSON。
BAD=""
[ -z "$MGR_T" ]   && BAD="$BAD manager票空"
[ -z "$ADMIN_T" ] && BAD="$BAD admin票空"
[ -z "$CUST_T" ]  && BAD="$BAD customer票空"
case "$CUST_ID" in
  ''|*[!0-9]*) BAD="$BAD customerId($CUST_ID)不是数字";;
esac
if [ -n "$BAD" ]; then
  echo "==> [准备失败] 脚手架没就绪：$BAD"
  echo "             票长 manager=${#MGR_T} admin=${#ADMIN_T} customer=${#CUST_T}"
  echo "             后端是否在 8081？manager/admin123 与 admin/admin123 能否登录？"
  exit 1
fi
# 失败行一律 `==> [准备失败]`：九个脚本统一这一个串，方便在 CI 日志里一把 grep
# （本脚本原先写的是 `  [致命]`，是第 0 步新写的两处之一，与其余六个不一致）
echo "  manager/admin/customer token 就绪（${#MGR_T} / ${#ADMIN_T} / ${#CUST_T} 字符）；顾客 13900000091 的 id = $CUST_ID"

# 建单 <token> <body>
# body 落文件再 --data-binary：MSYS2 会把命令行参数里的非 ASCII 转成 GBK，
# 带中文的请求体（如 C1 的配送地址）会被服务端当成非法 UTF-8 直接 400，
# 而报错完全指不到编码上。$2 是 shell 内部变量，不跨进程边界，写文件是字节安全的。
# 固定文件名就够：本脚本的 create 全是串行调用，后一次覆盖前一次
# （跟着 verify-stores.sh 的 postJson 走同一个约定，别改成计数器 ——
#  调用点写成 $(create ...) 时是子 shell，计数器自增回传不出来，白搭）
create() { printf '%s' "$2" > "$TMP/req-create.json"
           curl -s -X POST "$BASE/api/orders" -H "Content-Type: application/json" \
                -H "Authorization: Bearer $1" --data-binary "@$TMP/req-create.json"; }
# 从建单响应里取订单号，再用订单号查库拿 id（比从 JSON 里抠 id 稳：
# 明细也有 id 字段，抠错了会查到别的行）
orderIdOf() { db "select id from orders where order_no='$(jqf "$1" orderNo)';"; }

echo
echo "########## A. 鉴权：算价不能绕过身份 ##########"
check "A1 顾客 token 建门店单（source=1）→ 401 请使用员工账号" \
  "$(create "$CUST_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}")" \
  "请使用员工账号操作"

# 2026-09-11 口径：定价**写**接口只认店长，管理员 403（管理员只管人与店）。
# 闸门在 JwtInterceptor.checkRoleGate，按 "PUT /api/prices/**" 收口。
# 验三件事：被拒 / 理由说的是"不能改价" / **价格真的没被动过**——
# 只看 403 不够：一个"先改价再报 403"的实现也能骗过前两条
check "A2 管理员 token 改价 → 403（管理员不能修改价格）" \
  "$(curl -s -X PUT "$BASE/api/prices/11" -H "Content-Type: application/json" \
     -H "Authorization: Bearer $ADMIN_T" \
     -d '{"prices":[{"washTypeId":1,"price":99.00}]}')" \
  "管理员不能修改价格"
check "A3 被拒之后价目表没被动过（衬衫普洗仍是 15.00）" \
  "$(db "select price from clothes_prices where category_id=11 and wash_type_id=1;")" "15.00"

echo
echo "########## B. 算价权威：请求体里的 unitPrice 不作数 ##########"
# 门店单，衬衫(=11) 普洗(=1) ×2，价目表里是 15.00 → 应该是 30.00。
# 请求体里**故意**塞 unitPrice:1.00（老前端就是这么传的），既要不报错也要不生效
B1=$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":2,\"unitPrice\":1.00}]}")
check "B1 老前端多传 unitPrice 不报错（200，字段被忽略）" "$B1" '"code":200'
B1_ID=$(orderIdOf "$B1")
check "B2 库里总价 = 30.00（15.00 × 2，不是 1.00 × 2）" \
  "$(db "select total_amount from orders where id=$B1_ID;")" "30.00"
check "B3 库里明细单价 = 15.00（价目表的值写进去了）" \
  "$(db "select unit_price from order_items where order_id=$B1_ID;" | head -1)" "15.00"
check "B4 响应里的 unitPrice 也是 15.00（前端直接渲染，不用自己算）" \
  "$B1" '"unitPrice":15.00'

echo
echo "########## C. 网单也走同一套算价 ##########"
# deliveryAddress 是**必须**带的：2026-09-12 起网单没地址会被领域层挡下（400），
# 那这条就不是在测算价了。（这条规则本身由 verify-stores.sh 的 C 段负责验）
C1=$(create "$CUST_T" "{\"storeId\":1,\"source\":2,\"deliveryAddress\":\"杭州市西湖区文一西路 100 号\",\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1,\"unitPrice\":0.01}]}")
check "C1 顾客自助下单（塞 unitPrice:0.01）→ 200" "$C1" '"code":200'
C1_ID=$(orderIdOf "$C1")
check "C2 库里总价 = 15.00（1 分钱洗衬衫的漏洞已堵）" \
  "$(db "select total_amount from orders where id=$C1_ID;")" "15.00"
check "C3 网单没有操作员工（staff_id 为 NULL）" \
  "$(db "select ifnull(staff_id,'NULL') from orders where id=$C1_ID;")" "NULL"

echo
echo "########## D. 多明细：价格逐条查、总价逐条加 ##########"
D1=$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[
  {\"categoryId\":11,\"washTypeId\":1,\"quantity\":1},
  {\"categoryId\":11,\"washTypeId\":2,\"quantity\":1},
  {\"categoryId\":11,\"washTypeId\":3,\"quantity\":1}]}")
check "D1 三条明细（衬衫 普洗+精洗+单熨）→ 200" "$D1" '"code":200'
D1_ID=$(orderIdOf "$D1")
check "D2 总价 = 58.00（15 + 35 + 8，每条的价都对上）" \
  "$(db "select total_amount from orders where id=$D1_ID;")" "58.00"
check "D3 明细 3 行都落库了" \
  "$(db "select count(*) from order_items where order_id=$D1_ID;")" "3"

echo
echo "########## E. 拒绝的理由要能指导用户（400，不是 500）##########"
check "E1 分类 999 不存在 → 400 带明细序号（配置缺口）" \
  "$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[{\"categoryId\":999,\"washTypeId\":1,\"quantity\":1}]}")" \
  "第 1 条明细的衣物分类或洗涤方式不存在"
check "E2 羽绒服(=13) 普洗(=1) 价格为 0 → 400" \
  "$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[{\"categoryId\":13,\"washTypeId\":1,\"quantity\":1}]}")" \
  "「羽绒服」不支持「普洗」，请更换洗涤方式"
check "E3 第 2 条明细出错时序号是 2（不是永远第 1 条）" \
  "$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[
     {\"categoryId\":11,\"washTypeId\":1,\"quantity\":1},
     {\"categoryId\":13,\"washTypeId\":1,\"quantity\":1}]}")" \
  "第 2 条明细「羽绒服」不支持「普洗」"
check "E4 数量 0 → 400（形状校验在算价之前）" \
  "$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":0}]}")" \
  "数量必须为正整数"

echo
echo "########## F. 事务：明细写不进去，订单也不能留下 ##########"
# photos 列是 VARCHAR(1000)，塞 2000 字符让 order_items 插入失败。
# 重试循环只吞 DuplicateKeyException，这个错会直接往外抛 → 事务必须回滚
LONG=$(printf 'a%.0s' $(seq 1 2000))
BEFORE_ORDERS=$(db "select count(*) from orders;")
BEFORE_ITEMS=$(db "select count(*) from order_items;")
F1=$(create "$MGR_T" "{\"customerId\":$CUST_ID,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1,\"photos\":\"$LONG\"}]}")
checkNot "F1 超长 photos → 建单失败（不是 200）" "$F1" '"code":200'
check "F2 订单总数没变（半截订单被回滚掉了）" \
  "$(db "select count(*) from orders;")" "$BEFORE_ORDERS"
check "F3 明细总数没变" \
  "$(db "select count(*) from order_items;")" "$BEFORE_ITEMS"

echo
echo "########## G. 订单号重试所依赖的机制：InnoDB 只回滚失败的语句 ##########"
# 重试循环在事务内吞掉 DuplicateKeyException 再试一次 —— 这只有在
# "一条语句失败后事务仍可用"的前提下才成立。直接验这个前提：
# 同一个事务里，先故意撞一次唯一键，再插一行好数据，看它还插不插得进去。
#
# 必须走 stdin 批量模式：`mysql -e "..." --force` **不会**在出错后继续
# （-e 是一次性执行，--force 只对批量模式生效）—— 这个坑是这个脚本自己踩出来的，
# 当时表现为"事务被废掉了"的假警报，其实是测试工具没继续往下跑
TX=$(printf '%s\n' \
  'start transaction;' \
  'insert into clothes_prices (category_id, wash_type_id, price) values (11, 1, 15.00);' \
  'insert into clothes_prices (category_id, wash_type_id, price) values (999, 1, 1234.56);' \
  'select price from clothes_prices where category_id = 999;' \
  'rollback;' \
  | docker exec -i yunxi-mysql mysql --force -uroot -pqwaszx123 yunxi 2>&1 \
  | tr -d '\r' | grep -v "password on the command line")
check "G1 第一条 insert 确实撞了唯一键（用例本身有效）" "$TX" "Duplicate entry"
check "G2 撞键之后的 insert 仍然成功（事务没被废掉）" "$TX" "1234.56"
check "G3 rollback 之后那行不在了（探针没留垃圾）" \
  "$(db "select count(*) from clothes_prices where category_id=999;")" "0"

echo
echo "########## H. 收尾：不变量仍然成立 ##########"
check "H1 有普洗的品类精洗仍 = 普洗 + 20" \
  "$(db "select count(*) from clothes_prices p where p.wash_type_id=1 and p.price>0
        and (select price from clothes_prices q where q.category_id=p.category_id and q.wash_type_id=2) <> p.price+20;")" \
  "0"
echo "  本次新建的订单：门店单 $B1_ID / $D1_ID，网单 $C1_ID（脏数据，收尾时清）"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"

# 退出码就是断言结果 —— CI 用 `if bash "$s"` 判成败（.github/workflows/ci.yml:157），
# 而在 **Bug 40** 之前本脚本最后一行是 echo：**永远退 0**。断言红成一片，
# CI 照样打 OK（汇总行里那串"通过 X 项，失败 Y 项"还会照印，但 job 不会失败）——
# 假绿从"断言层"搬到了"汇总层"，而这一层没有任何断言在看着它。
# 注意 F 段那三条是**条件恒真**的（建单压根没发出去时 F1/F2/F3 无条件通过），
# 准备段那个 CUST_ID 形状守卫是它们唯一的兜底 —— 现在再加一层：退出码。
# admin / auth / stores 三个一直是对的（它们本来就有这一行），这行是照它们补的。
[ $FAIL -eq 0 ]
