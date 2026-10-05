# Небесная витрина / Ciel Vitrine

Android gallery with exact-byte originals, archive-first cloud workflow and explicitly scoped deletion. One connected provider at a time; Yandex Disk is the first integration, Google Drive follows.

## Current milestone: 0.1.0

- Native Compose gallery: real MediaStore images/videos, paginated recent additions, device folders, local albums, favorites, photo/GIF viewer and Media3 video playback.
- Full/selected/mixed media access is visible. A revoked grant is never treated as deletion.
- Experimental POS v1 creation and recovery through Android's document picker. Both operations reread the destination and compare SHA-256 and exact length. The source is never removed.
- Conservative JVM safety prototype: complete compound resource sets, bound proof persistence, separate physical copies, transactional reservations, atomic terminal state/outbox and 60-day Trash preflight.
- Initial Yandex REST client: metadata observation, upload without overwrite and streamed readback. It is **not connected to Android UI yet**. OAuth, persistent cloud jobs and real-provider probes are next.

Cloud upload, cloud-only viewing, automatic cleanup and destructive actions are not available in this APK. JDBC stores are JVM prototypes; Android production storage needs its own implementation. POS v1 is a public reversible transform, **not confidential encryption**. Its header does not carry a complete decoding recipe; keep the offline recovery package below until this gap is closed.

## Design

- [Accepted requirements, findings and development plan](docs/DEVELOPMENT_PLAN_RU.md)
- [Yandex OAuth setup](docs/YANDEX_SETUP_RU.md)
- [Milestone validation](docs/VALIDATION_0_1_RU.md)
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
```

The Python check decodes actual Kotlin output and compares independent original files. If fixtures were removed while test results stayed cached, run `./gradlew :pos-format:test --rerun-tasks` first.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for development only.

For Windows hosts affected by Java temporary socket path virtualization, use `./scripts/build-local.ps1`; it applies the [OpenJDK process-local workaround](https://mail.openjdk.org/pipermail/nio-dev/2023-March/013297.html). Compose BOM is pinned to 2025.10.01 for SDK 36; the September 2026 BOM requires SDK 37.

## Offline recovery

```text
python pos-reference-decoder/python/ciel_pos_decoder.py original.pos --output recovered-original.bin
```

Recovery checks bounded framing, CRC, container digest, original SHA-256, complete footer and exact length. Public hashes establish internal consistency. Trusted identity requires comparison with the independently captured original passport. Keep POS, decoder and format specification together until the complete recipe is embedded in the next format.
