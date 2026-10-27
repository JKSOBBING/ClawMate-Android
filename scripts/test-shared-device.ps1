param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [string]$EvidenceName = 'shared-device-regression'
)
$ErrorActionPreference = 'Stop'
if ($EvidenceName -notmatch '^[a-z0-9-]+$') { throw 'EvidenceName must contain lowercase letters, digits and hyphens' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $repoRoot ".arkui-x-validation/application/$EvidenceName"
if (Test-Path -LiteralPath $evidence) { throw 'Choose a new evidence name; previous results are preserved' }
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$devices = & $adbPath devices
if (@($devices | Where-Object { $_ -match '^\S+\s+device$' }).Count -ne 1) { throw 'Connect exactly one authorized test device' }
$policy = & $adbPath shell dumpsys window policy
if ($policy -match '^\s+showing=true$') { throw 'Unlock the test phone manually before running UI fixtures' }
New-Item -ItemType Directory -Path $evidence | Out-Null
foreach ($relative in @('shared-app/build/outputs/apk/debug/shared-app-debug.apk', 'shared-app/build/outputs/apk/androidTest/debug/shared-app-debug-androidTest.apk')) {
    $apk = (Resolve-Path -LiteralPath (Join-Path $repoRoot $relative)).Path
    & $adbPath install -r $apk *>> (Join-Path $evidence 'install.log')
    if ($LASTEXITCODE -ne 0) { throw "Installation failed: $relative" }
}
# These UI fixtures replace preference files before launch. A cold target process
# is required between groups so ArkTS singleton settings cannot outlive a fixture.
$groups = @(
    @{ Name='native-meeting'; Count=7; Classes='com.clawmate.shared.NativeCapabilitiesDeviceTest,com.clawmate.shared.MeetingIntegrationDeviceTest' },
    @{ Name='chat'; Count=1; Classes='com.clawmate.shared.ChatContextDeviceTest' },
    @{ Name='workflow'; Count=1; Classes='com.clawmate.shared.WorkflowModelDeviceTest#actualTaskConfirmationCancellationAndUnavailableModelNeverWriteSuccessfulSummary' }
)
try { foreach ($group in $groups) {
    & $adbPath shell am force-stop com.clawmate.shared
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stop the target test process' }
    $file = Join-Path $evidence ($group.Name + '.log')
    & $adbPath shell am instrument -w -r -e class $group.Classes com.clawmate.shared.test/androidx.test.runner.AndroidJUnitRunner *> $file
    $adbExit = $LASTEXITCODE
    $result = Get-Content -LiteralPath $file -Raw
    $expected = 'OK \(' + $group.Count + ' tests?\)'
    if ($adbExit -ne 0 -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or $result -notmatch $expected) { throw "Device group failed: $($group.Name); inspect $file" }
    Write-Output "$($group.Name): $($group.Count) passed"
} } finally {
    # Fixtures restored the original files; discard their cached ArkTS settings.
    & $adbPath shell am force-stop com.clawmate.shared
}
Write-Output '9 device checks passed in isolated target processes; no real cloud-model quality claim.'
