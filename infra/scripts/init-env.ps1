$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envPath = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $envPath) { throw '.env already exists; it was not overwritten.' }
$names = @('POSTGRES_PASSWORD','APP_M1_PASSWORD','MIGRATE_M1_PASSWORD','APP_M2_PASSWORD','MIGRATE_M2_PASSWORD','APP_M3_PASSWORD','MIGRATE_M3_PASSWORD')
$lines = foreach ($name in $names) {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  $name + '=' + ([BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())
}
[System.IO.File]::WriteAllLines($envPath, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created local .env with independent random passwords. Values are not printed.'
