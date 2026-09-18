#!/bin/bash
# 门店 + 顾客接口验收（真机：MySQL + Redis + 8081）
#
# 用法：bash scripts/verify-stores.sh     从任何目录都行
# 前置：docker compose up -d  且后端已在 8081 起着
#
# 覆盖第 5 步的两件事：
#   /api/stores                      —— 只返回营业中的门店
#   /api/customers/lookup-or-create  —— 手机号查/建档，同号永远同一个顾客
#
# 2026-09-12 晚：新增 D 段。注册口径改成"只要手机号+密码"之后，姓名挪到了
#   /api/customers/me                —— 顾客在个人中心看/改自己的档案
# 它和 B 段**同一个前缀、同一个资源**，只是操作的人不同（B 是员工代客、
# D 是顾客本人）。所以 D 段除了功能，还要钉住三件安全上的事：
#   · 顾客 token 改的一定是自己（请求体里塞 customerId 也改不了别人）
#   · 员工 token 进不来（401 请使用顾客账号）
#   · 管理员被闸门按**整个前缀**挡在外面（403）
#
# 五件事这个脚本要**真的证明**，不能只是"看起来对"：
#   1. 门店列表是**过滤**出来的，不是"停业店压根没建上" —— 所以断言两次：
#      列表里没有它（A3）、且它确实**以正确的字节**在库里且 status=0（A3b）。
#      少一条就是空断言。
#   2. created 是 true→false，不是两次都 true（那就等于每次都在建人）
#   3. 出参里没有 password —— 这是 CustomerView 存在的首要理由。
#      单测用反射钉了字段名，这里钉真实的 JSON 字节
#   4. 越权改名不成 —— 单说"我改成了自己的名字"是不够的，
#      必须同时断言"别人的档案一个字没动"，否则那可能是句空话
#   5. 姓名超 20 字 → 400 而不是 500（name 列是 VARCHAR(20)，
#      不拦就一路走到 INSERT 才被 MySQL 弹回来，报的是英文 SQL 异常）
#
# ══════ 中文会以两种方式坏掉（2026-09-12 两个都踩了）══════
#
# 【坑一】请求体里的中文：MSYS2 把非 ASCII 命令行参数交给原生 exe（curl.exe）
#   之前，会按当前 Windows 代码页（中文系统 = GBK）转一遍。实测：
#     curl -d '{"username":"中文测试"}' 发出 23 字节
#     --data-binary @文件             发出 27 字节
#   差的 4 字节正是 UTF-8(12) 与 GBK(8) 对「中文测试」的差。服务端收到的是
#   GBK 字节、不是合法 UTF-8，Jackson 直接回 400「请求体格式不正确」——
#   而报错完全指不到编码上。**修法：postJson/putJson（落文件 + --data-binary @）**
#
# 【坑二】SQL 和查库里的中文：mysql 命令行默认按 **latin1** 收发
#   （`show variables` 里 character_set_client / character_set_results 都是
#   latin1），跟 MSYS 没关系，是客户端自己的默认值。两个方向都坏：
#     写 → UTF-8 字节被当 latin1 解读、再转存 utf8mb4 = **双重编码**
#          （实测存进去的 hex 是 C3A4C2BAE28098…）
#     读 → 真 UTF-8 的中文转不成 latin1，整串变成 ?????
#   最阴的是**双重编码在读的时候会抵消回去**：存的是乱的，SELECT 出来却是好的。
#   于是"列表里不含这个店名"这类断言**无条件通过** —— 库里那个名字本来就写不出
#   正确的那六个字，怎么断言都找不到它。
#   **修法：db() 和 dbFile() 都必须带 `--default-character-set=utf8mb4`**
#
# 规矩：
#   · 带请求体的调用一律走 postJson/putJson，不要用裸 curl -d
#   · 带中文的 SQL 走 dbFile（stdin），且两个 db 助手都要带 utf8mb4
#   · 脚本内可以放心写中文 —— printf、变量、重定向都是字节安全的
#   · **造完数据必须断言库里的字节**（准备守卫 + A3c），否则乱码会让
#     "不存在"类断言假绿。这是本脚本最该记住的一条
#
# 本脚本是**踩过这两个坑的**：第一版把所有中文写在 -d 里，脚本从写出来那天
# 起就跑不了；用 -e 插的脚手架那行至今是乱码。verify-orders.sh 早就在用
# payload 文件绕坑一（见它 B 段注释），当时没跟那条约定。见 docs/bug-record.md
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
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
# --default-character-set=utf8mb4 **不能省**：mysql 命令行默认按 latin1 收发，
# 省了它会有两种完全相反的假象：
#   读：真 UTF-8 的中文转不成 latin1 → 全变成 ?????（看着像"库坏了"，其实是显示坏了）
#   写：UTF-8 字节被当 latin1 解读再转存 → **双重编码**（C3A4C2BAE28098…）
# 更阴的是双重编码在**读**的时候又会抵消回去 —— 存的是乱的、SELECT 出来却是好的，
# 于是"库里没有这个店名"这类断言会无条件通过。踩过，见 docs/bug-record.md
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }

