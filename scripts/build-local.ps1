param([string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36'))) { throw 'Android SDK 36 not found. Pass -AndroidSdk with the SDK path.' }
$sdkProperty = ([IO.Path]::GetFullPath($AndroidSdk)).Replace('\', '/').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value "sdk.dir=$sdkProperty"
$priorJavaOptions = $env:JAVA_TOOL_OPTIONS
$priorPosPython = $env:POS2_PYTHON
$socketDirectory = Join-Path $env:USERPROFILE '.gradle'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
try {
    $env:JAVA_TOOL_OPTIONS = "$priorJavaOptions -Djdk.net.unixdomain.tmpdir=$($socketDirectory.Replace('\', '/'))"
    $localPython = Join-Path $projectRoot '.venv/Scripts/python.exe'
    if (Test-Path -LiteralPath $localPython) { $env:POS2_PYTHON = $localPython } elseif (-not $env:POS2_PYTHON) { $env:POS2_PYTHON = 'python' }
    Push-Location $projectRoot
    try {
        & .\gradlew.bat test :app:assembleDebug :app:lintDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle checks failed' }
        & python scripts/test_pos_fixtures.py
        if ($LASTEXITCODE -ne 0) { throw 'Independent decoder checks failed' }
        & $env:POS2_PYTHON scripts/test_pos2_fixtures.py
        if ($LASTEXITCODE -ne 0) { throw 'Independent AES decoder checks failed' }
    } finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS = $priorJavaOptions; $env:POS2_PYTHON = $priorPosPython }
