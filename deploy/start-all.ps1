# 数字银行核心业务模拟系统 · 一键启动
# 用法（PowerShell 窗口）：
#   powershell -ExecutionPolicy Bypass -File deploy\start-all.ps1
#
# 前置：JDK 21、Maven 已构建（backend/target 下 5 个 JAR 存在）、node_modules 已安装。
# 说明：本机 bash 沙箱预设了 SERVER_PORT 环境变量，会覆盖 application.yml 的默认端口，
#       所以脚本对每个服务都显式设置 SERVER_PORT，避免多服务抢同一端口。

$ErrorActionPreference = 'Stop'

# 路径推导（脚本在 deploy/ 下）
$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend'
$Logs = Join-Path $Root 'logs'
$Java = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin\java.exe'

# ---- 可按需修改的本地配置 ----
$DbUrl = 'jdbc:mysql://127.0.0.1:3306/payment_db?serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$DbUser = 'root'
$DbPassword = '123456'
$BankToken = 'demo-bank-token-0123456789abcdef0123456789abcdef'
$AccountToken = 'demo-account-token-0123456789abcdef0123456789abcdef0123'
$RiskToken = 'demo-risk-token-0123456789abcdef0123456789abcdef0123456'

if (-not (Test-Path $Java)) { throw "找不到 JDK 21：$Java" }
if (-not (Test-Path $Logs)) { New-Item -ItemType Directory -Path $Logs | Out-Null }

# 检查 JAR
$modules = @('gateway', 'user-service', 'account-service', 'transaction-service', 'risk-service')
foreach ($m in $modules) {
  $jar = Join-Path $Backend "$m\target\$m-0.1.0-SNAPSHOT.jar"
  if (-not (Test-Path $jar)) {
    throw "缺少 $jar，请先在 backend 目录执行：mvnw.cmd -s .mvn/central-settings.xml clean install"
  }
}

# 检查 MySQL 服务
$mysql = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
if ($mysql -and $mysql.Status -ne 'Running') {
  Write-Host '启动 MySQL80 服务...' -ForegroundColor Yellow
  Start-Service -Name 'MySQL80'
  Start-Sleep -Seconds 5
}

# ---- 停掉旧进程（按端口）----
Write-Host '清理旧服务进程...' -ForegroundColor Yellow
$ports = @(8081, 8082, 8083, 8084, 8090, 5173)
foreach ($port in $ports) {
  $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($c in $conns) {
    $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -in @('java', 'node')) {
      Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
  }
}