# 本脚本**所有**带请求体的调用都走这三个（$2 可以含中文：它是 shell 内部变量，
# 不跨进程边界，所以不会被转码）。统一走一条路，是为了让加用例的人不需要
# 逐个判断"这个体里有没有中文"—— 判断本身就是要出错的地方。
# token 传空串 = 不带 Authorization 头（用来测"未登录"那条）
postJson() { printf '%s' "$2" > "$TMP/req-post.json"
             if [ -n "$3" ]; then
               curl -s -X POST "$BASE$1" -H "Authorization: Bearer $3" \
                    -H "Content-Type: application/json" --data-binary @"$TMP/req-post.json"
             else
               curl -s -X POST "$BASE$1" \
                    -H "Content-Type: application/json" --data-binary @"$TMP/req-post.json"
             fi; }
putJson() {  printf '%s' "$2" > "$TMP/req-put.json"
             if [ -n "$3" ]; then
               curl -s -X PUT "$BASE$1" -H "Authorization: Bearer $3" \
                    -H "Content-Type: application/json" --data-binary @"$TMP/req-put.json"
             else
               curl -s -X PUT "$BASE$1" \
                    -H "Content-Type: application/json" --data-binary @"$TMP/req-put.json"
             fi; }
# 带中文的 SQL 走 stdin（`docker exec -i` + 重定向，绕开参数转码）。
# --default-character-set=utf8mb4 同样是**必须的**：没有它，写进去的是双重编码的乱码
dbFile() { printf '%s\n' "$1" > "$TMP/scaffold.sql"
           docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi \
                  --default-character-set=utf8mb4 < "$TMP/scaffold.sql"; }

STORE_OPEN=1                       # V4 种子里的云洗中央门店
STORE_CLOSED_ID=99                 # 脚手架：一家停业的店
STORE_CLOSED_NAME="云洗停业测试店"
STORE_CLOSED_ADDR="杭州市余杭区测试路 1 号"
STORE_GHOST=999                    # 根本不存在的门店
CUSTOMER_GHOST=999                 # 根本不存在的顾客

# 网单的配送地址 —— 网单必填（2026-09-12 起），所以每个网单用例都得带上一个
ADDR="杭州市西湖区文一西路 100 号"

PHONE_NEW="139$(date +%s | tail -c 9)"     # 本次要建档的号
PHONE_NONAME="138$(date +%s | tail -c 9)"  # 用来测"新顾客必填姓名"
PHONE_CUST="136$(date +%s | tail -c 9)"    # 用来注册一个顾客去拿 token

# 21 个字的姓名：name 列是 VARCHAR(20)，这是**能捅进 SQL 异常的长度**
LONG_NAME=$(printf '衣%.0s' {1..21})
# 数它的长度**不能用 ${#LONG_NAME}**：Git Bash 的 locale 不是 UTF-8，
# ${#var} 和 wc -m 数的都是**字节**（一个「衣」占 3 字节，于是报 63），
# 拿它跟 21 比会永远不等。grep -o 是按字面字节序列匹配的，不看 locale，数出来才是 21。
# 这个坑和手机上那几处 ${#P} 不冲突 —— 手机号是 ASCII，字节数就是字符数
LONG_NAME_LEN=$(printf '%s' "$LONG_NAME" | grep -o '衣' | wc -l | tr -d ' ')

echo "########## 准备：脚手架 ##########"

