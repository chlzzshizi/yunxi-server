#!/bin/bash
# 认证域接口验收（真机：MySQL + Redis + 8081）
#
# 用法：bash scripts/verify-auth.sh     从任何目录都行
# 前置：docker compose up -d  且后端已在 8081 起着
#
# 为什么单独给认证写一个脚本：
#   其余 5 个脚本都只把登录当成"拿 token 的手段"，登录本身的对错无人断言——
#   5 处登录调用，0 条关于认证的断言。而 2026-09-11 的分层整改要把这几段规则
#   （账号停用、密码校验、手机号查重）从 controller 搬进应用服务。
#   搬家前先立一张网：**当前代码下它必须全绿**（characterization test，钉住现状），
#   搬完再跑还是绿的，才叫"行为没变"。
#
#   2026-09-12：D 段从"探针"升格成断言。当初只是打印看看，结果照出一个真问题——
#   缺 password 时 BCrypt 的英文原文 "rawPassword cannot be null" 被原样回给前端
#   （Bug 23）。修完之后这些行为就该被钉死，不再只是"拍张照"。
#
#   2026-09-12 晚：新增 E 段。注册口径改成"只要手机号 + 密码"，顺带修掉一个
#   **死循环**——门店单顾客（有名字、没密码）注册说"已注册请登录"、登录说
#   "未设置密码请先注册"，两句话互相指着对方。E 段钉住新的激活行为。
#
# 脏数据：一个停用账号（staff id=98, mgr_disabled）、一个无密码的门店单顾客
#   （13700000002, WalkIn）。两者都是 insert ignore，可重复跑。
#   每次运行新注册的顾客会在结尾删掉（见 F 段）。
#
# **E 段会改脚手架**（给 WalkIn 设密码），所以 F 段必须把它还原回 NULL——
# 这个脚本自己改的前置条件，得自己恢复，否则第二次跑必红。
BASE=http://localhost:8081
PASS=0; FAIL=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
# --default-character-set=utf8mb4：mysql 命令行默认按 latin1 收发，
# 读中文会整串变 ?????、写中文会存成双重编码 —— 说明见 verify-stores.sh 文件头
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }

# 每次运行用不同手机号：固定号第二次跑就变"重复注册"，B1 会莫名其妙红
PHONE_NEW="137$(date +%s | tail -c 9)"
PHONE_ABSENT="138$(date +%s | tail -c 9)"
PHONE_NOPWD="13700000002"

echo "########## 准备：脚手架 ##########"

# 停用账号（status=0）。密码哈希是 admin123 的 BCrypt——这里必须写死在 SQL 里，
# 因为要的就是"账号密码都对、但被停用"这个组合
#
# SQL 走 stdin 而不是 -e（2026-09-13 改，见 Bug 30）：这一行里有中文（'停用店长'），
# 而中文一旦进 `-e "…"` 就会被转两次码 —— MSYS2 先把命令行参数转成 GBK，latin1 的
# mysql 客户端再转一道，落库就是双重编码的乱码。这里原先还**漏了**
# --default-character-set=utf8mb4，两个坑一起踩：staff id=98 的名字从建出来那天
# 就是乱的，而且没有任何断言看它，所以乱着也没人喊（和 Bug 24 同形状，当时只修了别的脚本）
STAFF_DISABLED_NAME="停用店长"
printf '%s\n' "insert ignore into staff (id,username,password,name,role,store_id,status)
   values (98,'mgr_disabled','\$2a\$10\$fEzKJTH469Zd9GB0CKMLseS/iFVndCGene.WQiQ53Q/isi2yZa5oS','$STAFF_DISABLED_NAME',1,1,0);
   update staff set name='$STAFF_DISABLED_NAME' where id=98;
   insert ignore into customers (name,phone,password) values ('WalkIn','$PHONE_NOPWD',null);
   update customers set password=null where phone='$PHONE_NOPWD';" \
  | docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi \
           --default-character-set=utf8mb4 2>/dev/null

