# Installs a daily Task Scheduler job that runs the IUSER usage monitor
# silently (pythonw, no console window) with rotation enabled.
#
# Usage (run in PowerShell as Administrator, from this folder):
#   .\install_task.ps1
#
# Remove it later with:
#   .\install_task.ps1 -Uninstall

param(
    [switch]$Uninstall
)

$TaskName = "IUSERUsageMonitor"
$ScriptPath = Join-Path $PSScriptRoot "usage_monitor.py"

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Task '$TaskName' removed."
    exit 0
}

# Pick pythonw (no console window) when available, otherwise python.
$Python = Get-Command pythonw.exe -ErrorAction SilentlyContinue
if (-not $Python) {
    $Python = Get-Command python.exe -ErrorAction SilentlyContinue
}
if (-not $Python) {
    Write-Error "Python not found on PATH. Install Python 3.10+ first."
    exit 1
}
$PythonPath = $Python.Source

if (-not (Test-Path $ScriptPath)) {
    Write-Error "usage_monitor.py not found next to this script."
    exit 1
}
if (-not (Test-Path (Join-Path $PSScriptRoot "config.json"))) {
    Write-Error "config.json not found. Copy config.example.json to config.json and fill in your details first."
    exit 1
}

$Action = New-ScheduledTaskAction `
    -Execute $PythonPath `
    -Argument "`"$ScriptPath`"" `
    -WorkingDirectory $PSScriptRoot

# Run every day at 10:00; the script's cooldown prevents rotating too often.
$Trigger = New-ScheduledTaskTrigger -Daily -At 10:00AM

$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $Action `
    -Trigger $Trigger `
    -Settings $Settings `
    -Description "Checks IUT IUSER usage and rotates TP-Link PPPoE credentials when over the limit" `
    -Force | Out-Null

Write-Host "Task '$TaskName' installed. Runs daily at 10:00 AM."
Write-Host "Test it now with:  python `"$ScriptPath`" --check"