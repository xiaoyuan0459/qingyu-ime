param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [string]$OutputDirectory = "build/native-device-checks"
)
$ErrorActionPreference = "Stop"
$testComponent = "com.qingyu.imemodule.test/com.qingyu.imemodule.service.ImeService"
$previousMethod = (& $Adb -d shell settings get secure default_input_method).Trim()
if ($previousMethod -notmatch '^[A-Za-z0-9_.$/]+$') { throw "Cannot preserve current input method" }
$enabled = (& $Adb -d shell settings get secure enabled_input_methods) -join ""
$wasEnabled = ($enabled -split ':') | Where-Object { ($_ -split ';')[0] -eq $testComponent }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function Invoke-NativeCheck([string]$Name, [string]$Classes) {
    & $Adb -d shell input keyevent KEYCODE_WAKEUP
    & $Adb -d shell input keyevent KEYCODE_MENU
    & $Adb -d shell wm dismiss-keyguard
    $path = Join-Path $OutputDirectory "$Name.log"
    & $Adb -d shell am instrument -w -e class $Classes com.qingyu.imemodule.test/androidx.test.runner.AndroidJUnitRunner > $path
    $exitCode = $LASTEXITCODE
    $report = Get-Content -LiteralPath $path -Raw
    if ($exitCode -ne 0 -or $report -notmatch 'OK \(\d+ tests?\)' -or $report -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Android checks failed: $Name; inspect $path"
    }
    Write-Output "$Name passed"
}

try {
    Invoke-NativeCheck "preference-migration" "com.qingyu.inputmethod.ScriptModeMigrationTest"
    Invoke-NativeCheck "input-and-storage" "com.qingyu.inputmethod.TypingLatencyTest,com.qingyu.inputmethod.NativeScriptIntegrationTest,com.qingyu.inputmethod.AssociationSessionTest,com.qingyu.inputmethod.CandidateStrokeLookupTest,com.qingyu.inputmethod.FuzzySpellingGateEngineTest,com.qingyu.inputmethod.CorrectionSegmentationEngineTest,com.qingyu.inputmethod.RimePackUpgradeTest"
    Invoke-NativeCheck "touch-and-settings" "com.qingyu.inputmethod.NativeTypingDeviceTest"
    & $Adb -d logcat -d -s QingyuLatency:I '*:S' > (Join-Path $OutputDirectory "latency.log")
} finally {
    $current = (& $Adb -d shell settings get secure default_input_method).Trim()
    if ($current -eq $testComponent) { & $Adb -d shell ime set $previousMethod | Out-Null }
    if (-not $wasEnabled) { & $Adb -d shell ime disable $testComponent | Out-Null }
    $restored = (& $Adb -d shell settings get secure default_input_method).Trim()
    if ($restored -ne $previousMethod) { throw "Previous input method was not restored" }
    Write-Output "Previous phone input method restored"
}
