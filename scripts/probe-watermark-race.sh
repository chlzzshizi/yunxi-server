#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# 探针：作废水位线的"同一秒"窗口（docs/bug-record.md 里的 Bug 46，源头是 Bug 34 的边界①）
#
# ⚠️ 它**不是**验收脚本，故意不叫 verify-*.sh：
#   CI 那一步是 `for f in scripts/verify-*.sh` 扫的（九连跑），本探针不在其中，
#   也不进 scripts/README.md 那两张计数表（389 条是九个验收脚本的账）。
#   它是**诊断工具** —— 想复现这个窗口、或想确认窗口还在不在时手动跑。
#
# 为什么值得留一个脚本，而不是"知道了就行"：
#   甲段期望的那个 401 本身就是**错**的行为，只是它现在**必然发生**。
#   将来有人把水位线改成毫秒精度（或把判据从 < 改成 <=），甲段会**变红** ——
#   那时它红得对：说明 verify-admin.sh 里 relogin() 的那个 sleep 1 可以拿掉了。
#   所以：甲段红=有缺陷（现在），甲段绿=缺陷没了（将来）。两种都不是"脚本坏了"。
#
# ═══ 这个窗口到底在比什么（第一版探针在这里判错过，教训留在这）═══
#   写：revokeAll → auth:staff:invalidAfter:<id> = System.currentTimeMillis()（**毫秒**）
#   读：isRevoked(staffId, iat) → iat.getTime() < watermark
#   而 JWT 的 iat 是**秒**（jjwt 落到整秒，即 floor）。于是同一个自然秒里：
#       签发(.000) < 水位线(.264)  → 新票被判成旧的 → 401
#   判据只有一个：**iat 那一秒 == 水位线那一秒**。
#
#   ⚠️ 别用 `date +%s` 去判"是不是同一秒"：登录这一趟往返（curl + 文本处理）
#      在这台 Windows 上要几百毫秒，等你读到 date，秒已经翻过去了 —— 而票上的
#      iat 还停在上一秒。第一版就是拿 shell 的钟去判，把一次**判对了的 401**
#      报成了红。这里一律从**票本身**（iat claim）和 **Redis**（水位线）取数。
#
#   ⚠️ 也别在"作废"与"登录"之间夹任何东西 —— 连一次 docker exec redis-cli get
#      都有几百毫秒，够把窗口整段吃掉（实测过：夹了一次取水位线，窗口就没了；
#      甲段那次登录便跨到下一秒，成了 200）。所以甲段的结构是：
#      对齐秒 → 作废 → 立刻登录 → **之后**才去取数核对。
#
# 用法：bash scripts/probe-watermark-race.sh     后端要在 8081 上起着
# 副作用：建一个 staff 行（probe_wm<时间戳>，店长、status=1），不回收
#        —— 与 verify-admin.sh 往库里留行的既有做法一致。
# 退出码：0=窗口复现了（现状）  1=哪条断言不符  2=场景没造出来（机器太慢，不作结论）
# ASCII-only：非 ASCII 的 argv 会被 MSYS2 转成 GBK（吃过两次亏）。
# ─────────────────────────────────────────────────────────────────────
BASE=http://localhost:8081
PW=admin123
PW2=probe999
PW3=probe888
RUN=$(date +%s)
U="probe_wm$RUN"

PASS=0; FAIL=0
ID=""; ADMIN_T=""; T_A=""; W1=""

jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
hr()  { echo "$1" | grep -o '"code":[0-9]*' | head -1 | sed 's/"code"://'; }
msg() { echo "$1" | grep -o '"message":"[^"]*"' | head -1 | cut -d: -f2- | tr -d '"'; }
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  [OK]   $1"
  else FAIL=$((FAIL+1)); echo "  [FAIL] $1"; echo "         期望: $3"; echo "         实际: $2"; fi
}
checkContains() {
  case "$2" in *"$3"*) PASS=$((PASS+1)); echo "  [OK]   $1";;
    *) FAIL=$((FAIL+1)); echo "  [FAIL] $1"; echo "         期望含: $3"; echo "         实际: $2";; esac
}
# 票上那一秒（iat，秒）；水位线那一秒（毫秒 / 1000）
iat() { local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+')
        case $(( ${#p} % 4 )) in 2) p="$p==";; 3) p="$p=";; esac
        echo "$p" | base64 -d 2>/dev/null | grep -o '"iat":[0-9]*' | head -1 | cut -d: -f2; }
wm()  { docker exec yunxi-redis redis-cli get "auth:staff:invalidAfter:$1" 2>/dev/null | tr -d '\r'; }
wait_next_second() { local s; s=$(date +%s); while [ "$(date +%s)" = "$s" ]; do :; done; }
login() { jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
           -d "{\"username\":\"$U\",\"password\":\"$1\"}")" token; }
call()  { curl -s "$BASE/api/orders" -H "Authorization: Bearer $1"; }
revoke() { curl -s -X PUT "$BASE/api/staff/$ID/password" -H "Content-Type: application/json" \
             -H "Authorization: Bearer $ADMIN_T" -d "{\"password\":\"$1\"}" > /dev/null; }

ADMIN_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')" token)
if [ -z "$ADMIN_T" ]; then echo "==> [准备失败] admin 没拿到票（后端没起？）"; exit 1; fi
C=$(curl -s -X POST $BASE/api/staff -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_T" \
  -d "{\"username\":\"$U\",\"password\":\"$PW\",\"name\":\"$U\",\"role\":1,\"storeId\":1,\"phone\":null}")
ID=$(jqf "$C" id)
case "$ID" in ''|*[!0-9]*) echo "==> [准备失败] 探针员工没建出来：$C"; exit 1;; esac
echo "探针员工 $U id=$ID（跑完不回收，与本仓库往库里留行的既有做法一致）"

echo
echo "########## 甲. 作废 → 同一秒内登录 → 这张新票被判成旧的 ##########"
# 对齐到秒刚翻过去再动手，给"作废 → 登录"留将近一秒的余量。
# 不夹任何其它调用（见文件头）。最多试 3 次：这台机器慢的时候会失手。
TRY=0
while [ $TRY -lt 3 ]; do
  TRY=$((TRY+1))
  wait_next_second
  revoke "$PW2"                 # ← 写水位线，必须紧跟着登录
  T_A=$(login "$PW2")
  W1=$(wm "$ID")                # ← 取数在**之后**，不占窗口
  [ "$(iat "$T_A")" = "$(( W1 / 1000 ))" ] && break
done
echo "  （第 $TRY 次对齐；水位线 $W1（$(( W1 / 1000 )) 秒），票上 iat=$(iat "$T_A")）"
if [ "$(iat "$T_A")" != "$(( W1 / 1000 ))" ]; then
  echo "==> [场景没造出来] 作废与签发始终没落在同一秒（这台机器太慢），本探针不下结论"
  echo "    水位线 $W1，票上 iat=$(iat "$T_A")（差 $(( $(iat "$T_A") * 1000 - W1 )) 毫秒）"
  exit 2
fi
check "甲1 场景成立：签发那一秒 == 水位线那一秒（探出来的，不是假设的）" \
  "$(iat "$T_A")" "$(( W1 / 1000 ))"
check "甲2 同一秒内新签的票长度正常（不是空票 —— 空的也会 401，那是假通过）" \
  "$([ ${#T_A} -gt 100 ] && echo yes || echo no)" "yes"
R_A=$(call "$T_A")
check "甲3 拿它打 /api/orders → **401**（这就是 Bug 46 本身：新票被当成旧的拒掉）" "$(hr "$R_A")" "401"
checkContains "甲4 拒的话术正是 JwtInterceptor:75 那句（证明死因是水位线，不是别的 401）" \
  "$(msg "$R_A")" "账号已被停用或权限已变更"
check "甲5 再打一次仍是 401（同一张票的状态是定的，不是抖动）" "$(hr "$(call "$T_A")")" "401"

echo
echo "########## 乙. 隔出那一秒再登录（relogin 助手的做法）→ 放行 ##########"
sleep 1
T_B=$(login "$PW2")
check "乙1 这张票的 iat 已经晚于水位线那一秒" \
  "$([ "$(iat "$T_B")" -gt "$(( W1 / 1000 ))" ] && echo later || echo same)" "later"
check "乙2 同样打 /api/orders → 200" "$(hr "$(call "$T_B")")" "200"
check "乙3 再打一次仍是 200" "$(hr "$(call "$T_B")")" "200"

echo
echo "########## 丙. 被杀的是「签发时刻」，不是「哪一张票」 ##########"
# 这一段**不**含同秒场景（那是甲的事），所以它本来就不吃窗口、不会时红时绿：
# 丙1/丙2 里那两张票的 iat 都**早于**新水位线，无论什么时候去问都该是 401。
sleep 1
T_C=$(login "$PW2")
check "丙0 先签一张当下好用的票" "$(hr "$(call "$T_C")")" "200"
wait_next_second
revoke "$PW3"
W2=$(wm "$ID")
check "丙1 新一轮作废后：乙那张票（刚才还好用）立刻 401" "$(hr "$(call "$T_B")")" "401"
check "丙2 丙那张票同样 401" "$(hr "$(call "$T_C")")" "401"
check "丙3 两张票的 iat 都确实早于新水位线（这就是 401 的全部理由）" \
  "$(( $(iat "$T_B") * 1000 < W2 ? 1 : 0 ))$(( $(iat "$T_C") * 1000 < W2 ? 1 : 0 ))" "11"
sleep 1
check "丙4 换成新密码隔一秒登录 → 200（窗口只在那一个自然秒里）" \
  "$(hr "$(call "$(login "$PW3")")")" "200"

echo
echo "==============================="
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "==============================="
[ $FAIL -eq 0 ]