# ---- 通用启动函数 ----
function Start-Service-Jar {
  param(
    [string]$Name,
    [int]$Port,
    [string]$Jar,
    [string[]]$Envs
  )
  $setLines = $Envs | ForEach-Object { "set `"$_`"" }
  $cmdLine = ($setLines -join ' && ') + " && `"$Java`" -jar `"$Jar`""
  $log = Join-Path $Logs "$Name.log"
  # cmd /k 保持窗口存在；不用 -WindowStyle Hidden，
  # 否则非交互会话结束时子进程会被一起回收。
  Start-Process -FilePath 'cmd.exe' `
    -ArgumentList "/k `"$cmdLine > `"$log`" 2>&1`"" `
    -WorkingDirectory $Backend | Out-Null
  Write-Host ("  已启动 {0} (端口 {1})" -f $Name, $Port) -ForegroundColor Green
}

Write-Host '启动后端服务...' -ForegroundColor Yellow
Start-Service-Jar -Name 'user-service' -Port 8081 `
  -Jar (Join-Path $Backend 'user-service\target\user-service-0.1.0-SNAPSHOT.jar') `
  -Envs @(
    'SERVER_PORT=8081',
    'BANK_CREDENTIAL_STORE=mysql',
    "BANK_DB_URL=$DbUrl",
    "BANK_DB_USERNAME=$DbUser",
    "BANK_DB_PASSWORD=$DbPassword",
    "BANK_INTERNAL_TOKEN=$BankToken"
  )

Start-Service-Jar -Name 'account-service' -Port 8082 `
  -Jar (Join-Path $Backend 'account-service\target\account-service-0.1.0-SNAPSHOT.jar') `
  -Envs @(
    'SERVER_PORT=8082',
    "ACCOUNT_DB_URL=$DbUrl",
    "ACCOUNT_DB_USER=$DbUser",
    "ACCOUNT_DB_PASSWORD=$DbPassword",
    "ACCOUNT_INTERNAL_TOKEN=$AccountToken",
    'USER_SERVICE_URL=http://127.0.0.1:8081',
    "BANK_INTERNAL_TOKEN=$BankToken"
  )

Start-Service-Jar -Name 'risk-service' -Port 8084 `
  -Jar (Join-Path $Backend 'risk-service\target\risk-service-0.1.0-SNAPSHOT.jar') `
  -Envs @(
    'SERVER_PORT=8084',
    "RISK_DB_URL=$DbUrl",
    "RISK_DB_USER=$DbUser",
    "RISK_DB_PASSWORD=$DbPassword",
    "RISK_INTERNAL_TOKEN=$RiskToken",
    'USER_SERVICE_URL=http://127.0.0.1:8081',
    "BANK_INTERNAL_TOKEN=$BankToken"
  )

Start-Service-Jar -Name 'transaction-service' -Port 8083 `
  -Jar (Join-Path $Backend 'transaction-service\target\transaction-service-0.1.0-SNAPSHOT.jar') `
  -Envs @(
    'SERVER_PORT=8083',
    "TRANSACTION_DB_URL=$DbUrl",
    "TRANSACTION_DB_USER=$DbUser",
    "TRANSACTION_DB_PASSWORD=$DbPassword",
    'ACCOUNT_SERVICE_URL=http://127.0.0.1:8082',
    "ACCOUNT_INTERNAL_TOKEN=$AccountToken",
    'RISK_SERVICE_URL=http://127.0.0.1:8084',
    "RISK_INTERNAL_TOKEN=$RiskToken",
    'MOCK_CHANNEL_RESULT=SUCCESS',
    'USER_SERVICE_URL=http://127.0.0.1:8081',
    "BANK_INTERNAL_TOKEN=$BankToken"
  )

Start-Service-Jar -Name 'gateway' -Port 8090 `
  -Jar (Join-Path $Backend 'gateway\target\gateway-0.1.0-SNAPSHOT.jar') `
  -Envs @(
    'SERVER_PORT=8090',
    'USER_SERVICE_URL=http://127.0.0.1:8081',
    "BANK_INTERNAL_TOKEN=$BankToken"
  )

# ---- 等待后端就绪 ----
Write-Host '等待后端服务就绪（最多 60 秒）...' -ForegroundColor Yellow
$targets = @(8081, 8082, 8083, 8084, 8090)
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
  $allUp = $true
  foreach ($port in $targets) {
    try {
      $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
      if ($r.status -ne 'UP') { $allUp = $false }
    } catch { $allUp = $false }
  }
  if ($allUp) { break }
  Start-Sleep -Seconds 2
}
foreach ($port in $targets) {
  try {
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
    Write-Host ("  端口 {0}: {1}" -f $port, $r.status) -ForegroundColor $(if ($r.status -eq 'UP') { 'Green' } else { 'Red' })
  } catch {
    Write-Host ("  端口 {0}: 未就绪（日志见 logs\）" -f $port) -ForegroundColor Red
  }
}

# ---- 启动前端 ----
Write-Host '启动前端 Vite 开发服务器...' -ForegroundColor Yellow
$frontendLog = Join-Path $Logs 'frontend.log'
Start-Process -FilePath 'cmd.exe' `
  -ArgumentList "/k `"cd /d `"$Frontend`" && npm run dev > `"$frontendLog`" 2>&1`"" `
  -WorkingDirectory $Frontend | Out-Null

Start-Sleep -Seconds 5
Write-Host ''
Write-Host '===== 启动完成 =====' -ForegroundColor Cyan
Write-Host '前端页面：http://localhost:5173' -ForegroundColor Cyan
Write-Host '演示账号：UID-000001 / demo123' -ForegroundColor Cyan
Write-Host '服务日志：logs\ 目录' -ForegroundColor Cyan
