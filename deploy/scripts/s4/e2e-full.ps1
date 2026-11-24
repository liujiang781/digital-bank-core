param(
    [string]$BaseUrl = 'http://127.0.0.1:8085',
    [string]$OutDir = (Join-Path $PSScriptRoot '..\evidence')
)
# End-to-end test over all five services, multi-identity.
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $OutDir "e2e-$stamp.txt"
$rows = New-Object System.Collections.ArrayList
$pass = 0; $fail = 0; $skip = 0
function Rec([string]$id, [string]$name, [string]$status, [string]$detail) {
    $line = "{0,-4} {1,-38} {2,-4} {3}" -f $id, $name, $status, $detail
    Write-Host $line
    $null = $rows.Add($line)
    $script:pass += ($status -eq 'PASS')
    $script:fail += ($status -eq 'FAIL')
    $script:skip += ($status -eq 'SKIP')
    $rows | Set-Content -LiteralPath $log -Encoding UTF8
}
function Req([string]$method, [string]$path, [string]$token, $body, [string]$rid) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    if ($rid) { $headers['X-Request-Id'] = $rid }
    $uri = $BaseUrl + $path
    try {
        if ($null -ne $body) {
            $json = $body | ConvertTo-Json -Compress -Depth 6
            $bytes = [Text.Encoding]::UTF8.GetBytes($json)
            $r = Invoke-WebRequest -Method $method -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes -UseBasicParsing -TimeoutSec 40
        } else {
            $r = Invoke-WebRequest -Method $method -Uri $uri -Headers $headers -UseBasicParsing -TimeoutSec 40
        }
        $c = $r.Content; if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
        return @{ code = [int]$r.StatusCode; body = $c }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object IO.StreamReader($resp.GetResponseStream())
            return @{ code = [int]$resp.StatusCode; body = $sr.ReadToEnd() }
        }
        return @{ code = -1; body = $_.Exception.Message }
    }
}
function J($obj, [string]$expr) {
    try { return ($obj | ConvertFrom-Json).$expr } catch { return $null }
}
function Login([string]$id, [string]$pw) {
    $r = Req 'POST' '/api/v1/auth/login' $null @{ identityId = $id; password = $pw }
    $token = $null; $roles = @()
    try {
        $j = $r.body | ConvertFrom-Json
        $token = $j.data.token
        $roles = @($j.data.customer.roles)
        if ($roles.Count -eq 0) { $roles = @($j.data.roles) }
    } catch { }
    return @{ code = $r.code; token = $token; roles = ($roles -join ','); body = $r.body }
}

# ---------- A. health ----------
$ports = @{ 'user' = 8081; 'account' = 8082; 'transaction' = 8083; 'risk' = 8084; 'gateway' = 8085 }
$up = 0
foreach ($k in @('user', 'account', 'transaction', 'risk', 'gateway')) {
    try { if ((Invoke-RestMethod "http://127.0.0.1:$($ports[$k])/actuator/health" -TimeoutSec 5).status -eq 'UP') { $up++ } } catch { }
}
Rec 'A1' 'health(5 services)' ($(if($up -eq 5){'PASS'}else{'FAIL'})) "$up/5 UP"

# ---------- B. auth ----------
$u1 = Login 'UID-000001' 'demo123'; Rec 'B1' 'login UID-000001' ($(if($u1.token){'PASS'}else{'FAIL'})) "http=$($u1.code) roles=$($u1.roles) token_len=$($u1.token.Length)"
$u2 = Login 'UID-000002' 'demo123'; Rec 'B2' 'login UID-000002' ($(if($u2.token){'PASS'}else{'FAIL'})) "http=$($u2.code) roles=$($u2.roles)"
$ops = Login 'OPS-ADMIN' 'admin123'; Rec 'B3' 'login OPS-ADMIN' ($(if($ops.token){'PASS'}else{'FAIL'})) "http=$($ops.code) roles=$($ops.roles)"
$rsk = Login 'OPS-RISK' 'admin123'; Rec 'B4' 'login OPS-RISK' ($(if($rsk.token){'PASS'}else{'FAIL'})) "http=$($rsk.code) roles=$($rsk.roles)"
$appr = Login 'RISK-APPROVER' 'admin123'; Rec 'B8' 'login RISK-APPROVER' ($(if($appr.token){'PASS'}else{'FAIL'})) "http=$($appr.code) roles=$($appr.roles)"
$bad = Login 'UID-000001' 'wrong-pass'; Rec 'B5' 'login wrong password' ($(if($bad.code -ge 400 -and $bad.code -lt 500){'PASS'}else{'FAIL'})) "http=$($bad.code)"
$anon = Req 'GET' '/api/v1/accounts/ACC_S2_001/balance?reason=e2e' $null $null
Rec 'B6' 'anonymous access rejected' ($(if($anon.code -eq 401){'PASS'}else{'FAIL'})) "http=$($anon.code)"

