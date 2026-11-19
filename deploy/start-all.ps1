# 数字银行核心业务模拟系统 · 一键启动（单窗口版）
# 双击 deploy\一键启动.bat 调用本脚本。
#
# 前置：JDK 21、backend 已构建（5 个 JAR 存在）、frontend/node_modules 已安装。
# 坑 1：本机 bash/环境预设了 SERVER_PORT，会覆盖 application.yml 默认端口，
#       所以每个服务都显式设置 SERVER_PORT。
# 坑 2：npm 只在 WorkBuddy 的 Node 目录里，不在系统 PATH，
#       启动前端时必须把该目录加入 PATH。

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend'
$Logs = Join-Path $Root 'logs'
$Java = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin\java.exe'
$NodeBin = 'C:\Users\26043\.workbuddy\binaries\node\versions\22.22.2-6'

# ---- 本地配置（换环境时改这里）----
$DbUrl = 'jdbc:mysql://127.0.0.1:3306/payment_db?serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$DbUser = 'root'
$DbPassword = '123456'
$BankToken = 'demo-bank-token-0123456789abcdef0123456789abcdef'
$AccountToken = 'demo-account-token-0123456789abcdef0123456789abcdef0123'
$RiskToken = 'demo-risk-token-0123456789abcdef0123456789abcdef0123456'

if (-not (Test-Path $Java)) { throw "找不到 JDK 21：$Java" }
if (-not (Test-Path $Logs)) { New-Item -ItemType Directory -Path $Logs | Out-Null }

# 检查 JAR
foreach ($m in @('gateway', 'user-service', 'account-service', 'transaction-service', 'risk-service')) {
  $jar = Join-Path $Backend "$m\target\$m-0.1.0-SNAPSHOT.jar"
  if (-not (Test-Path $jar)) {
    throw "缺少 $jar，请先在 backend 目录执行：mvnw.cmd -s .mvn/central-settings.xml clean install"
  }
}

# 启动 MySQL（如未运行）
$mysql = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
if ($mysql -and $mysql.Status -ne 'Running') {
  Write-Host '启动 MySQL80...' -ForegroundColor Yellow
  Start-Service -Name 'MySQL80'
  Start-Sleep -Seconds 5
}

