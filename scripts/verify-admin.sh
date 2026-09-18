#!/bin/bash
# 管理员侧接口验收（真机：MySQL + Redis + 8081）
#
# 用法：bash scripts/verify-admin.sh     从任何目录都行
# 前置：docker compose up -d  且后端已在 8081 起着（**必须含 2026-09-18 这版**）
#
# 设计文档 §4.2 给管理员定义了"只管人与店"，§6.4 还点名了 /api/staff 和
# 门店写接口两个前缀 —— 但在这轮之前**它们一个都不存在**，后果写在 §8：
# "管理员能登录，除读价目表 / 读门店列表外处处 403。他被授权做的事，
#  恰好就是没实现的那部分。"
# 这个脚本证明那句话不再成立，并且**证明它是被授权的**（而不是把闸门拆了）。
#
# ══════ 七段各证明什么 ══════
#
#   A 员工 CRUD      —— 建号能登录、列表/详情一致、重名与超长是 400 不是 500、
#                       库里存的是 BCrypt 不是明文
#   B 停用即失效     —— 这轮最值钱的一条：一张**已经证明能用**的票，在停用后立刻死；
#                       且重新启用**不会**把它复活（水位线比布尔标记强的地方）
#   C 降级即失效     —— 把管理员降成店长，他那张还写着 role=0 的票立刻死，
#                       而且死法是 401 不是 403（顺序：作废检查排在角色闸门之前）
#   D 门店管理       —— 建店/改店/停业，停业立刻从顾客的选店列表消失；
#                       外加**调岗即失效**和**停业不踢人**两条并排立着
#   E 闸门·反方向    —— 店长打管理接口全程 403，**但读门店仍然 200**（不误伤）
#   F 闸门·原方向    —— 管理员打订单/定价写/顾客仍然是原来那三句（防回归）
#   G 门店引用校验   —— staff.store_id 没有外键，库里拦不下 999，只能应用层拦
#
# ══════ 三条反复踩过的规矩（改脚本前先读）══════
#
# 【一】中文请求体一律走 postJson/putJson（落文件 + --data-binary @）。
#   MSYS2 把非 ASCII 命令行参数交给原生 curl.exe 前会按 GBK 转一遍，
#   `curl -d '{"name":"张三"}'` 发出去的是 GBK 字节、不是合法 UTF-8，
#   服务端回 400「请求体格式不正确」—— 而报错完全指不到编码上。
#   完整说明在 verify-stores.sh 的文件头，这里不重复。
#
# 【二】**SQL 里的中文一律走 dbQ，不能用 db。** db() 的 SQL 是命令行参数，
#   同样会被 GBK 转码 —— `where name='云洗中央门店'` 查的是 GBK 字节序列，
#   永远查不到，于是"库里有这家店吗"这类断言会**无条件地**说没有（假绿）。
#   dbQ 走 stdin（printf + `docker exec -i`），字节安全。ASCII 的查询仍用 db()。
#   两个助手都带 --default-character-set=utf8mb4，那是另一件事，都不能省。
#
# 【三】准备阶段失败要立刻 exit 1，不能让它继续跑成断言失败（Bug 22）。
#
# ══════ 可重复跑：这次**不复用固定名字** ══════
#
# "删"在这轮的口径是**软停用**（status=0），用户名会被永久占住 ——
# 所以固定用户名（比如 e2e_manager）第二轮就会撞"用户名已存在"，
# 那条本来要验重名的断言会在第二轮起变成"这个号上轮建过"。
# 于是本脚本的员工号/门店名带本次的时间戳：第二轮是另一批新数据，
# 每条断言在**每一轮**都有意义。代价是每跑一次多两行 staff + 一行 stores
# （全部停在 status=0），累积型脚本，要干净数据只有 down -v。
#
# 顾客号例外：它走真实注册，收尾时**硬删**（顾客删了就删了，
# 和 verify-stores.sh 同一个做法，那张表本来就没有"停用"一说）。
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

# ASCII 的查询用这个
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }
# **带中文的查询必须用这个**（理由见文件头【二】）。和 db() 唯一的差别是走 stdin。
# 名字取成 dbQ 是为了让"这条查询里有中文"在调用点上看得见 ——
# 用错 db() 不会报错，只会静默地查不到（假绿）。
dbQ() { printf '%s\n' "$1" > "$TMP/q-admin.sql"
        docker exec -i yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
               --default-character-set=utf8mb4 < "$TMP/q-admin.sql" 2>/dev/null | tr -d '\r'; }

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
getJson() {  curl -s "$BASE$1" -H "Authorization: Bearer $2"; }

# 水位线是 Redis 里的键（auth:staff:invalidAfter:<id>），要能**直接看**它 ——
# 只看"旧票 401"分不出是水位线生效还是别的原因（比如票本来就过期了）。
# 助手抄 bench-coupon.sh 的 redis_get。
redis_get() { docker exec yunxi-redis redis-cli get "$1" 2>/dev/null | tr -d '\r'; }

# 作废之后**重新登录** —— 必须等过那一秒，所以单独留个助手（Bug 34 的窗口）。
#
# 机制：JWT 的 iat 是**秒**精度，水位线是**毫秒**（revokeAll 写
# System.currentTimeMillis()，isRevoked 判的是 iat.getTime() < watermark）。
# 若"作废那次操作"与"这次重新登录"落在**同一个自然秒**里，新票的 iat 会被
# 截断到该秒的起点 → iat < 水位线 成立 → **刚签发的新票被判成旧的**（偏拒绝方向）。
# sleep 1 是从构造上排除它的唯一办法：≥1 秒必然跨过一个秒边界。
#
# 为什么以前只有 B6c 那一处写着 sleep 1：本地 docker exec 慢（一次几百毫秒），
# 别的几处"几乎总能"跨过秒边界 —— 那是**运气**，不是保证。
# 2026-09-19 CI 第一次真跑就证伪了：本脚本 **116 / 2**，C4b 和 D5 两条
# 正是被这个窗口打中的（同一份脚本在本地 118/118 一直绿）。
# 现在四处重新登录全走这里，规则只住一个地方。
relogin() {  # relogin <用户名> <密码> → 新票
  sleep 1
  jqf "$(postJson /api/auth/staff/login "{\"username\":\"$1\",\"password\":\"$2\"}" "")" token
}

# ═══════════════════ 准备 ═══════════════════

