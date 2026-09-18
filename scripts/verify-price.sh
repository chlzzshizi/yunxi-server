#!/bin/bash
# 定价域读接口验收（真机：MySQL + Redis + 8081）
# 覆盖：鉴权门槛、分类树、洗涤方式、价目表拼装、设计文档 §4.5 样例
BASE=http://localhost:8081
PASS=0; FAIL=0

check() {  # check "用例名" "响应" "期望片段"
  if echo "$2" | grep -q "$3"; then
    echo "  [OK]   $1"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1"; echo "         期望含: $3"; echo "         实际: $2"; FAIL=$((FAIL+1))
  fi
}
checkq() { # checkq "用例名" "实际数字" "期望数字"
  if [ "$2" = "$3" ]; then
    echo "  [OK]   $1 = $2"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1 期望 $3 实际 $2"; FAIL=$((FAIL+1))
  fi
}
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

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

echo "########## 准备：两种身份的 token ##########"

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)

# 13900000091 这个顾客**是本脚本建的**（price-write / pricing-authority 排在本脚本
# 后面，指望它已经在库里 —— 那是它们自己的自证工作，见那两个脚本的准备段）。
# 原先这里是一段手写的"注册，失败就登录"，功能与 login_or_register 逐字等价 ——
# 换成共用那份，是为了让"脚手架从哪来"这件事全仓只有一种写法
CUST_T=$(login_or_register PriceReader 13900000091)

# 脚手架自检（**Bug 38** 的规矩："就绪"必须是断言，不能是台词 —— 原先这里印的是
# `${MGR_T:0:20}`，把票面值抄进了 CI 的日志产物，而且票空着也照印）。
# 印长度不印票面值：空/非空一眼可判，也不泄漏票。
if [ -z "$MGR_T" ] || [ -z "$CUST_T" ]; then
  echo "==> [准备失败] 脚手架没拿到 token（manager=${#MGR_T} 字符，customer=${#CUST_T} 字符）"
  echo "             后端是否在 8081？manager/admin123 能否登录？"
  exit 1
fi
# 失败行一律 `==> [准备失败]`：九个脚本统一这一个串，方便在 CI 日志里一把 grep
# （本脚本原先写的是 `  [致命]`，是第 0 步新写的两处之一，与其余六个不一致）
echo "  manager/customer token 就绪（${#MGR_T} / ${#CUST_T} 字符）"

echo
echo "########## A. 鉴权门槛 ##########"

check "A1 无 token 拉分类 → 401 未登录" \
  "$(curl -s $BASE/api/categories)" "未登录"

check "A2 无 token 拉价目表 → 401" \
  "$(curl -s $BASE/api/prices)" "未登录"

check "A3 伪造 token → 401" \
  "$(curl -s $BASE/api/prices -H 'Authorization: Bearer not.a.real.token')" "Token 无效"

check "A4 顾客 token 拉价目表 → 200（有意偏离 §6.4：顾客看不到价格就没法下单）" \
  "$(curl -s $BASE/api/prices -H "Authorization: Bearer $CUST_T")" '"code":200'

check "A5 员工 token 拉分类 → 200" \
  "$(curl -s $BASE/api/categories -H "Authorization: Bearer $MGR_T")" '"code":200'

echo
echo "########## B. 衣物分类 ##########"

CATS=$(curl -s $BASE/api/categories -H "Authorization: Bearer $MGR_T")
check "B1 分类总数 16（4 一级 + 12 叶子）" "$(echo "$CATS" | grep -o '"parentId"' | wc -l | tr -d ' ')" "16"
check "B2 一级分类 4 个" "$(echo "$CATS" | grep -o '"parentId":null' | wc -l | tr -d ' ')" "4"
# 叶子 = parentId 非 null。不能用 sortOrder 计数：一级分类也有 sortOrder（16 个）
check "B3 叶子分类 12 个" "$(echo "$CATS" | grep -o '"parentId":[0-9]' | wc -l | tr -d ' ')" "12"
check "B4 中文名 UTF-8 正常（衬衫）" "$CATS" "衬衫"
check "B5 一级分类在前（上衣 parentId=null）" "$CATS" '"name":"上衣","icon":null,"parentId":null'