# 一家停业的店（status=0）。显式 id，可重复跑。
# 没有它，A3 那条"列表里不含停业店"就是句废话 —— 库里本来就没有停业的店
#
# 第二条 update 是**自我修复**，不是画蛇添足：第一版脚本用 `-e "…中文…"` 插过一次，
# 库里那一行是**双重编码的乱码**（GBK 转码的产物）。insert ignore 遇到已存在的
# id 什么都不做，于是乱码会一直留着 —— 而乱码恰好让 A3 通过（列表里当然找不到
# 一个正确写法的店名）。修字节是让 A3 恢复意义的前提
#
# 2026-09-13 补：那句 update 原先只写 name，而 insert 里有**两个**中文列 ——
# address 的乱码就这样留了四天（store id=2 同一个毛病，见 verify-orders.sh 的 F 段）。
# **自愈只修了"有断言盯着的那一列"**，所以修法必须成对：整行写回 + 整行断言
dbFile "insert ignore into stores (id,name,address,phone,status)
        values ($STORE_CLOSED_ID,'$STORE_CLOSED_NAME','$STORE_CLOSED_ADDR','0571-00000000',0);
        update stores set name='$STORE_CLOSED_NAME', address='$STORE_CLOSED_ADDR'
        where id=$STORE_CLOSED_ID;"

# 准备阶段守卫（Bug 22 教训）：前置条件不成立就立刻停，别让它继续跑成断言失败
C_STATUS=$(db "select status from stores where id=$STORE_CLOSED_ID;")
if [ "$C_STATUS" != "0" ]; then
  echo "==> [准备失败] 停业店 id=$STORE_CLOSED_ID 的 status 应为 0，实际 '$C_STATUS'"; exit 1
fi
# 名字的字节也必须对。这条和 A3c 是同一件事的两次断言，重复是**故意的**：
# 准备阶段的守卫负责"立刻喊停"（脚本自己的硬要求 1），A3c 负责"把结论写清楚"。
# 库里若存的是乱码，A3（列表里不含这个店名）会无条件通过 —— 假绿
C_NAME=$(db "select name from stores where id=$STORE_CLOSED_ID;")
if [ "$C_NAME" != "$STORE_CLOSED_NAME" ]; then
  echo "==> [准备失败] 停业店 id=$STORE_CLOSED_ID 的名字字节不对"
  echo "              期望 '$STORE_CLOSED_NAME'"
  echo "              实际 '$C_NAME'（乱码说明插入时被转码了）"; exit 1
fi
# 地址同理。这条原先没有 —— 于是 address 的乱码在库里躺了四天没人喊。
# name 会自愈纯属侥幸：恰好有个断言在它身上。**没有断言的中文列 = 会静悄悄烂掉的列**
C_ADDR=$(db "select address from stores where id=$STORE_CLOSED_ID;")
if [ "$C_ADDR" != "$STORE_CLOSED_ADDR" ]; then
  echo "==> [准备失败] 停业店 id=$STORE_CLOSED_ID 的地址字节不对"
  echo "              期望 '$STORE_CLOSED_ADDR'"
  echo "              实际 '$C_ADDR'（乱码说明插入时被转码了）"; exit 1
fi
O_STATUS=$(db "select status from stores where id=$STORE_OPEN;")
if [ "$O_STATUS" != "1" ]; then
  echo "==> [准备失败] $STORE_OPEN 号店的 status 应为 1，实际 '$O_STATUS'"; exit 1
fi
# 这条守卫不是形式主义：LONG_NAME 是拼出来的，万一 printf 在这儿不按预期展开
# （比如参数个数不对），它就变成空串或 1 个字 —— 后面 B13/D5 的两条 400 断言
# 会**因为空名字而通过**（空名字也是 400，理由不同而已），
# 看着全绿，实际"超长拒绝"那条一条都没测到
if [ "$LONG_NAME_LEN" != "21" ]; then
  echo "==> [准备失败] LONG_NAME 应为 21 个字，实际 $LONG_NAME_LEN 个（'$LONG_NAME'）"; exit 1
fi

# 三个手机号都是脚本自己拼的（前缀 + date +%s | tail -c 9）—— 判**形状**：
# 恰好 11 位数字。拼歪了（tail 的用法变了、date 输出变了）后端回的是
# 400「手机号格式不正确」，而下游只会报"注册没 200"、"拿不到 id" ——
# 真因是脚手架拼错了，不是被测的那条规则坏了。**Bug 38** 的形状：
# 准备段只判"有没有"，不判"对不对"
for p in "$PHONE_NEW" "$PHONE_NONAME" "$PHONE_CUST"; do
  case "$p" in
    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
    *) echo "==> [准备失败] 手机号 '$p' 不是 11 位数字（date/tail 那段拼歪了？）"; exit 1;;
  esac
