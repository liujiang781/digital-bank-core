# Requires: Windows PowerShell 5.1+, MySQL client tools
# Usage:
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/backup-restore.ps1 -Mode backup
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/backup-restore.ps1 -Mode restore -File deploy/backups/payment_db-20261117-1015.sql
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/backup-restore.ps1 -Mode drill
param(
    [ValidateSet('backup', 'restore', 'drill')]
    [string]$Mode = 'backup',
    [string]$Database = 'payment_db',
    [string]$File = '',
    [string]$MysqlHome = ''
)

$ErrorActionPreference = 'Stop'
if (-not $MysqlHome) {
    $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($cmd) { $MysqlHome = Split-Path $cmd.Source } else { $MysqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0\bin' }
}
$mysql = Join-Path $MysqlHome 'mysql.exe'
$dump = Join-Path $MysqlHome 'mysqldump.exe'
if (-not (Test-Path $mysql) -or -not (Test-Path $dump)) { throw "MySQL client tools not found in $MysqlHome ; add MySQL bin to PATH or pass -MysqlHome" }

$secure = Read-Host 'MySQL root password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$backupDir = Join-Path $root 'deploy\backups'
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'

function Do-Backup([string]$db, [string]$target) {
    $line = '"' + $dump + '" -u root --single-transaction --routines --triggers --default-character-set=utf8mb4 ' + $db + ' > "' + $target + '"'
    cmd.exe /c $line
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed: $db" }
    $size = (Get-Item $target).Length
    Write-Host "[BACKUP] $db -> $target ($size bytes)" -ForegroundColor Green
}

function Do-Restore([string]$db, [string]$source) {
    if (-not (Test-Path $source)) { throw "backup file not found: $source" }
    $line = '"' + $mysql + '" -u root ' + $db + ' < "' + $source + '"'
    cmd.exe /c $line
    if ($LASTEXITCODE -ne 0) { throw "restore failed: $source" }
    Write-Host "[RESTORE] $source -> $db" -ForegroundColor Green
}

switch ($Mode) {
    'backup' {
        $target = Join-Path $backupDir "$Database-$stamp.sql"
        Do-Backup $Database $target
    }
    'restore' {
        if (-not $File) { throw 'pass -File <backup.sql>' }
        Do-Restore $Database $File
        & $mysql -u root --default-character-set=utf8mb4 -e "SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='$Database';"
    }
    'drill' {
        $drillDb = 's4_backup_drill'
        Write-Host '=== 1. create drill db and 3 rows ==='
        & $mysql -u root --default-character-set=utf8mb4 -e "DROP DATABASE IF EXISTS $drillDb; CREATE DATABASE $drillDb DEFAULT CHARACTER SET utf8mb4; USE $drillDb; CREATE TABLE drill (id INT PRIMARY KEY, note VARCHAR(32)); INSERT INTO drill VALUES (1,'alpha'),(2,'beta'),(3,'gamma'); SELECT COUNT(*) AS rows_before FROM drill;"
        Write-Host '=== 2. mysqldump ==='
        $target = Join-Path $backupDir "$drillDb-$stamp.sql"
        Do-Backup $drillDb $target
        Write-Host '=== 3. drop table (simulate failure) ==='
        & $mysql -u root --default-character-set=utf8mb4 -e "USE $drillDb; DROP TABLE drill; SELECT COUNT(*) AS table_exists FROM information_schema.tables WHERE table_schema='$drillDb' AND table_name='drill';"
        Write-Host '=== 4. restore from backup ==='
        Do-Restore $drillDb $target
        Write-Host '=== 5. verify ==='
        & $mysql -u root --default-character-set=utf8mb4 -e "USE $drillDb; SELECT COUNT(*) AS rows_after FROM drill; SELECT * FROM drill ORDER BY id;"
        & $mysql -u root --default-character-set=utf8mb4 -e "DROP DATABASE $drillDb;"
        Write-Host '[DRILL] done, drill db removed' -ForegroundColor Green
    }
}

$env:MYSQL_PWD = ''