# 准备阶段守卫（Bug 22 教训）：前置条件不成立就立刻停，别让它继续跑成断言失败
D_STATUS=$(db "select status from staff where username='mgr_disabled';")
if [ "$D_STATUS" != "0" ]; then
  echo "==> [准备失败] mgr_disabled 的 status 应为 0，实际 '$D_STATUS'"; exit 1
fi
# 名字的字节也必须盯住。这条原先没有 —— 而"没有断言看着的中文列"正是会静悄悄烂掉的列：
# 乱码不报错、不影响 A5（停用账号的登录被拒和名字无关），只会一直躺在库里等人肉眼撞见
D_NAME=$(db "select name from staff where username='mgr_disabled';")
if [ "$D_NAME" != "$STAFF_DISABLED_NAME" ]; then
  echo "==> [准备失败] mgr_disabled 的名字字节不对"
  echo "              期望 '$STAFF_DISABLED_NAME'"
  echo "              实际 '$D_NAME'（乱码说明插入时被转码了）"; exit 1
fi
NP_PWD=$(db "select ifnull(password,'<NULL>') from customers where phone='$PHONE_NOPWD';")
if [ "$NP_PWD" != "<NULL>" ]; then
  echo "==> [准备失败] $PHONE_NOPWD 的 password 应为 NULL，实际 '$NP_PWD'"; exit 1
fi
echo "  脚手架就绪：mgr_disabled(停用)  $PHONE_NOPWD(无密码顾客)  新号 $PHONE_NEW"
echo

echo "########## A. 员工登录 ##########"

STAFF_OK=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"manager","password":"admin123"}')
check "A1 正确凭据 → 200" "$STAFF_OK" '"code":200'
MGR_T=$(jqf "$STAFF_OK" token)
if [ -n "$MGR_T" ]; then
  echo "  [OK]   A1b 返回了非空 token（${#MGR_T} 字符）"; PASS=$((PASS+1))
else
  echo "  [FAIL] A1b token 为空"; FAIL=$((FAIL+1))
fi

check "A2 密码错 → 401 用户名或密码错误" \
  "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
     -d '{"username":"manager","password":"wrong-password"}')" \
  "用户名或密码错误"

# 用户名不存在必须报**同一句话**，否则攻击者能靠报错差异枚举出哪些用户名存在
check "A3 用户名不存在 → 401 同一句（不可枚举用户名）" \
  "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
     -d '{"username":"no-such-user-xyz","password":"admin123"}')" \
  "用户名或密码错误"

check "A4 密码对但账号被停用 → 403 账号已被停用" \
  "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
     -d '{"username":"mgr_disabled","password":"admin123"}')" \
  "账号已被停用"

check "A5 拿到的 token 能过拦截器（证明 token 真的可用）" \
  "$(curl -s "$BASE/api/categories" -H "Authorization: Bearer $MGR_T")" '"code":200'

echo
echo "########## B. 顾客注册 ##########"

REG=$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
  -d "{\"name\":\"AuthProbe\",\"phone\":\"$PHONE_NEW\",\"password\":\"123456\"}")
check "B1 新手机号注册 → 200（注册即登录，直接给 token）" "$REG" '"code":200'
CUST_T=$(jqf "$REG" token)

check "B2 同一手机号再注册 → 400 该手机号已注册" \
  "$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
     -d "{\"name\":\"AuthProbe\",\"phone\":\"$PHONE_NEW\",\"password\":\"123456\"}")" \
  "该手机号已注册"

check "B3 缺 phone → 400" \
  "$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
     -d '{"password":"123456"}')" \
  "手机号和密码不能为空"

# 2026-09-12 起注册只要手机号+密码。B1 那个请求体里仍然带着 name，
# 它必须**照样 200** —— 否则前端还没改就会整片挂掉（Spring 收成 Map，
# 用不上的键自然丢掉，不该报错）
check "B3b 多传一个用不上的 name 也不报错（老前端兼容）" "$REG" '"code":200'
checkNot "B3c 新顾客的档案里没有姓名（线上注册确实不知道他是谁）" \
  "$(db "select ifnull(name,'<NULL>') from customers where phone='$PHONE_NEW';")" \
  'AuthProbe'

