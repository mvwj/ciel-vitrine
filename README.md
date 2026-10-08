# Небесная витрина / Ciel Vitrine

Android gallery with exact-byte originals, archive-first cloud workflow and explicitly scoped deletion. One connected provider at a time; Yandex Disk is the first integration, Google Drive follows.

## Current milestone: 0.1.3

- Native Compose gallery: real MediaStore images/videos, paginated recent additions, device folders, local albums, favorites, photo/GIF viewer and Media3 video playback.
- Full/selected/mixed media access is visible. A revoked grant is never treated as deletion.
- CVPOS2 creation and recovery through Android's document picker, with legacy POS v1 import. Exact bytes, standard AES-256-GCM, public recovery key and full non-executable recovery recipe inside the file. Both operations reread the destination. The source is never removed.
- Conservative JVM safety prototype: complete compound resource sets, bound proof persistence, separate physical copies, transactional reservations, atomic terminal state/outbox and 60-day Trash preflight.
- Yandex LoginSDK with the registered public Client ID, device-local AES-GCM/Android Keystore session storage and read-only identity/Disk/app-folder checks. A saved account cannot be silently rebound. A successful real-account sign-in is still pending device validation.
- Offline archive-all inventory: encrypted Android SQLite payloads, transactional scan pages/cursors, source epochs/generations and exact source rereads; persistent bounded upload queue, pause/resume, account/root binding and upload without overwrite. Full cloud recovery readback compares original and container SHA-256, length, bundle identity and stable observed object identity/version. An uncertain upload is reconciled by reading, never a blind repeated PUT.

The upload prototype has a **128 MiB per-resource limit** and requires an unmetered network and a stable app-folder ID. Larger files appear explicitly as unsupported in the inventory. Manual complete resource sets can create physically separate Gallery Store containers after fresh readback of every cold member. Real-account Yandex validation is pending. Multipart, automatic Live Photo pairing, cloud thumbnails/viewing, self-repair, automatic cleanup and destructive actions are not available. JDBC stores remain JVM safety prototypes; Android archive rows do not authorize deleting originals. Public recovery keys deliberately permit the provider to read originals. Use the local 0.1.3 APK for the registered OAuth certificate; CI uses a different certificate.

## Design

- [Accepted requirements, findings and development plan](docs/DEVELOPMENT_PLAN_RU.md)
- [Yandex OAuth setup](docs/YANDEX_SETUP_RU.md)
- [Milestone validation](docs/VALIDATION_0_1_RU.md)
- [Yandex sign-in milestone validation](docs/VALIDATION_0_1_1_RU.md)
- [Archive milestone validation](docs/VALIDATION_0_1_2_RU.md)
- [Mass queue and working originals validation](docs/VALIDATION_0_1_3_RU.md)
- [CVPOS2 format and recovery](docs/CVPOS2_SPEC.md)
- [v26 audit](gallery_architecture_v26_audit_ru.md)
- [v29 working specification](docs/SPEC_V29.md)
- [Experimental POS v1 byte layout](docs/POS_V1_SPEC.md)
- [Offline reference decoder](pos-reference-decoder/python/ciel_pos_decoder.py)

Accepted user requirements determine scope. Historical notes and specification prose describe intended behavior, not proof that code implements it.

## Build and verify

JDK 17+, Android SDK 36 and Build Tools 36.0.0. Configure SDK in ignored `local.properties` or `ANDROID_HOME`. Shared modules use a JDK 17 toolchain.

```text
./gradlew test :app:assembleDebug :app:lintDebug
python scripts/test_pos_fixtures.py
python scripts/test_pos2_fixtures.py
```

Install `requirements-recovery.txt` into a Python environment first. Set `POS2_PYTHON` to that interpreter if it is not the default `python`; Windows helper also detects `.venv/Scripts/python.exe`. Tests encode with Kotlin and independent Python and verify both directions, including actual original fixtures. If fixtures were removed while test results stayed cached, run `./gradlew :pos-format:test --rerun-tasks` first.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for development only.

For Windows hosts affected by Java temporary socket path virtualization, use `./scripts/build-local.ps1`; it applies the [OpenJDK process-local workaround](https://mail.openjdk.org/pipermail/nio-dev/2023-March/013297.html). Compose BOM is pinned to 2025.10.01 for SDK 36; the September 2026 BOM requires SDK 37.

## Offline recovery

```text
python pos-reference-decoder/python/ciel_pos_decoder.py original.pos --output recovered-original.bin
python pos-reference-decoder/python/ciel_pos2_decoder.py original.cv2 --output recovered-original.bin
```

The independent JVM distribution (`./gradlew :pos-reference-decoder:installDist`) supports both formats using Java 17+. CVPOS2 recovery checks bounded framing, GCM tags, prefix/original hashes, the mandatory final record and EOF. Public keys and hashes establish internal consistency; trusted identity requires the independently captured original passport. CVPOS2 embeds the full recipe. Legacy POS v1 still needs its external specification and decoder.
