# Небесная витрина / Ciel Vitrine

Android gallery with exact-byte originals, archive-first cloud workflow and explicitly scoped deletion. One connected provider at a time; Yandex Disk is the first integration, Google Drive follows.

## Current milestone: 0.1.7

New cloud containers and manual exports use the neutral `.bin` extension. CVPOS3 preserves observed source dates alongside exact original bytes; CVPOS2 and POS v1 remain readable. Existing jobs retain their naming policy, including uncertain uploads. The extension does not hide the public recovery key or establish confidentiality from the provider.

- Native Compose gallery: real MediaStore images/videos grouped by capture day, square thumbnails without gaps, date scrollbar with haptic feedback, device folders, local albums, favorites, photo/GIF viewer and Media3 video playback. A complete metadata index supports jumps to old days; thumbnails/media remain lazy.
- Full/selected/mixed media access is visible. A revoked grant is never treated as deletion.
- CVPOS3 creation and recovery through Android's document picker, with CVPOS2/POS v1 import. Exact bytes, standard AES-256-GCM, public recovery key and full non-executable recovery recipe inside the file. Source dates are captured from the actual original descriptor and carried into independent working copies. Restore authenticates the complete container in private staging before touching its destination, then rereads the written bytes. Both export and restore reject nonempty destinations and aliases of their source. Original name/MIME cannot be silently truncated. Native futimens preserves nanosecond mtime where supported and verifies it on the same descriptor; unsupported destination dates are reported. Original EXIF dates are preserved in the media bytes; unknown filesystem creation dates are not invented. Export verifies the source through a second full read. The source is never removed.
- Media safety checks cover 14 owned fixtures and actual image/video viewers, including orientation, alpha, animation advancement, same-URI thumbnail edits and explicit decoding errors. See the validation report for exact scope; RAW/HDR/Live Photo and cloud viewing are not established by these checks.
- Conservative JVM safety prototype: complete compound resource sets, bound proof persistence, separate physical copies, transactional reservations, atomic terminal state/outbox and 60-day Trash preflight.
- Yandex LoginSDK with the registered public Client ID, device-local AES-GCM/Android Keystore session storage and read-only identity/Disk/app-folder checks. A saved account cannot be silently rebound. Real-account sign-in was confirmed on the phone after enabling Disk information access and signing in again. Metadata GETs have a 75-second deadline and one explicit retry after an I/O failure; PUTs and partial download callbacks are never replayed by that helper. Downloads follow at most five redirects, validate every HTTPS Yandex destination and drop OAuth authorization on a host change; the recovery reader runs only on the final successful response.
- Offline archive-all inventory: encrypted Android SQLite payloads, transactional scan pages/cursors, source epochs/generations and exact source rereads; persistent bounded upload queue, pause/resume, account/root binding and upload without overwrite. Full cloud recovery readback compares original and container SHA-256, length, bundle identity and stable observed object identity/version. An uncertain upload is reconciled by reading, never a blind repeated PUT.

The upload prototype has a **128 MiB per-resource limit** and requires an unmetered network and a stable app-folder ID. Larger files appear explicitly as unsupported in the inventory. Manual complete resource sets can create physically separate Gallery Store containers after fresh readback of every cold member. Ten pinned public JPEGs completed real Yandex Cold/Gallery roundtrips on the phone; all 20 recoveries after removing only private test sources matched original bytes, EXIF and native mtime. See the current validation report for scope and results. Multipart, automatic Live Photo pairing, cloud thumbnails/viewing, self-repair, automatic cleanup and destructive actions are not available. JDBC stores remain JVM safety prototypes; Android archive rows do not authorize deleting originals. Public recovery keys deliberately permit the provider to read originals. Use the local 0.1.7 APK for the registered OAuth certificate; CI uses a different certificate.

## Design

- [Accepted requirements, findings and development plan](docs/DEVELOPMENT_PLAN_RU.md)
- [Yandex OAuth setup](docs/YANDEX_SETUP_RU.md)
- [Milestone validation](docs/VALIDATION_0_1_RU.md)
- [Yandex sign-in milestone validation](docs/VALIDATION_0_1_1_RU.md)
- [Archive milestone validation](docs/VALIDATION_0_1_2_RU.md)
- [Mass queue and working originals validation](docs/VALIDATION_0_1_3_RU.md)
- [Neutral container names and compatibility validation](docs/VALIDATION_0_1_4_RU.md)
- [CVPOS2 format and recovery](docs/CVPOS2_SPEC.md)
- [CVPOS3 original dates and recovery](docs/CVPOS3_SPEC.md)
- [Ten-photograph and date validation](docs/VALIDATION_0_1_5_RU.md)
- [Daily timeline and real Yandex validation](docs/VALIDATION_0_1_6_RU.md)
- [Original safety, native metadata and speed validation](docs/VALIDATION_0_1_7_RU.md)
- [v26 audit](gallery_architecture_v26_audit_ru.md)
- [v29 working specification](docs/SPEC_V29.md)
- [Experimental POS v1 byte layout](docs/POS_V1_SPEC.md)
- [Offline reference decoder](pos-reference-decoder/python/ciel_pos_decoder.py)

Accepted user requirements determine scope. Historical notes and specification prose describe intended behavior, not proof that code implements it.

## Build and verify

JDK 17+, Android SDK 36, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. Configure SDK in ignored `local.properties` or `ANDROID_HOME`. Shared modules use a JDK 17 toolchain.

```text
./gradlew test :app:assembleDebug :app:lintDebug
python scripts/test_pos_fixtures.py
python scripts/test_pos2_fixtures.py
```

Install `requirements-recovery.txt` into a Python environment first. Set `POS2_PYTHON` to that interpreter if it is not the default `python`; Windows helper also detects `.venv/Scripts/python.exe`. Tests encode with Kotlin and independent Python and verify both directions, including actual original fixtures. If fixtures were removed while test results stayed cached, run `./gradlew :pos-format:test --rerun-tasks` first.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for development only.

Android instrumentation requires `./scripts/prepare-photo-corpus.ps1` and `python scripts/prepare-safety-corpus.py` before building `:app:assembleDebugAndroidTest`. The first downloads ten pinned public JPEGs and verifies hashes; the second needs `requirements-safety.txt` and FFmpeg with x264/x265/AV1 to create 14 owned media fixtures. The full suite runs on an isolated emulator. Real Yandex tests require a connected account and an explicit `-e real_cloud true` argument; they leave their own verified test containers in the cloud. Never run the full permission/inventory/session test suite against a personal phone library. See [0.1.7 validation](docs/VALIDATION_0_1_7_RU.md) for measured results, safe phone classes and limits.

For Windows hosts affected by Java temporary socket path virtualization, use `./scripts/build-local.ps1`; it applies the [OpenJDK process-local workaround](https://mail.openjdk.org/pipermail/nio-dev/2023-March/013297.html). Compose BOM is pinned to 2025.10.01 for SDK 36; the September 2026 BOM requires SDK 37.

## Offline recovery

```text
python pos-reference-decoder/python/ciel_pos_decoder.py original.pos --output recovered-original.bin
python pos-reference-decoder/python/ciel_pos2_decoder.py original.bin --output recovered-original.bin
```

The independent JVM distribution (`./gradlew :pos-reference-decoder:installDist`) supports all three formats using Java 17+. CVPOS2/3 recovery checks bounded framing, GCM tags, prefix/original hashes, the mandatory final record and EOF. Public keys and hashes establish internal consistency; trusted identity requires the independently captured original passport. CVPOS2/3 embed the full recipe. Legacy POS v1 still needs its external specification and decoder.