# Bug 5 教训：哈希必须由 encoder 生成，不能手写。这里查库看真实字节
# [$] 是 grep 里的字面 $，避免在 shell 里转义得眼瞎
HASH=$(db "select password from customers where phone='$PHONE_NEW';")
check "B4 入库的是 BCrypt 哈希（查库，不是看接口回显）" "$HASH" '^[$]2a[$]10[$]'
checkNot "B4b 库里不是明文密码" "$HASH" '123456'

echo
echo "########## C. 顾客登录 ##########"

check "C1 正确凭据 → 200" \
  "$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_NEW\",\"password\":\"123456\"}")" '"code":200'

check "C2 密码错 → 401 手机号或密码错误" \
  "$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_NEW\",\"password\":\"wrong-password\"}")" \
  "手机号或密码错误"

check "C3 手机号不存在 → 401 同一句（不可枚举手机号）" \
  "$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_ABSENT\",\"password\":\"123456\"}")" \
  "手机号或密码错误"

# 门店单顾客（到店自动建档，password 为 NULL）——password 为 null 时直接
# matches 会抛异常，必须显式挡掉，所以这条是个正儿八经的规则
check "C4 无密码的门店单顾客 → 401 该手机号未设置密码（不是 500）" \
  "$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_NOPWD\",\"password\":\"123456\"}")" \
  "该手机号未设置密码，请先注册"

check "C5 顾客 token 能过拦截器" \
  "$(curl -s "$BASE/api/orders?page=1&pageSize=1" -H "Authorization: Bearer $CUST_T")" '"code":200'

echo
echo "########## D. 缺参数：也不许把内部原文漏出去（Bug 23）##########"
# 这段原来是"探针"（只打印、不断言）。当时三个缺 password 的请求全回：
#   {"code":400,"message":"rawPassword cannot be null"}
# —— BCryptPasswordEncoder.matches(null,..) 抛的英文原文被全局处理器原样透传。
#
# 修完之后的规矩：**用户看得到的 message 里不许有英文**。框架异常的消息都是英文、
# 写给开发者的，这就是它的指纹；业务失败一律回中文那句。所以每条都验两件事：
# 回的是哪个码哪句话，以及消息里有没有混进英文。

D1=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
       -d '{"username":"manager"}')
check   "D1 员工登录缺 password → 401（不是 400「参数不正确」）" "$D1" '"code":401'
check   "D1b 消息与「密码错」逐字相同" "$D1" "用户名或密码错误"
checkNot "D1c 消息里没有英文原文（rawPassword…）" "$(jqf "$D1" message)" '[A-Za-z]'

# 缺 username 走的是"查库查不到"（SQL `username = NULL` 永不成立），同样是 401 那一句。
# 这条盯的是：别哪天给 mapper 加个动态 if，把 null 变成"参数错误"
D2=$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
       -d '{"password":"admin123"}')
check   "D2 员工登录缺 username → 401 同一句" "$D2" "用户名或密码错误"
checkNot "D2b 消息里没有英文" "$(jqf "$D2" message)" '[A-Za-z]'

D3=$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
       -d "{\"phone\":\"$PHONE_NEW\"}")
check   "D3 顾客登录缺 password → 401 手机号或密码错误" "$D3" "手机号或密码错误"
checkNot "D3b 消息里没有英文" "$(jqf "$D3" message)" '[A-Za-z]'

# 守卫放在 hasPassword 之后：门店单顾客没带密码时，该看到的仍是"去注册"，
# 而不是通用那句 —— 提前拦等于把唯一的出路藏起来
D4=$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
       -d "{\"phone\":\"$PHONE_NOPWD\"}")
check "D4 门店单顾客 + 没带密码 → 仍回「未设置密码，请先注册」" "$D4" "该手机号未设置密码，请先注册"

D5=$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
       -d "{\"phone\":\"$PHONE_ABSENT\"}")
check   "D5 顾客注册缺 password → 400 手机号和密码不能为空" "$D5" "手机号和密码不能为空"
checkNot "D5b 消息里没有英文" "$(jqf "$D5" message)" '[A-Za-z]'