if (-not ($u1.token -and $u2.token -and $ops.token -and $rsk.token -and $appr.token)) { throw 'login prerequisite failed' }

# ---------- C. customer / roles ----------
$me = Req 'GET' '/api/v1/customers/me' $u1.token $null
Rec 'C1' 'GET /customers/me' ($(if($me.code -eq 200){'PASS'}else{'FAIL'})) "http=$($me.code)"
$cl = Req 'GET' '/api/v1/customers?reason=e2e&page=1&pageSize=10' $ops.token $null
Rec 'C2' 'GET /customers (ops)' ($(if($cl.code -eq 200){'PASS'}else{'FAIL'})) "http=$($cl.code)"
Rec 'C3' 'GET /roles (read)' 'SKIP' 'service only exposes PUT /roles/{id}'

# ---------- D. account ----------
$scopedDeny = Req 'GET' '/api/v1/accounts/scoped' $u1.token $null
Rec 'D7' 'scoped denied for plain customer' ($(if($scopedDeny.code -eq 403){'PASS'}else{'FAIL'})) "http=$($scopedDeny.code)"
$scoped = Req 'GET' '/api/v1/accounts/scoped?reason=e2e' $ops.token $null
$scopedCount = 0; try { $scopedCount = @((($scoped.body | ConvertFrom-Json).data)).Count } catch { }
Rec 'D1' 'GET /accounts/scoped' ($(if($scoped.code -eq 200){'PASS'}else{'FAIL'})) "http=$($scoped.code) count=$scopedCount"
$acc = Req 'GET' '/api/v1/accounts/ACC_S2_001' $u1.token $null
$accStatus = $null; try { $accStatus = ($acc.body | ConvertFrom-Json).data.status } catch { }
Rec 'D2' 'GET /accounts/{id}' ($(if($acc.code -eq 200){'PASS'}else{'FAIL'})) "http=$($acc.code) status=$accStatus"
$bal0 = Req 'GET' '/api/v1/accounts/ACC_S2_001/balance?reason=e2e' $u1.token $null
$b0 = $null; try { $b0 = [decimal](($bal0.body | ConvertFrom-Json).data.bookBalance) } catch { }
Rec 'D3' 'GET balance (owner)' ($(if($bal0.code -eq 200 -and $null -ne $b0){'PASS'}else{'FAIL'})) "http=$($bal0.code) book=$b0"
$balX = Req 'GET' '/api/v1/accounts/ACC_S2_001/balance?reason=e2e' $u2.token $null
Rec 'D4' 'balance cross-customer blocked' ($(if($balX.code -in @(403,404)){'PASS'}else{'FAIL'})) "http=$($balX.code)"
$opsList = Req 'GET' '/api/v1/accounts/ACC_S2_001/operations' $u1.token $null
Rec 'D5' 'GET /accounts/{id}/operations' ($(if($opsList.code -eq 200){'PASS'}else{'FAIL'})) "http=$($opsList.code)"
$fe = Req 'GET' '/api/v1/accounts/ACC_S2_001/fund-entries' $u1.token $null
Rec 'D6' 'GET /accounts/{id}/fund-entries' ($(if($fe.code -eq 200){'PASS'}else{'FAIL'})) "http=$($fe.code)"

# ---------- E. transactions ----------
$rid1 = [guid]::NewGuid().ToString()
$dep = Req 'POST' '/api/v1/deposits' $u1.token @{ accountId = 'ACC_S2_001'; amount = '5.00'; requestId = $rid1 } $rid1
$depTrade = $null; $depStatus = $null
try { $depTrade = ($dep.body | ConvertFrom-Json).data.tradeNo; $depStatus = ($dep.body | ConvertFrom-Json).data.status } catch { }
Rec 'E1' 'deposit 5.00' ($(if($depStatus -eq 'SUCCESS'){'PASS'}else{'FAIL'})) "http=$($dep.code) status=$depStatus trade=$depTrade"
$dep2 = Req 'POST' '/api/v1/deposits' $u1.token @{ accountId = 'ACC_S2_001'; amount = '5.00'; requestId = $rid1 } $rid1
$depTrade2 = $null; try { $depTrade2 = ($dep2.body | ConvertFrom-Json).data.tradeNo } catch { }
Rec 'E2' 'deposit idempotent replay' ($(if($depTrade2 -eq $depTrade -and $depTrade){'PASS'}else{'FAIL'})) "same_trade=$(if($depTrade2 -eq $depTrade){'yes'}else{'no'})"

