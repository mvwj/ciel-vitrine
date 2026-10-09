$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskCorpus = Join-Path $taskRoot 'app/src/androidTest/assets/photo-corpus'
New-Item -ItemType Directory -Path $taskCorpus -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'artifacts') -Force | Out-Null
$taskCommit = '5332a9cf0220e9c6c93f88a187daa90808051e10'
$taskNames = @('Canon_40D.jpg','Canon_DIGITAL_IXUS_400.jpg','Canon_PowerShot_S40.jpg','Fujifilm_FinePix6900ZOOM.jpg','Fujifilm_FinePix_E500.jpg','Kodak_CX7530.jpg','Konica_Minolta_DiMAGE_Z3.jpg','Nikon_COOLPIX_P1.jpg','Nikon_D70.jpg','Olympus_C8080WZ.jpg')
$taskExpected = Get-Content -LiteralPath (Join-Path $taskRoot 'docs/fixtures/ten-photo-corpus.json') -Raw | ConvertFrom-Json
$taskManifest = foreach ($taskName in $taskNames) {
    $taskUrl = "https://raw.githubusercontent.com/ianare/exif-samples/$taskCommit/jpg/$taskName"
    $taskFile = Join-Path $taskCorpus $taskName
    if (-not (Test-Path -LiteralPath $taskFile)) { Invoke-WebRequest -Uri $taskUrl -OutFile $taskFile }
    $taskHash = (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskSize = (Get-Item -LiteralPath $taskFile).Length
    $taskEntry = @($taskExpected | Where-Object { $_.name -eq $taskName })
    if ($taskEntry.Count -ne 1 -or $taskEntry[0].url -ne $taskUrl -or $taskEntry[0].sha256 -ne $taskHash -or $taskEntry[0].bytes -ne $taskSize) { throw "Public fixture does not match the pinned manifest: $taskName" }
    [pscustomobject]@{name=$taskName; url=$taskUrl; bytes=$taskSize; sha256=$taskHash}
}
$taskManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot 'artifacts/photo-corpus-manifest.json') -Encoding utf8
"Prepared $($taskNames.Count) public test photographs; provenance and hashes recorded."
