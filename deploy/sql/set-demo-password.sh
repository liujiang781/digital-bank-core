#!/usr/bin/env bash
set -euo pipefail

if command -v mysql >/dev/null 2>&1; then
  MYSQL="$(command -v mysql)"
else
  MYSQL="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
fi
[ -x "$MYSQL" ] || { echo "找不到 mysql 客户端，请把 MySQL bin 目录加入 PATH，或修改本脚本里的默认路径" >&2; exit 1; }

python -c "import bcrypt" 2>/dev/null || python -m pip install --quiet bcrypt

hash_for() {
  PWD_IN="$1" python -c "import bcrypt,os;print(bcrypt.hashpw(os.environ['PWD_IN'].encode(),bcrypt.gensalt(rounds=12,prefix=b'2a')).decode())"
}

read -rsp "个人账号口令（UID-000001 / UID-000002）: " P_USER; echo
read -rsp "运营账号口令（OPS-ADMIN）: " P_ADMIN; echo

if [[ -z "$P_USER" || -z "$P_ADMIN" ]]; then
  echo "口令不能为空"
  exit 1
fi

H_USER=$(hash_for "$P_USER")
H_ADMIN=$(hash_for "$P_ADMIN")

read -rsp "MySQL root 密码: " ROOT_PWD; echo
export MYSQL_PWD="$ROOT_PWD"

TMP=$(mktemp)
cat > "$TMP" <<SQL
UPDATE credential SET password_hash='$H_USER' WHERE customer_id IN ('CUS-0001','CUS-0002');
UPDATE credential SET password_hash='$H_ADMIN' WHERE customer_id='CUS-ADMIN';
SQL

"$MYSQL" -u root payment_db < "$TMP"
rm -f "$TMP"

"$MYSQL" -u root payment_db -e "SELECT customer_id, LEFT(password_hash,7) AS algo FROM credential ORDER BY customer_id;"

unset MYSQL_PWD
echo "演示口令已写入三个账号"