$rid2 = [guid]::NewGuid().ToString()
$wd = Req 'POST' '/api/v1/withdrawals' $u1.token @{ accountId = 'ACC_S2_001'; amount = '2.00'; currency = 'CNY'; purpose = 'e2e'; requestId = $rid2 } $rid2
$wdStatus = $null; $wdTrade = $null
try { $wdStatus = ($wd.body | ConvertFrom-Json).data.status; $wdTrade = ($wd.body | ConvertFrom-Json).data.tradeNo } catch { }
Rec 'E3' 'withdrawal 2.00' ($(if($wdStatus -eq 'SUCCESS'){'PASS'}else{'FAIL'})) "http=$($wd.code) status=$wdStatus trade=$wdTrade"

$b2pre = Req 'GET' '/api/v1/accounts/ACC_S2_002/balance?reason=e2e' $u2.token $null
$pre2 = 0; try { $pre2 = [decimal](($b2pre.body | ConvertFrom-Json).data.bookBalance) } catch { }
$rid3 = [guid]::NewGuid().ToString()
$tf = Req 'POST' '/api/v1/transfers' $u1.token @{ payerAccountId = 'ACC_S2_001'; payeeAccountId = 'ACC_S2_002'; amount = '1.00'; currency = 'CNY'; purpose = 'e2e'; requestId = $rid3 } $rid3
$tfStatus = $null; $tfTrade = $null
try { $tfStatus = ($tf.body | ConvertFrom-Json).data.status; $tfTrade = ($tf.body | ConvertFrom-Json).data.tradeNo } catch { }
Rec 'E4' 'internal transfer 1.00' ($(if($tfStatus -eq 'SUCCESS'){'PASS'}else{'FAIL'})) "http=$($tf.code) status=$tfStatus trade=$tfTrade"

$rid4 = [guid]::NewGuid().ToString()
$tfX = Req 'POST' '/api/v1/transfers' $u2.token @{ payerAccountId = 'ACC_S2_001'; payeeAccountId = 'ACC_S2_002'; amount = '1.00'; currency = 'CNY'; purpose = 'e2e'; requestId = $rid4 } $rid4
Rec 'E5' 'transfer from other account blocked' ($(if($tfX.code -in @(403,404)){'PASS'}else{'FAIL'})) "http=$($tfX.code)"

$byReq = Req 'GET' "/api/v1/transactions/by-request?requestId=$rid1&businessType=DEPOSIT" $u1.token $null
$byReqTrade = $null; try { $byReqTrade = ($byReq.body | ConvertFrom-Json).data.tradeNo } catch { }
Rec 'E6' 'GET /transactions/by-request' ($(if($byReq.code -eq 200 -and $byReqTrade -eq $depTrade){'PASS'}else{'FAIL'})) "http=$($byReq.code) trade=$byReqTrade"
$detail = Req 'GET' "/api/v1/transactions/$depTrade" $u1.token $null
Rec 'E7' 'GET /transactions/{tradeNo}' ($(if($detail.code -eq 200){'PASS'}else{'FAIL'})) "http=$($detail.code)"
$receipt = Req 'GET' "/api/v1/transactions/$depTrade/receipt" $u1.token $null
Rec 'E8' 'GET /transactions/{tradeNo}/receipt' ($(if($receipt.code -eq 200){'PASS'}else{'FAIL'})) "http=$($receipt.code) len=$($receipt.body.Length)"
$tl = Req 'GET' '/api/v1/transactions?reason=e2e&page=1&pageSize=10' $u1.token $null
$tlTotal = $null; try { $tlTotal = ($tl.body | ConvertFrom-Json).data.total } catch { }
Rec 'E9' 'GET /transactions (paged)' ($(if($tl.code -eq 200 -and $tlTotal -ge 3){'PASS'}else{'FAIL'})) "http=$($tl.code) total=$tlTotal"

