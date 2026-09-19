param(
    [string]$Godot = $env:GODOT_PATH,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Save = '',
    [int]$Port = 17321,
    [switch]$SkipBuild,
    [switch]$Smoke,
    [switch]$Headless
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'Unciv-master'
$assets = Join-Path $sourceRoot 'android\assets'
$local = Join-Path $projectRoot '.local'

function Get-JdkMajorVersion([string]$HomePath) {
    if (-not $HomePath -or -not (Test-Path "$HomePath\bin\java.exe") -or
        -not (Test-Path "$HomePath\bin\javac.exe")) { return 0 }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $version = (& "$HomePath\bin\javac.exe" -version 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -and $version -match '\bjavac\s+(\d+)') { return [int]$Matches[1] }
        return 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}
$previousJava = $env:JAVA_HOME
$previousToken = $env:UNCIV_GATEWAY_TOKEN
$previousPort = $env:UNCIV_GATEWAY_PORT
$previousSave = $env:UNCIV_INITIAL_SAVE
$kernel = $null
$frontend = $null
$exitCode = 0
try {
    if (-not (Test-Path "$sourceRoot\gradlew.bat") -or -not (Test-Path "$assets\jsons")) {
        throw "Upstream source is incomplete. Expected Unciv-master beside run.ps1: $sourceRoot"
    }
    if ($Headless -and -not $Smoke) { throw '-Headless requires -Smoke.' }
    if (-not $JavaHome) {
        $candidates = @()
        $installedJava = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($installedJava) { $candidates += Split-Path (Split-Path $installedJava.Source -Parent) -Parent }
        $patterns = @(
            "$projectRoot\.tools\jdk*\bin\javac.exe",
            'D:\Program Files\jdk-*\bin\javac.exe',
            "$env:USERPROFILE\.qoder\extensions\redhat.java-*\jre\*\bin\javac.exe",
            "$env:ProgramFiles\Java\*\bin\javac.exe",
            "$env:ProgramFiles\Eclipse Adoptium\*\bin\javac.exe",
            "$env:ProgramFiles\Microsoft\jdk-*\bin\javac.exe"
        )
        $candidates += Get-ChildItem -Path $patterns -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | ForEach-Object { Split-Path (Split-Path $_.FullName -Parent) -Parent }
        foreach ($candidate in $candidates) {
            if ((Get-JdkMajorVersion $candidate) -ge 21) { $JavaHome = $candidate; break }
        }
    }
    if ((Get-JdkMajorVersion $JavaHome) -lt 21) {
        throw 'JDK 21+ with java.exe and javac.exe required. Set JAVA_HOME or pass -JavaHome <JDK directory>.'
    }
    $JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
    if (-not $Godot) {
        $installedGodot = Get-Command godot.exe -ErrorAction SilentlyContinue
        if ($installedGodot) { $Godot = $installedGodot.Source }
        else {
            $patterns = @(
                "$projectRoot\.tools\godot\Godot*.exe",
                'D:\Program Files\bin\Godot*.exe',
                'D:\software\godot\Godot*.exe',
                'D:\software\godot\*\Godot*.exe',
                "$env:ProgramFiles\Godot\Godot*.exe"
            )
            $candidate = Get-ChildItem -Path $patterns -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike '*console*' } |
                Sort-Object FullName -Descending | Select-Object -First 1
            if ($candidate) { $Godot = $candidate.FullName }
        }
    }
    if (-not $Godot -or -not (Test-Path -LiteralPath $Godot -PathType Leaf)) {
        throw 'Godot executable not found. Set GODOT_PATH or pass -Godot <Godot executable>.'
    }
    $Godot = (Resolve-Path -LiteralPath $Godot).Path
    $env:JAVA_HOME = $JavaHome
    if (-not $SkipBuild) {
        & "$sourceRoot\gradlew.bat" -p $sourceRoot :godot-kernel:installDist :godot-kernel:test --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Kernel build/tests failed.' }
    }
    $libs = Join-Path $PSScriptRoot 'kernel\build\install\godot-kernel\lib\*'
    if (-not (Test-Path (Split-Path $libs -Parent))) { throw 'Build first: run.ps1 without -SkipBuild.' }
    if ($Smoke -and -not (Test-Path "$local\tests\settlement-promise.json")) {
        throw 'Smoke fixture is missing. Run run.ps1 without -SkipBuild to generate it.'
    }
    New-Item -ItemType Directory -Force $local | Out-Null
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $env:UNCIV_GATEWAY_TOKEN = [Convert]::ToBase64String($bytes)
    $env:UNCIV_GATEWAY_PORT = [string]$Port
    $env:UNCIV_INITIAL_SAVE = if ($Save) { (Resolve-Path $Save).Path } else { '' }
    $kernel = Start-Process -FilePath "$JavaHome\bin\java.exe" -PassThru -WindowStyle Hidden `
        -WorkingDirectory $assets `
        -ArgumentList @('-Xmx2g', '-cp', "`"$libs`"", 'com.unciv.godot.GatewayMainKt', '--root', "`"$projectRoot`"", '--port', "$Port") `
        -RedirectStandardOutput "$local\kernel.log" -RedirectStandardError "$local\kernel-error.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($kernel.HasExited) { throw "Kernel exited. See $local\kernel-error.log" }
        try {
            $hello = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api" -Method Post `
                -ContentType 'application/json' -Headers @{ Authorization = "Bearer $env:UNCIV_GATEWAY_TOKEN" } `
                -Body '{"protocol":1,"action":"hello"}' -TimeoutSec 2
            if ($hello.ok) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'Kernel did not become ready; check .local/kernel-error.log.' }
    $godotArgs = @('--path', "`"$PSScriptRoot`"")
    if ($Headless) { $godotArgs += '--headless' }
    if ($Smoke) { $godotArgs += @('--', '--smoke') }
    $startedAt = [DateTime]::UtcNow
    $frontend = Start-Process -FilePath $Godot -ArgumentList $godotArgs -PassThru `
        -RedirectStandardOutput "$local\godot.log" -RedirectStandardError "$local\godot-error.log"
    # PowerShell 5 重定向输出时需保留进程句柄，否则退出码可能为 null。
    $frontendHandle = $frontend.Handle
    if ($Smoke -and -not $frontend.WaitForExit(210000)) {
        throw 'Godot smoke timed out. See .local/godot-error.log.'
    }
    # 无参等待也确保重定向输出已经排空。
    $frontend.WaitForExit()
    $exitCode = $frontend.ExitCode
    if ($null -eq $exitCode) { throw 'Godot exit code unavailable. See .local/godot-error.log.' }
    if ($Smoke) {
        if ($exitCode -ne 0) { throw "Godot smoke failed (exit $exitCode). See .local/godot-error.log." }
        $reportPath = Join-Path $local 'smoke-result.json'
        if (-not (Test-Path $reportPath) -or (Get-Item $reportPath).LastWriteTimeUtc -lt $startedAt) {
            throw 'Godot exited without a fresh smoke report. See .local/godot-error.log.'
        }
        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($report.ok -ne $true) { throw 'Godot smoke report did not indicate success.' }
        Write-Host "Smoke passed. Results: $reportPath"
    }
} finally {
    if ($frontend -and -not $frontend.HasExited) { Stop-Process -Id $frontend.Id }
    if ($kernel -and -not $kernel.HasExited) { Stop-Process -Id $kernel.Id }
    $env:JAVA_HOME = $previousJava
    $env:UNCIV_GATEWAY_TOKEN = $previousToken
    $env:UNCIV_GATEWAY_PORT = $previousPort
    $env:UNCIV_INITIAL_SAVE = $previousSave
}
exit $exitCode
