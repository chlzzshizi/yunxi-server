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

echo "########## 准备：两种身份的 token ##########"

MGR_T=$(jqf "$(curl -s -X POST $BASE/api/auth/staff/login -H 'Content-Type: application/json' \
  -d '{"username":"manager","password":"admin123"}')" token)
echo "  manager token: ${MGR_T:0:20}..."

CUST_RESP=$(curl -s -X POST $BASE/api/auth/customer/register -H 'Content-Type: application/json' \
  -d '{"name":"PriceReader","phone":"13900000091","password":"123456"}')
CUST_T=$(jqf "$CUST_RESP" token)
[ -z "$CUST_T" ] && CUST_T=$(jqf "$(curl -s -X POST $BASE/api/auth/customer/login \
  -H 'Content-Type: application/json' -d '{"phone":"13900000091","password":"123456"}')" token)
echo "  customer token: ${CUST_T:0:20}..."

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