done

ADMIN_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')" token)
MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
  -d '{"username":"manager","password":"admin123"}')" token)
if [ -z "$MGR_T" ] || [ -z "$ADMIN_T" ]; then
  echo "==> [准备失败] 登录没拿到 token（后端没起？还是账号被上一轮脚本停用了？）"
  echo "             票长 admin=${#ADMIN_T} manager=${#MGR_T}"
  exit 1
fi

# 顾客 token：注册一个本次专用的号，收尾时删掉。
# 这里**故意不用** login_or_register（那六份拷贝里的同名函数）：本脚本的号每次现拼
# （date +%s | tail -c 9），注册必然成功 —— 那份函数里"失败就登录"的兜底在这儿是死分支。
# 而万一它真走进去（号被上一轮的残渣占了），拿到的会是一张**旧顾客**的票，
# D 段"名字从 NULL 变成补名测试"这类断言就变成对着旧状态说话。
# 所以这里要的是"注册失败就当场停"，不是"想办法弄到一张票"。
CUST_T=$(jqf "$(postJson /api/auth/customer/register \
  "{\"phone\":\"$PHONE_CUST\",\"password\":\"123456\"}" "")" token)
if [ -z "$CUST_T" ]; then
  echo "==> [准备失败] 顾客注册没拿到 token（票长 ${#CUST_T}；$PHONE_CUST 是不是已被占了？）"; exit 1
fi
echo "  脚手架就绪：停业店 id=$STORE_CLOSED_ID  新号 $PHONE_NEW  顾客号 $PHONE_CUST"
# 印**长度**不印票面值：空/非空一眼可判，票本身不进 CI 的日志产物
echo "            票长 admin=${#ADMIN_T} manager=${#MGR_T} customer=${#CUST_T}"
echo

echo "########## A. GET /api/stores ##########"

STORES=$(curl -s "$BASE/api/stores" -H "Authorization: Bearer $MGR_T")
check "A1 店长查门店列表 → 200" "$STORES" '"code":200'
check "A2 含营业中的「云洗中央门店」" "$STORES" "云洗中央门店"

# 这三条是一组，缺一不可：
#   A3  说明列表里没有停业的店
#   A3b 说明它确实存在于库里 —— 否则 A3 通过只是因为"这家店根本没建出来"
#   A3c 说明库里那个名字的**字节是对的** —— 这是 A3 成立的前提。
#       库里若存的是乱码，那不管接口过不过滤，列表里都找不到"云洗停业测试店"
#       这六个字的正确写法，A3 就是一条永远为真的假绿。
#       2026-09-12 实测：第一版脚本用 -e 插的正是乱码，A3 当时就在假绿
checkNot "A3 不含停业的「$STORE_CLOSED_NAME」（过滤掉了）" "$STORES" "$STORE_CLOSED_NAME"
check  "A3b 但库里确实有这家店且 status=0（证明 A3 是过滤、不是没建上）" \
  "$(db "select status from stores where id=$STORE_CLOSED_ID;")" '^0$'
check  "A3c 库里那个店名的字节是对的（字节对了 A3 才算在证明「过滤」）" \
  "$(db "select name from stores where id=$STORE_CLOSED_ID;")" "^$STORE_CLOSED_NAME$"

# 门店信息本来就是公开的：顾客下单必须先选店，所以顾客 token 也得能读
check "A4 顾客 token 也能查（下单要选店）" \
  "$(curl -s "$BASE/api/stores" -H "Authorization: Bearer $CUST_T")" '"code":200'

check "A5 无 token → 401" "$(curl -s "$BASE/api/stores")" "未登录"

echo
echo "########## B. POST /api/customers/lookup-or-create ##########"

BODY="{\"phone\":\"$PHONE_NEW\",\"name\":\"建档测试甲\"}"
B1=$(postJson /api/customers/lookup-or-create "$BODY" "$MGR_T")
check "B1 新手机号 → 200 且 created=true" "$B1" '"created":true'

B2=$(postJson /api/customers/lookup-or-create "$BODY" "$MGR_T")
check "B2 同手机号再来一次 → created=false（没有第二次建人）" "$B2" '"created":false'
# 原先还有一条 B2b「返回的还是同一个 customerId」（拿 B1 回显的 id 比对）。
# "同号永远同一个顾客"这条规则现在由三处守着：B2 的 created=false、B3b 的
# 库里只有一行（那条更硬 —— 库里的行数才是事实），以及单测
# CustomerAppServiceTest.returnsExisting。回显 id 那一条是第三份拷贝，删掉

check "B3 库里这个号的 password 是 NULL（门店单顾客不上线登录）" \
  "$(db "select ifnull(password,'<NULL>') from customers where phone='$PHONE_NEW';")" '<NULL>'
check "B3b 库里只有一行（两次调用没建出两个人）" \
  "$(db "select count(*) from customers where phone='$PHONE_NEW';")" '^1$'

# 出参里没有 password —— CustomerView 存在的首要理由。单测反射钉了字段名，
# 这里钉真实的 JSON 字节：接口回给前端的东西里就是不该有这四个字母
checkNot "B4 响应体里没有 password 字段" "$B1" 'password'
# B4b（false 那次也验一遍）删了：同一个端点、同一个 CustomerView，
# 字段集合由 CustomerAppServiceTest.viewHasNoPasswordField 反射钉着，
# 而"真实 JSON 字节里没有这四个字母"这件事 D1c 在 /me 那个投影上还钉着一次

# 老顾客改名：柜台顺手打个错别字不该悄悄改档案
B5=$(postJson /api/customers/lookup-or-create \
       "{\"phone\":\"$PHONE_NEW\",\"name\":\"建档测试乙\"}" "$MGR_T")
check  "B5 老顾客传了新名字 → 返回的仍是档案里的原名（不改名）" "$B5" "建档测试甲"
checkNot "B5b 返回值里没有新名字" "$B5" "建档测试乙"
check  "B5c 库里也没被改名" \
  "$(db "select name from customers where phone='$PHONE_NEW';")" '建档测试甲'

check "B6 新手机号但没姓名 → 400 新顾客必须填写姓名" \
  "$(postJson /api/customers/lookup-or-create \
     "{\"phone\":\"$PHONE_NONAME\",\"name\":\"\"}" "$MGR_T")" \
  "新顾客必须填写姓名"
check "B6b 被拒之后库里没这个号（真没建）" \
  "$(db "select count(*) from customers where phone='$PHONE_NONAME';")" '^0$'

check "B7 手机号为空 → 400 手机号不能为空" \
  "$(postJson /api/customers/lookup-or-create '{"name":"没有手机号"}' "$MGR_T")" \
  "手机号不能为空"

# 建档是为建单服务的，顾客自己不建档（他自己走注册），管理员不碰订单
check "B8 顾客 token → 401 请使用员工账号操作" \
  "$(postJson /api/customers/lookup-or-create "$BODY" "$CUST_T")" \
  "请使用员工账号操作"
# 措辞 2026-09-12 晚改过：闸门按**整个 /api/customers 前缀**收口，而前缀下
# 现在有 /lookup-or-create（员工代客）和 /me（顾客自助）两类操作，
# 原来的"不参与顾客建档"在 /me 上会变成一句错话（他不是不能建档，
# 是压根没有"自己"这个档案）—— 所以改成覆盖整个前缀的说法
check "B9 管理员 token → 403（角色闸门，按 URL 收口）" \
  "$(postJson /api/customers/lookup-or-create "$BODY" "$ADMIN_T")" \
  "管理员不参与顾客相关操作"
check "B9b 管理员查门店列表仍 → 200（闸门只挡建档，不挡看店）" \
  "$(curl -s "$BASE/api/stores" -H "Authorization: Bearer $ADMIN_T")" '"code":200'
check "B10 无 token → 401" \
  "$(postJson /api/customers/lookup-or-create "$BODY" "")" "未登录"

# ── 补名字：线上注册的顾客没有姓名，店员建档时只在「没有」时补 ──
# 这是 2026-09-12 的决定②。判据是"改写已有信息"还是"补全缺失信息"：
# 补全可以做，改写不行（柜台顺手打个错别字不该悄悄改档案）
check "B11 线上注册的顾客档案里确实没有姓名（V9 起 name 可空）" \
  "$(db "select ifnull(name,'<NULL>') from customers where phone='$PHONE_CUST';")" '<NULL>'

B11=$(postJson /api/customers/lookup-or-create \
        "{\"phone\":\"$PHONE_CUST\",\"name\":\"补名测试\"}" "$MGR_T")
check  "B11b 店员建档 → created=false（他是老顾客，不是新建）" "$B11" '"created":false'
check  "B11c 返回的名字补上了" "$B11" "补名测试"
check  "B11d 库里也真补上了" \
  "$(db "select name from customers where phone='$PHONE_CUST';")" '补名测试'
check  "B11e 补名字不是新建一行（还是只有一行）" \
  "$(db "select count(*) from customers where phone='$PHONE_CUST';")" '^1$'

check "B12 补过之后再传别的名字 → 一个字都不动" \
  "$(postJson /api/customers/lookup-or-create \
     "{\"phone\":\"$PHONE_CUST\",\"name\":\"另一个名字\"}" "$MGR_T")" \
  "补名测试"
check "B12b 库里也没被改" \
  "$(db "select name from customers where phone='$PHONE_CUST';")" '补名测试'

# ── 姓名长度：name 列是 VARCHAR(20)，代码不拦就会变成 500 ──
# 21 个字一路走到 INSERT 才被 MySQL 弹回来，报的是 DataTooLong 那串英文 SQL 异常。
# 这是 2026-09-12 晚修掉的一个**本来就存在**的缺口（不是这次新引入的）：
# 柜台建档那条路一直没有长度校验，只是没人试过 21 个字的名字
B13=$(postJson /api/customers/lookup-or-create \
        "{\"phone\":\"$PHONE_NONAME\",\"name\":\"$LONG_NAME\"}" "$MGR_T")
check   "B13 店员传 21 个字的姓名 → 400（不是 500 的 SQL 异常）" "$B13" "姓名不能超过 20 个字"
checkNot "B13b 消息里没有英文（DataTooLong…）" "$(jqf "$B13" message)" '[A-Za-z]'
# B13c（被拒之后库里没这个号）删了：B6b 已经在为**同一个手机号**断言 count=0，
# 而"长度校验失败一个字都不写"的单测家是 CustomerAppServiceTest.tooLongNameNotFilled；
# 超长这条规则"没写进去"的探针由 D5c 在 /me 上留着一条

echo
echo "########## C. 建单的不可信输入（orders 没有外键，只能靠代码守）##########"
# orders.store_id / orders.customer_id 都只是普通索引，**不是外键** ——
# 数据库不会拦下一个 999。这几条校验全是纯代码的，所以必须真机验一次。
# 判据是"这个值是不是请求体来的"：
#   网单   storeId    来自请求体 → 回库确认     customerId 来自顾客 token → 不查
#   门店单 customerId 来自请求体 → 回库确认     storeId    来自员工 token → 不查
# 每条坏值都要配一条好值的对照，否则 400 可能是别的原因造成的（见 C1 的注释）
# 判**形状**不是有无（**Bug 38** 的规矩）：db() 把 stderr 丢了，MySQL 一挂它返回空串，
# 而空串拼进 payload 会成为 "customerId":, 这种畸形 JSON —— 400 的理由就变成
# "请求体格式不正确"，C 段每条断言都指不到真因上
CUST_ID_IN_DB=$(db "select id from customers where phone='$PHONE_CUST';")
case "$CUST_ID_IN_DB" in
  '')       echo "==> [准备失败] 顾客 $PHONE_CUST 查不到 id（MySQL 没起？后端没起？）"; exit 1;;
  *[!0-9]*) echo "==> [准备失败] 顾客 $PHONE_CUST 的 id 不是数字：'$CUST_ID_IN_DB'"; exit 1;;