$b1 = Req 'GET' '/api/v1/accounts/ACC_S2_001/balance?reason=e2e' $u1.token $null
$bv1 = $null; try { $bv1 = [decimal](($b1.body | ConvertFrom-Json).data.bookBalance) } catch { }
$exp1 = $b0 + 5 - 2 - 1
Rec 'E10' 'balance after 3 trades' ($(if($bv1 -eq $exp1){'PASS'}else{'FAIL'})) "book=$bv1 expect=$exp1"
$b2 = Req 'GET' '/api/v1/accounts/ACC_S2_002/balance?reason=e2e' $u2.token $null
$bv2 = $null; try { $bv2 = [decimal](($b2.body | ConvertFrom-Json).data.bookBalance) } catch { }
Rec 'E11' 'payee balance +1.00' ($(if($bv2 -eq ($pre2 + 1)){'PASS'}else{'FAIL'})) "book=$bv2 expect=$($pre2 + 1)"

# ---------- F. risk ----------
$rules = Req 'GET' '/api/v1/risk/rules' $rsk.token $null
$ruleId = $null; try { $ruleId = (@((($rules.body | ConvertFrom-Json).data)) | Where-Object { $_.ruleType -eq 'LIMIT_POLICY' } | Select-Object -First 1).ruleId } catch { }
Rec 'F1' 'GET /risk/rules' ($(if($rules.code -eq 200){'PASS'}else{'FAIL'})) "http=$($rules.code) limitRule=$ruleId"
$events = Req 'GET' '/api/v1/risk/events?reason=e2e&page=1&pageSize=10' $rsk.token $null
$evTotal = 0; $evId = $null
try { $evTotal = ($events.body | ConvertFrom-Json).data.total; $evId = @((($events.body | ConvertFrom-Json).data.items))[0].eventId } catch { }
Rec 'F2' 'GET /risk/events' ($(if($events.code -eq 200){'PASS'}else{'FAIL'})) "http=$($events.code) total=$evTotal"
$lists = Req 'GET' '/api/v1/risk/lists?page=1&pageSize=10' $rsk.token $null
Rec 'F3' 'GET /risk/lists' ($(if($lists.code -eq 200){'PASS'}else{'FAIL'})) "http=$($lists.code)"
$subj = 'ACC_S2_002'
$rid5 = [guid]::NewGuid().ToString()
$lc = Req 'POST' '/api/v1/risk/lists/changes' $rsk.token @{ listType = 'BLACKLIST'; subjectType = 'ACCOUNT'; subjectId = $subj; reasonCode = 'E2E_TEST'; enabled = $true; op = 'UPSERT'; reason = 'e2e'; requestId = $rid5 } $rid5
$ent1 = $null; try { $ent1 = ($lc.body | ConvertFrom-Json).data.entryId } catch { }
Rec 'F4' 'submit list UPSERT' ($(if($lc.code -eq 200){'PASS'}else{'FAIL'})) "http=$($lc.code) subject=$subj"
$rc1 = Req 'GET' '/api/v1/risk/changes?page=1&pageSize=10' $appr.token $null
$ch1 = @((($rc1.body | ConvertFrom-Json).data.items)) | Where-Object { $_.status -eq 'PENDING' -and $ent1 -and $_.changeJson -match [regex]::Escape($ent1) } | Select-Object -First 1
if ($ch1) {
    $ridA = [guid]::NewGuid().ToString()
    $ap1 = Req 'POST' "/api/v1/risk/changes/$($ch1.changeId)/approve" $appr.token @{ decision = 'APPROVE'; note = 'e2e'; requestId = $ridA } $ridA
    Rec 'F4b' 'approve list change (2nd RISK)' ($(if($ap1.code -eq 200){'PASS'}else{'FAIL'})) "http=$($ap1.code) change=$($ch1.changeId)"
} else { Rec 'F4b' 'approve list change (2nd RISK)' 'FAIL' 'no pending change' }
$lists2 = Req 'GET' "/api/v1/risk/lists?listType=BLACKLIST&page=1&pageSize=50" $appr.token $null
$found = $false; try { $found = @(@(($lists2.body | ConvertFrom-Json).data.items) | Where-Object { $_.subjectId -eq $subj -and $_.enabled }).Count -gt 0 } catch { }
Rec 'F5' 'list entry visible after approval' ($(if($found){'PASS'}else{'FAIL'})) "subject=$subj found=$found"
$lists2b = Req 'GET' "/api/v1/risk/lists?listType=BLACKLIST&page=1&pageSize=50" $rsk.token $null
$foundSub = $true; try { $foundSub = @(@(($lists2b.body | ConvertFrom-Json).data.items) | Where-Object { $_.subjectId -eq $subj }).Count -gt 0 } catch { }
Rec 'F5b' 'submitter sees own list entry' ($(if($foundSub){'PASS'}else{'FAIL'})) "found=$foundSub (submittedBy should be OPS-RISK)"
$rid5b = [guid]::NewGuid().ToString()
$rm = Req 'POST' '/api/v1/risk/lists/changes' $rsk.token @{ listType = 'BLACKLIST'; subjectType = 'ACCOUNT'; subjectId = $subj; reasonCode = 'E2E_TEST'; enabled = $false; op = 'REMOVE'; reason = 'e2e cleanup'; requestId = $rid5b } $rid5b
$ent2 = $null; try { $ent2 = ($rm.body | ConvertFrom-Json).data.entryId } catch { }
$rc2 = Req 'GET' '/api/v1/risk/changes?page=1&pageSize=10' $appr.token $null
$ch2 = @((($rc2.body | ConvertFrom-Json).data.items)) | Where-Object { $_.status -eq 'PENDING' -and $ent2 -and $_.changeJson -match [regex]::Escape($ent2) } | Select-Object -First 1
if ($ch2) { $ridB = [guid]::NewGuid().ToString(); $null = Req 'POST' "/api/v1/risk/changes/$($ch2.changeId)/approve" $appr.token @{ decision = 'APPROVE'; note = 'cleanup'; requestId = $ridB } $ridB }
$lists3 = Req 'GET' "/api/v1/risk/lists?listType=BLACKLIST&page=1&pageSize=50" $appr.token $null
$still = $true; try { $still = @(@(($lists3.body | ConvertFrom-Json).data.items) | Where-Object { $_.subjectId -eq $subj -and $_.enabled }).Count -gt 0 } catch { }
Rec 'F10' 'list REMOVE takes effect' ($(if($rm.code -eq 200 -and -not $still){'PASS'}else{'FAIL'})) "http=$($rm.code) still_enabled=$still"
$changes = Req 'GET' '/api/v1/risk/changes?page=1&pageSize=10' $rsk.token $null
Rec 'F6' 'GET /risk/changes' ($(if($changes.code -eq 200){'PASS'}else{'FAIL'})) "http=$($changes.code)"

