#requires -Version 7.0
param(
    [string]$CredentialFile = 'F:\PostgreSQL\private\pj-dev-credential.xml',
    [string]$JdkHome,
    [int]$WebPort = 5177,
    [int]$BackendPort = 18102,
    [int]$ManagementPort = 18182,
    [string]$DatabaseUrl = 'jdbc:postgresql://127.0.0.1:5432/pj_g5_migration_dev',
    [switch]$OpenBrowser
)

$ErrorActionPreference = 'Stop'
$campusRoot = Split-Path -Parent $PSScriptRoot
$campusStateFile = Join-Path $campusRoot '.local\campus-processes.json'
$campusUrl = "https://127.0.0.1:$WebPort"
if (Test-Path -LiteralPath $campusStateFile) {
    $campusPrevious = Get-Content -LiteralPath $campusStateFile -Raw | ConvertFrom-Json
    $campusBothAlive = $true
    foreach ($campusEntry in @($campusPrevious.backend, $campusPrevious.web)) {
        $campusExisting = Get-Process -Id $campusEntry.pid -ErrorAction SilentlyContinue
        if (-not $campusExisting -or $campusExisting.StartTime.ToUniversalTime().Ticks -ne [long]$campusEntry.startTicks) { $campusBothAlive = $false }
    }
    if ($campusBothAlive) { Write-Output "Campus is already running: $campusUrl"; return }
    & (Join-Path $PSScriptRoot 'Stop-CampusLocal.ps1')
}
foreach ($campusPort in @($BackendPort, $ManagementPort, $WebPort)) {
    $campusSocket = [Net.Sockets.TcpClient]::new()
    try {
        $campusAttempt = $campusSocket.ConnectAsync('127.0.0.1', $campusPort)
        try { $null = $campusAttempt.Wait(300) } catch { }
        if ($campusSocket.Connected) { throw "Port $campusPort is already in use. No existing process was stopped." }
    } finally { $campusSocket.Dispose() }
}
if (-not $JdkHome) { $JdkHome = Join-Path $campusRoot '.local\tools\jdk-21.0.13+9' }
$campusJar = Join-Path $campusRoot 'services\approval\target\approval-service-0.1.0-SNAPSHOT.jar'
$campusVite = Join-Path $campusRoot 'node_modules\vite\bin\vite.js'
if (-not (Test-Path -LiteralPath $campusJar)) { throw 'Build the approval backend first; see apps/web/README.md.' }
if (-not (Test-Path -LiteralPath $campusVite)) { throw 'Install frontend dependencies with npm ci at the repository root first.' }
. (Join-Path $PSScriptRoot 'Use-ApprovalLocal.ps1') -CredentialFile $CredentialFile -JdkHome $JdkHome -DatabaseUrl $DatabaseUrl
. (Join-Path $PSScriptRoot 'Use-ApprovalAdmin.ps1')
$env:APP_BIND_ADDRESS = '127.0.0.1'
$env:APP_PORT = [string]$BackendPort
$env:MANAGEMENT_PORT = [string]$ManagementPort
New-Item -ItemType Directory -Path (Join-Path $campusRoot '.local') -Force | Out-Null
$campusBackend = $null
$campusWeb = $null
try {
    $campusBackend = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-jar', ('"' + $campusJar + '"')) `
        -WorkingDirectory $campusRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $campusRoot '.local\campus-backend.log') -RedirectStandardError (Join-Path $campusRoot '.local\campus-backend-error.log')
    $campusReady = $false
    $campusDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $campusDeadline) {
        if ($campusBackend.HasExited) { throw 'Backend exited. See .local/campus-backend.log.' }
        try { if ((Invoke-RestMethod "http://127.0.0.1:$ManagementPort/actuator/health/readiness" -TimeoutSec 2).status -eq 'UP') { $campusReady = $true; break } } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $campusReady) { throw 'Backend readiness timed out.' }
    # The frontend child only needs a loopback proxy target, never database/bootstrap/service secrets.
    $campusSavedEnvironment = @{}
    $campusPrivateNames = @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','MIGRATION_DATABASE_URL','MIGRATION_USERNAME','MIGRATION_PASSWORD','DB_URL','DB_USERNAME','DB_PASSWORD','DB_MIGRATION_URL','DB_MIGRATION_USERNAME','DB_MIGRATION_PASSWORD','G5_BOOTSTRAP_PASSWORD','G5_IDEMPOTENCY_HMAC_KEY','G4_SERVICE_G5_TOKEN','G6_SERVICE_G5_TOKEN','G5_SERVICE_G1_TOKEN','G5_SERVICE_G2_TOKEN','G5_SERVICE_G3_TOKEN','G5_SERVICE_G4_TOKEN','G5_SERVICE_G6_TOKEN')
    try {
        foreach ($campusName in $campusPrivateNames) {
            $campusSavedEnvironment[$campusName] = [Environment]::GetEnvironmentVariable($campusName, 'Process')
            Remove-Item -LiteralPath "Env:$campusName" -ErrorAction SilentlyContinue
        }
        $campusOldProxy = $env:APPROVAL_PROXY_TARGET
        $env:APPROVAL_PROXY_TARGET = "http://127.0.0.1:$BackendPort"
        $campusWeb = Start-Process -FilePath (Get-Command node -ErrorAction Stop).Source -ArgumentList @(('"' + $campusVite + '"'), '--host', '127.0.0.1', '--port', [string]$WebPort) `
            -WorkingDirectory (Join-Path $campusRoot 'apps\web') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $campusRoot '.local\campus-web.log') -RedirectStandardError (Join-Path $campusRoot '.local\campus-web-error.log')
    } finally {
        foreach ($campusName in $campusPrivateNames) { if ($null -eq $campusSavedEnvironment[$campusName]) { Remove-Item -LiteralPath "Env:$campusName" -ErrorAction SilentlyContinue } else { [Environment]::SetEnvironmentVariable($campusName, $campusSavedEnvironment[$campusName], 'Process') } }
        $env:APPROVAL_PROXY_TARGET = $campusOldProxy
    }
    $campusReady = $false
    $campusDeadline = [DateTime]::UtcNow.AddSeconds(40)
    while ([DateTime]::UtcNow -lt $campusDeadline) {
        if ($campusWeb.HasExited) { throw 'Frontend exited. See .local/campus-web-error.log.' }
        try { if ((Invoke-WebRequest $campusUrl -SkipCertificateCheck -TimeoutSec 2).StatusCode -eq 200) { $campusReady = $true; break } } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $campusReady) { throw 'Frontend startup timed out.' }
    @{
        root = $campusRoot; url = $campusUrl
        backend = @{ pid = $campusBackend.Id; startTicks = $campusBackend.StartTime.ToUniversalTime().Ticks }
        web = @{ pid = $campusWeb.Id; startTicks = $campusWeb.StartTime.ToUniversalTime().Ticks }
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $campusStateFile -Encoding UTF8
    Write-Output "Campus is running: $campusUrl"
    Write-Output 'Local HTTPS uses a self-signed certificate. See apps/web/README.md for the first visit.'
    if ($OpenBrowser) { Start-Process $campusUrl }
} catch {
    foreach ($campusProcess in @($campusWeb, $campusBackend)) {
        if ($campusProcess -and -not $campusProcess.HasExited) { Stop-Process -Id $campusProcess.Id }
    }
    throw
}