esac

# C1 特意带上**合法地址**：不带的话 400 会来自"网单没地址"那条规则（见 C3），
# 断言还是绿的，但测到的就不是门店校验了 —— 断言过的理由必须也是被测的那个
C1=$(postJson /api/orders \
       "{\"storeId\":$STORE_GHOST,\"source\":2,\"deliveryAddress\":\"$ADDR\",\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
       "$CUST_T")
check "C1 网单选了不存在的门店 $STORE_GHOST → 400 门店不存在或已停业" "$C1" "门店不存在或已停业"
check "C1b 没落库（幽灵门店的订单不该存在）" \
  "$(db "select count(*) from orders where store_id=$STORE_GHOST;")" '^0$'

# 对照组：同样的请求体、只把 storeId 换成真店 —— 必须成功。
# 否则 C1 的 400 可能来自 payload 里别的地方（比如写错了 categoryId），
# 那这条断言就只是在证明"这个请求体是坏的"
C2=$(postJson /api/orders \
       "{\"storeId\":$STORE_OPEN,\"source\":2,\"deliveryAddress\":\"$ADDR\",\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
       "$CUST_T")
check "C2 同样的请求体、门店换成营业中的 $STORE_OPEN → 200（对照组）" "$C2" '"code":200'
NEW_ORDER_ID=$(jqf "$C2" id)

