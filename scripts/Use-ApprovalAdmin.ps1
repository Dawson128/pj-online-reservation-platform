param([string]$SecretFile)

if ($MyInvocation.InvocationName -ne '.') {
    throw 'Dot-source this file: . .\scripts\Use-ApprovalAdmin.ps1'
}
$approvalAdminRoot = Split-Path -Parent $PSScriptRoot
if (-not $SecretFile) { $SecretFile = Join-Path $approvalAdminRoot '.local\approval-admin-credential.xml' }
if (-not (Test-Path -LiteralPath $SecretFile)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $SecretFile) -Force | Out-Null
    $approvalAdminRandom = New-Object byte[] 18
    $approvalAdminGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $approvalAdminGenerator.GetBytes($approvalAdminRandom) } finally { $approvalAdminGenerator.Dispose() }
    $approvalAdminSecure = ConvertTo-SecureString ([Convert]::ToBase64String($approvalAdminRandom)) -AsPlainText -Force
    [PSCredential]::new('admin', $approvalAdminSecure) | Export-Clixml -LiteralPath $SecretFile
    Remove-Variable approvalAdminRandom,approvalAdminSecure
}
$approvalAdminCredential = Import-Clixml -LiteralPath $SecretFile -ErrorAction Stop
if ($approvalAdminCredential -isnot [PSCredential]) { throw 'A Windows-encrypted PSCredential file is required.' }
$env:G5_BOOTSTRAP_ENABLED = 'true'
$env:G5_BOOTSTRAP_ACCOUNT = $approvalAdminCredential.UserName
$env:G5_BOOTSTRAP_PASSWORD = $approvalAdminCredential.GetNetworkCredential().Password
Remove-Variable approvalAdminCredential
$approvalHmacPath = Join-Path $approvalAdminRoot '.local\g5-idempotency-credential.xml'
if (-not (Test-Path -LiteralPath $approvalHmacPath)) {
    $approvalHmacSecure = ConvertTo-SecureString ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))) -AsPlainText -Force
    [PSCredential]::new('g5-idempotency', $approvalHmacSecure) | Export-Clixml -LiteralPath $approvalHmacPath
    Remove-Variable approvalHmacSecure
}
$approvalHmacCredential = Import-Clixml -LiteralPath $approvalHmacPath
if ($approvalHmacCredential -isnot [PSCredential]) { throw 'An encrypted G5 idempotency credential is required.' }
$env:G5_IDEMPOTENCY_HMAC_KEY = $approvalHmacCredential.GetNetworkCredential().Password
Remove-Variable approvalHmacCredential
Write-Host 'Initial administrator secret loaded for this terminal; existing database passwords are never reset by bootstrap.'
