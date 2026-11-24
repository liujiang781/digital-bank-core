param(
    [string]$BaseUrl = 'http://127.0.0.1:8085',
    [string]$Identity = 'UID-000001',
    [string]$Password = 'demo123',
    [string]$AccountId = 'ACC_S2_001',
    [decimal]$Amount = 1.00,
    [string]$OutDir = (Join-Path $PSScriptRoot '..\evidence')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $OutDir "acceptance-$stamp.txt"
$rows = New-Object System.Collections.ArrayList
function Rec([string]$name, [string]$status, [string]$detail) {
    $line = "{0,-34} {1,-5} {2}" -f $name, $status, $detail
    Write-Host $line
    $null = $rows.Add($line)
    $rows | Set-Content -Path $log -Encoding UTF8
}
function Http([string]$method, [string]$url, $headers, $body) {
    try {
        if ($body) {
            $r = Invoke-WebRequest -Method $method -Uri $url -Headers $headers -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 40
        } elseif ($headers) {
            $r = Invoke-WebRequest -Method $method -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 40
        } else {
            $r = Invoke-WebRequest -Method $method -Uri $url -UseBasicParsing -TimeoutSec 40
        }
        return @{ code = [int]$r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object IO.StreamReader($resp.GetResponseStream())
            return @{ code = [int]$resp.StatusCode; body = $sr.ReadToEnd() }
        }
        return @{ code = -1; body = $_.Exception.Message }
    }
}

Rec 'env' 'INFO' "target=$BaseUrl identity=$Identity account=$AccountId amount=$Amount"

# 1. health
$ports = @{ 'user' = 8081; 'account' = 8082; 'transaction' = 8083; 'risk' = 8084; 'gateway' = 8085 }
$healthOk = 0
foreach ($k in $ports.Keys) {
    try {
        $s = Invoke-RestMethod -Uri "http://127.0.0.1:$($ports[$k])/actuator/health" -TimeoutSec 5
        if ($s.status -eq 'UP') { $healthOk++ }
    } catch { }
}
Rec 'health(5 services)' ($(if($healthOk -eq 5){'PASS'}else{'FAIL'})) "$healthOk/5 UP"

# 2. login
$login = Http 'POST' "$BaseUrl/api/v1/auth/login" $null (@{ identityId = $Identity; password = $Password } | ConvertTo-Json -Compress)
$token = $null
try { $token = ($login.body | ConvertFrom-Json).data.token } catch { }
Rec 'login' ($(if($token){'PASS'}else{'FAIL'})) "http=$($login.code) token_len=$(if($token){$token.Length}else{0})"
if (-not $token) { throw 'login failed' }
$h = @{ Authorization = "Bearer $token" }

# 3. balance before
$b1 = Http 'GET' "$BaseUrl/api/v1/accounts/$AccountId/balance?reason=S4%20acceptance" $h $null
$before = $null
try { $before = [decimal](($b1.body | ConvertFrom-Json).data.bookBalance) } catch { }
Rec 'balance(before)' ($(if($null -ne $before){'PASS'}else{'FAIL'})) "http=$($b1.code) book=$before"

# 4. deposit (fresh request id, header must equal body)
$rid = [guid]::NewGuid().ToString()
$dh = @{ Authorization = "Bearer $token"; 'X-Request-Id' = $rid }
$db = @{ accountId = $AccountId; amount = $Amount.ToString('0.00'); requestId = $rid } | ConvertTo-Json -Compress
$d1 = Http 'POST' "$BaseUrl/api/v1/deposits" $dh $db
$trade1 = $null; $status1 = $null
try { $trade1 = ($d1.body | ConvertFrom-Json).data.tradeNo; $status1 = ($d1.body | ConvertFrom-Json).data.status } catch { }
Rec 'deposit(first)' ($(if($status1 -eq 'SUCCESS'){'PASS'}else{'FAIL'})) "http=$($d1.code) status=$status1 trade=$trade1"

# 5. idempotent replay (same request id)
$d2 = Http 'POST' "$BaseUrl/api/v1/deposits" $dh $db
$trade2 = $null
try { $trade2 = ($d2.body | ConvertFrom-Json).data.tradeNo } catch { }
Rec 'deposit(replay)' ($(if($trade2 -eq $trade1 -and $trade1){'PASS'}else{'FAIL'})) "same_trade=$(if($trade2 -eq $trade1){'yes'}else{'no'}) trade=$trade2"

# 6. balance after
Start-Sleep -Seconds 1
$b2 = Http 'GET' "$BaseUrl/api/v1/accounts/$AccountId/balance?reason=S4%20acceptance" $h $null
$after = $null
try { $after = [decimal](($b2.body | ConvertFrom-Json).data.bookBalance) } catch { }
$delta = if ($null -ne $before -and $null -ne $after) { $after - $before } else { $null }
Rec 'balance(after)' ($(if($delta -eq $Amount){'PASS'}else{'FAIL'})) "book=$after delta=$delta expect=$Amount"

# 7. transaction list
$tl = Http 'GET' "$BaseUrl/api/v1/transactions?reason=S4%20acceptance&page=1&pageSize=10" $h $null
$total = $null; $firstTrade = $null
try { $total = ($tl.body | ConvertFrom-Json).data.total; $firstTrade = ($tl.body | ConvertFrom-Json).data.items[0].tradeNo } catch { }
Rec 'transaction_list' ($(if($null -ne $total -and $total -ge 1){'PASS'}else{'FAIL'})) "http=$($tl.code) total=$total first=$firstTrade"

# 8. flow persisted (query through list detail is not available; rely on total + balance)
Rec 'summary' 'DONE' "evidence=$log"
