# Небесная витрина / Ciel Vitrine

Android gallery with exact-byte originals, archive-first cloud workflow and explicitly scoped deletion. One connected provider at a time; Yandex Disk is the first integration, Google Drive follows.

## Current milestone: 0.1.10

R5-A improvements were integrated into the existing project on 30 October 2026, with additional recovery, cache, gesture and export fixes. Historical audit results are separate from the new validation. See [integration and remaining limits](docs/INTEGRATION_2026_10_30_RU.md).

New cloud containers and manual exports use the neutral `.bin` extension. CVPOS3 preserves observed source dates alongside exact original bytes; CVPOS2 and POS v1 remain readable. Existing jobs retain their naming policy, including uncertain uploads. The extension does not hide the public recovery key or establish confidentiality from the provider.

- Native Compose gallery: real MediaStore images/videos grouped by capture day, square thumbnails with 2 dp gutters in a dark theme, date scrollbar with haptic feedback, device folders, local albums, favorites, photo/GIF viewer and Media3 video playback. A complete metadata index supports jumps to old days; thumbnails/media remain lazy.
- Full-screen adjacent-media pager, full-resolution local region decoding, hardware Canvas/Gainmap photo rendering and a SurfaceView video player with play/pause and seeking. Multi-selection queues exactly the chosen originals; favorites and album actions also accept selections.
- Focal-point zoom, bounded initial photo decoding and replacement of translucent detail pixels without double compositing. Obsolete viewport batches stop between requests; generations reject late publication.
- Large/unknown-size originals and videos use authenticated CVMP1 multipart manifests over independent CVPOS3 objects. There is no 128 MiB source admission limit; 128 MiB remains a compatibility boundary for old single-object jobs. Multipart verification and playback follow the authenticated remote manifest, not just the local upload journal.
- Media3 consumes verified original-byte parts on demand. Quality changes preserve position, play/pause and volume. Prepared SDR renditions have separate jobs and never replace originals; unsupported HDR transformation is rejected. The default clears owned viewing cache after the last consumer closes. Interrupted ciphertext can resume within retained-cache sessions, with strict HTTP Range and complete revalidation before exposure.
- Image thumbnails decode the current original descriptor rather than trusting MediaStore's potentially stale thumbnail cache. A bounded 16 MiB generation/binding cache checks read access even on hits; at most three thumbnail loads run concurrently.
- Static cloud photographs use independently encrypted lossless raster tiles packed into immutable cloud objects. The viewer fetches only visible indexed HTTP ranges, requires exact 206/Content-Range/length, verifies trusted ciphertext SHA-256 and AEAD, and preserves native pixel/color/Gainmap data. No complete-original fallback runs in this photo viewing path. Full originals remain independent exact-byte archives. [Protocol and limits](docs/PARTIAL_CLOUD_VIEW_RU.md).
- Device-private measurement histories record actual successful/failed attempts and received tile bytes. The displayed/exported report is scoped to the chosen jobs; cache reads are separate, nested stages are not summed. Recording a tile does not rebuild all histories.
- Manual restore verifies the entire container before offering its original name/MIME in the save picker, then requires the same container SHA-256 before writing. Android 11+ favorite/album bindings include the MediaStore epoch and row insertion generation; legacy URI links stay unresolved until explicit confirmation. Old preferences are retained, unavailable members are not deleted and unreadable album data cannot be overwritten. Thumbnail/viewer state includes the binding. Android 10 can browse but cannot safely add members without an insertion generation.
- Full/selected/mixed media access is visible. A revoked grant is never treated as deletion.
- CVPOS3 creation and recovery through Android's document picker, with CVPOS2/POS v1 import. Exact bytes, standard AES-256-GCM, public recovery key and full non-executable recovery recipe inside the file. Source dates are captured from the actual original descriptor and carried into independent working copies. Restore authenticates the complete container in private staging before touching its destination, then rereads the written bytes. Both export and restore reject nonempty destinations and aliases of their source. Original name/MIME cannot be silently truncated. Native futimens preserves nanosecond mtime where supported and verifies it on the same descriptor; unsupported destination dates are reported. Original EXIF dates are preserved in the media bytes; unknown filesystem creation dates are not invented. Export verifies the source through a second full read. The source is never removed.
- Media safety checks cover 14 owned fixtures and actual image/video viewers, including orientation, alpha, animation advancement, same-URI thumbnail edits and explicit decoding errors. Additional tile tests cover native Ultra HDR gainmaps, F16 pixels, partial ranges, cache and uncertain uploads. An opt-in real Yandex test passed on a generated Ultra HDR JPEG. These checks do not establish universal RAW/Live Photo support or the physical brightness of every HDR screen.
- Conservative JVM safety prototype: complete compound resource sets, bound proof persistence, separate physical copies, transactional reservations, atomic terminal state/outbox and 60-day Trash preflight.
- Yandex LoginSDK with the registered public Client ID, device-local AES-GCM/Android Keystore session storage and read-only identity/Disk/app-folder checks. A saved account cannot be silently rebound. Real-account sign-in was confirmed on the phone after enabling Disk information access and signing in again. Metadata GETs have a 75-second deadline per attempt and at most three attempts; PUTs and partially delivered streaming bodies are never replayed by that helper. Downloads follow at most five redirects, validate every HTTPS Yandex destination and drop OAuth authorization on a host change; the recovery reader runs only on the final successful response.
- Offline archive-all inventory: encrypted Android SQLite payloads, transactional scan pages/cursors, source epochs/generations and exact source rereads; persistent bounded upload queue, pause/resume, account/root binding and upload without overwrite. Full cloud recovery readback compares original and container SHA-256, length, bundle identity and stable observed object identity/version. An uncertain upload is reconciled by reading, never a blind repeated PUT.

Uploads require an unmetered network, cloud quota, bounded temporary space and a stable app-folder ID. Old size-only rejections migrate back to the multipart queue. Manual resource sets create physically separate Gallery Store containers after fresh readback of every cold member, including mixed SINGLE/MULTIPART sets. The earlier ten public JPEGs completed real Yandex Cold/Gallery roundtrips; that historical result is not a new multipart cloud benchmark. Static cloud JPEG/PNG viewing still requires a complete verified device-indexed tile pyramid. Whole-original preparation/fallback uses a 256 MiB owned viewer cache and cannot currently prepare larger photos through that path. Automatic Live Photo pairing, complete catalog recovery after reinstall, freeing original storage and destructive actions remain unavailable. Legacy SINGLE video needs an explicit working multipart copy before streaming. Native HDR playback depends on the device; HDR rendition encoding is disabled. JPEG/PNG tile preparation, real Yandex multipart performance and physical HDR quality remain separate validation tasks. JDBC stores remain JVM safety prototypes; Android archive rows do not authorize deleting originals. Public recovery keys deliberately permit the provider to read originals. Local APKs use the registered OAuth certificate; CI uses a different certificate.

## Design

- [Partial encrypted cloud photo viewing](docs/PARTIAL_CLOUD_VIEW_RU.md)

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
- [Dark theme, gutters and verified restore/album bindings](docs/VALIDATION_0_1_8_RU.md)
- [Partial network viewing, HDR pixels and selected-file measurements](docs/VALIDATION_0_1_9_RU.md)
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
