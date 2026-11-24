# S4 backup / restore / rollback drill
# Steps:
#   1. baseline snapshot (table count, key row counts, balances)
#   2. full logical backup (mysqldump) + per-table backups (account, risk_list_entry, risk_freeze_release)
#   3. fault A: DROP TABLE risk_list_entry  -> restore from table backup -> verify
#   4. fault B: corrupt account.book_balance -> restore table -> verify balance
#   5. fault C: DROP DATABASE payment_db     -> recreate + import full dump -> verify 22 tables
#   6. rollback drill: DROP TABLE risk_freeze_release (22 -> 21) -> restore -> verify 22
# Output: evidence/drill-<stamp>.log
param(
    [string]$Database = 'payment_db',
    [string]$DbUser = 'root',
    [string]$DbPassword = '',
    [string]$MysqlHome = '',
    [string]$OutDir = (Join-Path $PSScriptRoot '..\evidence'),
    [switch]$SkipDatabaseDrop
)

$ErrorActionPreference = 'Stop'
if (-not $MysqlHome) {
    $c = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($c) { $MysqlHome = Split-Path $c.Source } else { $MysqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0\bin' }
}
$mysql = Join-Path $MysqlHome 'mysql.exe'
$dump  = Join-Path $MysqlHome 'mysqldump.exe'
foreach ($f in @($mysql, $dump)) { if (-not (Test-Path $f)) { throw "not found: $f" } }

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$work  = Join-Path $OutDir "drill-$stamp"
New-Item -ItemType Directory -Path $work -Force | Out-Null
$log   = Join-Path $OutDir "drill-$stamp.log"

if ($DbPassword) {
    $env:MYSQL_PWD = $DbPassword
} elseif (-not $env:MYSQL_PWD) {
    $secure = Read-Host "MySQL password for $DbUser" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
# SQL 文件放到纯 ASCII 临时目录，避免 source 命令处理中文路径
$asciiWork = Join-Path $env:TEMP ("s4drill-" + $stamp)
New-Item -ItemType Directory -Path $asciiWork -Force | Out-Null

$script:lines = @()
function Log([string]$msg) {
    $t = (Get-Date).ToString('HH:mm:ss')
    $line = "[$t] $msg"
    Write-Host $line
    $script:lines += $line
    $script:lines | Set-Content -Path $log -Encoding UTF8
}
function Sql([string]$q) {
    return (& $mysql -u $DbUser --default-character-set=utf8mb4 -N -B -e $q 2>&1 | Out-String).Trim()
}
function SqlFile([string]$file) {
    $ErrorActionPreference = 'Continue'
    $out = & $mysql -u $DbUser --default-character-set=utf8mb4 $Database -e "source $file" 2>&1 | Out-String
    $ErrorActionPreference = 'Stop'
    return $out.Trim()
}
function Failed([string]$out) {
    return ($out -match 'ERROR \d+')
}
function TableCount { return [int](Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database'") }
function RowCount([string]$t) { return [int](Sql "SELECT COUNT(*) FROM $Database.``$t``") }

$report = New-Object System.Collections.ArrayList
function Step([string]$name, [scriptblock]$body) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $ok = $true; $detail = ''
    try { $detail = [string](& $body) } catch { $ok = $false; $detail = 'EXCEPTION: ' + $_.Exception.Message }
    $sw.Stop()
    $sec = [math]::Round($sw.Elapsed.TotalSeconds, 2)
    $status = if ($ok) { 'PASS' } else { 'FAIL' }
    Log ("{0,-42} {1}  {2,6}s  {3}" -f $name, $status, $sec, $detail)
    $null = $report.Add([pscustomobject]@{ step = $name; status = $status; seconds = $sec; detail = $detail })
    if (-not $ok) { throw "step failed: $name" }
}

Log "=== S4 backup/restore/rollback drill $stamp ==="
Log ("database=" + $Database + "  mysql=" + $MysqlHome)

# ---------- 1. baseline ----------
$base = @{}
Step '1 baseline snapshot' {
    $base.tables = TableCount
    $base.account = RowCount 'account'
    $base.customer = RowCount 'customer'
    $base.list = RowCount 'risk_list_entry'
    $base.txn = RowCount 'transaction'
    $base.balance = [double](Sql "SELECT book_balance FROM $Database.account WHERE account_id='ACC_S2_001'")
    if ($base.tables -lt 22) { throw "expect >=22 tables, got $($base.tables)" }
    "tables=$($base.tables) account=$($base.account) customer=$($base.customer) txn=$($base.txn) balance=$($base.balance)"
}

# ---------- 2. backups ----------
$fullDump = Join-Path $asciiWork 'full.sql'
Step '2a full logical backup' {
    & $dump -u $DbUser --single-transaction --no-tablespaces --routines --triggers --default-character-set=utf8mb4 --result-file=$fullDump $Database 2>&1 | Out-Null
    $size = (Get-Item $fullDump).Length
    $head = Get-Content $fullDump -TotalCount 200 | Out-String
    if ($size -lt 10000) { throw "dump too small: $size bytes" }
    if ($head -notmatch 'CREATE TABLE') { throw 'dump has no CREATE TABLE' }
    "size=$([math]::Round($size/1KB,1))KB tables_in_dump=$(([regex]::Matches((Get-Content $fullDump -Raw),'CREATE TABLE')).Count)"
}
$tblAccount = Join-Path $asciiWork 't_account.sql'
$tblList    = Join-Path $asciiWork 't_risk_list_entry.sql'
$tblFreeze  = Join-Path $asciiWork 't_risk_freeze_release.sql'
Step '2b table backups' {
    & $dump -u $DbUser --single-transaction --no-tablespaces --default-character-set=utf8mb4 --result-file=$tblAccount $Database account 2>&1 | Out-Null
    & $dump -u $DbUser --single-transaction --no-tablespaces --default-character-set=utf8mb4 --result-file=$tblList    $Database risk_list_entry 2>&1 | Out-Null
    & $dump -u $DbUser --single-transaction --no-tablespaces --default-character-set=utf8mb4 --result-file=$tblFreeze  $Database risk_freeze_release 2>&1 | Out-Null
    "ok: " + ((Get-ChildItem $asciiWork -Filter 't_*.sql' | ForEach-Object { $_.Name }) -join ',')
}

# ---------- 3. fault A: drop a table ----------
Step '3a fault A: DROP TABLE risk_list_entry' {
    Sql "DROP TABLE $Database.risk_list_entry" | Out-Null
    $n = TableCount
    if ($n -ne ($base.tables - 1)) { throw "expect $($base.tables - 1) tables, got $n" }
    "tables=$n (structure broken)"
}
Step '3b restore table from dump' {
    $out = SqlFile $tblList
    if (Failed $out) { throw $out }
    $n = TableCount; $r = RowCount 'risk_list_entry'
    if ($n -ne $base.tables) { throw "expect $($base.tables) tables, got $n" }
    if ($r -ne $base.list) { throw "row count mismatch: expect $($base.list), got $r" }
    "tables=$n rows=$r"
}

# ---------- 4. fault B: corrupt data ----------
Step '4a fault B: corrupt account.book_balance' {
    Sql "UPDATE $Database.account SET book_balance=0.01 WHERE account_id='ACC_S2_001'" | Out-Null
    $v = [double](Sql "SELECT book_balance FROM $Database.account WHERE account_id='ACC_S2_001'")
    if ($v -ne 0.01) { throw "inject failed, balance=$v" }
    "balance=$v (corrupted)"
}
Step '4b restore account table' {
    $out = SqlFile $tblAccount
    if (Failed $out) { throw $out }
    $v = [double](Sql "SELECT book_balance FROM $Database.account WHERE account_id='ACC_S2_001'")
    $r = RowCount 'account'
    if ([math]::Abs($v - $base.balance) -gt 0.001) { throw "balance not restored: $v vs $($base.balance)" }
    if ($r -ne $base.account) { throw "row count mismatch: $r vs $($base.account)" }
    "balance=$v rows=$r"
}

# ---------- 5. fault C: drop database ----------
if ($SkipDatabaseDrop) {
    Log '5  SKIP database drop (flag -SkipDatabaseDrop)'
} else {
    Step '5a fault C: DROP DATABASE' {
        Sql "DROP DATABASE $Database" | Out-Null
        $n = [int](Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$Database'")
        if ($n -ne 0) { throw 'database still exists' }
        "database dropped"
    }
    Step '5b recreate database and import full dump' {
        Sql "CREATE DATABASE $Database DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" | Out-Null
        $out = SqlFile $fullDump
        if (Failed $out) { throw $out }
        $n = TableCount
        if ($n -ne $base.tables) { throw "expect $($base.tables) tables, got $n" }
        "tables=$n"
    }
    Step '5c verify data after full restore' {
        $acc = RowCount 'account'; $cus = RowCount 'customer'; $txn = RowCount 'transaction'
        $v = [double](Sql "SELECT book_balance FROM $Database.account WHERE account_id='ACC_S2_001'")
        if ($acc -ne $base.account) { throw "account rows $acc vs $($base.account)" }
        if ($cus -ne $base.customer) { throw "customer rows $cus vs $($base.customer)" }
        if ($txn -ne $base.txn) { throw "transaction rows $txn vs $($base.txn)" }
        if ([math]::Abs($v - $base.balance) -gt 0.001) { throw "balance $v vs $($base.balance)" }
        "account=$acc customer=$cus txn=$txn balance=$v"
    }
}

# ---------- 6. schema rollback drill ----------
Step '6a rollback drill: drop risk_freeze_release (22->21)' {
    Sql "DROP TABLE $Database.risk_freeze_release" | Out-Null
    $n = TableCount
    if ($n -ne ($base.tables - 1)) { throw "expect $($base.tables - 1) tables, got $n" }
    "tables=$n"
}
Step '6b roll forward: restore risk_freeze_release' {
    $out = SqlFile $tblFreeze
    if (Failed $out) { throw $out }
    $n = TableCount
    if ($n -ne $base.tables) { throw "expect $($base.tables) tables, got $n" }
    "tables=$n"
}

# ---------- 7. checksum of key tables ----------
Step '7 final structural check' {
    $tables = [int](Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database'")
    $fk = [int](Sql "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='$Database'")
    $ck = [int](Sql "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='$Database' AND constraint_type='CHECK'")
    if ($tables -ne 22) { throw "tables=$tables" }
    if ($fk -ne 17) { throw "fk=$fk" }
    if ($ck -ne 48) { throw "check=$ck" }
    "tables=$tables fk=$fk check=$ck"
}

$env:MYSQL_PWD = ''
foreach ($f in @($fullDump, $tblAccount, $tblList, $tblFreeze)) {
    if (Test-Path $f) {
        $dst = Join-Path $work (Split-Path $f -Leaf)
        [IO.File]::WriteAllBytes($dst, [IO.File]::ReadAllBytes($f))
    }
}
$report | Export-Csv -Path (Join-Path $work 'drill-steps.csv') -NoTypeInformation -Encoding UTF8
Log ("=== done. artifacts: " + $work + " ===")
Log ("total_seconds=" + [math]::Round((($report | Measure-Object seconds -Sum).Sum), 2))
Write-Host ("saved: " + $log)
