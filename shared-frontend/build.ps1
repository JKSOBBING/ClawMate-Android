param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$scratchRoot = Join-Path $repoRoot '.arkui-x-validation'
$projectRoot = Join-Path $scratchRoot 'application/project'
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidSdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
$env:ARKUIX_SDK_HOME = Join-Path $scratchRoot 'sdk'
$env:OpenHarmony_HOME = Join-Path $scratchRoot 'openharmony-sdk'
$buildNode = Join-Path $scratchRoot 'node/node-v20.19.5-win-x64'
$env:PATH = "$buildNode;$env:JAVA_HOME\bin;$env:PATH"
Push-Location $repoRoot
try {
    node shared-frontend/prepare.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Source preparation failed' }
    Push-Location $projectRoot
    try {
        node ../../build-tools/node_modules/@ohos/hvigor/bin/hvigor.js --mode module -p module=entry@default -p product=default -p buildMode=debug assembleHap --no-daemon --no-parallel
        if ($LASTEXITCODE -ne 0) { throw 'ArkTS compilation failed' }
    } finally { Pop-Location }
    python experiments/arkui-x/package-android.py --project $projectRoot --sdk (Join-Path $scratchRoot 'sdk/20/arkui-x') --android-app (Join-Path $scratchRoot 'application/runtime')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime packaging failed' }
    $gradleArguments = @(':shared-app:assembleDebug', '--no-daemon', '--console=plain')
    if ($Offline) { $gradleArguments += '--offline' }
    & ./gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
