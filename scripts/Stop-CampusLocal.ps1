#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$campusStopRoot = Split-Path -Parent $PSScriptRoot
$campusStopState = Join-Path $campusStopRoot '.local\campus-processes.json'
if (-not (Test-Path -LiteralPath $campusStopState)) { Write-Output 'No launcher-owned campus process record.'; return }
$campusStopRecord = Get-Content -LiteralPath $campusStopState -Raw | ConvertFrom-Json
if ($campusStopRecord.root -ne $campusStopRoot) { throw 'Process record belongs to a different checkout.' }
$campusStopTargets = @()
foreach ($campusStopEntry in @($campusStopRecord.web, $campusStopRecord.backend)) {
    $campusStopProcess = Get-Process -Id $campusStopEntry.pid -ErrorAction SilentlyContinue
    if (-not $campusStopProcess) { continue }
    if ($campusStopProcess.StartTime.ToUniversalTime().Ticks -ne [long]$campusStopEntry.startTicks) {
        throw 'Recorded PID has been reused. No process was stopped.'
    }
    $campusStopCommand = (Get-CimInstance Win32_Process -Filter "ProcessId=$($campusStopEntry.pid)").CommandLine
    if (-not $campusStopCommand -or $campusStopCommand.IndexOf($campusStopRoot, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw 'Process command does not belong to this checkout. No process was stopped.'
    }
    $campusStopTargets += $campusStopProcess
}
foreach ($campusStopProcess in $campusStopTargets) { Stop-Process -Id $campusStopProcess.Id }
Remove-Item -LiteralPath $campusStopState
Write-Output 'Launcher-owned frontend/backend stopped. PostgreSQL remains running.'