if ($evId) {
    $rid6 = [guid]::NewGuid().ToString()
    $dp = Req 'POST' "/api/v1/risk/alerts/$evId/dispose" $ops.token @{ decision = 'CONFIRM_RISK'; basis = 'e2e dispose'; requestId = $rid6 } $rid6
    Rec 'F7' 'POST alerts/{id}/dispose' ($(if($dp.code -eq 200){'PASS'}else{'FAIL'})) "http=$($dp.code) event=$evId"
} else {
    Rec 'F7' 'POST alerts/{id}/dispose' 'SKIP' 'no risk event in db'
}

if ($ruleId) {
    $rid7 = [guid]::NewGuid().ToString()
    $rc = Req 'PUT' "/api/v1/risk/rules/$ruleId" $rsk.token @{ depositSingleLimit = '20000.00'; reason = 'e2e submit'; requestId = $rid7 } $rid7
    $chId = $null; try { $chId = ($rc.body | ConvertFrom-Json).data.changeId } catch { }
    Rec 'F8' 'PUT /risk/rules/{id} submit' ($(if($rc.code -eq 200 -and $chId){'PASS'}else{'FAIL'})) "http=$($rc.code) change=$chId"
    if ($chId) {
        $rid8 = [guid]::NewGuid().ToString()
        $ap = Req 'POST' "/api/v1/risk/changes/$chId/approve" $appr.token @{ decision = 'REJECT'; note = 'e2e keep rule'; requestId = $rid8 } $rid8
        Rec 'F9' 'rule change REJECT by 2nd RISK' ($(if($ap.code -eq 200){'PASS'}else{'FAIL'})) "http=$($ap.code) change=$chId"
    } else { Rec 'F9' 'change approve' 'SKIP' 'no changeId' }
} else {
    Rec 'F8' 'PUT /risk/rules/{id}' 'SKIP' 'no ruleId'
    Rec 'F9' 'change approve' 'SKIP' 'no changeId'
}

# ---------- summary ----------
$total = $pass + $fail + $skip
$line = "SUMMARY total=$total pass=$pass fail=$fail skip=$skip log=$log"
Write-Host $line
$null = $rows.Add($line)
$rows | Set-Content -LiteralPath $log -Encoding UTF8
