param(
    [string]$PostgresRoot = 'F:\PostgreSQL',
    [string]$JdkHome,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$campusTestRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) { $JdkHome = Join-Path $campusTestRoot '.local\tools\jdk-21.0.13+9' }
$campusTestDatabase = 'pj_g5_auth_it_web_' + (Get-Date -AsUTC -Format 'yyyyMMdd_HHmmss') + '_' + ([guid]::NewGuid().ToString('N').Substring(0, 6))
$campusManagementDatabase = $campusTestDatabase + '_management'
if ($campusTestDatabase -notmatch '^pj_g5_auth_it_web_[a-zA-Z0-9_]+$') { throw 'Invalid isolated test database name.' }
$campusPreviousLocation = Get-Location
try {
    Set-Location -LiteralPath $campusTestRoot
    & (Join-Path $PostgresRoot 'connect.ps1') -Admin -Command "CREATE DATABASE $campusTestDatabase OWNER pj_dev;"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the isolated browser test database.' }
    & (Join-Path $PostgresRoot 'connect.ps1') -Admin -Command "CREATE DATABASE $campusManagementDatabase OWNER pj_dev;"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the isolated management browser database.' }
    . (Join-Path $PSScriptRoot 'Use-ApprovalLocal.ps1') `
        -CredentialFile (Join-Path $PostgresRoot 'private\pj-dev-credential.xml') `
        -JdkHome $JdkHome -DatabaseUrl "jdbc:postgresql://127.0.0.1:5432/$campusTestDatabase"
    $env:PSQL_BIN = Join-Path $PostgresRoot '17\bin\psql.exe'
    $env:WEB_MANAGEMENT_DB_URL = "jdbc:postgresql://127.0.0.1:5432/$campusManagementDatabase"
    $env:npm_config_cache = Join-Path $campusTestRoot '.local\npm-cache'
    New-Item -ItemType Directory -Path '.local\web-e2e' -Force | Out-Null
    $campusTestDatabase | Set-Content -LiteralPath '.local\web-e2e\database-name.txt' -Encoding UTF8
    $campusAppJar = Join-Path $campusTestRoot 'services\approval\target\approval-service-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $campusAppJar)) {
        & '.\services\approval\mvnw.cmd' -f '.\services\approval\pom.xml' -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    Set-Location -LiteralPath (Join-Path $campusTestRoot 'apps\web')
    if (-not $SkipBuild) {
        npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    }
    npm run test:e2e
    if ($LASTEXITCODE -ne 0) { throw 'Real browser authentication acceptance failed; inspect the local report.' }
} finally {
    Set-Location -LiteralPath $campusPreviousLocation
}
