param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenPath = 'mvn',
    [string]$Settings,
    [string]$MavenRepository,
    [string]$PythonPath = 'python',
    [switch]$SkipHttp
)
$ErrorActionPreference = 'Stop'
$exp4Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
    throw '请通过 -JavaHome 指定 JDK 21，或设置 JAVA_HOME。'
}
$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
$exp4Version = & (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | Out-String
if ($exp4Version -notmatch 'version "21\.') { throw '本项目要求 JDK 21。' }
$exp4Evidence = Join-Path $exp4Repo 'docs/04-测试报告/证据/2026-11-17'
New-Item -ItemType Directory -Force $exp4Evidence | Out-Null
$exp4Args = @('-fae', 'test', '-Dstyle.color=never', '-l', (Join-Path $exp4Evidence 'maven-reproduce.log'))
if ($Settings) { $exp4Args += @('-s', (Resolve-Path $Settings).Path) }
if ($MavenRepository) { $exp4Args += "-Dmaven.repo.local=$MavenRepository" }
Push-Location (Join-Path $exp4Repo 'backend')
try {
    & $MavenPath @exp4Args
    $exp4MavenExit = $LASTEXITCODE
} finally { Pop-Location }
if ($exp4MavenExit -ne 0) { throw "Maven 测试失败，查看 $exp4Evidence/maven-reproduce.log；不要沿用上次结果。" }
& $PythonPath (Join-Path $PSScriptRoot 'collect-exp4-evidence.py')
if ($LASTEXITCODE -ne 0) { throw '汇总 JUnit 证据失败。' }
if (-not $SkipHttp) {
    & $PythonPath (Join-Path $PSScriptRoot 'test-exp4-s1-http.py') --java (Join-Path $JavaHome 'bin/java.exe')
    if ($LASTEXITCODE -ne 0) { throw 'HTTP 联调失败，请检查 s1-http-results.json。' }
}
Write-Output '测试执行完成；结果以本次生成的 JSON 和 Maven 日志为准。'