# ── 网单必须有配送地址（域层规则，2026-09-12 补的口子）──
# 门店用营业中的 $STORE_OPEN：门店校验排在地址校验**前面**，
# 店选错了就轮不到地址这条规则说话，那样 C3 会变成 C1 的翻版
C3=$(postJson /api/orders \
       "{\"storeId\":$STORE_OPEN,\"source\":2,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
       "$CUST_T")
check "C3 网单没填配送地址 → 400 网单必须填写配送地址" "$C3" "网单必须填写配送地址"
# 断言里带上 source=2：C4 会给同一个顾客建一张**无地址的门店单**，
# 不带 source 的话这条会在 C4 之后变成假红（被测的是"网单里没有无地址的"）
check "C3b 没落库（没地址的网单不该存在）" \
  "$(db "select count(*) from orders where customer_id=$CUST_ID_IN_DB and source=2 and delivery_address is null;")" '^0$'

# 对照组：同样不填地址，门店单必须放行 —— 没有这一条，C3 就只是在证明
# "没地址就 400"，而不是"这条规则只对网单生效"。门店单的衣服就在店里等人来取
C4=$(postJson /api/orders \
       "{\"customerId\":$CUST_ID_IN_DB,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
       "$MGR_T")
check "C4 门店单不填地址 → 200（地址是网单特有的要求）" "$C4" '"code":200'
C4_ORDER_ID=$(jqf "$C4" id)

