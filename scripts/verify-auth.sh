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
# 脏数据：一个停用账号（staff id=98, mgr_disabled）、一个无密码的门店单顾客
#   （13700000002, WalkIn）。两者都是 insert ignore，可重复跑。
#   每次运行新注册的顾客会在结尾删掉（见 F 段）。
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
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N -e "$1" 2>/dev/null | tr -d '\r'; }

# 每次运行用不同手机号：固定号第二次跑就变"重复注册"，B1 会莫名其妙红
PHONE_NEW="137$(date +%s | tail -c 9)"
PHONE_ABSENT="138$(date +%s | tail -c 9)"
PHONE_NOPWD="13700000002"

echo "########## 准备：脚手架 ##########"

# 停用账号（status=0）。密码哈希是 admin123 的 BCrypt——这里必须写死在 SQL 里，
# 因为要的就是"账号密码都对、但被停用"这个组合
docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -e \
  "insert ignore into staff (id,username,password,name,role,store_id,status)
   values (98,'mgr_disabled','\$2a\$10\$fEzKJTH469Zd9GB0CKMLseS/iFVndCGene.WQiQ53Q/isi2yZa5oS','停用店长',1,1,0);
   insert ignore into customers (name,phone,password) values ('WalkIn','$PHONE_NOPWD',null);
   update customers set password=null where phone='$PHONE_NOPWD';" 2>/dev/null

# 准备阶段守卫（Bug 22 教训）：前置条件不成立就立刻停，别让它继续跑成断言失败
D_STATUS=$(db "select status from staff where username='mgr_disabled';")
if [ "$D_STATUS" != "0" ]; then
  echo "==> [准备失败] mgr_disabled 的 status 应为 0，实际 '$D_STATUS'"; exit 1
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
     -d '{"name":"NoPhone","password":"123456"}')" \
  "姓名、手机号、密码不能为空"

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
echo "########## D. 探针：当前行为记录（不做断言）##########"
# 这几条现在**没有防御**（body.get 取不到就是 null，直接往下传）。
# 装修前先把毛坯的样子拍下来——是 400 还是 500，是中文还是英文内部异常，
# 看清楚了才好决定要不要在整改时顺手补上
echo "  D1 员工登录缺 password："
echo "     $(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
             -d '{"username":"manager"}')"
echo "  D2 员工登录缺 username："
echo "     $(curl -s -X POST $BASE/api/auth/staff/login -H "Content-Type: application/json" \
             -d '{"password":"admin123"}')"
echo "  D3 顾客登录缺 password："
echo "     $(curl -s -X POST $BASE/api/auth/customer/login -H "Content-Type: application/json" \
             -d "{\"phone\":\"$PHONE_NEW\"}")"
echo "  D4 顾客注册缺 name："
echo "     $(curl -s -X POST $BASE/api/auth/customer/register -H "Content-Type: application/json" \
             -d "{\"phone\":\"$PHONE_ABSENT\",\"password\":\"123456\"}")"

echo
echo "########## F. 收尾：清掉本次注册的顾客 ##########"
if [ -n "$PHONE_NEW" ] && [ ${#PHONE_NEW} -eq 11 ]; then
  db "delete from customers where phone='$PHONE_NEW';"
  echo "  已删除本次注册的 $PHONE_NEW（脚手架 mgr_disabled / WalkIn 保留，供下次复用）"
else
  echo "  [跳过] PHONE_NEW 不合法（'$PHONE_NEW'），不执行删除"
fi

echo
echo "================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "================================"
[ $FAIL -eq 0 ]
