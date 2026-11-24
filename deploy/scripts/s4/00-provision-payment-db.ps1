param(
    [string]$Database = 'payment_db',
    [string]$RootUser = 'root',
    [string]$RootCnf = '',
    [switch]$SkipBackup,
    [switch]$SkipInit,
    [switch]$SkipGrants,
    [string]$OutDir = (Join-Path $PSScriptRoot '..\evidence')
)
# Provision payment_db to the 22-table schema.
# Safety: backup first, never DROP DATABASE, never touch repo files, never reset service passwords.
$ErrorActionPreference = 'Stop'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$mysqldump = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
foreach ($p in @($mysql, $mysqldump)) { if (-not (Test-Path $p)) { throw "not found: $p" } }

$schemaSrc = (Get-Item 'D:\1-*\repo\deploy\sql\schema.sql').FullName
$initSrc = (Get-Item 'D:\1-*\repo\deploy\sql\init-data.sql').FullName
$grantSrc = (Get-Item 'D:\1-*\*\sql\03-grant-payment-db.sql' | Where-Object { Test-Path (Join-Path $_.DirectoryName '01-verify-22tables.sql') } | Select-Object -First 1).FullName
foreach ($p in @($schemaSrc, $initSrc, $grantSrc)) { if (-not (Test-Path $p)) { throw "not found: $p" } }

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $OutDir "provision-$stamp.log"
$tmp = Join-Path $env:TEMP "s4provision-$stamp"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$steps = New-Object System.Collections.ArrayList
function Step([string]$name, [string]$status, [string]$detail) {
    $line = "{0} [{1}] {2} :: {3}" -f (Get-Date -Format 'HH:mm:ss'), $status, $name, $detail
    Write-Host $line
    $null = $steps.Add($line)
    $steps | Set-Content -LiteralPath $log -Encoding UTF8
}
function Q([string]$sql, [switch]$tab) {
    $a = @()
    if ($RootCnf) { $a += "--defaults-extra-file=$RootCnf" }
    $a += @('-h', '127.0.0.1', '-u', $RootUser, '--default-character-set=utf8mb4')
    if ($tab) { $a += '-t' } else { $a += @('-N', '-B') }
    $a += @('-e', $sql)
    & $mysql @a 2>&1
}

# --- credentials ---
if (-not $RootCnf -and -not $env:MYSQL_PWD) {
    $sec = Read-Host -Prompt "MySQL root password" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

$startAll = Get-Date
Step 'precheck' 'PASS' "db=$Database schema=$schemaSrc"

# --- 0. before baseline ---
$beforeTables = (Q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_type='BASE TABLE';").Trim()
$beforeRows = (Q "SELECT COALESCE((SELECT COUNT(*) FROM $Database.customer),0), COALESCE((SELECT COUNT(*) FROM $Database.account),0);").Trim()
Step 'baseline-before' 'INFO' "tables=$beforeTables customer/account=$beforeRows"

# --- 1. full backup ---
if (-not $SkipBackup) {
    $bakTmp = Join-Path $tmp 'pre-rebuild.sql'
    $a = @()
    if ($RootCnf) { $a += "--defaults-extra-file=$RootCnf" }
    $a += @('-h', '127.0.0.1', '-u', $RootUser, '--single-transaction', '--no-tablespaces',
            '--routines', '--triggers', '--default-character-set=utf8mb4', "--result-file=$bakTmp", $Database)
    & $mysqldump @a 2>&1 | Out-Null
    if (-not (Test-Path $bakTmp) -or (Get-Item $bakTmp).Length -lt 1024) { throw 'backup failed or too small' }
    $bakDst = Join-Path $OutDir "pre-rebuild-$stamp.sql"
    [IO.File]::WriteAllBytes($bakDst, [IO.File]::ReadAllBytes($bakTmp))
    $kb = [math]::Round((Get-Item $bakDst).Length / 1KB, 1)
    Step 'backup' 'PASS' "size=${kb}KB file=$bakDst"
} else {
    Step 'backup' 'SKIP' 'requested -SkipBackup'
}

# --- 2. render schema/init/grant with target database name ---
function Render([string]$src, [string]$name) {
    $t = Get-Content -LiteralPath $src -Raw -Encoding UTF8
    $t = $t -replace 'payment_db', $Database
    $dst = Join-Path $tmp $name
    [IO.File]::WriteAllText($dst, $t, (New-Object System.Text.UTF8Encoding($false)))
    return ($dst -replace '\\', '/')
}
$schemaTmp = Render $schemaSrc 'schema.sql'
$initTmp = Render $initSrc 'init-data.sql'
$grantTmp = Render $grantSrc 'grants.sql'

# --- 3. apply schema ---
$sw = [Diagnostics.Stopwatch]::StartNew()
$out = Q "source $schemaTmp"
$sw.Stop()
if ($out) { Step 'schema' 'WARN' (($out | Out-String).Trim() -replace '\r?\n', ' | ') } else { Step 'schema' 'PASS' ("{0:N2}s" -f $sw.Elapsed.TotalSeconds) }

# --- 4. seed data ---
if (-not $SkipInit) {
    $sw.Restart()
    $out = Q "source $initTmp"
    $sw.Stop()
    if ($out) { Step 'init-data' 'WARN' (($out | Out-String).Trim() -replace '\r?\n', ' | ') } else { Step 'init-data' 'PASS' ("{0:N2}s" -f $sw.Elapsed.TotalSeconds) }
} else {
    Step 'init-data' 'SKIP' 'requested -SkipInit (old rows kept where tables survived)'
}

# --- 5. grants ---
if (-not $SkipGrants) {
    $sw.Restart()
    $out = Q "source $grantTmp"
    $sw.Stop()
    if ($out) { Step 'grants' 'WARN' (($out | Out-String).Trim() -replace '\r?\n', ' | ') } else { Step 'grants' 'PASS' ("{0:N2}s" -f $sw.Elapsed.TotalSeconds) }
} else {
    Step 'grants' 'SKIP' 'requested -SkipGrants'
}

# --- 6. verify structure ---
$stats = (Q "SELECT
 (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_type='BASE TABLE') AS tables,
 (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='$Database' AND constraint_type='FOREIGN KEY') AS fk,
 (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='$Database' AND constraint_type='CHECK') AS chk,
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$Database') AS cols,
 (SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.statistics WHERE table_schema='$Database' AND index_name<>'PRIMARY') AS idx;").Trim()
Step 'structure' ($(if ($stats -match '^22\t17\t48\t216\t50$') { 'PASS' } else { 'FAIL' })) "tables/fk/check/cols/idx = $($stats -replace '\t', '/')"

$rows = (Q "SELECT
 (SELECT COUNT(*) FROM $Database.customer), (SELECT COUNT(*) FROM $Database.account),
 (SELECT COUNT(*) FROM $Database.credential), (SELECT COUNT(*) FROM $Database.risk_rule);").Trim()
Step 'seed-rows' 'INFO' "customer/account/credential/risk_rule = $($rows -replace '\t', '/')"

$legacy = (Q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_name='risk_freeze_release';").Trim()
Step 'new-table' ($(if ($legacy -eq '1') { 'PASS' } else { 'FAIL' })) "risk_freeze_release exists=$legacy"

$total = ((Get-Date) - $startAll).TotalSeconds
Step 'done' 'INFO' ("total={0:N2}s log={1}" -f $total, $log)
if ($RootCnf) { Step 'credential' 'INFO' "used cnf file: $RootCnf (not deleted by this script)" }
Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