RUN=$(date +%s)
U_A="e2e_a$RUN"          # A/B 段的主角：建出来、改一改、停掉
U_C="e2e_c$RUN"          # C/D/G 段的主角：先当管理员，再被降级，再被调岗
STORE_NEW="验收新店$RUN" # D 段新建的门店（名字带时间戳，见文件头）
STORE_NEW_ADDR="杭州市拱墅区验收路 $RUN 号"
STORE_OPEN=1             # V4 种子里的云洗中央门店
STORE_CLOSED_ID=99       # 脚手架：一家停业的店（verify-stores.sh 也在用）
STORE_CLOSED_NAME="云洗停业测试店"
STORE_CLOSED_ADDR="杭州市余杭区测试路 1 号"   # 与 verify-stores.sh 逐字一致 —— 同一行数据，两个脚本都在自愈它
STORE2_NAME="二号门店"                        # 与 verify-orders.sh 的 F 段逐字一致，同上
STORE2_ADDR="验收用"
STORE_GHOST=999          # 根本不存在的门店
STAFF_GHOST=999999       # 根本不存在的员工
PW="pw-$RUN"             # 新建账号的初始密码
PW2="pw2-$RUN"           # 重置之后的新密码（E14 段用；原先只有 A10d 的 404 用过它，
                         # A10d 搬进单测后它一度没用了 —— 现在补的成功路径正需要它）
PHONE_CUST="136$(date +%s | tail -c 9)"   # 用来注册一个顾客去拿 token（11 位）

# 31 个字的用户名：staff.username 是 VARCHAR(30)，这是**能捅进 SQL 异常的长度**
LONG_USER=$(printf 'u%.0s' {1..31})
# 数长度不能用 ${#LONG_USER}：Git Bash 的 locale 不是 UTF-8，${#var} 数的是字节。
# 这里全是 ASCII 所以没事，但**50 个汉字那一格**就必须用 grep 数（见 verify-stores.sh）
LONG_STORE_NAME=$(printf '店%.0s' {1..51})
LONG_STORE_LEN=$(printf '%s' "$LONG_STORE_NAME" | grep -o '店' | wc -l | tr -d ' ')

