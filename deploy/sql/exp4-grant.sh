#!/usr/bin/env bash
set -euo pipefail

if command -v mysql >/dev/null 2>&1; then
  MYSQL="$(command -v mysql)"
else
  MYSQL="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
fi
[ -x "$MYSQL" ] || { echo "找不到 mysql 客户端，请把 MySQL bin 目录加入 PATH，或修改本脚本里的默认路径" >&2; exit 1; }
BASE_URL="jdbc:mysql://127.0.0.1:3306/payment_db?serverTimezone=Asia/Shanghai&characterEncoding=utf8"
OUT="$(cd "$(dirname "$0")/.." && pwd)/.env.local"

gen() { python -c "import secrets;print('Db'+secrets.token_urlsafe(15))"; }
tok() { python -c "import secrets;print(secrets.token_hex(32))"; }

BANK=$(gen); ACC=$(gen); TXN=$(gen); RISK=$(gen); RO=$(gen); TEST=$(gen)
BANK_TOKEN=$(tok); ACC_TOKEN=$(tok); RISK_TOKEN=$(tok)

read -rsp "MySQL root 密码: " ROOT_PWD; echo
export MYSQL_PWD="$ROOT_PWD"

"$MYSQL" -u root <<SQL
CREATE DATABASE IF NOT EXISTS s2_exp4_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'bank_user'@'%' IDENTIFIED BY '$BANK';
ALTER USER 'bank_user'@'%' IDENTIFIED BY '$BANK';
CREATE USER IF NOT EXISTS 'account_service'@'%' IDENTIFIED BY '$ACC';
ALTER USER 'account_service'@'%' IDENTIFIED BY '$ACC';
CREATE USER IF NOT EXISTS 'transaction_service'@'%' IDENTIFIED BY '$TXN';
ALTER USER 'transaction_service'@'%' IDENTIFIED BY '$TXN';
CREATE USER IF NOT EXISTS 'risk_service'@'%' IDENTIFIED BY '$RISK';
ALTER USER 'risk_service'@'%' IDENTIFIED BY '$RISK';
CREATE USER IF NOT EXISTS 's2_readonly'@'%' IDENTIFIED BY '$RO';
ALTER USER 's2_readonly'@'%' IDENTIFIED BY '$RO';
CREATE USER IF NOT EXISTS 's2_exp4_test'@'%' IDENTIFIED BY '$TEST';
ALTER USER 's2_exp4_test'@'%' IDENTIFIED BY '$TEST';

GRANT SELECT, INSERT, UPDATE ON payment_db.customer TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.credential TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.user_command TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.staff_authorization TO 'bank_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.staff_account_scope TO 'bank_user'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'bank_user'@'%';
GRANT SELECT ON payment_db.account TO 'bank_user'@'%';

GRANT SELECT, INSERT, UPDATE ON payment_db.account TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.account_command TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.account_reservation TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.transaction_flow TO 'account_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.\`transaction\` TO 'account_service'@'%';
GRANT SELECT, INSERT ON payment_db.account_status_log TO 'account_service'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'account_service'@'%';
GRANT SELECT ON payment_db.customer TO 'account_service'@'%';
GRANT SELECT, UPDATE ON payment_db.transfer_order TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_rule TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_event TO 'account_service'@'%';
GRANT SELECT ON payment_db.risk_freeze_release TO 'account_service'@'%';
GRANT LOCK TABLES ON payment_db.* TO 'account_service'@'%';

GRANT SELECT, INSERT, UPDATE ON payment_db.\`transaction\` TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.mock_channel_deposit TO 'transaction_service'@'%';
GRANT SELECT, INSERT ON payment_db.transfer_order TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account TO 'transaction_service'@'%';
GRANT SELECT ON payment_db.account_reservation TO 'transaction_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.trade_manual_action TO 'transaction_service'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'transaction_service'@'%';

GRANT SELECT, INSERT, UPDATE ON payment_db.risk_rule TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_event TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_alert_disposal TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.risk_change_request TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_change_approval TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.risk_list_entry TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.audit_log TO 'risk_service'@'%';
GRANT SELECT ON payment_db.account TO 'risk_service'@'%';
GRANT SELECT ON payment_db.account_status_log TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_freeze_release TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_alert_disposal TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.risk_change_request TO 'risk_service'@'%';
GRANT SELECT, INSERT ON payment_db.risk_change_approval TO 'risk_service'@'%';
GRANT SELECT, INSERT, UPDATE ON payment_db.risk_list_entry TO 'risk_service'@'%';
GRANT INSERT ON payment_db.audit_log TO 'risk_service'@'%';
GRANT SELECT ON payment_db.\`transaction\` TO 'risk_service'@'%';

GRANT SELECT ON payment_db.account TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.account_reservation TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.transaction_flow TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.\`transaction\` TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.customer TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.risk_rule TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.risk_event TO 's2_readonly'@'%';
GRANT SELECT ON payment_db.account_status_log TO 's2_readonly'@'%';

GRANT ALL PRIVILEGES ON s2_exp4_test.* TO 's2_exp4_test'@'%';

FLUSH PRIVILEGES;
SQL

unset MYSQL_PWD

cat > "$OUT" <<EOF
BANK_DB_URL=$BASE_URL
BANK_DB_USERNAME=bank_user
BANK_DB_PASSWORD=$BANK
BANK_CREDENTIAL_STORE=mysql

ACCOUNT_DB_URL=$BASE_URL
ACCOUNT_DB_USER=account_service
ACCOUNT_DB_PASSWORD=$ACC

TRANSACTION_DB_URL=$BASE_URL
TRANSACTION_DB_USER=transaction_service
TRANSACTION_DB_PASSWORD=$TXN

RISK_DB_URL=$BASE_URL
RISK_DB_USER=risk_service
RISK_DB_PASSWORD=$RISK

S2_EXP4_DB_URL=jdbc:mysql://127.0.0.1:3306/s2_exp4_test?serverTimezone=Asia/Shanghai&characterEncoding=utf8
S2_EXP4_DB_USER=s2_exp4_test
S2_EXP4_DB_PASSWORD=$TEST
S2_READONLY_DB_USER=s2_readonly
S2_READONLY_DB_PASSWORD=$RO

BANK_INTERNAL_TOKEN=$BANK_TOKEN
ACCOUNT_INTERNAL_TOKEN=$ACC_TOKEN
RISK_INTERNAL_TOKEN=$RISK_TOKEN
EOF

chmod 600 "$OUT" 2>/dev/null || true
echo "账号与授权已就绪，凭据写入：$OUT"