# 清理旧进程
Write-Host '清理旧进程...' -ForegroundColor Yellow
foreach ($port in @(8081, 8082, 8083, 8084, 8090, 5173)) {
  foreach ($c in (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
    $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -in @('java', 'node')) {
      Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
  }
}

# ---- 通用启动函数 ----
# 生成一个临时 .cmd 设置环境变量后启动服务，再用 Process 隐藏窗口运行。
# 不用 Start-Process：在自动化会话里它的子进程会随会话结束被回收；
# 用 Process 直接启动并让脚本保持等待，进程能持续存活。
function Start-Backend {
  param([string]$Name, [string]$Jar, [hashtable]$Envs)
  $logFile = Join-Path $Logs "$Name.log"
  $cmdFile = Join-Path $Logs "_$Name.cmd"

  # 启动前清空旧日志，避免就绪检测读到上一次的 Started 标记
  [System.IO.File]::WriteAllText($logFile, '')

  # cmd 路径含中文（D:\AAA编程\软件工程），必须用带 BOM 的 UTF-8 写，
  # 且第一行 chcp 65001 让 cmd 按 UTF-8 解释路径。
  $lines = @('@echo off', 'chcp 65001 >nul')
  foreach ($k in $Envs.Keys) { $lines += "set `"$k=$($Envs[$k])`"" }
  $lines += "call `"$Java`" -jar `"$Jar`""
  $lines += "exit /b %ERRORLEVEL%"
  [System.IO.File]::WriteAllLines($cmdFile, $lines, (New-Object System.Text.UTF8Encoding $true))

  # 用 cmd /c 执行 cmd 文件，输出重定向到日志
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = 'cmd.exe'
  $psi.Arguments = '/c "' + $cmdFile + ' > "' + $logFile + '" 2>&1"'
  $psi.WorkingDirectory = $Backend
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true

  $proc = [System.Diagnostics.Process]::Start($psi)
  Write-Host ("  已启动 {0} (PID {1})" -f $Name, $proc.Id) -ForegroundColor Green
}

Write-Host '启动后端服务...' -ForegroundColor Yellow
Start-Backend -Name 'user-service' -Jar (Join-Path $Backend 'user-service\target\user-service-0.1.0-SNAPSHOT.jar') -Envs @{
  SERVER_PORT = '8081'; BANK_CREDENTIAL_STORE = 'mysql'; BANK_DB_URL = $DbUrl
  BANK_DB_USERNAME = $DbUser; BANK_DB_PASSWORD = $DbPassword; BANK_INTERNAL_TOKEN = $BankToken
}
Start-Backend -Name 'account-service' -Jar (Join-Path $Backend 'account-service\target\account-service-0.1.0-SNAPSHOT.jar') -Envs @{
  SERVER_PORT = '8082'; ACCOUNT_DB_URL = $DbUrl; ACCOUNT_DB_USER = $DbUser
  ACCOUNT_DB_PASSWORD = $DbPassword; ACCOUNT_INTERNAL_TOKEN = $AccountToken
  USER_SERVICE_URL = 'http://127.0.0.1:8081'; BANK_INTERNAL_TOKEN = $BankToken
}
Start-Backend -Name 'risk-service' -Jar (Join-Path $Backend 'risk-service\target\risk-service-0.1.0-SNAPSHOT.jar') -Envs @{
  SERVER_PORT = '8084'; RISK_DB_URL = $DbUrl; RISK_DB_USER = $DbUser
  RISK_DB_PASSWORD = $DbPassword; RISK_INTERNAL_TOKEN = $RiskToken
  USER_SERVICE_URL = 'http://127.0.0.1:8081'; BANK_INTERNAL_TOKEN = $BankToken
}
Start-Backend -Name 'transaction-service' -Jar (Join-Path $Backend 'transaction-service\target\transaction-service-0.1.0-SNAPSHOT.jar') -Envs @{
  SERVER_PORT = '8083'; TRANSACTION_DB_URL = $DbUrl; TRANSACTION_DB_USER = $DbUser
  TRANSACTION_DB_PASSWORD = $DbPassword; ACCOUNT_SERVICE_URL = 'http://127.0.0.1:8082'
  ACCOUNT_INTERNAL_TOKEN = $AccountToken; RISK_SERVICE_URL = 'http://127.0.0.1:8084'
  RISK_INTERNAL_TOKEN = $RiskToken; MOCK_CHANNEL_RESULT = 'SUCCESS'
  USER_SERVICE_URL = 'http://127.0.0.1:8081'; BANK_INTERNAL_TOKEN = $BankToken
}
Start-Backend -Name 'gateway' -Jar (Join-Path $Backend 'gateway\target\gateway-0.1.0-SNAPSHOT.jar') -Envs @{
  SERVER_PORT = '8090'; USER_SERVICE_URL = 'http://127.0.0.1:8081'; BANK_INTERNAL_TOKEN = $BankToken
}

# 等待后端就绪
Write-Host '等待后端就绪（最多 60 秒）...' -ForegroundColor Yellow
$modules = @('user-service', 'account-service', 'risk-service', 'transaction-service', 'gateway')
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
  $allStarted = $true
  foreach ($m in $modules) {
    $log = Join-Path $Logs "$m.log"
    if (-not (Test-Path $log) -or -not (Select-String -Path $log -Pattern 'Started .*Application' -Quiet)) {
      $allStarted = $false
    }
  }
  if ($allStarted) { break }
  Start-Sleep -Seconds 2
}
# 直接看日志里的 Started 标记，比 HTTP 探测更可靠（不受本机网络策略影响）
foreach ($m in @('user-service', 'account-service', 'risk-service', 'transaction-service', 'gateway')) {
  $log = Join-Path $Logs "$m.log"
  $started = $false
  if (Test-Path $log) {
    $started = Select-String -Path $log -Pattern 'Started .*Application' -Quiet
  }
  if ($started) {
    Write-Host ("  {0}: 启动成功" -f $m) -ForegroundColor Green
  } else {
    Write-Host ("  {0}: 未检测到 Started 标记，请查看 logs\{0}.log" -f $m) -ForegroundColor Yellow
  }
}

# ---- 启动前端：显式补 Node 路径到 PATH ----
Write-Host '启动前端...' -ForegroundColor Yellow
$feLog = Join-Path $Logs 'frontend.log'
[System.IO.File]::WriteAllText($feLog, '')
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'cmd.exe'
$psi.Arguments = "/c `"set `"PATH=$NodeBin;%PATH%`" && cd /d `"$Frontend`" && npm run dev > `"$Logs\frontend.log`" 2>&1`""
$psi.WorkingDirectory = $Frontend
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$proc.Start() | Out-Null

Start-Sleep -Seconds 6
$feLog = Join-Path $Logs 'frontend.log'
if (Test-Path $feLog) {
  # VITE 是纯 ASCII 标记，避免 cmd 输出 GBK 编码导致中文/箭头匹配失败
  $feOk = Select-String -Path $feLog -Pattern 'VITE' -Quiet
  if ($feOk) { Write-Host '  前端: 已就绪' -ForegroundColor Green }
  else { Write-Host '  前端: 未就绪，见 logs\frontend.log' -ForegroundColor Red }
}

Write-Host ''
Write-Host '===== 完成 =====' -ForegroundColor Cyan
Write-Host '页面：http://localhost:5173' -ForegroundColor Cyan
Write-Host '账号：UID-000001 / demo123' -ForegroundColor Cyan
Write-Host '日志：logs\ 目录（无窗口弹出）' -ForegroundColor Cyan
