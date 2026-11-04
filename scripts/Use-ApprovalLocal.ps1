param(
    [Parameter(Mandatory = $true)]
    [string]$CredentialFile,
    [string]$DatabaseUrl = 'jdbc:postgresql://127.0.0.1:5432/pj_local',
    [string]$JdkHome
)

# Dot-source this helper to configure only the current PowerShell process.
if ($MyInvocation.InvocationName -ne '.') {
    throw 'Dot-source this file: . .\scripts\Use-ApprovalLocal.ps1 -CredentialFile <private-file>'
}

$approvalRepositoryRoot = Split-Path -Parent $PSScriptRoot
$approvalCredential = Import-Clixml -LiteralPath $CredentialFile -ErrorAction Stop
if ($approvalCredential -isnot [System.Management.Automation.PSCredential]) {
    throw 'The private file must contain an encrypted PowerShell PSCredential.'
}

if ($JdkHome) {
    $approvalJavaPath = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $approvalJavaPath -PathType Leaf)) {
        throw 'JdkHome must point to an extracted Windows JDK 21.'
    }
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkHome).Path
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
}

# Explicit local helper selects the legacy aliases; inherited deployment variables must not override the chosen isolated database.
foreach ($approvalName in @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','MIGRATION_DATABASE_URL','MIGRATION_USERNAME','MIGRATION_PASSWORD','SERVER_ADDRESS','PORT')) { Remove-Item -LiteralPath "Env:$approvalName" -ErrorAction SilentlyContinue }
$env:DB_URL = $DatabaseUrl
$env:DB_USERNAME = $approvalCredential.UserName
$env:DB_PASSWORD = $approvalCredential.GetNetworkCredential().Password

# Local development only: the existing development account owns this database.
# Test/production environments must supply separate restricted runtime/migration accounts.
$env:DB_MIGRATION_URL = $DatabaseUrl
$env:DB_MIGRATION_USERNAME = $env:DB_USERNAME
$env:DB_MIGRATION_PASSWORD = $env:DB_PASSWORD

if (-not $env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME = Join-Path $approvalRepositoryRoot '.local\maven-user-home' }
$approvalRepositoryOption = '-Dmaven.repo.local="' + (Join-Path $approvalRepositoryRoot '.local\maven-repository') + '"'
if ($env:MAVEN_OPTS -notlike '*-Dmaven.repo.local=*') {
    $env:MAVEN_OPTS = ($env:MAVEN_OPTS + ' ' + $approvalRepositoryOption).Trim()
}

Remove-Variable approvalCredential -ErrorAction SilentlyContinue
Write-Host 'Approval local environment loaded for this terminal; credentials were not printed or saved.'