# ── 门店单的 customerId 同样来自请求体，一样要回库确认 ──
# C4 顺便就是这条的对照组（真顾客 → 200）
C5=$(postJson /api/orders \
       "{\"customerId\":$CUSTOMER_GHOST,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
       "$MGR_T")
check "C5 门店单挂在不存在的顾客 $CUSTOMER_GHOST 上 → 400 顾客不存在" "$C5" "顾客不存在"
check "C5b 没落库（幽灵顾客的订单不该存在）" \
  "$(db "select count(*) from orders where customer_id=$CUSTOMER_GHOST;")" '^0$'

echo
echo "########## D. 个人中心 /api/customers/me（顾客自助）##########"
# 前缀和 B 段一样、资源也一样（都是"顾客"），但**操作的人不同**：
# B 段是员工代客，这里是顾客自己。同一个前缀两种身份，
# 所以断言也得成对：功能一条、越权一条，缺了后者等于没测安全边界

D1=$(curl -s "$BASE/api/customers/me" -H "Authorization: Bearer $CUST_T")
check   "D1 顾客看自己的档案 → 200" "$D1" '"code":200'
check   "D1b 返回的是自己的手机号" "$D1" "$PHONE_CUST"
checkNot "D1c 出参里没有 password" "$D1" 'password'
check   "D1d 名字是 B 段店员补的那个（说明 B11 那次补名字真的落库了）" "$D1" "补名测试"

# 改名。这条同时钉住 rename 与 fillName 的区别：fillName 带"只在没名字时生效"
# 的第二道锁，而个人中心是**本人主动改**，有名字也必须能覆盖
D2=$(putJson /api/customers/me '{"name":"本人改名"}' "$CUST_T")
check "D2 顾客改自己的名字 → 200" "$D2" '"code":200'
check "D2b 返回里就是新名字（前端不用再查一次）" "$D2" "本人改名"
check "D2c 库里真改了（不是只改了回显）" \
  "$(db "select name from customers where phone='$PHONE_CUST';")" '本人改名'

