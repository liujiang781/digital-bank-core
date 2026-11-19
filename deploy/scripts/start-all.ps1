# Requires: Windows PowerShell 5.1+, JDK 21, built backend (mvnw clean install)
# Usage: powershell -ExecutionPolicy Bypass -File deploy/scripts/start-all.ps1
param(
    [string]$BackendDir = '',
    [string]$EnvFile = '',
    [string]$GatewayPort = '8085',
    [int]$TimeoutSec = 180
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')

if (-not $BackendDir) {
    $candidate = Join-Path $root 'backend'
    if (Test-Path (Join-Path $candidate 'mvnw.cmd')) { $BackendDir = $candidate }
    else { throw "backend not found. Run this script from <repo>/deploy/scripts or pass -BackendDir." }
}
if (-not (Test-Path (Join-Path $BackendDir 'mvnw.cmd'))) { throw "mvnw.cmd not found in $BackendDir" }

if (-not $EnvFile) {
    foreach ($p in @((Join-Path $root '.env.local'), (Join-Path $root '.env'), (Join-Path $root 'deploy\.env.local'), (Join-Path $root 'deploy\.env'))) {
        if (Test-Path $p) { $EnvFile = $p; break }
    }
}
if ($EnvFile -and (Test-Path $EnvFile)) {
    Get-Content $EnvFile | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*\s*=' } | ForEach-Object {
        $kv = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process')
    }
    Write-Host "Loaded env: $EnvFile"
} else {
    Write-Warning 'No env file found; services will use application.yml defaults.'
}

$services = @(
    @{ Name = 'user-service';        Port = 8081; Health = '/api/v1/user/health' },
    @{ Name = 'account-service';     Port = 8082; Health = '/api/v1/account/health' },
    @{ Name = 'transaction-service'; Port = 8083; Health = '/api/v1/transaction/health' },
    @{ Name = 'risk-service';        Port = 8084; Health = '/api/v1/risk/health' }
)

function Wait-Health([string]$url, [int]$timeout) {
    $deadline = (Get-Date).AddSeconds($timeout)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 3
    }
    return $false
}

if ($env:SERVER_PORT) {
    Write-Warning "Ignoring inherited SERVER_PORT=$env:SERVER_PORT; each service is pinned to its own port."
    Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue
}

$started = @()
foreach ($s in $services) {
    $title = "digital-bank-$($s.Name)"
    Start-Process -FilePath 'cmd.exe' -WorkingDirectory $BackendDir `
        -ArgumentList '/c', "title $title && set SERVER_PORT=$($s.Port) && .\mvnw.cmd -pl $($s.Name) spring-boot:run" | Out-Null
    $started += $s
    Write-Host "Started $($s.Name) on port $($s.Port)"
}

Start-Process -FilePath 'cmd.exe' -WorkingDirectory $BackendDir `
    -ArgumentList '/c', "title digital-bank-gateway && set SERVER_PORT=$GatewayPort && .\mvnw.cmd -pl gateway spring-boot:run" | Out-Null
Write-Host "Started gateway on port $GatewayPort"

$failed = @()
foreach ($s in $started) {
    if (Wait-Health "http://localhost:$($s.Port)$($s.Health)" $TimeoutSec) {
        Write-Host "[UP]   $($s.Name)" -ForegroundColor Green
    } else {
        Write-Host "[DOWN] $($s.Name)" -ForegroundColor Red
        $failed += $s.Name
    }
}
if (Wait-Health "http://localhost:$GatewayPort/actuator/health" $TimeoutSec) {
    Write-Host "[UP]   gateway" -ForegroundColor Green
} else {
    Write-Host "[DOWN] gateway" -ForegroundColor Red
    $failed += 'gateway'
}

if ($failed.Count -gt 0) {
    Write-Host ("Failed: " + ($failed -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host "All services UP. Health only, not business acceptance." -ForegroundColor Green
