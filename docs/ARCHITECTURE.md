# Architecture

Ciel Vitrine separates an exact original, its working cloud copy and disposable
viewing data. These are different records with different authority.

## Storage and trust

| Layer | Role | What it cannot prove |
|---|---|---|
| MediaStore | Observed phone original, epoch/generation, dimensions and dates | Access loss does not mean deletion. |
| Cold Archive | First exact-byte original container | A completed PUT does not prove successful recovery. |
| Gallery Store | Physically separate working original container | Another object in the same account is not an independent provider backup. |
| Tile packs / video renditions | Prepared viewing data | A thumbnail or derivative is not an original. |
| Device catalog | Encrypted journal, bindings and verification proofs | A row alone cannot authorize deleting an original. |
| Owned cache | Bounded temporary authenticated viewing data | Cache eviction must not delete originals or durable previews. |

Cloud object identity, observed version, length and hashes are bound to the
expected account, root and original. Uncertain uploads are reconciled through
readback. Permission failures, unavailable objects and stale source generations
stop destructive transitions.

## Original formats

CVPOS3 stores the original media bytes with source metadata in authenticated
records. Large originals use a CVMP1 manifest and independent CVPOS3 parts with
64-bit lengths. The manifest chain is validated from the remote root, rather
than trusting just the local journal. Earlier CVPOS2/POS v1 files remain readable.

The container includes its recovery key. This enables recovery without a
separate secret and allows the provider to read the original. Device-private
catalog/session keys are separate and remain in Android Keystore.

## Viewing

JPEG/PNG cloud photos use indexed ranges from immutable encrypted tile packs.
Each requested range is checked against its trusted identity/hash, AEAD and
raster geometry before display. The viewer requests visible detail and rejects
obsolete viewport results. Original containers remain separate.

Media3 obtains verified multipart original bytes on demand. Prepared SDR video
qualities are separate derivatives. Codec/HDR availability is device-dependent;
HDR rendition encoding is currently rejected.

## Modules

| Module | Responsibility |
|---|---|
| `app` | Compose UI, MediaStore, Keystore, WorkManager, SQLite device journal, native raster/date helpers and Media3 integration |
| `archive-engine` | Original upload/recovery state, multipart transport and owned viewing cache |
| `pos-format` | Container framing, exact-byte encoding, authentication and metadata |
| `pos-reference-decoder` | Independent JVM and Python recovery tools |
| `provider-adapter` | Provider contract and bounded Yandex HTTP behavior |
| `core-model`, `core-crypto` | Domain rules and cryptographic primitives |
| `control-storage`, `catalog-storage` | JVM persistence prototypes |
| `saga-engine`, `reader-share`, `state-machine-tests` | Safety prototypes, exact-original readers and state transition obligations |

Some JVM safety models describe future trash/purge behavior. Their presence is
not evidence that Android cloud deletion or a 60-day trash is implemented.

## Decisions and validation

- [Local upload/removal workflow](CLOUD_STATUS_AND_LOCAL_RELEASE_RU.md)
- [Partial photo protocol](PARTIAL_CLOUD_VIEW_RU.md)
- [Video and multipart originals](VIDEO_AND_LARGE_ORIGINALS_RU.md)
- [CVPOS3](CVPOS3_SPEC.md) and [CVPOS2](CVPOS2_SPEC.md)
- [Requirements and development plan](DEVELOPMENT_PLAN_RU.md)
- [Historical specification](SPEC_V29.md): intended rules, not an implementation checklist already completed
- [Validation index](README.md#validation)
