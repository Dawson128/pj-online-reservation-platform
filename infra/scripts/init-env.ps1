param([switch]$AddServiceTokens)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envPath = Join-Path $repoRoot '.env'
$exists = Test-Path -LiteralPath $envPath
if ($exists -and -not $AddServiceTokens) { throw '.env already exists; use -AddServiceTokens to append only missing service tokens.' }
$serviceNames = @('G5_SERVICE_G1_TOKEN','G5_SERVICE_G3_TOKEN','G5_SERVICE_G4_TOKEN','G5_SERVICE_G6_TOKEN')
$names = if ($exists) { $serviceNames } else { @('POSTGRES_PASSWORD','APP_M1_PASSWORD','MIGRATE_M1_PASSWORD','APP_M2_PASSWORD','MIGRATE_M2_PASSWORD','APP_M3_PASSWORD','MIGRATE_M3_PASSWORD') + $serviceNames }
$existingNames = if ($exists) { @(Get-Content -LiteralPath $envPath | ForEach-Object { if ($_ -match '^([A-Z0-9_]+)=') { $Matches[1] } }) } else { @() }
$names = @($names | Where-Object { $_ -notin $existingNames })
$lines = foreach ($name in $names) {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  $name + '=' + ([BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())
}
if ($names.Count -eq 0) { Write-Output 'All service token keys already exist; no values changed.'; exit 0 }
if ($exists) { [System.IO.File]::AppendAllText($envPath, "`n" + ($lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false))) }
else { [System.IO.File]::WriteAllLines($envPath, $lines, (New-Object System.Text.UTF8Encoding($false))) }
Write-Output 'Local configuration prepared with distinct random credentials. Existing values were preserved; no values are printed.'
