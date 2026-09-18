#!/bin/bash
# 定价写入 + 精洗派生验收（真机：MySQL + Redis + 8081）
# 覆盖：鉴权、叶子校验、精洗派生、拒绝手填、逃生舱、落库真相、数据还原
BASE=http://localhost:8081
PASS=0; FAIL=0

check() {  # check "用例名" "响应" "期望片段"
  if echo "$2" | grep -q "$3"; then
    echo "  [OK]   $1"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1"; echo "         期望含: $3"; echo "         实际: $2"; FAIL=$((FAIL+1))
  fi
}
jqf() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
# 直接查库：接口说改成功了不算，库里真是那个数才算
# --default-character-set=utf8mb4：mysql 命令行默认按 latin1 收发，
# 读中文会整串变 ?????、写中文会存成双重编码 —— 说明见 verify-stores.sh 文件头
db() { docker exec yunxi-mysql mysql -uroot -pqwaszx123 yunxi -N \
       --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }

# PUT 一个价格，回显响应
put() { # put <分类id> <washTypeId> <价格>
  curl -s -X PUT "$BASE/api/prices/$1" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"prices\":[{\"washTypeId\":$2,\"price\":$3}]}"
}
priceOf() { db "select price from clothes_prices where category_id=$1 and wash_type_id=$2;"; }

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
# 顾客脚手架 13900000091 **不是本脚本建的**，是 verify-price.sh 建的 —— 而它在通配符
# 顺序里排在本脚本**后面**（"price-write" < "price"：'-'(0x2D) 比 '.'(0x2E) 小）。
# 所以库里没这个号时，只 login 是拿不到票的 —— 这就是 Bug 38：
# 票为空 → `Authorization: Bearer `（空值）→ 拦截器回「未登录」→ A1 报"鉴权坏了"，
# 而真因是"脚手架没拿到票"。改用两个兄弟脚本早就在用的 login_or_register。
CUST_T=$(login_or_register PriceWriterCust 13900000091)

# 脚手架自检（Bug 38）。**空票必须当场停下**：原先这里只印一句"就绪"，票是空的也照印。
# 印长度而不是票面值：空/非空一眼可判，也不把票写进 CI 的日志产物。
if [ -z "$MGR_T" ] || [ -z "$CUST_T" ]; then
  echo "==> [准备失败] 脚手架没拿到 token（manager=${#MGR_T} 字符，customer=${#CUST_T} 字符）"
  echo "             后端是否在 8081？manager/admin123 能否登录？"
  exit 1
fi
# 失败行一律 `==> [准备失败]`：九个脚本统一这一个串，方便在 CI 日志里一把 grep
# （本脚本原先写的是 `  [致命]`，是第 0 步新写的两处之一，与其余六个不一致）
echo "  manager/customer token 就绪（${#MGR_T} / ${#CUST_T} 字符）"
TOKEN=$MGR_T

echo "  初始状态：衬衫 11 = $(priceOf 11 1)/$(priceOf 11 2)/$(priceOf 11 3)   羽绒服 13 = $(priceOf 13 1)/$(priceOf 13 2)/$(priceOf 13 3)"

echo
echo "########## A. 鉴权 ##########"
check "A1 顾客 token 改价 → 401 请使用员工账号" \
  "$(curl -s -X PUT $BASE/api/prices/11 -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $CUST_T" -d '{"prices":[{"washTypeId":1,"price":1.00}]}')" \
  "请使用员工账号操作"
check "A2 无 token 改价 → 401 未登录" \
  "$(curl -s -X PUT $BASE/api/prices/11 -H 'Content-Type: application/json' \
     -d '{"prices":[{"washTypeId":1,"price":1.00}]}')" "未登录"

echo
echo "########## B. 参数与规则校验（400，不是 500）##########"
check "B1 给一级分类「上衣」定价 → 400" "$(put 1 1 10.00)" "只能给二级（叶子）分类设置价格"
check "B2 不存在的分类 → 400" "$(put 999 1 10.00)" "衣物分类不存在"
check "B3 洗涤方式=4 → 400" "$(put 11 4 10.00)" "没有这个洗涤方式"
check "B4 空数组 → 400" \
  "$(curl -s -X PUT $BASE/api/prices/11 -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $TOKEN" -d '{"prices":[]}')" "至少要传一条价格"
