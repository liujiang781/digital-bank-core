# S4 performance test (PowerShell 5.1 compatible)
# Scenarios: login, balance query, transaction list, deposit (write)
# Output: console summary + CSV under evidence/
param(
    [string]$BaseUrl   = 'http://localhost:8085',
    [string]$Identity  = 'UID-000001',
    [string]$Password  = 'demo123',
    [string]$AccountId = 'ACC_S2_001',
    [int]$LoginCount   = 10,
    [int]$ReadCount    = 200,
    [int]$WriteCount   = 20,
    [double]$DepositAmount = 1.00,
    [string]$OutDir    = (Join-Path $PSScriptRoot '..\evidence')
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csv   = Join-Path $OutDir "perf-$stamp.csv"
$log   = Join-Path $OutDir "perf-$stamp.txt"
$rows  = New-Object System.Collections.ArrayList

function Add-Row($scenario, $idx, $ms, $status, $note) {
    $null = $rows.Add([pscustomobject]@{
        scenario = $scenario; index = $idx; elapsed_ms = [math]::Round($ms, 2); status = $status; note = $note
    })
}

function Invoke-Timed([string]$method, [string]$url, $headers, $body) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $status = ''; $note = ''
    try {
        $p = @{ Method = $method; Uri = $url; TimeoutSec = 30; UseBasicParsing = $false }
        if ($headers) { $p.Headers = $headers }
        if ($body) {
            $p.ContentType = 'application/json'
            $p.Body = ([string]$body)
        }
        $r = Invoke-RestMethod @p
        $status = 'OK'
        if ($r.code) { $note = [string]$r.code }
    } catch {
        $status = 'ERR'
        if ($_.Exception.Response) { $note = 'HTTP ' + [int]$_.Exception.Response.StatusCode }
        else { $note = $_.Exception.Message }
    }
    $sw.Stop()
    return @{ ms = $sw.Elapsed.TotalMilliseconds; status = $status; note = $note }
}

function Get-Stats($list) {
    if (-not $list -or $list.Count -eq 0) { return $null }
    $a = @($list | Sort-Object)
    $n = $a.Count
    function P($q) {
        $i = [math]::Ceiling($q * $n) - 1
        if ($i -lt 0) { $i = 0 }
        if ($i -ge $n) { $i = $n - 1 }
        return $a[$i]
    }
    return [pscustomobject]@{
        n = $n
        avg = [math]::Round((($a | Measure-Object -Average).Average), 2)
        p50 = [math]::Round((P 0.50), 2)
        p95 = [math]::Round((P 0.95), 2)
        p99 = [math]::Round((P 0.99), 2)
        max = [math]::Round($a[$n - 1], 2)
        min = [math]::Round($a[0], 2)
    }
}

$summary = New-Object System.Collections.ArrayList

# ---------- 0. connectivity ----------
Write-Host "== S4 perf test =="
Write-Host ("target     : " + $BaseUrl)
Write-Host ("identity   : " + $Identity)
Write-Host ("account    : " + $AccountId)
$seg = Invoke-Timed 'GET' "$BaseUrl/actuator/health" $null $null
Add-Row 'health' 0 $seg.ms $seg.status $seg.note
Write-Host ("health     : " + $seg.status + " " + $seg.ms + " ms")

# ---------- 1. login (BCrypt cost 12) ----------
$loginMs = @(); $token = $null; $customer = $null
for ($i = 1; $i -le $LoginCount; $i++) {
    $b = @{ identityId = $Identity; password = $Password } | ConvertTo-Json -Compress
    $r = Invoke-Timed 'POST' "$BaseUrl/api/v1/auth/login" $null $b
    Add-Row 'login' $i $r.ms $r.status $r.note
    if ($r.status -eq 'OK') { $loginMs += $r.ms }
    else { Write-Host ("login #" + $i + " failed: " + $r.note) }
}
if ($loginMs.Count -eq 0) { throw "all login attempts failed; check service and credentials" }
$null = $summary.Add([pscustomobject]@{ scenario = 'login'; stats = (Get-Stats $loginMs) })
$resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{ identityId = $Identity; password = $Password } | ConvertTo-Json -Compress)
$token = $resp.data.token
$customer = $resp.data.customer
Write-Host ("login      : roles=" + ($customer.roles -join ',') + " scope=" + ($customer.accountScope -join ','))
$h = @{ Authorization = "Bearer $token" }