# ── 越权：请求体里塞一个 customerId，想改 B 段那个顾客 ──
# UpdateProfileRequest 里**根本没有** customerId 这个字段，Spring 默认忽略
# 不认识的键，所以这个请求会照常 200 —— 只是改的仍然是自己。
# 这里必须断言两件事，缺一不可：
#   D3c 被改的仍然是自己的名（越权失败）
#   D3d 别人的档案一个字没动（否则"改成了自己的名字"可能只是句空话）
# 两个 id 判**形状**（不是有无）：见 C 段那条同款注释 —— 空串拼出的
# "customerId":, 会让 D3 的请求体整个 400，而 D3 想验的是"塞了 customerId 也不越权"。
# 形状判据同时管住了"空"（`''` 是它的一支），所以这里不再另写一条 -z 检查
OTHER_ID=$(db "select id from customers where phone='$PHONE_NEW';")
MY_ID=$(db "select id from customers where phone='$PHONE_CUST';")
for v in OTHER_ID MY_ID; do
  case "${!v}" in
    '')          echo "==> [准备失败] 两个顾客的 id 有一个查不到：$v=''（MySQL 没起？）"; exit 1;;
    *[!0-9]*)    echo "==> [准备失败] 顾客 id 不成形：$v='${!v}'"; exit 1;;
  esac
done
D3=$(putJson /api/customers/me \
       "{\"name\":\"越权改名\",\"customerId\":$OTHER_ID}" "$CUST_T")
check   "D3 塞 customerId 想改别人 → 仍 200（多传的键被忽略，不是报错）" "$D3" '"code":200'
check   "D3b 改的仍是自己" "$D3" "越权改名"
check   "D3c 返回的 customerId 还是自己的（$MY_ID）" "$D3" "\"customerId\":$MY_ID"
check   "D3d 别人的档案一个字没动" \
  "$(db "select name from customers where phone='$PHONE_NEW';")" '建档测试甲'

# ── 参数校验 ──
check "D4 名字传空 → 400 姓名不能为空" \
  "$(putJson /api/customers/me '{"name":""}' "$CUST_T")" \
  "姓名不能为空"

D5=$(putJson /api/customers/me "{\"name\":\"$LONG_NAME\"}" "$CUST_T")
check   "D5 21 个字的姓名 → 400（不是 500 的 SQL 异常）" "$D5" "姓名不能超过 20 个字"
# D5b（没有英文）删了：本脚本的这条属性由 B13b 代表（同一属性不需要两处），
# 而属性本身归 GlobalExceptionHandlerTest 的五条通道
check   "D5c 超长值没被写进去（名字还是上一轮那个）" \
  "$(db "select name from customers where phone='$PHONE_CUST';")" '越权改名'

# ── 三种进不来的身份 ──
check "D6 员工 token 调 /me → 401 请使用顾客账号登录" \
  "$(curl -s "$BASE/api/customers/me" -H "Authorization: Bearer $MGR_T")" \
  "请使用顾客账号登录"
check "D7 管理员 token → 403（闸门按**整个前缀**收口，/me 也在里面）" \
  "$(curl -s "$BASE/api/customers/me" -H "Authorization: Bearer $ADMIN_T")" \
  "管理员不参与顾客相关操作"
check "D8 无 token → 401" "$(curl -s "$BASE/api/customers/me")" "未登录"

echo
echo "########## F. 收尾：清掉本次造的数 ##########"

# 顾客（含那个用来拿 token 的），只在手机号确实是 11 位时删
for P in "$PHONE_NEW" "$PHONE_NONAME" "$PHONE_CUST"; do
  if [ -n "$P" ] && [ ${#P} -eq 11 ]; then
    db "delete from customers where phone='$P';"
  else
    echo "  [跳过] 手机号 '$P' 不合法，不执行删除"
  fi
done
echo "  已删除本次的三个顾客号"

# C2 / C4 建的那两单：按 id 精确删，不按条件删（免得误伤真数据）
for OID in "$NEW_ORDER_ID" "$C4_ORDER_ID"; do
  if [ -n "$OID" ]; then
    db "delete from order_items where order_id=$OID; delete from orders where id=$OID;"
    echo "  已删除 C 段建的订单 id=$OID"
  fi
done

# 停业店**故意保留** —— 它就是脚手架，下次跑还要用它验"列表过滤"
echo "  保留停业店 id=$STORE_CLOSED_ID（下次运行复用的脚手架）"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
[ $FAIL -eq 0 ]
