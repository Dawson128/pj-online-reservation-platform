#requires -Version 7.0
param([string]$PostgresRoot='F:\PostgreSQL',[string]$JdkHome,[string]$EvidenceDirectory='.local/g5-acceptance',[string]$TestPattern)
$ErrorActionPreference='Stop'
$managementTestRoot=Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) { $JdkHome=Join-Path $managementTestRoot '.local/tools/jdk-21.0.13+9' }
$managementTestSuffix=(Get-Date -AsUTC -Format 'yyyyMMdd_HHmmss')+'_'+([guid]::NewGuid().ToString('N').Substring(0,6))
$managementAuthDatabase='pj_g5_auth_it_auth_'+$managementTestSuffix
$managementDirectoryDatabase='pj_g5_auth_it_directory_'+$managementTestSuffix
$managementConfigDatabase='pj_g5_auth_it_config_'+$managementTestSuffix
$managementOutboxDatabase='pj_g5_auth_it_outbox_'+$managementTestSuffix
$managementBusinessDatabase='pj_g5_auth_it_business_'+$managementTestSuffix
$managementResourceFactsDatabase='pj_g5_auth_it_resource_facts_'+$managementTestSuffix
$managementPreviousLocation=Get-Location
try {
    Set-Location -LiteralPath $managementTestRoot
    foreach ($managementTestDatabase in @($managementAuthDatabase,$managementDirectoryDatabase,$managementConfigDatabase,$managementResourceFactsDatabase,$managementBusinessDatabase,$managementOutboxDatabase)) {
        & (Join-Path $PostgresRoot 'connect.ps1') -Admin -Command "CREATE DATABASE $managementTestDatabase OWNER pj_dev;"
        if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated G5 acceptance databases.' }
    }
    . (Join-Path $PSScriptRoot 'Use-ApprovalLocal.ps1') -CredentialFile (Join-Path $PostgresRoot 'private/pj-dev-credential.xml') -DatabaseUrl "jdbc:postgresql://127.0.0.1:5432/$managementAuthDatabase" -JdkHome $JdkHome
    $env:G5_MANAGEMENT_TEST_DB_URL="jdbc:postgresql://127.0.0.1:5432/$managementDirectoryDatabase"
    $env:G5_CONFIG_TEST_DB_URL="jdbc:postgresql://127.0.0.1:5432/$managementConfigDatabase"
    $env:G5_RESOURCE_FACTS_TEST_DB_URL="jdbc:postgresql://127.0.0.1:5432/$managementResourceFactsDatabase"
    $env:G5_BUSINESS_TEST_DB_URL="jdbc:postgresql://127.0.0.1:5432/$managementBusinessDatabase"
    $env:G5_OUTBOX_TEST_DB_URL="jdbc:postgresql://127.0.0.1:5432/$managementOutboxDatabase"
    New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
    @($managementAuthDatabase,$managementDirectoryDatabase,$managementConfigDatabase,$managementResourceFactsDatabase,$managementBusinessDatabase,$managementOutboxDatabase) | Set-Content (Join-Path $EvidenceDirectory 'database-names.txt')
    $managementMavenArguments=@()
    if ($TestPattern) { $managementMavenArguments+="-Dit.test=$TestPattern" }
    & '.\services\approval\mvnw.cmd' -f '.\services\approval\pom.xml' -B -ntp -Ppostgres-it @managementMavenArguments verify
    if ($LASTEXITCODE -ne 0) { throw 'G5 authentication/management acceptance failed.' }
} finally { Set-Location -LiteralPath $managementPreviousLocation }