# ---------- 2. balance query (read) ----------
$readMs = @()
$balanceReason = [uri]::EscapeDataString('S4 perf test')
for ($i = 1; $i -le $ReadCount; $i++) {
    $r = Invoke-Timed 'GET' "$BaseUrl/api/v1/accounts/$AccountId/balance`?reason=$balanceReason" $h $null
    Add-Row 'balance' $i $r.ms $r.status $r.note
    if ($r.status -eq 'OK') { $readMs += $r.ms }
}
$null = $summary.Add([pscustomobject]@{ scenario = 'balance'; stats = (Get-Stats $readMs) })
Write-Host ("balance    : " + (@(Get-Stats $readMs)).p95 + " ms p95 (" + $readMs.Count + " ok)")

# ---------- 3. transaction list (read, back-office) ----------
$listMs = @()
$reason = [uri]::EscapeDataString('S4 perf test')
for ($i = 1; $i -le $ReadCount; $i++) {
    $r = Invoke-Timed 'GET' "$BaseUrl/api/v1/transactions`?reason=$reason&page=1&pageSize=20" $h $null
    Add-Row 'transaction_list' $i $r.ms $r.status $r.note
    if ($r.status -eq 'OK') { $listMs += $r.ms }
}
$null = $summary.Add([pscustomobject]@{ scenario = 'transaction_list'; stats = (Get-Stats $listMs) })
Write-Host ("txn list   : " + (@(Get-Stats $listMs)).p95 + " ms p95 (" + $listMs.Count + " ok)")

# ---------- 4. deposit (write, idempotent) ----------
$writeMs = @(); $okCount = 0
$amountText = $DepositAmount.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
for ($i = 1; $i -le $WriteCount; $i++) {
    $rid = [guid]::NewGuid().ToString()
    $b = @{ accountId = $AccountId; amount = $amountText; requestId = $rid } | ConvertTo-Json -Compress
    $wh = @{ Authorization = "Bearer $token"; 'X-Request-Id' = $rid }
    $r = Invoke-Timed 'POST' "$BaseUrl/api/v1/deposits" $wh $b
    Add-Row 'deposit' $i $r.ms $r.status $r.note
    if ($r.status -eq 'OK') { $writeMs += $r.ms; $okCount++ }
}
$writeStats = Get-Stats $writeMs
$null = $summary.Add([pscustomobject]@{ scenario = 'deposit'; stats = $writeStats })
if ($writeStats) {
    Write-Host ("deposit    : " + $writeStats.p95 + " ms p95, " + $okCount + " ok, " + ([math]::Round((1000 / $writeStats.avg), 1)) + " tps")
} else {
    Write-Host "deposit    : all failed"
}

# ---------- 5. export ----------
$rows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8

$lines = @()
$lines += "=== S4 perf test $stamp ==="
$lines += ("target=" + $BaseUrl + " identity=" + $Identity + " account=" + $AccountId)
foreach ($s in $summary) {
    if ($s.stats) {
        $lines += ("{0,-18} n={1,-4} min={2,-8} avg={3,-8} p50={4,-8} p95={5,-8} p99={6,-8} max={7}" -f $s.scenario, $s.stats.n, $s.stats.min, $s.stats.avg, $s.stats.p50, $s.stats.p95, $s.stats.p99, $s.stats.max)
    }
}
$lines += ("deposit_ok=" + $okCount + " / " + $WriteCount + " amount_each=" + $amountText)
$lines += "NOTE: single host, Windows, DriverManagerDataSource (no pool) -> for trend, not benchmark."
$lines | Set-Content -Path $log -Encoding UTF8

Write-Host ""
Write-Host ("saved: " + $csv)
Write-Host ("saved: " + $log)
