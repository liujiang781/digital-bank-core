# Requires: Windows PowerShell 5.1+, MySQL client
# Usage: powershell -ExecutionPolicy Bypass -File deploy/scripts/verify-db.ps1
param(
    [string]$Database = 'payment_db',
    [string]$MysqlHome = ''
)

$ErrorActionPreference = 'Stop'
if (-not $MysqlHome) {
    $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($cmd) { $MysqlHome = Split-Path $cmd.Source } else { $MysqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0\bin' }
}
$mysql = Join-Path $MysqlHome 'mysql.exe'
if (-not (Test-Path $mysql)) { throw "mysql.exe not found: $mysql ; add MySQL bin to PATH or pass -MysqlHome" }

$secure = Read-Host 'MySQL root password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$outFile = Join-Path $root "verify-output-$stamp.txt"

$checks = @"
SELECT VERSION() AS mysql_version, @@character_set_server AS charset, @@collation_server AS collation;
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='$Database';
SELECT COUNT(*) AS fk_count FROM information_schema.referential_constraints WHERE constraint_schema='$Database';
SELECT COUNT(*) AS check_count FROM information_schema.table_constraints WHERE constraint_schema='$Database' AND constraint_type='CHECK';
SELECT rule_id, rule_type, deposit_single_limit, outgoing_single_limit, outgoing_daily_limit, enabled
  FROM $Database.risk_rule ORDER BY rule_id;
SELECT account_id, book_balance, reserved_amount, (book_balance - reserved_amount) AS available,
  CASE WHEN book_balance >= reserved_amount THEN 'OK' ELSE 'VIOLATION' END AS check_result
  FROM $Database.account ORDER BY account_id;
SELECT c.identity_id, cr.username, LEFT(cr.password_hash, 7) AS hash_prefix,
  CASE WHEN cr.password_hash LIKE '`$2a`$%' OR cr.password_hash LIKE '`$2b`$%' THEN 'BCrypt' ELSE 'NOT-BCrypt' END AS hash_algo
  FROM $Database.customer c JOIN $Database.credential cr ON cr.customer_id = c.customer_id
  WHERE cr.credential_type = 'USERNAME' ORDER BY c.identity_id;
"@

"=== verify-db $stamp ===" | Out-File -FilePath $outFile -Encoding utf8
$ErrorActionPreference = 'Continue'
$block = & $mysql -u root --default-character-set=utf8mb4 -e $checks 2>&1 | Out-String
Add-Content -Path $outFile -Value $block -Encoding UTF8

$verifySql = Join-Path $PSScriptRoot '..\sql\verify.sql'
if (Test-Path $verifySql) {
    Add-Content -Path $outFile -Value "`n=== verify.sql (-f) ===" -Encoding UTF8
    Add-Content -Path $outFile -Value "NOTE: 3 errors are EXPECTED: two Duplicate entry (uk_transaction_request / uk_transaction_flow_role) and one ERROR 3819 chk_transaction_amount (negative amount, proves CHECK is enforced on MySQL 8.0.16+)." -Encoding UTF8
    $block = Get-Content $verifySql -Raw | & $mysql -f -u root --default-character-set=utf8mb4 $Database 2>&1 | Out-String
    Add-Content -Path $outFile -Value $block -Encoding UTF8
}
$ErrorActionPreference = 'Stop'

$env:MYSQL_PWD = ''
"Saved: $outFile"
