param(
  [int]$Port = 18080,
  [string]$Cloudflared = 'cloudflared.exe'
)

$ErrorActionPreference = 'Stop'
$exe = $Cloudflared
if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) {
  $fallback = Join-Path $PSScriptRoot 'cloudflared.exe'
  if (Test-Path $fallback) {
    $exe = $fallback
  } else {
    throw "cloudflared not found. Download it first: curl.exe -L --ssl-no-revoke -o cloudflared.exe https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
  }
}

Write-Host "Starting quick tunnel to http://localhost:$Port ..."
$log = Join-Path $env:TEMP ("cloudflared-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".log")
$p = Start-Process -FilePath $exe -ArgumentList 'tunnel', '--url', "http://localhost:$Port", '--no-autoupdate' `
  -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden

for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 2
  if (Test-Path $log) {
    $m = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -AllMatches -ErrorAction SilentlyContinue
    if ($m) {
      $url = $m.Matches[0].Value
      Write-Host "PUBLIC URL: $url"
      Write-Host "Health   : $url/actuator/health"
      Write-Host "PID      : $($p.Id)"
      Write-Host "Stop     : Stop-Process -Id $($p.Id)"
      exit 0
    }
  }
  if ($p.HasExited) { throw "cloudflared exited early, see $log and $log.err" }
}
throw "Timed out waiting for tunnel URL, see $log"
