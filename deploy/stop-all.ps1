# 数字银行核心业务模拟系统 · 一键停止
# 用法（PowerShell 窗口）：
#   powershell -ExecutionPolicy Bypass -File deploy\stop-all.ps1

$ports = @(8081, 8082, 8083, 8084, 8090, 5173)

foreach ($port in $ports) {
  $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($c in $conns) {
    $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -in @('java', 'node')) {
      Write-Host ("停止 {0} (端口 {1}, PID {2})" -f $p.ProcessName, $port, $p.Id) -ForegroundColor Yellow
      Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
  }
}

Write-Host '所有服务已停止。' -ForegroundColor Green