# 脚手架守卫：admin / manager 必须还能登录（三个脚本的准备段都指着它们，
# 其中 manager 是本脚本 E 段的店长主体）。登不上就直接停，别往下跑成一片红
ADMIN_T=$(jqf "$(postJson /api/auth/staff/login \
  "{\"username\":\"admin\",\"password\":\"admin123\"}" "")" token)
MGR_T=$(jqf "$(postJson /api/auth/staff/login \
  "{\"username\":\"manager\",\"password\":\"admin123\"}" "")" token)
if [ -z "$ADMIN_T" ] || [ -z "$MGR_T" ]; then
  echo "==> [准备失败] admin / manager 登录没拿到 token（后端没起？还是账号被上一轮脚本停用了？）"
  echo "             票长 admin=${#ADMIN_T} manager=${#MGR_T}"
  exit 1
fi
# ══ 脚手架自备：99 与 2 缺了就自己种 ══（**Bug 39** 的修法）
#
# 原写法是"要求 stores 1/2/99 都已经存在"，但它们全由**别的脚本**种下：
# id=1 来自 V4 种子，id=2 来自 verify-orders.sh 的 F 段，
# id=99 来自 verify-stores.sh 的准备段 —— 而本脚本按通配符**排第一**
# （admin < auth < coupons < orders < … < stores）。于是：
#   · 本机九连跑能过，靠的是**上一轮留下的残渣**
#   · CI 每次都是全新库（docker run 无卷）→ 2/99 不存在 → 这里当场 exit 1
#     → e2e job 在**第一个脚本**就红，后面八个根本没跑过
#
# 这和 Bug 38、和 verify-pricing-authority 的顾客残渣是**同一个形状**：
# **把"执行顺序"当成了"本脚本的前提"**。顺序是调度者的偶然，前提才是脚本自己的。
# 所以修法不是写"本脚本依赖 A、B 先跑"（那只是把耦合从代码挪进注释），
# 而是**自己把前提种出来** —— 和 4 份 login_or_register 拷贝同一个道理。
#
# 代价说清楚：这换掉了"顺便检测别人有没有把脚手架弄坏"。那层检测本来也名不副实 ——
# 循环里的 id=2 **本脚本一条断言都不读**（grep 全脚本，它只出现在原来那条守卫里），
# 守的是一条自己不用的前提。换来的是"单跑任何一个都成立"，后者才是本仓库的规矩。
dbQ "insert ignore into stores (id,name,address,phone,status)
     values ($STORE_CLOSED_ID,'$STORE_CLOSED_NAME','$STORE_CLOSED_ADDR','0571-00000000',0);
     update stores set name='$STORE_CLOSED_NAME', address='$STORE_CLOSED_ADDR'
     where id=$STORE_CLOSED_ID;"
dbQ "insert ignore into stores (id,name,address) values (2,'$STORE2_NAME','$STORE2_ADDR');
     update stores set name='$STORE2_NAME', address='$STORE2_ADDR' where id=2;"

# 种完立刻自证（Bug 22 教训：前提不成立当场停，别让它跑成后面一片红）。
# 下面三条**不是**在验上面那两句 insert 写对了没 —— 它们刚写完，读出来当然是对的；
# 验的是**写进去的那条路**（printf → 文件 → stdin → mysql 客户端）有没有在哪一段
# 被转码。少了它们，Bug 30 会以"这次换了一列"的形式再犯一次：
# 乱码不报错、不影响任何断言，只会安安静静躺在库里等人肉眼撞见。
#
# id=1 是**真前提**（V4 种子的云洗中央门店，D 段的读接口全指着有店可列），
# 所以它保留"必须存在"的硬断言。它同时是"显式 id 方案还在不在"的探针：
# 若有人把种子改成自增，本脚本 D1 新建的店会顶掉 id=2，这条就会红。
N1=$(db "select count(*) from stores where id=1;")
if [ "$N1" != "1" ]; then
  echo "==> [准备失败] 脚手架门店 id=1 应该有且只有一行，实际 $N1 行（V4 种子被动过？）"; exit 1
fi
# 99 号店必须是停业的，否则 D10b 那句"老接口里没有 99 号停业店"是句废话
C_STATUS=$(db "select status from stores where id=$STORE_CLOSED_ID;")
if [ "$C_STATUS" != "0" ]; then
  echo "==> [准备失败] 脚手架停业店 id=$STORE_CLOSED_ID 的 status 应为 0，实际 '$C_STATUS'"
  echo "              （它被本脚本或 verify-stores.sh 之外的什么东西开起来了？）"; exit 1
fi
# 名字的字节也必须对。这条和 D10b 是同一件事的两次断言，重复是**故意的**：
# 准备守卫负责"立刻喊停"，D10b 负责"把结论写清楚"。
# 而**乱码恰好让 D10b 通过**（列表里当然找不到一个正确写法的店名）—— 假绿。
# 同一个理由在 verify-stores.sh 的准备守卫里（那边对应的是 A3/A3c）
C_NAME=$(dbQ "select name from stores where id=$STORE_CLOSED_ID;")
if [ "$C_NAME" != "$STORE_CLOSED_NAME" ]; then
  echo "==> [准备失败] 停业店 id=$STORE_CLOSED_ID 的名字字节不对"
  echo "              期望 '$STORE_CLOSED_NAME'"
  echo "              实际 '$C_NAME'（乱码说明插入时被转码了）"; exit 1
fi
if [ "$LONG_STORE_LEN" != "51" ]; then
  echo "==> [准备失败] LONG_STORE_NAME 应为 51 个字，实际 $LONG_STORE_LEN 个"; exit 1
fi

# 手机号判**形状**（恰好 11 位数字）：它是脚本自己拼的（date +%s | tail -c 9），
# 拼歪了后端回 400「手机号格式不正确」，而下面只会说"顾客注册没拿到 token" ——
# 真因看不见。**Bug 38** 的形状：准备段只判"有没有"，不判"对不对"
case "$PHONE_CUST" in
  [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
  *) echo "==> [准备失败] PHONE_CUST='$PHONE_CUST' 不是 11 位数字（date/tail 那段拼歪了？）"; exit 1;;
esac

# 这里**故意不用** login_or_register（那六份拷贝里的同名函数）：本脚本的号每次现拼
# （date +%s | tail -c 9），注册必然成功 —— 那份函数里"失败就登录"的兜底在这儿是死分支，
# 而万一它真走进去，拿到的会是一张**上一轮旧顾客**的票（本脚本结尾也不删这个号）。
# 这张票是用来验"顾客进不了 /api/staff"的，是谁的票不影响结论 —— 但它必须是**这一次**
# 注册出来的，否则"顾客能注册"这件事本身就不再被任何断言看着了
CUST_T=$(jqf "$(postJson /api/auth/customer/register \
  "{\"phone\":\"$PHONE_CUST\",\"password\":\"123456\"}" "")" token)
if [ -z "$CUST_T" ]; then
  echo "==> [准备失败] 顾客注册没拿到 token（票长 ${#CUST_T}；$PHONE_CUST 是不是已被占了？）"; exit 1
fi
echo "  脚手架就绪：本次账号 $U_A / $U_C  新店 $STORE_NEW  顾客号 $PHONE_CUST"
# 印**长度**不印票面值：空/非空一眼可判，票本身不进 CI 的日志产物
echo "            票长 admin=${#ADMIN_T} manager=${#MGR_T} customer=${#CUST_T}"
echo

echo "########## A. 员工 CRUD（POST/GET/PUT /api/staff）##########"

# role=1 就是"新建一个店长"（staff 表里只有 role=0/1 两档，没有第三档）。
# storeId 传 1 = 云洗中央门店 —— 这条同时也给 G1 当**对照组**：
# 同样的请求体、只把 storeId 换成 999，那时才能说 400 是门店校验给的
A1=$(postJson /api/staff \
  "{\"username\":\"$U_A\",\"password\":\"$PW\",\"name\":\"验收店长\",\"role\":1,\"storeId\":1,\"phone\":\"13700000001\"}" \
  "$ADMIN_T")
check   "A1 建一个 role=1 的店长 → 200" "$A1" '"code":200'
check   "A1b 回显的 role 是枚举名 MANAGER（返回体里枚举是名字，不是 1）" "$A1" '"role":"MANAGER"'
check   "A1c 回显带上门店名（前端不用再拉一次门店列表自己映射）" "$A1" "云洗中央门店"
checkNot "A1d 出参里没有 password 字段（真实 JSON 字节，不是「记得别填」）" "$A1" 'password'
U_A_ID=$(jqf "$A1" id)
if [ -z "$U_A_ID" ]; then
  echo "==> [准备失败] A1 没拿到员工 id，原始响应：$A1"; exit 1
fi

# 库里那行也得对 —— 只看回显的话，"回显对了但库里存的是明文"照样能过
check "A1e 库里存的是 BCrypt 哈希（\$2a\$ 开头），不是明文" \
  "$(db "select left(password,4) from staff where id=$U_A_ID;")" '\$2a\$'
check "A1g 新建一律启用（status=1）" \
  "$(db "select status from staff where id=$U_A_ID;")" '^1$'
check "A1h 库里 store_id 就是 1（不是回显里写着、库里没写）" \
  "$(db "select store_id from staff where id=$U_A_ID;")" '^1$'
# 中文列要**按字节**断言：库里若是乱码，后面"改名后是某某"的断言会无条件通过
check "A1i 库里姓名的字节是对的" \
  "$(dbQ "select name from staff where id=$U_A_ID;")" '^验收店长$'

# 新号能登录 —— 建号功能的一半在这里（建得出来但登不进去等于没建）
A2=$(postJson /api/auth/staff/login "{\"username\":\"$U_A\",\"password\":\"$PW\"}" "")
T_A=$(jqf "$A2" token)
if [ -z "$T_A" ]; then
  echo "==> [准备失败] 新建的 $U_A 登录没拿到 token，原始响应：$A2"; exit 1
fi
check "A2 新号能登录（建号功能的另一半）" "$A2" '"code":200'
# 详情用**管理员**的票查：/api/staff 整个前缀（含读）是管理员专区，
# 连"查自己的详情"店长也进不去 —— E1 断的就是这条。
# 这里原先用的是 $T_A（他自己那张店长票），于是要么无条件红、
# 要么（真放开了读的话）悄悄变成一条和 E1 打架的断言。见 Bug 37
check "A2b 用管理员的票查他的详情 → 200" "$(getJson "/api/staff/$U_A_ID" "$ADMIN_T")" '"code":200'
check "A2c 详情里用户名对得上" "$(getJson "/api/staff/$U_A_ID" "$ADMIN_T")" "$U_A"

A3=$(getJson /api/staff "$ADMIN_T")
check "A3 员工列表里有它" "$A3" "$U_A"

# 改姓名 + 手机号。**改姓名不该踢人** —— 那不是安全要求，是骚扰
A4=$(putJson "/api/staff/$U_A_ID" \
  "{\"name\":\"验收店长改\",\"role\":1,\"storeId\":1,\"phone\":\"13700000002\"}" "$ADMIN_T")
check "A4 改姓名+手机号 → 200" "$A4" '"code":200'
check "A4b 回显里就是新名字" "$A4" "验收店长改"
check "A4c 详情（另一次请求）也是新名字，不是只改了回显" \
  "$(getJson "/api/staff/$U_A_ID" "$ADMIN_T")" "验收店长改"
check "A4d 库里真改了" "$(dbQ "select name from staff where id=$U_A_ID;")" '^验收店长改$'
# 这一条是单测里 verifyNoInteractions 的真机对应：单测断言"没调 revokeAll"，
# 这里断言**那张票现在还能用** —— 它们是同一件事的两端。
# 少了它，"改个错别字把柜台电脑上的人弹下线"这种毛病不会有人发现
#
# 探针必须选**店长进得去的**接口：/api/staff 上店长的票"好也是 403"，
# 拿它当探针，这条断言无条件红（而且看着像"改名把人踢了"）。见 Bug 37
check "A4e 改姓名**不**踢人：A2 那张票现在还能用" \
  "$(getJson /api/orders "$T_A")" '"code":200'
# 更强的说法：改姓名这条路**一个字都没往 Redis 里写**。
# `grep '.'` 探的是"有没有任何字符"—— 键不存在时 redis-cli 输出空，`.` 匹配不上
checkNot "A4f 改姓名没写失效水位线（Redis 里那个键压根不存在）" \
  "$(redis_get "auth:staff:invalidAfter:$U_A_ID")" '.'

# 手机号传空白串 → 存 NULL 不存空串（V9 的教训：'' 和 NULL 在 SQL 里是两回事）
putJson "/api/staff/$U_A_ID" \
  "{\"name\":\"验收店长改\",\"role\":1,\"storeId\":1,\"phone\":\"   \"}" "$ADMIN_T" > /dev/null
check "A5 手机号传空白串 → 库里是 NULL 不是 ''" \
  "$(db "select ifnull(phone,'<NULL>') from staff where id=$U_A_ID;")" '<NULL>'

# ── 重名：必须先查再报 400，不能靠"插进去撞唯一键"（那条路是英文 SQL 异常 + 500）──
A6=$(postJson /api/staff \
  "{\"username\":\"$U_A\",\"password\":\"$PW\",\"name\":\"重名\",\"role\":1,\"storeId\":1,\"phone\":null}" \
  "$ADMIN_T")
check   "A6 重名建号 → 400 用户名已存在" "$A6" "用户名已存在"
checkNot "A6b 消息里没有英文（Duplicate entry…）" "$(jqf "$A6" message)" '[A-Za-z]'

# ── 超长：VARCHAR(30) 不拦就一路走到 INSERT 才被 MySQL 弹回来 ──
# 长度这条**留在脚本里**是刻意的：它压的是"真实列宽"（VARCHAR(30)），
# 单测里的 31 字只证明"应用层数了字数"，证明不了列宽就是 30
A7=$(postJson /api/staff \
  "{\"username\":\"$LONG_USER\",\"password\":\"$PW\",\"name\":\"超长\",\"role\":1,\"storeId\":1,\"phone\":null}" \
  "$ADMIN_T")
check   "A7 用户名 31 字 → 400（不是 500 的 SQL 异常）" "$A7" "用户名不能超过 30 个字"
checkNot "A7b 消息里没有英文（DataTooLong…）" "$(jqf "$A7" message)" '[A-Za-z]'

# ── 形状校验：只留两条代表探针（必填一条、状态范围一条）──
# 原先这一段还有三条：role=5 / role=null / 密码为空，已搬进
# StaffAdminAppServiceTest（create 与 update 各自一套）。
# 留这两条是为了证"应用层的校验真接上了、回的是 400 中文"这件事本身 ——
# 具体是哪条规则、什么边界（20 字/30 字/emoji/null），去单测里看，那里一次跑完
check "A9 姓名为空 → 400 姓名不能为空" \
  "$(postJson /api/staff \
     "{\"username\":\"y$RUN\",\"password\":\"$PW\",\"name\":\"  \",\"role\":1,\"storeId\":null,\"phone\":null}" \
     "$ADMIN_T")" "姓名不能为空"
check "A9c status=2 → 400 状态只能是 0(停用) 或 1(启用)" \
  "$(putJson "/api/staff/$U_A_ID/status" '{"status":2}' "$ADMIN_T")" \
  "状态只能是 0(停用) 或 1(启用)"

# ── 404：**路径上那个资源不存在** ⇒ 404（400 留给"请求体里引用的 id 不合法"）──
# 四条路由（查/改/停用/重置密码）里留前两条当代表：404 这条口径
# 由 A10 + A10b 钉住就够了，第三第四条是同一条规则换了个动词
check "A10 查不存在的员工 → 404 员工不存在" \
  "$(getJson "/api/staff/$STAFF_GHOST" "$ADMIN_T")" "员工不存在"
check "A10b 改不存在的员工 → 404" \
  "$(putJson "/api/staff/$STAFF_GHOST" \
     "{\"name\":\"谁\",\"role\":1,\"storeId\":null,\"phone\":null}" "$ADMIN_T")" "员工不存在"


echo
echo "########## B. 停用即失效（这轮最值钱的一条）##########"
# 顺序是刻意的：**先证明这张票能用，再停用，再断言它死了**。
# 反过来写（直接停用就断言 401）测的是"它本来就不能用"，那条断言无条件通过 ——
# 和 verify-stores.sh 的 A3/A3b 是同一个形状的坑
#
# 探针和 B4 用**同一条 URL**（店长进得去的 /api/orders），于是 B1→B4 是
# "同一个请求、同一张票：停用前 200、停用后 401"的干净前后对照。
# B3 那边则故意留在 /api/staff 上，用它证另一件事（见 B3 上面的注释）—— 见 Bug 37
check "B1 停用**之前**，这张票是在用的（对照组）" \
  "$(getJson /api/orders "$T_A")" '"code":200'

B2=$(putJson "/api/staff/$U_A_ID/status" '{"status":0}' "$ADMIN_T")
check "B2 停用 → 200 且回显 status=0" "$B2" '"status":0'
check "B2b 库里 status=0" "$(db "select status from staff where id=$U_A_ID;")" '^0$'
# 直接看 Redis 里那个键 —— 只看"旧票 401"分不出是水位线生效还是别的原因
WM=$(redis_get "auth:staff:invalidAfter:$U_A_ID")
check "B2c 停用写下了失效水位线（Redis 里那个键真的在）" "$WM" '^[0-9][0-9]*$'
# 停掉的人必须还在列表里，否则没法把他启用回来（列表走 findAll 不是 findOpen）
check "B2d 停用后他仍然出现在员工列表里（不然没人能把他启用回来）" \
  "$(getJson /api/staff "$ADMIN_T")" "$U_A"
check "B2e 详情里那一行的 status 是 0" \
  "$(getJson "/api/staff/$U_A_ID" "$ADMIN_T")" '"status":0'

# B3/B3b **故意**留在 /api/staff：店长的票在这条 URL 上"票好=403（闸门挡的）、
# 票废=401"，所以拿到 401 只可能来自作废检查 —— 顺带钉住了 JwtInterceptor:74
# 那句注释声称的事：**作废检查排在角色闸门之前**。
# 换成 /api/orders 反而弱了：那边 200/401/403 都可能出现，分不清是谁在说话。
# （而这正是 A2b/B1 原来的毛病：用一条"对方本来就进不去"的 URL 当探针 —— Bug 37）
check   "B3 同一张票**立刻** 401（不用等 24 小时自然过期）" \
  "$(getJson /api/staff "$T_A")" '"code":401'
# 措辞必须指向真正的原因：写"账号已被停用"的话，降级和改密码那两条路
# 会拿到一句错话（Bug 20 的教训）
check   "B3b 那句话说的是「已停用或权限已变更」，不是「未登录」" \
  "$(getJson "/api/staff/$U_A_ID" "$T_A")" "账号已被停用或权限已变更，请重新登录"
# 票是**整张**废了，不是只废了这一个前缀
check "B4 拿同一张票去订单接口也是 401（废的是票，不是一个 URL）" \
  "$(getJson /api/orders "$T_A")" '"code":401'

check "B5 停用后再登录 → 403 账号已被停用" \
  "$(postJson /api/auth/staff/login "{\"username\":\"$U_A\",\"password\":\"$PW\"}" "")" \
  "账号已被停用"

# ── 重新启用：旧票**不会**复活（这是水位线比"布尔停用标记"强的地方）──
B6=$(putJson "/api/staff/$U_A_ID/status" '{"status":1}' "$ADMIN_T")
check "B6 重新启用 → 200 且回显 status=1" "$B6" '"status":1'
check "B6b 重新启用后，停用前那张票**仍然是 401**（旧票不会复活）" \
  "$(getJson /api/staff "$T_A")" '"code":401'
# 但要能重新登录 —— 否则"启用"这个动作是假的。
# 这里的 sleep 1 是**这个窗口最早被发现的地方**（Bug 34 就是在这里量到的），
# 现在那段说明在 relogin 助手里 —— 四处重新登录共用同一条规则。
T_A2=$(relogin "$U_A" "$PW")
if [ -z "$T_A2" ]; then
  echo "==> [准备失败] 启用之后重新登录没拿到 token —— 那说明「启用」是假的"; exit 1
fi
check "B6c 启用后重新登录拿到的新票能用" "$(getJson /api/orders "$T_A2")" '"code":200'

echo
echo "########## C. 降级即失效（admin → 店长）##########"
# 为什么这条不能漏：把越权的管理员降级，他那张票里还签到 role=0，
# 只做"停用即失效"的话，他还能继续管人与店，最长 24 小时。
# 这是同一个洞的另一半，漏掉等于留半个后门

# 建管理员时**故意传 storeId=1** —— 期望它被强制成 null（与 V3 种子 admin 一致）。
# 这里是**忽略**入参而不是报 400：前端角色下拉框一改，门店下拉框里往往还留着
# 上一次选的值，为此弹一个 400 是拿用户的疏忽惩罚他
C1=$(postJson /api/staff \
  "{\"username\":\"$U_C\",\"password\":\"$PW\",\"name\":\"验收管理员\",\"role\":0,\"storeId\":1,\"phone\":null}" \
  "$ADMIN_T")
check "C1 建 role=0 的管理员 → 200" "$C1" '"code":200'
check "C1b 回显的 role 是 ADMIN" "$C1" '"role":"ADMIN"'
check "C1c 管理员传了 storeId=1 → 回显里被强制成 null" "$(jqf "$C1" storeId)" '^null$'
U_C_ID=$(jqf "$C1" id)
if [ -z "$U_C_ID" ]; then echo "==> [准备失败] C1 没拿到员工 id：$C1"; exit 1; fi
check "C1d 库里 store_id 也是 NULL（不是只在回显里抹掉了）" \
  "$(db "select ifnull(store_id,'<NULL>') from staff where id=$U_C_ID;")" '<NULL>'

T_C=$(jqf "$(postJson /api/auth/staff/login "{\"username\":\"$U_C\",\"password\":\"$PW\"}" "")" token)
if [ -z "$T_C" ]; then echo "==> [准备失败] 新建的管理员登录没拿到 token"; exit 1; fi
check "C2 这个管理员**确实**进得来管理接口（对照组，先证明票是好用的）" \
  "$(getJson /api/staff "$T_C")" '"code":200'

C3=$(putJson "/api/staff/$U_C_ID" \
  "{\"name\":\"验收管理员\",\"role\":1,\"storeId\":null,\"phone\":null}" "$ADMIN_T")
check "C3 降级为店长 → 200" "$C3" '"code":200'
check "C3b 库里 role=1" "$(db "select role from staff where id=$U_C_ID;")" '^1$'

check "C4 同一张票**立刻** 401（票里还签着旧的 role=0，不作废就还能用）" \
  "$(getJson /api/staff "$T_C")" '"code":401'
# C4b 是 C4 的**判据**：两张票一个 401 一个 403，才说明 C4 的死因是"票作废了"，
# 而不是"角色不够"（403）。少了它，C4 的 401 可能只是别的原因
T_C2=$(relogin "$U_C" "$PW")
if [ -z "$T_C2" ]; then echo "==> [准备失败] 降级后重新登录没拿到 token"; exit 1; fi
check "C4b 换一张**新票**：他现在只是店长 → 403（两句不同，C4 的 401 才说得清是"票废了"）" \
  "$(getJson /api/staff "$T_C2")" "员工与门店管理只对管理员开放"

echo
echo "########## D. 门店管理（POST/PUT /api/stores + /api/stores/all）##########"

D1=$(postJson /api/stores \
  "{\"name\":\"$STORE_NEW\",\"address\":\"$STORE_NEW_ADDR\",\"phone\":\"0571-88887777\"}" "$ADMIN_T")
check "D1 建店 → 200" "$D1" '"code":200'
check "D1b 新建一律营业（status=1）" "$D1" '"status":1'
S_NEW_ID=$(jqf "$D1" id)
if [ -z "$S_NEW_ID" ]; then echo "==> [准备失败] D1 没拿到门店 id：$D1"; exit 1; fi
check "D1c 库里 status=1" "$(db "select status from stores where id=$S_NEW_ID;")" '^1$'
# 中文列按字节断言（库里若是乱码，"停业后列表里看不到它"会无条件通过）
check "D1d 库里门店名的字节是对的" \
  "$(dbQ "select name from stores where id=$S_NEW_ID;")" "^$STORE_NEW$"

# 探针用**顾客**的票：这条说的是"顾客下单时选得到这家店"，
# 用店长的票也能过（读接口对任何有效 token 都开），但那句话就名不副实了 ——
# 验的是店长的读权限，不是顾客的下单路径（D8 同理）
check "D2 顾客的选店列表（GET /api/stores）里能看到新店" \
  "$(getJson /api/stores "$CUST_T")" "$STORE_NEW"
check "D3 管理列表（/api/stores/all）里也有它" \
  "$(getJson /api/stores/all "$ADMIN_T")" "$STORE_NEW"
check "D3b /all 里能看到 99 号**停业**店（all 才是含停业的那个投影）" \
  "$(getJson /api/stores/all "$ADMIN_T")" "$STORE_CLOSED_NAME"

D4=$(putJson "/api/stores/$S_NEW_ID" \
  "{\"name\":\"$STORE_NEW\",\"address\":\"杭州市拱墅区改过的地址\",\"phone\":null}" "$ADMIN_T")
check "D4 改地址 → 200" "$D4" '"code":200'
check "D4b 库里地址真改了" \
  "$(dbQ "select address from stores where id=$S_NEW_ID;")" '^杭州市拱墅区改过的地址$'
# 改资料不该顺手把停业的店开起来（反过来也一样）
check "D4c 改资料没有顺手改 status（还是 1）" "$(jqf "$D4" status)" '^1$'

# ── 调岗：把 C 段那个店长挂到新店上 ──
# 四种作废情形里的第三种（停用 / 降级 / 改门店 / 重置密码）。漏掉它的话，
# "把他调到别的店"之后，他手上那张票里签的还是旧 storeId
# D5 的票必须在**上一次作废（C3 降级）那一秒之外**签发 —— 否则它一出生就是旧的
T_C3=$(relogin "$U_C" "$PW")
if [ -z "$T_C3" ]; then echo "==> [准备失败] 调岗前登录没拿到 token"; exit 1; fi
check "D5 调岗之前，这张票是在用的（对照组）" \
  "$(getJson /api/stores "$T_C3")" '"code":200'

D6=$(putJson "/api/staff/$U_C_ID" \
  "{\"name\":\"验收管理员\",\"role\":1,\"storeId\":$S_NEW_ID,\"phone\":null}" "$ADMIN_T")
check "D6 把店长调到新店 → 200" "$D6" '"code":200'
check "D6b 库里 store_id 变了" \
  "$(db "select store_id from staff where id=$U_C_ID;")" "^$S_NEW_ID$"
check "D6c **调岗也作废了**他那张票（401）" "$(getJson /api/stores "$T_C3")" '"code":401'

T_C4=$(relogin "$U_C" "$PW")
if [ -z "$T_C4" ]; then echo "==> [准备失败] 调岗后重新登录没拿到 token"; exit 1; fi
check "D6d 重新登录的新票能用" "$(getJson /api/stores "$T_C4")" '"code":200'

# ── 停业 ──
D7=$(putJson "/api/stores/$S_NEW_ID/status" '{"status":0}' "$ADMIN_T")
check "D7 停业 → 200 且回显 status=0" "$D7" '"status":0'
check "D7b 库里 status=0" "$(db "select status from stores where id=$S_NEW_ID;")" '^0$'
# 这一组三条缺一不可，形状同 verify-stores 的 A3/A3b/A3c：
#   D8  顾客的列表里没有了
#   D8b 但管理的列表里还有 —— 否则 D8 通过只是因为"这家店根本没建上"
#   D8c 而且往它下单会被挡 —— 停业是**即时生效**的，不是"列表里藏起来"而已
checkNot "D8 停业后顾客的选店列表里**没有**它了" "$(getJson /api/stores "$CUST_T")" "$STORE_NEW"
check    "D8b 但 /api/stores/all 里还看得到（证明 D8 是过滤、不是店没了）" \
  "$(getJson /api/stores/all "$ADMIN_T")" "$STORE_NEW"
check    "D8c 往停业店下单 → 400 门店不存在或已停业" \
  "$(postJson /api/orders \
     "{\"storeId\":$S_NEW_ID,\"source\":2,\"deliveryAddress\":\"杭州市西湖区文一西路 100 号\",\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
     "$CUST_T")" \
  "门店不存在或已停业"

# ── 停业**不踢人** ──
# 停业门店 ≠ 数据隔离。员工的 token 里签的是 storeId，不是门店的营业状态；
# 停业的真实后果发生在**下单那一刻**（D8c 刚验过）。所以这张票必须还活着 ——
# 不然"停掉一家店"就会顺手把店里所有人踢下线，而那是另一件事
check "D9 停业**不**踢人：停业前那张票仍然能用（token 里签的是 storeId，不是营业状态）" \
  "$(getJson /api/stores "$T_C4")" '"code":200'

# ── 老口径回归：GET /api/stores 仍然只含营业中的店 ──
D10=$(getJson /api/stores "$ADMIN_T")
check    "D10 老接口仍然含营业中的「云洗中央门店」" "$D10" "云洗中央门店"
checkNot "D10b 老接口里没有 99 号停业店（这条同时守着 verify-stores 的 A3）" \
  "$D10" "$STORE_CLOSED_NAME"

check "D11 改不存在的门店 → 404 门店不存在" \
  "$(putJson "/api/stores/$STORE_GHOST" "{\"name\":\"谁\",\"address\":\"哪\",\"phone\":null}" "$ADMIN_T")" \
  "门店不存在"
check "D11b 停业不存在的门店 → 404 门店不存在" \
  "$(putJson "/api/stores/$STORE_GHOST/status" '{"status":0}' "$ADMIN_T")" "门店不存在"
D12=$(putJson "/api/stores/$S_NEW_ID" \
  "{\"name\":\"$LONG_STORE_NAME\",\"address\":\"路\",\"phone\":null}" "$ADMIN_T")
check   "D12 店名 51 字 → 400（不是 500 的 SQL 异常）" "$D12" "门店名称不能超过 50 个字"
check   "D12c 被拒后库里名字没变" "$(dbQ "select name from stores where id=$S_NEW_ID;")" "^$STORE_NEW$"
# 门店形状校验也照样只留一条代表：D12d 证"接上了"，具体规则（地址必填、
# 建店侧、status 越界）在 StoreAdminAppServiceTest 的 Create/Update 两套里。
# "消息里没有英文"这条属性现在只留 A6b（撞唯一键）和 A7b（超列宽）两条 ——
# 它们是 MySQL 两种不同的失败，各自都可能把英文漏出来，所以不算重复；
# 门店侧那条（DataTooLong）是这里的第三份拷贝，删掉
check "D12d 店名为空 → 400 门店名称不能为空" \
  "$(putJson "/api/stores/$S_NEW_ID" '{"name":"  ","address":"路","phone":null}' "$ADMIN_T")" \
  "门店名称不能为空"

echo
echo "########## E. 闸门·反方向：管理接口只对管理员开放 ##########"
# 2026-09-18 新加的方向。原来 checkRoleGate 只有"拦管理员"那半边，
# 开头就是 `if (role != ADMIN) return;` —— 于是**店长能到达任何一个 URL**，
# 能建号、能改别人的角色、能停用管理员。这九条把新方向逐个路由钉住。
# 说辞要一致：**这些都是 403 而不是 401** —— 票是好的，是角色不够
GATE_MSG="员工与门店管理只对管理员开放，请使用管理员账号"
check "E1 店长 GET /api/staff（读也归管理员）→ 403" "$(getJson /api/staff "$MGR_T")" "$GATE_MSG"
check "E2 店长 GET /api/staff/{id} → 403" "$(getJson /api/staff/1 "$MGR_T")" "$GATE_MSG"
check "E3 店长 POST /api/staff 建号 → 403" \
  "$(postJson /api/staff \
     "{\"username\":\"hack$RUN\",\"password\":\"$PW\",\"name\":\"黑客\",\"role\":0,\"storeId\":null,\"phone\":null}" \
     "$MGR_T")" "$GATE_MSG"
check "E4 店长 PUT /api/staff/{id} 改别人角色 → 403" \
  "$(putJson "/api/staff/$U_C_ID" '{"name":"黑客","role":0,"storeId":null,"phone":null}' "$MGR_T")" \
  "$GATE_MSG"
check "E5 店长 PUT /api/staff/{id}/password 重置别人密码 → 403" \
  "$(putJson "/api/staff/$U_C_ID/password" '{"password":"hacked"}' "$MGR_T")" "$GATE_MSG"
check "E6 店长 PUT /api/staff/{id}/status 停用管理员 → 403" \
  "$(putJson "/api/staff/1/status" '{"status":0}' "$MGR_T")" "$GATE_MSG"
check "E7 店长 POST /api/stores 建店 → 403" \
  "$(postJson /api/stores '{"name":"hack","address":"hack","phone":null}' "$MGR_T")" "$GATE_MSG"
check "E8 店长 PUT /api/stores/{id} 改店 → 403" \
  "$(putJson "/api/stores/$S_NEW_ID" '{"name":"hack","address":"hack","phone":null}' "$MGR_T")" \
  "$GATE_MSG"
check "E9 店长 PUT /api/stores/{id}/status 停业 → 403" \
  "$(putJson "/api/stores/$S_NEW_ID/status" '{"status":1}' "$MGR_T")" "$GATE_MSG"
# E10/E11 是**不能误伤**的那半边：门店的读对所有有效 token 开放是既有口径
# （§6.4「任意有效 token」），而且 verify-stores.sh 的 B9b 硬钉着
# "管理员 GET /api/stores 必须 200"（见 F6）。闸门按**方法**分就是为了它们。
check "E10 店长 GET /api/stores **仍然 200**（读不误伤：下单要先选店）" \
  "$(getJson /api/stores "$MGR_T")" '"code":200'
check "E11 店长 GET /api/stores/all 仍然 200（暂停业的读也是任意有效 token）" \
  "$(getJson /api/stores/all "$MGR_T")" '"code":200'
check "E11b 顾客 GET /api/stores 仍然 200" "$(getJson /api/stores "$CUST_T")" '"code":200'
check "E11c 顾客 GET /api/stores/all 仍然 200" \
  "$(getJson /api/stores/all "$CUST_T")" '"code":200'
# 顾客走的是另一条路（controller 里的 requireStaff），措辞也不同 ——
# 闸门只管员工之间的事，顾客能用哪些接口由各 controller 自己判断
check "E12 顾客 token POST /api/stores → 401 请使用员工账号操作（不是 403）" \
  "$(postJson /api/stores '{"name":"hack","address":"hack","phone":null}' "$CUST_T")" \
  "请使用员工账号操作"
check "E12b 顾客 token GET /api/staff → 401 请使用员工账号操作" \
  "$(getJson /api/staff "$CUST_T")" "请使用员工账号操作"
# 被拒之后**什么都没发生**：E8 想改的店名一个字没动
check "E13 店长那九次尝试之后，新店的资料一个字没动" \
  "$(dbQ "select name from stores where id=$S_NEW_ID;")" "^$STORE_NEW$"
check "E13b 新店的 status 也没被 E9 打开（仍然是 0）" \
  "$(db "select status from stores where id=$S_NEW_ID;")" '^0$'
check "E13c E3 想建的号不存在" \
  "$(db "select count(*) from staff where username='hack$RUN';")" '^0$'
check "E13d E5 想重置的密码没生效（原密码还能登录）" \
  "$(postJson /api/auth/staff/login "{\"username\":\"$U_C\",\"password\":\"$PW\"}" "")" '"code":200'
check "E13e 管理员 admin 没被 E6 停用（脚手架守卫的第二次断言，故意的）" \
  "$(db "select status from staff where username='admin';")" '^1$'

echo
echo "########## E14–E19 改密码的成功路径（本仓脚本第一次真跑它）##########"
# 这一段补的是一个**量出来的洞**：`PUT /api/staff/{id}/password` 在本仓脚本里
# **从来没有成功跑通过一次** —— E5 碰的是它的 403、原 A10d 碰的是它的 404，
# "200 之后那一串"没人验过。而它的语义偏偏最重（StaffAdminAppService:215-219）：
# **改密码 = 从前签发的凭据全部不算数**，少作废一步就等于"密码改了，别人的会话还活着"。
#
# 骨架完全照 B 段（停用即失效）：**先证这张票能用，再改密码，再断言它死了**。
# 反过来写（改完直接断言 401）测的是"它本来就不能用"，那条断言无条件通过（Bug 37）。
#
# 对照组和 E18 用同一条 URL（/api/orders）—— 此刻 U_C 已被 C 段降级成店长，
# 店长进得去这条。**特意不用 /api/staff**：店长在那条 URL 上"票好=403、票废=401"，
# 403 会把前后对照搅浑（B3 那条注释里同一个坑）。E18 则因此干净地只可能来自作废检查。
# 走 relogin（要等过一秒）：上一次作废是 D6 的调岗，离得不算近但**不保证跨秒**
T_C5=$(relogin "$U_C" "$PW")
if [ -z "$T_C5" ]; then
  echo "==> [准备失败] 改密码前的对照组登录没拿到票（$U_C / $PW）"; exit 1
fi
check "E14 改密码**之前**，这张票是在用的（对照组）" \
  "$(getJson /api/orders "$T_C5")" '"code":200'

OLD_HASH=$(db "select password from staff where id=$U_C_ID;")
check "E15 管理员重置密码 → 200" \
  "$(putJson "/api/staff/$U_C_ID/password" "{\"password\":\"$PW2\"}" "$ADMIN_T")" '"code":200'
# 直接看 Redis 里那个键 —— 只看"旧票 401"分不出是水位线生效还是别的原因（同 B2c）
WM2=$(redis_get "auth:staff:invalidAfter:$U_C_ID")
check "E15b 改密码写下了失效水位线（Redis 里那个键真的在）" "$WM2" '^[0-9][0-9]*$'
NEW_HASH=$(db "select password from staff where id=$U_C_ID;")
check "E15c 库里那串哈希真的换了（不是只改了回显）" \
  "$([ "$OLD_HASH" = "$NEW_HASH" ] && echo 一样 || echo 变了)" "变了"

check "E16 新密码能登录 → 200（改密码的另一半：得让人进得来）" \
  "$(postJson /api/auth/staff/login "{\"username\":\"$U_C\",\"password\":\"$PW2\"}" "")" '"code":200'
check "E17 旧密码不能登录 → 401（不是 200）" \
  "$(postJson /api/auth/staff/login "{\"username\":\"$U_C\",\"password\":\"$PW\"}" "")" '"code":401'
check "E18 改密码前签发的票**当场**作废 → 401（同一张票、同一条 URL）" \
  "$(getJson /api/orders "$T_C5")" '"code":401'
# 水位线是**按 staffId** 写的，不是一把全局锁 —— 这条防的是"改一个人的密码，全店掉线"
check "E19 别人的票不受影响（E5 之后管理员的票还在这儿用着）" \
  "$(getJson /api/staff "$ADMIN_T")" '"code":200'

echo
echo "########## F. 闸门·原方向（防回归）##########"
# 反方向是**新加的**，最容易顺手把原方向带歪。这六条是三条老规则 + 一条别误伤。
# 说辞一个字都不该变 —— 闸门的台词也是被测的（scripts/README.md）
check "F1 管理员 POST /api/orders → 403 管理员不参与订单操作" \
  "$(postJson /api/orders \
     "{\"customerId\":1,\"source\":1,\"items\":[{\"categoryId\":11,\"washTypeId\":1,\"quantity\":1}]}" \
     "$ADMIN_T")" \
  "管理员不参与订单操作"
check "F2 管理员 GET /api/orders 也是 403（含读接口）" \
  "$(getJson /api/orders "$ADMIN_T")" "管理员不参与订单操作"
check "F3 管理员 PUT /api/prices/11 → 403 管理员不能修改价格" \
  "$(curl -s -X PUT "$BASE/api/prices/11" -H "Content-Type: application/json" \
     -H "Authorization: Bearer $ADMIN_T" \
     -d '{"prices":[{"washTypeId":1,"price":99.00}]}')" \
  "管理员不能修改价格"
# 只看 403 不够：一个"先改价再报 403"的实现也能骗过前一条
check "F3b 被拒之后价目表没被动过（衬衫普洗仍是 15.00）" \
  "$(db "select price from clothes_prices where category_id=11 and wash_type_id=1;")" "15.00"
check "F4 管理员 GET /api/customers/me → 403 管理员不参与顾客相关操作" \
  "$(getJson /api/customers/me "$ADMIN_T")" "管理员不参与顾客相关操作"
check "F4b 管理员 POST /api/customers/lookup-or-create → 403 同一句" \
  "$(postJson /api/customers/lookup-or-create \
     "{\"phone\":\"$PHONE_CUST\",\"name\":\"谁\"}" "$ADMIN_T")" \
  "管理员不参与顾客相关操作"
# 新闸门最容易误伤的就是这条：verify-stores.sh 的 B9b 也钉着它，
# 这里再钉一次是**故意重复** —— 两个脚本各自独立地守着同一个口径
check "F5 管理员 GET /api/stores 仍然 200（新闸门按方法分就是为了它）" \
  "$(getJson /api/stores "$ADMIN_T")" '"code":200'
check "F5b 管理员 GET /api/stores/all 也是 200" \
  "$(getJson /api/stores/all "$ADMIN_T")" '"code":200'

echo
echo "########## G. 门店引用校验（staff.store_id 没有外键）##########"
# 库里**没有外键**（staff.store_id 只是个普通索引，见 V1），所以数据库拦不下
# 一个 999 —— 这条只能应用层拦。不拦就能建出一个 store_id=999 的店长，
# 他的 token 里会签着一个不存在的门店，属于"平时看不出问题、排查时查到天亮"的脏数据。
# A1 是这里的**对照组**：同一个请求体、只把 storeId 换成 1 就 200。
# 没有它，G1 的 400 可能来自请求体里别的地方，那这条断言只是在证明"这个体是坏的"
G1=$(postJson /api/staff \
  "{\"username\":\"g$RUN\",\"password\":\"$PW\",\"name\":\"幽灵店店长\",\"role\":1,\"storeId\":$STORE_GHOST,\"phone\":null}" \
  "$ADMIN_T")
check "G1 建店长时 storeId=$STORE_GHOST（不存在）→ 400 门店不存在" "$G1" "门店不存在"
check "G1b 被拒之后真没建（幽灵门店的店长不该存在）" \
  "$(db "select count(*) from staff where username='g$RUN';")" '^0$'
check "G2 改员工时 storeId=$STORE_GHOST → 400 门店不存在" \
  "$(putJson "/api/staff/$U_C_ID" \
     "{\"name\":\"验收管理员\",\"role\":1,\"storeId\":$STORE_GHOST,\"phone\":null}" "$ADMIN_T")" \
  "门店不存在"
# 挂到**停业**的门店上是**允许**的（查的是 findById 不是 findOpenById）：
# 把一个店长先挂到还没开业 / 已停业的店上是正常操作，拿 findOpenById 会把
# "停业的店"误判成"不存在的店"，于是你永远改不了一个已停业门店的员工 ——
# 而那正是他最需要被改的时候
check "G3 挂到 99 号**停业**门店 → 200（停业 ≠ 不存在）" \
  "$(putJson "/api/staff/$U_C_ID" \
     "{\"name\":\"验收管理员\",\"role\":1,\"storeId\":$STORE_CLOSED_ID,\"phone\":null}" "$ADMIN_T")" \
  '"code":200'
check "G3b 库里真的挂上去了" \
  "$(db "select store_id from staff where id=$U_C_ID;")" "^$STORE_CLOSED_ID$"

echo
echo "########## H. 收尾：把本次造的数据软停用 ##########"
# **不硬删**：这轮"删"的口径就是软停用（StaffController 的注释写了为什么）。
# 员工行留着，用户名就占着 —— 所以本脚本的名字都带时间戳（见文件头）。
for PAIR in "员工 $U_A_ID $U_A" "员工 $U_C_ID $U_C"; do
  set -- $PAIR
  R=$(putJson "/api/staff/$2/status" '{"status":0}' "$ADMIN_T")
  if echo "$R" | grep -q '"code":200'; then
    echo "  已停用 $1 id=$2（$3）"
  else
    echo "  [注意] 停用 $1 id=$2 没成功：$R"
  fi
done
R=$(putJson "/api/stores/$S_NEW_ID/status" '{"status":0}' "$ADMIN_T")
if echo "$R" | grep -q '"code":200'; then
  echo "  已停业 门店 id=$S_NEW_ID（$STORE_NEW）"
else
  echo "  [注意] 停业门店 id=$S_NEW_ID 没成功：$R"
fi
# 顾客是**硬删**的：顾客表本来就没有"停用"一说，而且它是脚本注册的临时号
if [ -n "$PHONE_CUST" ] && [ ${#PHONE_CUST} -eq 11 ]; then
  db "delete from customers where phone='$PHONE_CUST';"
  echo "  已删除顾客号 $PHONE_CUST"
else
  echo "  [跳过] 手机号 '$PHONE_CUST' 不合法，不执行删除"
fi
# 脚手架一个字不动 —— 它们下次跑还要用
echo "  保留脚手架：stores id=1/2/99、staff id=98/99、admin / manager"
echo "  本次留下的残渣（都是 status=0，累积型）：staff $U_A / $U_C、stores $STORE_NEW"

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
[ $FAIL -eq 0 ]
