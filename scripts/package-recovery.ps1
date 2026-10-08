$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$distribution = Join-Path $projectRoot 'pos-reference-decoder/build/install/pos-reference-decoder'
if (-not (Test-Path -LiteralPath $distribution)) { throw 'Run :pos-reference-decoder:installDist first.' }
$packageDirectory = Join-Path $projectRoot 'artifacts/cvpos2-offline-recovery-0.1.2'
New-Item -ItemType Directory -Path $packageDirectory -Force | Out-Null
$jvmDirectory = Join-Path $packageDirectory 'jvm'
New-Item -ItemType Directory -Path $jvmDirectory -Force | Out-Null
Copy-Item -Path (Join-Path $distribution '*') -Destination $jvmDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'pos-reference-decoder/python/ciel_pos2_decoder.py'),(Join-Path $projectRoot 'requirements-recovery.txt'),(Join-Path $projectRoot 'docs/CVPOS2_SPEC.md') -Destination $packageDirectory
$readme = @'
CVPOS2 offline recovery

The JVM decoder is ready to run with Java 17 or later. No Android application,
account, personal key, network, Python or Gradle is needed for recovery.
This package does not include a Java runtime; prepare one before going offline.

Windows: jvm\bin\pos-reference-decoder.bat ORIGINAL.bin NEW-OUTPUT.bin
Linux/macOS: bash jvm/bin/pos-reference-decoder ORIGINAL.bin NEW-OUTPUT.bin

The input can use .bin or .cv2: the format is identified from its contents.

Choose a NEW output path. Existing files are not overwritten. The decoder checks
GCM tags, sequence/offset, the mandatory footer, original/prefix hashes and EOF.
On failure a newly created partial CVPOS2 output is removed. Do not execute any
bootstrap content. It is a bounded JSON description, not code.

The public recovery key is deliberately stored inside the container. Anyone who
has the file, including the cloud provider, can recover its exact original bytes.
Internal consistency is not a trusted identity proof: compare SHA-256 and length
with your independently kept original passport when identity matters.

Optional independent Python reader: ciel_pos2_decoder.py ORIGINAL.bin --output
NEW-OUTPUT.bin. Install requirements-recovery.txt before going offline; Python
dependencies are NOT bundled here. See CVPOS2_SPEC.md for the format.

Legacy POS v1 is also supported by the JVM command. Its format still depends on
an external specification; new Android exports use CVPOS2.
'@
Set-Content -LiteralPath (Join-Path $packageDirectory 'README.txt') -Value $readme -Encoding utf8
Compress-Archive -Path (Join-Path $packageDirectory '*') -DestinationPath (Join-Path $projectRoot 'artifacts/cvpos2-offline-recovery.zip') -Force