echo
echo "########## C. 洗涤方式 ##########"

WASH=$(curl -s $BASE/api/wash-types -H "Authorization: Bearer $MGR_T")
check "C1 固定 3 种" "$(echo "$WASH" | grep -o '"description"' | wc -l | tr -d ' ')" "3"
check "C2 普洗 id=1" "$WASH" '"id":1,"name":"普洗"'
check "C3 精洗 id=2，说明里写明 +20 规则" "$WASH" '价格=普洗价+20元'
check "C4 单熨 id=3" "$WASH" '"id":3,"name":"单熨"'

echo
echo "########## D. 价目表 ##########"

PRICES=$(curl -s $BASE/api/prices -H "Authorization: Bearer $MGR_T")
check "D1 价格行数 36（12 叶子 × 3 洗涤）" "$(echo "$PRICES" | grep -o '"washTypeId"' | wc -l | tr -d ' ')" "36"
check "D2 拼上了分类名（读模型，不是裸 id）" "$PRICES" '"categoryName"'
check "D3 拼上了洗涤方式名" "$PRICES" '"washTypeName"'
check "D4 精洗出现 12 次（每个叶子一条）" "$(echo "$PRICES" | grep -o '"washTypeName":"精洗"' | wc -l | tr -d ' ')" "12"
check "D5 supported 字段存在（前端据此置灰）" "$PRICES" '"supported":'

# 逐行取某分类的三行（每条 PriceRow 是扁平对象，没有嵌套大括号）
row() { echo "$PRICES" | grep -o "{[^{}]*\"categoryId\":$1,[^{}]*}"; }

echo
echo "  ── 设计文档 §4.5 样例：衬衫 15/35/8 ──"
check "D6 衬衫·普洗 = 15.00" "$(row 11)" '"washTypeName":"普洗","price":15.00,"supported":true'
check "D7 衬衫·精洗 = 35.00（= 15 + 20，后端派生）" "$(row 11)" '"washTypeName":"精洗","price":35.00,"supported":true'
check "D8 衬衫·单熨 = 8.00" "$(row 11)" '"washTypeName":"单熨","price":8.00,"supported":true'

echo
echo "  ── 设计文档 §4.5 样例：羽绒服 0/60/0（无普洗，精洗手填）──"
check "D9 羽绒服·普洗 = 0.00 且 supported=false（前端置灰）" "$(row 13)" '"washTypeName":"普洗","price":0.00,"supported":false'
check "D10 羽绒服·精洗 = 60.00 且 supported=true（60 ≠ 0+20，逃生舱）" "$(row 13)" '"washTypeName":"精洗","price":60.00,"supported":true'
check "D11 羽绒服·单熨 = 0.00 且 supported=false" "$(row 13)" '"washTypeName":"单熨","price":0.00,"supported":false'

echo
echo "  ── 全表不变量：有普洗的叶子，精洗必须 = 普洗 + 20 ──"
BAD=0
for cid in $(echo "$PRICES" | grep -o '"categoryId":[0-9]*' | cut -d: -f2 | sort -u); do
  P=$(echo "$(row $cid)" | grep -o '"washTypeName":"普洗","price":[0-9.]*' | cut -d: -f3)
  R=$(echo "$(row $cid)" | grep -o '"washTypeName":"精洗","price":[0-9.]*' | cut -d: -f3)
  # 只在"有普洗(>0)"时校验；用 awk 做小数比较，bash 只支持整数
  if [ -n "$P" ] && awk "BEGIN{exit !($P > 0)}"; then
    if ! awk "BEGIN{exit !($R == $P + 20)}"; then
      echo "        分类 $cid：普洗 $P 但精洗 $R"; BAD=$((BAD+1))
    fi
  fi
done
checkq "D12 12 个叶子里违反 +20 规则的" "$BAD" "0"

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
