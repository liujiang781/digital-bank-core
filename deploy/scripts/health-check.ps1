# Requires: Windows PowerShell 5.1+
# Usage: powershell -ExecutionPolicy Bypass -File deploy/scripts/health-check.ps1
param(
    [string]$GatewayPort = '18080'
)

$targets = @(
    @{ Name = 'gateway';           Url = "http://localhost:$GatewayPort/actuator/health" },
    @{ Name = 'user-service';      Url = "http://localhost:$GatewayPort/api/v1/user/health" },
    @{ Name = 'account-service';   Url = "http://localhost:$GatewayPort/api/v1/account/health" },
    @{ Name = 'transaction-svc';   Url = "http://localhost:$GatewayPort/api/v1/transaction/health" },
    @{ Name = 'risk-service';      Url = "http://localhost:$GatewayPort/api/v1/risk/health" }
)

$failed = 0
foreach ($t in $targets) {
    try {
        $r = Invoke-WebRequest -Uri $t.Url -UseBasicParsing -TimeoutSec 10
        # Windows PowerShell 5.1 may expose .Content as byte[] or decode it as Latin-1,
        # so always re-read the raw body as UTF-8.
        $body = $r.Content
        if ($body -is [byte[]]) {
            $body = [System.Text.Encoding]::UTF8.GetString($body)
        } else {
            $raw = $r.RawContentStream
            $raw.Position = 0
            $ms = New-Object System.IO.MemoryStream
            $raw.CopyTo($ms)
            $body = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
        }
        if ($body.Length -gt 160) { $body = $body.Substring(0, 160) + '...' }
        Write-Host ("[PASS] {0,-16} {1} {2}" -f $t.Name, $r.StatusCode, $body) -ForegroundColor Green
    }
    catch {
        $failed++
        Write-Host ("[FAIL] {0,-16} {1}" -f $t.Name, $_.Exception.Message) -ForegroundColor Red
    }
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "All targets UP (health only, not business acceptance)." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$failed target(s) failed." -ForegroundColor Red
    exit 1
}