echo
echo "########## E. 注册口径（2026-09-12：只要手机号 + 密码）##########"
# 这一段守的是一个**曾经真实存在的死循环**：
#   门店单顾客（柜台建档：有名字、没密码）想线上注册
#     → 注册回 400「该手机号已注册，请直接登录」
#     → 他去登录，回 401「该手机号未设置密码，请先注册」
#   两句话互相指着对方，他永远进不来。
# 现在改成：手机号已存在**且没有密码** → 给他补上密码（激活），不是报错。

E_STATUS=$(db "select ifnull(password,'<NULL>') from customers where phone='$PHONE_NOPWD';")
if [ "$E_STATUS" != "<NULL>" ]; then
  echo "==> [准备失败] $PHONE_NOPWD 跑 E 段前必须是「无密码」状态，实际 '$E_STATUS'"; exit 1
fi

E1=$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
       -d "{\"phone\":\"$PHONE_NOPWD\",\"password\":\"newpwd123\"}")
check "E1 无密码老顾客注册 → 200（激活，不再是「已注册」）" "$E1" '"code":200'
E_T=$(jqf "$E1" token)
if [ -n "$E_T" ]; then
  echo "  [OK]   E1b 直接拿到了 token（注册即登录）"; PASS=$((PASS+1))
else
  echo "  [FAIL] E1b 没拿到 token"; FAIL=$((FAIL+1))
fi

check "E2 补进去的是 BCrypt 哈希（不是明文）" \
  "$(db "select password from customers where phone='$PHONE_NOPWD';")" '^[$]2a[$]10[$]'
check "E2b 而且验的是刚设的那个密码" \
  "$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_NOPWD\",\"password\":\"newpwd123\"}")" '"code":200'

# 激活**不是新建**：还是那一行，id 没变、人数没变
check "E3 激活后该手机号仍然只有一行（没建出新顾客）" \
  "$(db "select count(*) from customers where phone='$PHONE_NOPWD';")" '^1$'
check "E3b 名字还在（激活只补密码，没碰别的字段）" \
  "$(db "select name from customers where phone='$PHONE_NOPWD';")" 'WalkIn'

# 已经有密码的人再来注册，才是真的重复注册
check "E4 已激活的号再注册 → 400 该手机号已注册" \
  "$(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_NOPWD\",\"password\":\"another-pwd\"}")" \
  "该手机号已注册，请直接登录"
check "E4b 而且密码没被后一次注册覆盖掉（还是 newpwd123 能登）" \
  "$(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
     -d "{\"phone\":\"$PHONE_NOPWD\",\"password\":\"newpwd123\"}")" '"code":200'

echo
echo "########## F. 收尾：清掉本次造的数，还原脚手架 ##########"
if [ -n "$PHONE_NEW" ] && [ ${#PHONE_NEW} -eq 11 ]; then
  db "delete from customers where phone='$PHONE_NEW';"
  echo "  已删除本次注册的 $PHONE_NEW（脚手架 mgr_disabled / WalkIn 保留，供下次复用）"
else
  echo "  [跳过] PHONE_NEW 不合法（'$PHONE_NEW'），不执行删除"
fi

# E 段给 WalkIn 设了密码。必须还原成 NULL，否则下次跑脚本时准备阶段的
# 那条守卫（NP_PWD 必须是 <NULL>）会直接 exit 1 —— 脚本自己把自己的前置条件毁了。
# 「会改数据的脚本要自己还原」这条先例见 verify-price-write.sh
db "update customers set password=null where phone='$PHONE_NOPWD';"
RESTORED=$(db "select ifnull(password,'<NULL>') from customers where phone='$PHONE_NOPWD';")
if [ "$RESTORED" = "<NULL>" ]; then
  echo "  已还原 $PHONE_NOPWD 的密码为 NULL（脚手架回到初始状态）"
else
  echo "  [FAIL] 还原 $PHONE_NOPWD 失败，实际 '$RESTORED' —— 下次跑会准备失败"
  FAIL=$((FAIL+1))
fi

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
[ $FAIL -eq 0 ]