check "B5 负数价格 → 400" "$(put 11 1 -5.00)" "不能为负数"
check "B6 缺 price 字段 → 400" \
  "$(curl -s -X PUT $BASE/api/prices/11 -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $TOKEN" -d '{"prices":[{"washTypeId":1}]}')" "缺少洗涤方式或价格"

echo
echo "########## C. 精洗派生（核心）##########"
check "C1 衬衫普洗改 20 → 200" "$(put 11 1 20.00)" '"code":200'
check "C2 库里的普洗 = 20.00" "$(priceOf 11 1)" "20.00"
check "C3 库里的精洗被自动重算成 40.00（不是保留旧的 35）" "$(priceOf 11 2)" "40.00"
check "C4 单熨没被牵连（仍是 8.00）" "$(priceOf 11 3)" "8.00"
check "C5 读接口也立刻反映新价（精洗 40.00）" \
  "$(curl -s $BASE/api/prices -H "Authorization: Bearer $TOKEN" | grep -o "{[^{}]*\"categoryId\":11,[^{}]*}")" \
  '"washTypeName":"精洗","price":40.00'

echo
echo "########## D. 有普洗时拒绝手填精洗 ##########"
check "D1 衬衫手填精洗 99 → 400「由普洗价自动计算」" "$(put 11 2 99.00)" "不能手工设置"
check "D2 库里精洗仍是 40.00（400 之后没被改坏）" "$(priceOf 11 2)" "40.00"
check "D3 一次请求同时传普洗和精洗 → 400（以本次普洗为准）" \
  "$(curl -s -X PUT $BASE/api/prices/11 -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"prices":[{"washTypeId":1,"price":18.00},{"washTypeId":2,"price":99.00}]}')" \
  "不能手工设置"
check "D4 同上的 400 是整笔拒绝：普洗没被改成 18.00" "$(priceOf 11 1)" "20.00"

echo
echo "########## E. 逃生舱：无普洗时允许手填精洗 ##########"
check "E1 羽绒服（普洗=0）手填精洗 65 → 200" "$(put 13 2 65.00)" '"code":200'
check "E2 库里羽绒服精洗 = 65.00" "$(priceOf 13 2)" "65.00"
check "E3 羽绒服普洗仍是 0.00（没被这个请求带出来）" "$(priceOf 13 1)" "0.00"

echo
echo "########## F. 关掉普洗不连带清精洗 ##########"
check "F1 衬衫普洗改 0 → 200" "$(put 11 1 0.00)" '"code":200'
check "F2 普洗已置 0" "$(priceOf 11 1)" "0.00"
check "F3 精洗保留 40.00（静默清零等于替人删数据）" "$(priceOf 11 2)" "40.00"
check "F4 此时手填精洗被放行（已无普洗）" "$(put 11 2 42.00)" '"code":200'
check "F5 精洗 = 42.00" "$(priceOf 11 2)" "42.00"

echo
echo "########## G. 还原种子数据（后续步骤与前端演示要用）##########"
check "G1 衬衫普洗还原 15.00" "$(put 11 1 15.00)" '"code":200'
check "G2 精洗被重算回 35.00" "$(priceOf 11 2)" "35.00"
check "G3 羽绒服精洗还原 60.00" "$(put 13 2 60.00)" '"code":200'
echo "  最终状态：衬衫 11 = $(priceOf 11 1)/$(priceOf 11 2)/$(priceOf 11 3)   羽绒服 13 = $(priceOf 13 1)/$(priceOf 13 2)/$(priceOf 13 3)"
check "G4 全表不变量仍成立（有普洗的品类精洗=普洗+20）" \
  "$(db "select count(*) from clothes_prices p where p.wash_type_id=1 and p.price>0
        and (select price from clothes_prices q where q.category_id=p.category_id and q.wash_type_id=2) <> p.price+20;")" \
  "0"

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
