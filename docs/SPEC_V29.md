# Ciel Vitrine — normative architecture v29

**Status:** normative working specification. This file replaces the chronological v1–v28 notebook as the source of truth for implementation decisions. Historical notebooks remain research notes only.

**Audit basis for this revision:** the complete v26 audit is available and has been reconciled finding-by-finding: A01–A15, B01–B10, C01–C08 and D01–D11 (44 findings total). Section 22 records the normative disposition of every finding. Benchmark-class findings remain measurement obligations rather than protocol invariants.

---

## 0. Rule classes

Every rule in this document is one of:

- **MUST** — correctness/safety requirement; implementation must not violate it.
- **POLICY** — product choice configurable by the user/vault.
- **CAPABILITY GATE** — feature is enabled only after the relevant Android/provider contract is demonstrated.
- **BENCHMARK** — tunable parameter; never a protocol invariant.

Later prose does not implicitly override earlier rules. Protocol bytes are versioned explicitly.

---

## 1. Product contract

### PROD-001 — exact originals
**MUST.** Durable original storage never decodes/re-encodes the source. The authoritative payload is the exact byte sequence read from the accepted source representation.

### PROD-002 — keyless recoverability of originals
**MUST.** Every durable cloud original is stored as a public, self-describing, reversible `PortableOriginalSet` (POS). Recovery of original bytes requires the complete POS set plus the public POS decoding contract carried/identified according to POS-009; it does **not** require Android, Room, the original app, `VaultRootSecret`, `RecoverySecret`, or a network service. A stronger “objects alone after all external documentation is lost” claim is permitted only when the embedded non-executable bootstrap descriptor is demonstrably sufficient for the independent decoder.

### PROD-003 — privacy consequence
**MUST.** POS is **not confidential encryption**. A cloud provider or any party that possesses all required POS objects can recover the original, including embedded EXIF/GPS/HDR metadata. The product must not claim end-to-end confidentiality for authoritative originals.

### PROD-004 — private Gallery layer
**MUST.** User structures and regeneratable working data may remain secret-key E2E encrypted: albums, favorites, visibility state, private catalog fields, thumbnails, previews, zoom tiles, video proxies, private journals/snapshots and other derivatives.

### PROD-005 — graceful private-key loss
**MUST.** Loss of the private Gallery recovery secret may destroy private Gallery organization, but must not prevent byte-perfect recovery of POS originals. A basic gallery may be rebuilt by re-parsing recovered originals.

### PROD-006 — one provider per vault
**POLICY v1.** A vault has one active cloud provider/account. Gallery Store and Cold Archive may be separate physical objects/namespaces in the same provider/account; therefore they do not constitute independent provider/account failure domains.

### PROD-007 — exact-byte recovery vs Gallery-semantic recovery
**MUST.** Keyless POS recovery guarantees exact authoritative `ResourceVersion` byte streams. It does **not** by itself guarantee restoration of private Gallery semantics such as albums, favorites, visibility, user-authored capture-date corrections, edit recipes, exclusions/tombstones or manually corrected compound-asset relationships.

Those user-authored semantics are authoritative private state, not disposable derivatives. In v1 they require recoverable private Gallery state (`RecoverySecret` path). Any semantic metadata intentionally duplicated into public POS metadata becomes visible to the cloud provider and must be documented as such.

---

## 2. Core data model

### DATA-001 — Asset and Resource
An `Asset` is a gallery-level item. It owns one or more authoritative `Resource`s.

Examples:
- JPEG still: one resource.
- Motion Photo container: normally one resource.
- imported Apple Live Photo: still resource + paired video resource.

### DATA-002 — immutable ResourceVersion
**MUST.** Each accepted byte sequence becomes an immutable `ResourceVersion`.

```text
ResourceVersion {
  resourceVersionId
  resourceId, assetId
  sourceRepresentationKind
  trustedOriginalByteLength
  trustedOriginalSha256
  createdAt
  mediaFactsRef?          // optional diagnostic facts, not identity proof
}
```

A mutation of source bytes creates a new version. v1 does not deduplicate authoritative original objects across Gallery Store and Cold Archive if doing so would merge their lifecycles.

### DATA-003 — source representation honesty
`sourceRepresentationKind` is one of:

- `EXACT_ORIGINAL` — Android/file API contract gives the actual original bytes requested.
- `USER_SUPPLIED_FILE` — user explicitly selected a file; exactness is relative to that file.
- `PLATFORM_REPRESENTATION` — Android/provider supplied a redacted/transcoded/derived representation.

**MUST.** A `PLATFORM_REPRESENTATION` is preserved exactly but must not be advertised as the camera/device original.

### DATA-004 — SourceDescriptor vs OriginalPassport
Before EOF, only a cheap `SourceDescriptor` may exist: URI/file identity, observed length if known, generation/revision fields, timestamps and representation kind.

After successful EOF, create `OriginalPassport`:

```text
OriginalPassport {
  resourceVersionId
  exactByteLength
  trustedOriginalSha256
  sourceDescriptorAtStart
  sourceDescriptorAtEnd
  optional media diagnostics
}
```

**MUST.** No destructive action may rely on a candidate whose final passport was not durably committed.

### DATA-005 — independent state facts, not one combined enum
**MUST.** Safety-critical state is stored as independent facts. At minimum the model distinguishes:

- immutable `ResourceVersion` / `OriginalPassport`;
- `SourceAccess` and `SourcePresence`;
- `SourceBinding` / observed source version;
- `UploadJobState`;
- `RemoteCopyPresence`;
- `VerificationEvidence`;
- `BackupIntent` separately for Gallery Store and Cold Archive;
- `Visibility` / `TrashRecord`;
- `DestructiveOperationState`.

Derived labels such as “backed up”, “safe”, “cloud-only” or “verified” are projections over these facts, never durable truth by themselves.

`SourcePresence` must be able to distinguish at least `PRESENT`, `UNKNOWN`, `ACCESS_REVOKED`, `PARTIAL_SCOPE`, `VOLUME_OFFLINE`, `PROVIDER_UNAVAILABLE` and `ABSENT_CONFIRMED`. Loss of visibility/access is not deletion.

### DATA-006 — source identity and MediaStore epoch
**MUST.** A MediaStore/source binding includes enough scope to prevent ID reuse from impersonating an old source:

```text
SourceBinding {
  authority
  volumeOrCollection
  providerDatabaseEpochOrVersion?
  providerItemId
  accessScopeIdentity
  observedGenerationOrRevision?
  representationKind
}
```

A source ID is not a `Resource` identity. If the provider/MediaStore database epoch changes, prior bindings require reconciliation before absence or identity is trusted. Hash+length may help rebind a known byte sequence after reading, but do not automatically merge two user-visible Assets.

A scan may publish `ABSENT_CONFIRMED` only after a complete enumeration under the same access scope, volume/provider epoch and scan generation. Partial/no-access scans never imply deletion and never trigger remote purge.

### DATA-007 — compound resource-set revision
**MUST.** Multi-resource assets have an explicit resource-set revision/commit group. Individual resources may upload independently, but “asset fully protected” and destructive predicates apply to the complete current resource-set revision. Pairing heuristics must not silently mutate an already committed set without creating a new set revision.

---

## 3. Portable Original Set (POS) contract

`PortableOriginalSet` is the new normative name for the keyless authoritative-original representation. `POB` in historical notes maps to the single-part special case of POS.

### POS-001 — one-file and multi-part recovery
**MUST.** Small originals may use one physical POS object. Large originals may use multiple parts. The public recovery contract is therefore:

> all required objects of one POS set are sufficient to recover the original without private catalog state.

No claim is made that an arbitrary single part can recover the whole original.

### POS-002 — immutable bootstrap header
**MUST.** The initial header contains only data known before streaming the original. It must never require the final source SHA-256.

Conceptual fields:

```text
magic
formatVersion
setId
partId / partIndex
originalStartOffset
transformId + public parameters
record framing version
flags (length-known / final-part / etc.)
```

### POS-003 — streamed records
**MUST.** Original bytes are processed sequentially into independently framed reversible records. Record framing must permit bounded-memory sequential recovery and bounded random access.

The reversible transform is public and requires no secret. Its exact algorithm/byte layout is a **P0 protocol-freeze item** and must be self-described/versioned. It is obfuscation/portable transformation, not a confidentiality boundary.

### POS-004 — footer after EOF
**MUST.** Final byte length and full SHA-256 are emitted only after EOF in a canonical footer/final manifest.

Conceptually:

```text
originalByteLength
originalSha256
recordCount
ordered-part summary / manifest digest
format completion marker
```

An object/set without a valid completion footer/manifest is never `COMPLETE`.

### POS-005 — public part identity
Each part must expose enough public information to reject mixed sets and discover ordering:

```text
setId
partIndex
originalByteRange
partPayloadLength
part/container digest
formatVersion
```

A public set manifest contains the ordered part list, whole original length and whole original SHA-256. Recovery tooling must diagnose missing/duplicate/mixed parts rather than silently produce bytes.

### POS-006 — standalone consistency vs authenticity
**MUST.** POS internal hashes prove consistency/corruption detection only. Because the format has no secret, an attacker/provider can replace payload and recompute public hashes.

Three concepts stay separate:

1. `containerConsistency` — POS structure and internal digests agree.
2. `trustedOriginalMatch` — recovered bytes match a digest captured independently from the original source.
3. `authorAuthenticity` — requires an external trust anchor/signature identity; standalone keyless POS alone cannot prove it.

### POS-007 — independent reference decoder
**MUST before release.** Publish/store an extremely small reference decoder and format description with fixtures. Recovery must be tested by a second implementation that shares no production decoding code.

### POS-008 — no semantic dependency on media format
POS treats payload as bytes. Unsupported JPEG XL/RAW/vendor formats must still back up and restore exactly.

### POS-009 — complete public decoding recipe and parser safety
**MUST.** `formatVersion` or a transform name is not sufficient documentation by itself. The frozen POS v1 contract must normatively define byte order, framing, counters/nonces where applicable, digest coverage, final-record rules, part ordering and all transform parameters.

The recovery package must remain usable offline without network lookups. If the product claims recovery from stored POS objects even after external documentation is lost, the set must carry a bounded, non-executable canonical bootstrap descriptor sufficient for an independent decoder to interpret the representation. A URL or opaque “spec identifier” does not satisfy that stronger claim.

Recovery metadata is parsed as data only. No recovery reader executes embedded scripts or follows network instructions automatically; all lengths/counts/allocations are bounded and unsupported versions fail closed.

---

## 4. Copy locations and failure domains

### COPY-001 — ExactCopyLocation

```text
ExactCopyLocation {
  copyId
  resourceVersionId
  role: LOCAL_SOURCE | GALLERY_STORE | COLD_ARCHIVE
  providerAccountId?
  physicalObjectSetId
  failureDomainIds[]
  observedRevision
  state
}
```

### COPY-002 — physical copy != logical state
**MUST.** Trash, visibility flags, catalog references and aliases do not create extra safety copies if they reference the same physical POS objects.

### COPY-003 — failure-domain accounting
**MUST.** UI/safety logic distinguishes:

- number of verified physical exact-copy locations;
- number of independent failure domains.

Gallery Store + Cold Archive under one Google/Yandex account are usually two physical replicas but one account/provider failure domain.

### COPY-004 — no unsafe cross-store dedup in v1
**MUST.** Do not deduplicate Gallery Store and Cold Archive down to one physical object identity if the result makes one deletion lifecycle destroy the other.

---

## 5. Upload policy

### UP-001 — safety policy is separate from upload order
Two independent concepts:

```text
DeletionSafetyPolicy
UploadStrategy
```

`Archive First` is a deletion-safety policy, not a requirement to serialize whole uploads.

### UP-002 — recommended vault policy
**POLICY default: `ARCHIVE_GATED_STRICT`.** Before any local exact original is removed, every authoritative resource of the asset must have:

- verified exact Gallery Store copy;
- verified exact Cold Archive copy;
- recoverable committed Gallery catalog state;
- no known writer fork/conflict;
- current source still matching the captured `ResourceVersion`.

### UP-003 — supported upload strategies
Implementation may choose per provider/source:

- one-read parallel tee;
- upload once + verified provider server-side copy;
- stable second source read;
- bounded staged-parts delivered to both stores.

**MUST.** Failure of one branch does not roll back a separately verified successful branch.

### UP-004 — one-read promise is conditional
**MUST.** Never claim simultaneously “whole Archive first”, “whole Gallery second”, “one source read” and “bounded staging” unless provider server-side copy makes that composition true.

### UP-005 — source mutation
At start and end, compare source descriptors. On resume, verify the resumed source prefix/chunk against previously committed source evidence.

If source bytes may have changed, abandon the candidate representation and create a new `ResourceVersion`/container identity.

For private AEAD blobs, changed plaintext must never reuse an old key/nonce schedule.

### UP-006 — resume checkpoint safety and source snapshot honesty
**MUST.** Resuming a private AEAD writer uses either immutable staged bytes/ciphertext for the record/part being retried, or a durably committed plaintext-record digest that is checked **before** re-encrypting under a previously used nonce. If the re-read plaintext differs or cannot be proven identical, abandon that writer identity and restart with a new DEK/nonce domain.

Re-sending previously committed ciphertext byte-for-byte is allowed. Re-encrypting changed plaintext with a previously used key/nonce pair is not.

Source metadata checks before/after a read do not prove an atomic source snapshot. `ExactSourceResolver` records the source stability level actually provided by the platform/provider. Where snapshot consistency is not guaranteed, the `OriginalPassport` attests to the byte stream successfully read; before destructive local removal the current source must still be shown to match that captured `ResourceVersion`.

---

## 6. Verification evidence

### VER-001 — evidence is an observation, not a boolean promise

```text
VerificationEvidence {
  resourceVersionId
  originalByteLength
  trustedOriginalSha256
  copyId
  providerAccountId
  vaultRootBinding
  containerFormatVersion
  containerBytes
  containerSha256
  orderedParts: [identity, range, size, digest, observedRevision]
  method: SERVER_DIGEST | FULL_CONTAINER_READBACK | FULL_RECOVERY_READBACK | VERIFIED_SERVER_COPY
  verifiedAt
  observedDownloadability
  invalidationGeneration
}
```

Historical evidence is retained for diagnosis. Current usability is derived from current remote revisions/capabilities and policy.

### VER-002 — strongest proof
`FULL_RECOVERY_READBACK` means the remote POS was read from the provider, publicly reversed as a stream, and the recovered length + SHA-256 matched the **independently trusted** `OriginalPassport`.

### VER-003 — strict Free Up proof
For normal Gallery `Free up device` under strict policy, all authoritative resource versions must have fresh exact evidence for both Cold Archive and Gallery Store, plus a recoverable Gallery catalog commit.

Full recovery readback is the baseline first proof. A cheaper proof may replace repeated readback only when it is cryptographically/contractually bound to an unchanged remote object revision and derives from an already trusted exact copy.

### VER-004 — evidence invalidation
Evidence becomes unusable if any bound fact changes: remote revision/object identity, part list, provider account binding, resource version, trust generation, writer conflict, or provider capability relevant to the proof.

### VER-005 — remote server checksum limit
A provider checksum can prove equality of uploaded POS container bytes to locally computed POS bytes. It does not by itself prove that the POS encoder represented the intended source unless linked to a trusted source passport and tested recovery path.

### VER-006 — readback must bind one remote revision
**MUST.** A full or ranged verification readback must not assemble bytes from multiple remote revisions. Where the provider exposes a stable revision/version, stat it before and after the read and bind every range response to the same object identity/revision. A revision change invalidates the observation even if it might be metadata-only; it is a reason to revalidate, not automatically evidence of corruption.

Metadata visibility alone is not downloadability. `VerificationEvidence` records whether bytes were actually readable. If a strong server digest is unavailable for the relevant object/revision, the adapter falls back to readback according to policy.

---

## 7. Local durability and state projection

### DB-001 — single safety-critical control database
**MUST.** Safety-critical local truth lives in one `control.db` (SQLCipher if local confidentiality is desired) with WAL and `synchronous=FULL` for durability barriers.

It contains at minimum:

- `ResourceVersion` + trusted digest/length;
- exact copy identities and current observations;
- durable operation/outbox log;
- destructive reservations;
- exclusions;
- tombstones;
- writer/head state;
- upload/resume identities required to reconcile remote effects.

### DB-002 — catalog is a projection
`catalog.db` contains UI/query projections: timeline, albums, parsed metadata, derivative refs, scrub indexes, etc. It may use WAL + `synchronous=NORMAL` where losing the latest projection transaction is recoverable.

### DB-003 — no cross-DB atomicity assumption
**MUST.** `control.db -> catalog.db` propagation is by idempotent events/replay. No invariant may depend on one atomic transaction across both DB files.

### DB-004 — durable intent before external effect
**MUST.** Any operation that may cause an irreversible external effect first commits exact intent, target identities, scope and idempotency keys to `control.db` with a durability barrier.

### DB-005 — reconcile ambiguous effects
After process death, power loss or network timeout, the operation re-queries provider/MediaStore using durable identities. “Request sent” is never equivalent to “state changed”.

### DB-006 — database instance is not a single physical connection
A single `control.db`/`catalog.db` instance per process/session does not require serializing every query through one SQLite connection. The implementation may use a bounded reader pool with SQLite/WAL’s single-writer semantics, provided every SQLCipher connection is initialized consistently and transactions remain short.

Pool size is a **BENCHMARK** validated under simultaneous timeline + import/restore load. Long-lived UI identity uses opaque `assetId` (or an explicitly generation-qualified local key), not an assumption that a recycled rowid is harmless to delayed callbacks or remembered UI state.

---

## 8. Writer coordination and forks

### WR-001 — writerEpoch is history, not a lock
A signed epoch/revision proves who authored state, not mutual exclusion.

### WR-002 — accepted head
Takeover records:

```text
newWriterIdentity
newEpoch
acceptedJournalHead/checkpoint from previous history
```

Entries before the accepted head remain history. Late old-writer entries are not silently merged into the authoritative branch.

### WR-003 — fork behavior
**MUST.** If two valid next heads/epochs are observed, enter `WRITER_CONFLICT`.

While conflicted or authoritative-head freshness is unknown:

- additive remote objects may be retained as reconcilable candidates;
- remote destructive operations and GC are blocked;
- local `Free up device` is also blocked if its proof depends on conflicted cloud state.

### WR-004 — server fencing limitation
Client signatures cannot revoke an old device’s OAuth ability. A strong destructive-operation fence requires a demonstrated provider atomic reservation/CAS primitive.

**CAPABILITY GATE.** Provider adapters must prove the chosen atomic reservation/precondition behavior in real accounts before multi-device destructive cloud operations are considered safe. Otherwise the product must expose the limitation and conservatively block destructive cloud actions in takeover/fork uncertainty.

### WR-005 — preflight before every remote deletion
Immediately before a destructive provider call: refresh authoritative head, validate no fork, validate reservation ownership and use provider revision/precondition if available.

---

## 9. Unified destructive-operation saga

### DEL-001 — one engine
`Free up`, Gallery copy deletion, Cold Archive deletion, Trash purge and “erase selected copies” all use the same mutation engine.

### DEL-002 — durable reservation
A `DestructiveReservation` covers affected `ResourceVersion`s and exact copy locations. Pending operations participate in remaining-copy calculations.

### DEL-003 — minimum sequence

1. durably record exact user intent/scope and future-purge authorization;
2. acquire reservation;
3. refresh writer/head and source/copy observations;
4. gather fresh evidence;
5. calculate post-operation remaining physical locations/failure domains including other pending mutations;
6. enforce `DeletionSafetyPolicy` or verify explicit last-copy destruction authorization;
7. execute the smallest platform/provider step;
8. reconcile actual result, including ambiguous timeout/process recreation;
9. durably commit observed result;
10. release reservation only when the saga is terminal or safely resumable.

### DEL-004 — no inferred deletion
A location transitions to `ABSENT` only after observation/reconciliation, never merely because a delete call or Android consent returned.

### DEL-005 — Free Up strict predicate

```text
mayRequestFreeUp(asset, ctx) =
  ctx.explicitUserScopeIncludesLocalRemoval
  AND acceptedWriterHeadFresh
  AND noKnownFork
  AND reservationHeld
  AND everyAuthoritativeResourceHasStableMatchingLocalSource
  AND everyAuthoritativeResourceHasFreshExactGalleryEvidence
  AND everyAuthoritativeResourceHasFreshExactColdArchiveEvidence
  AND recoverableGalleryCatalogCommitExists
  AND plannedRemainingLocationsSatisfyPolicy
```

Preview readiness is a UX policy, not proof of original safety.

### DEL-006 — multi-resource all-or-partial semantics
The predicate applies to **all** authoritative resources. A Live Photo with only one verified component is not fully protected.

### DEL-007 — long Android consent
Android consent cannot lock the MediaStore source. Therefore remote copies are not destroyed while local-consent outcome is unresolved when the requested scope would otherwise risk zero exact copies. After consent, reconcile every URI and source observation before continuing.

### DEL-008 — no misleading “Delete everywhere”
UI uses precise labels:

- `Free up device`;
- `Remove Gallery cloud copy, keep device`;
- `Remove Cold Archive copy`;
- `Delete from gallery and device`;
- `Erase all known copies`.

“Everywhere” must not imply unknown exports, recipients or provider internal backups are erased.

### DEL-009 — reservation ordering and cancellation generation
**MUST.** Reservations covering overlapping `ResourceVersion`/copy sets are acquired in a deterministic order. Post-operation copy counts include all other pending reserved mutations. The reservation remains held until reconciliation reaches a terminal or safely resumable state, including while Android consent UI is unresolved.

Immediately before every external destructive dispatch, re-check the operation’s cancellation generation, writer/head authority, reservation ownership and any provider revision precondition.

### DEL-010 — Android consent race is an explicit boundary
**MUST.** A system delete/consent dialog is not a conditional compare-and-delete of the exact bytes observed during earlier proof. The guarantee is therefore “the source matched the captured version at preflight”, not mathematical atomicity across an arbitrarily long user dialog.

Where the app has a platform contract that permits a second controlled delete after permission is granted, revalidate immediately before that delete. Where the platform itself performs deletion from a long-lived system request, keep reservations and protect remote copies until the result is reconciled; do not claim that a local `SAFE` flag eliminates external source mutation races.

---

## 10. Trash and retention

### TRASH-001 — 60-day Gallery Trash
**POLICY default.** Gallery deletion moves the asset to logical Trash for 60 days. Trash is visibility/retention state over existing copy locations; it is not an additional physical exact copy.

### TRASH-002 — future purge authorization
The original delete action records exactly what the future expiry is allowed to purge and whether the user explicitly authorized a future last-copy destruction.

### TRASH-003 — expiry safety
At 60 days:

- if the authorized purge leaves policy-satisfying exact copies, execute through the destructive saga;
- if it would remove the last exact copy and last-copy purge was not explicitly authorized, transition to `EXPIRED_AWAITING_CONFIRMATION`;
- Cold Archive is never purged merely because Gallery Trash expired unless the original explicit scope included it.

### TRASH-004 — restore race
Restore can cancel only a purge not yet irreversibly dispatched. If provider deletion is already ambiguous/dispatched, UI reports reconciliation/repair state rather than promising atomic undo.

### TRASH-005 — retention timing and policy immutability
**POLICY default.** “60-day Trash” means retain for **at least** 60 days, then purge at the next eligible execution opportunity unless an independently demonstrated server-side executor provides stronger timing. The original `TrashRecord` freezes the authorized future purge scope and last-copy authorization; changing the vault safety policy later does not silently broaden that authorization.

### TRASH-006 — local-only and retained-local Trash
A local-only asset can be hidden/trashed without deleting its source, but the UI must not promise cloud undo or freed device space when no protected remote copy exists. If the product implements a retained local Trash copy, that copy is durable storage governed as an `ExactCopyLocation`; it is never merely a `cacheDir` artifact.

---

## 11. User-visible storage model

### UX-001 — independent axes
For every resource version, UI state derives independently from:

- Gallery visibility: visible / archived / trashed;
- local exact original presence;
- Gallery Store exact copy presence/evidence;
- Cold Archive exact copy presence/evidence;
- private derivatives readiness.

`cloud-only` does not mean hidden.

### UX-002 — Cloud Storage screen
Provide filters/status for:

- Gallery cloud-only;
- on-device + Gallery;
- Cold Archive protected;
- local-only / excluded;
- archived/trashed;
- partial/multi-resource problem;
- verification/recovery problem.

### UX-003 — first cloud setup prompt
Once, at first cloud enablement, ask whether to enable Cold Archive protection. Recommended choice is `Archive-gated strict`. Do not prompt before every action; the selected vault safety policy governs later gates.

### UX-004 — exclusions
Deleting only the Gallery copy while keeping the local source creates a durable Gallery-backup exclusion so automatic backup does not immediately recreate it. Archive exclusion is separate.

### UX-005 — exclusion/tombstone durability and scope
**MUST.** Backup exclusions and deletion tombstones are authoritative user intent. If the product promises that this intent survives reinstall while private recovery remains available, the corresponding mutation must be durably committed to the recoverable private journal/snapshot before a remote deletion depends on it.

Identity suppression and content-based suppression are distinct. A tombstone for one resource/source identity does not silently ban every future import of identical bytes; an explicit re-import may create a new allowed revision. Conversely, a URI-only tombstone must not be advertised as preventing resurrection after source identity changes.

Keyless POS recovery alone does not recover secret exclusions/tombstones after loss of all private recovery material.

### UX-006 — readiness and verification are separate axes
UI and policy distinguish at least:

- uploaded bytes;
- recovery verification in progress / verified;
- safe-to-free under current policy;
- `RemoteViewReady`;
- `LocalPreviewReady`;
- `OfflineOriginalReady`.

A remote preview or small local thumbnail does not imply offline sharp viewing, and a successful upload callback does not imply a verified recoverable backup.

---

## 12. Private Gallery cryptography

### PRIV-001 — scope
Secret-key E2E applies to private Gallery structures/derivatives, not POS originals.

### PRIV-002 — keys
Baseline model:

- random 256-bit `VaultRootSecret`;
- local password/biometric unlock wraps/accesses the root locally;
- optional `RecoverySecret` recovers private Gallery state on a new device;
- per-private-blob random DEKs;
- authenticated encryption baseline: XChaCha20-Poly1305, format-versioned;
- device signing key for authorized private journal/control mutations.

### PRIV-003 — no false locked-worker guarantee
Because POS recovery is public, a locked background process with access to POS bytes can in principle reconstruct originals. Do not claim that app-lock cryptographically prevents reading historical POS originals. App-lock protects private Gallery state and UI surfaces, not keyless POS confidentiality.

### PRIV-004 — plaintext disk
Normal viewer/share/recovery paths should avoid persistent plaintext cache, but the phrase “only ciphertext on disk” must not be used as a confidentiality claim for POS because POS itself is reversibly public.

### PRIV-005 — private-vault trust bootstrap and rollback limit
**MUST.** After unwrapping private vault material, derive/check the expected root/wrap public identities and bind them to the expected vault identity; a chain that validates only against a public key carried inside the same replaceable chain is not a sufficient vault identity proof.

An already enrolled device pins its expected vault trust anchor/head. A fresh install cannot detect a fully self-consistent remote rollback without an independent freshness anchor; the product must not claim otherwise.

### PRIV-006 — rotations are separate operations
Local password change, `RecoverySecret` slot replacement, writer authorization revocation, root/private-encryption identity rotation and provider OAuth revocation are separate operations with separate effects.

Replacing `RecoverySecret` does not cryptographically revoke an attacker’s previously copied valid wrapper if the underlying root secret is unchanged. Revoking a writer certificate does not revoke provider OAuth or erase previously readable POS originals. Confidentiality revocation for private objects requires rotating the relevant secret/wrappers with an explicit old-data boundary.

### PRIV-007 — unlock-session generation and lock cleanup
Private UI/decode work is bound to an `UnlockSessionId`. On lock, cancel outstanding private requests where possible, close private UI surfaces, clear decoded caches/viewer pins/player buffers under app control, and reject any asynchronous result whose session generation is stale. Active share leases are a separately documented exception with their own lifecycle.

Secret/plaintext cleanup is best-effort. SQL/WAL pages, provider versions/trash, GPU/native buffers and already copied recipient bytes mean the product must not promise forensic physical erasure from an app-level delete.

---

## 13. One exact-original reader

### READ-001 — ExactOriginalReader
**MUST.** Viewer-original path, video, share, export, verification and standalone in-app recovery reuse one original-byte abstraction:

```text
ExactOriginalReader(resourceVersion, preferredLocation)
  -> sequentialRead(offset,length)
  -> randomRead(offset,length) where representation supports it
```

Backends:

- stable local exact source;
- single-part POS;
- multi-part POS;
- future compatibility readers for older published formats.

### READ-002 — revision-safe range reads
For cloud ranges, validate HTTP/provider range semantics: exact `Content-Range`, identity encoding, expected object revision, no mixed-revision assembly. A `200` response to a range request is handled explicitly, not interpreted as the requested offset.

### READ-003 — random access requirement for POS v1
**P0 protocol gate.** The chosen public transform/framing must support deterministic mapping from original byte ranges to bounded POS ranges so cloud-only video and large-file export do not require downloading the whole object.

### READ-004 — verified range transport contract
**MUST.** A ranged read validates object identity/revision, exact requested start/end/total, exact received byte count and representation encoding. `206` alone is insufficient. A `200` whole-object response, `416`, truncation and unsupported range semantics are explicit outcomes, not silently coerced into the requested offset.

Retries are bounded. Cache keys include vault/account/copy generation plus immutable content/object identity; a mutable provider path alone is not a cache key. Redirect handling must not forward OAuth credentials to an arbitrary redirect host unless the provider contract explicitly authorizes it.

---

## 14. Share and export

### SHARE-001 — Original means exact bytes
Original share/export reads through `ExactOriginalReader` and supplies the exact `ResourceVersion` byte stream. Success can be verified against trusted length/SHA-256 where the destination can be reread.

### SHARE-002 — seekable Android share
Preferred cloud-only path: app-owned `content://` provider / proxy file descriptor exposing seekable bytes on demand. No normal-path plaintext temp file is required. Compatibility temp plaintext is an explicit bounded fallback only.

### SHARE-003 — receiver caveat
The app guarantees bytes it exposes. A receiving app may recompress if it treats the item as media rather than a file/document.

### SHARE-004 — MediaStore export
Use pending/unpublished destination semantics where available, stream exact bytes, close, then publish. “Writer completed” and “destination later still equals source” are distinct facts; strong destination proof requires reread/hash when demanded.

### SHARE-005 — export proof levels
Export status distinguishes:

1. `STREAM_VERIFIED` — bytes emitted by `ExactOriginalReader` matched the intended source stream;
2. `DESTINATION_COMMITTED` — the destination accepted/closed/published according to its contract;
3. `DESTINATION_READBACK_VERIFIED` — reopening the destination and hashing its actual bytes matched the source.

The first two do not imply the third. Pending export identity is durably recorded when crash cleanup/reconciliation is required. For providers where a reliable reread is unavailable, the UI must not claim destination byte equality.

### SHARE-006 — Exact share uses a fixed ResourceVersion
**MUST.** “Original file / Exact” defaults to the app-owned seekable provider/proxy over a fixed `ResourceVersion`. Passing the original MediaStore URI directly is only a **CAPABILITY GATE** optimization after the target/API contract demonstrates that the recipient receives the same unredacted, untranscoded representation.

Share authorization is checked on open and on subsequent proxy callbacks. An already opened FD has its own bounded lifetime; revoking a URI cannot retract bytes already read by the recipient. Cloud-backed callbacks require bounded workers, timeout/cancellation and deadlock-safe scheduling.

---

## 15. Media ingest and support

### MEDIA-001 — archive support is format-agnostic
Unknown/future formats still receive exact POS backup/recovery.

### MEDIA-002 — viewer support is separate
Viewer/metadata support is capability-based for JPEG/PNG/WebP/HEIF/HEIC/AVIF/JXL/TIFF/BMP, RAW families, GIF/animated WebP/animated AVIF, MP4/MOV/MKV/WebM/3GP and future formats.

Unsupported viewer format shows a generic card; it does not fail backup.

### MEDIA-003 — compound assets
Motion Photo and Live Photo grouping never changes authoritative bytes. Pairing heuristics are metadata-based and must tolerate uncertain pairing rather than joining unrelated files.

### MEDIA-004 — malformed media isolation
Decoder/XMP/parser failure must never invalidate exact byte backup. Enforce dimension/parse/time/memory limits against decode bombs and malformed metadata.

### MEDIA-005 — ExactSourceResolver is API-specific
**MUST.** All ingest paths go through one `ExactSourceResolver` that records the representation guarantee actually provided by that API/provider. On Android this may require different contracts for MediaStore original-format descriptors, location-redaction requirements, SAF/document providers and picker/cloud-provider streams.

The resolver checks pending/trash/access state, reads to real EOF, records actual byte count and maps the result to `EXACT_ORIGINAL`, `USER_SUPPLIED_FILE` or `PLATFORM_REPRESENTATION`. A provider-supplied/redacted/transcoded stream may still be archived exactly, but must not be marketed as the device/camera original.

### MEDIA-006 — video proxy seekability is demonstrated, not inferred
A fragmented MP4 container is not automatically seekable. A generated proxy is declared random-seek capable only after the chosen muxer/extractor versions pass integration tests for usable index availability, duration, seeks across the full timeline, long GOPs, B-frames and VFR content.

If streaming fMP4 is used, the design must explicitly provide an extractor-supported index (`mfra` or another demonstrated mechanism); otherwise use an indexed representation with a proven seek path. Media fragment boundaries and POS/private-crypto record boundaries need not align; readers must tolerate crossing part/record boundaries.

### MEDIA-007 — proxy-FD image decode is a measured capability
**BENCHMARK / CAPABILITY GATE.** For formats supported by platform decoders, prototype `ExactOriginalReader -> proxy FD -> platform decoder/region decoder` before adding format-specific native random-access code. Enable it only when measured bytes-read, RAM and latency are acceptable. It is not assumed to save bandwidth for every decoder/format.

---

## 16. Gallery derivatives and performance

Correctness gates precede tuning.

### PERF-001 — required baseline derivatives
Core v1 keeps:

- one small encrypted thumbnail profile;
- one encrypted display-preview profile;
- optional video compatibility proxy when direct playback is unsuitable.

Deep zoom tiles, broad RAW development, multiple proxy classes and exotic cache policies are postponed until measured need.

### PERF-002 — UI priority
Priority:

`VISIBLE_UI > explicit user action/share/export > adjacent prefetch > backup > maintenance`.

Speculative prefetch never outranks an explicit user action.

### PERF-003 — timeline
Keep compact indexed timeline + keyset pagination. Fast scrub uses in-memory date/rank anchors, but anchor details are benchmark/policy, not durable protocol.

### PERF-004 — cache algorithms
W-TinyLFU/SIEVE/S3-FIFO or similar are not architectural commitments. Choose after traces on real devices.

### PERF-005 — powerful Android
Scheduler may scale decode/crypto/network concurrency aggressively on high-end devices while respecting UI latency, thermal headroom, provider throttling and memory pressure. Concurrency numbers are benchmark values, not protocol constants.

### PERF-006 — fairness and starvation control
The scheduler uses separate resource lanes/admission where useful. `VISIBLE_UI` gets reserved capacity, but no speculative prefetch may indefinitely starve an explicit share/export or authoritative backup. Background lanes use aging/minimum-progress rules so long-running backup can advance under sustained scrolling.

### PERF-007 — streaming derivative generation
Disposable derivatives whose final length/hash are unknown at start use a derivative-specific streaming format with footer/final descriptor or bounded encrypted local staging followed by upload. Network backpressure must not be allowed to block synchronous codec/muxer callbacks through an unbounded dependency chain.

Crash-resume of transcoding from an arbitrary byte offset is not a v1 guarantee. Baseline recovery is restart of the disposable derivative with a new derivative identity/key/nonce domain.

### PERF-008 — partial-pack/range reuse
**BENCHMARK.** A partial thumbnail/pack read must not automatically force later re-download of already verified useful ranges. The first implementation may use the common verified range cache or a partial-pack file plus verified-block map. Whole-pack completion is policy-driven by useful-byte fraction, network policy, free space and offline intent.

Coalescing bounds debounce, gap overfetch and total overfetch ratio. Cancelling one consumer must not cancel a shared read still needed by others; an explicit demand may promote an already-running prefetch.

### PERF-009 — timeline grouping must match ordering
**MUST.** Date/grouping semantics are explicit and stable. A date bucket used for rank/prefix counts must correspond to a contiguous interval of the full timeline ordering key. The implementation therefore chooses and documents either capture civil date with its timezone provenance and compatible sort key, or a single timeline timezone with compatible grouping.

Timezone changes, DST, unknown dates and user date corrections are test fixtures. Filtered/album timelines maintain their own count/anchor projections. Generation changes during a scrub require a retained window/versioned index, deferred generation swap, or explicit re-anchor rather than assuming an old snapshot still exists.

### PERF-010 — scrub placeholder truth
**MUST.** Immediate scrub feedback may use fixed geometry plus a date/day representative placeholder already in RAM. Per-item ThumbHash/thumbnail is shown only after the relevant metadata/window is loaded; the UI must not promise zero-DB-I/O access to data that is not resident.

### PERF-011 — global peak-memory admission
**MUST.** Memory admission accounts for peak—not only final bitmap bytes—including encoded buffers, decoder intermediates, RAW demosaic, gain maps, codec samples, native allocations, hardware bitmaps and GPU textures. One global admission controller coordinates decode/storage/network tokens and enforces hard source dimension/frame/parser bounds.

For 100–200 MP or similarly hostile inputs, no-OOM claims require a measured memory-bounded backend path. Otherwise the viewer may degrade/refuse decode while exact byte backup remains valid.

### PERF-012 — shared range/cache infrastructure
Use one `ExactOriginalReader` / verified range transport/storage substrate for original, video, tile, thumbnail-pack and share reads where representation permits. Different workloads get admission classes/quotas rather than independent integrity/retry implementations that duplicate bytes.

Coil remains owner of normal decoded-image caching unless measured requirements justify otherwise; viewport pins count against the same memory budget. Cache policy starts simple (LRU/2Q-class) and changes only on trace evidence.

### PERF-013 — private journal packaging is measured
**BENCHMARK.** Do not assume one remote immutable object per Asset scales acceptably. Compare signed-entry journal segments with a signed segment/head descriptor, bounded by bytes and max age, plus explicit flush for safety-critical commits. A snapshot limits tail replay.

Measure provider API calls per committed asset, signing cost and time-to-recover-head. Packaging may reduce object count but must not weaken commit visibility, signatures or recovery semantics.

### PERF-014 — query-plan regression is behavioral
Query-plan tests validate the intended access path, absence of unnecessary full sorts and real latency/visited-row scaling with depth/selectivity. They do not fail merely because SQLite prints the word `SCAN`. Row-value keyset pagination remains the baseline but is benchmarked on the exact SQLite/SQLCipher build used for release.

---

## 17. Background execution

### BG-001 — durable queue
Background jobs are resumable drainers over durable `control.db` work identities, not one WorkManager job per photo.

### BG-002 — user-initiated large transfers
Use the platform’s current user-initiated transfer mechanism when supported; automatic backup remains interruptible/reconcilable background work.

### BG-003 — kill-safe
Correctness does not depend on worker shutdown callbacks. Process death or OS stop at any state is recovered from durable intent + remote/source reconciliation.

### BG-004 — locked worker / private catalog coordination
A locked background worker may update only the minimum safety/control projections required for source binding, exclusions, operations and upload reconciliation. It does not directly mutate the decrypted private catalog snapshot.

Safety-critical events are durably emitted to the outbox/private journal and applied idempotently to the catalog after unlock/recovery. Asset/resource identities are stable across that replay. For multi-resource assets, the worker commits resource-set revision membership so the UI cannot mistake one uploaded component for a complete asset backup.

---

## 18. Provider contract

Each adapter publishes demonstrated capabilities rather than pretending providers are identical.

```text
ProviderCapabilities {
  resumableUpload
  stableObjectIdentity
  strongServerDigest
  rangeRead
  serverSideCopy
  copyDigestPreservation
  atomicCreateOrReservation
  conditionalMutationByRevision
  trash/version semantics
  maxObjectSize
}
```

### CLOUD-001 — normal namespace
Durable recovery objects live in a user-recoverable provider namespace, not solely in an app-private namespace whose disconnect may delete data.

### CLOUD-002 — ambiguous success
Every upload/delete/copy has stable idempotency identity and reconcile path for server-success/client-timeout.

### CLOUD-003 — integration evidence
Range/resume/copy/checksum/conditional-delete behavior must be tested on real accounts. Desktop mocks do not certify provider contracts.

### CLOUD-004 — path is not object existence
**MUST.** Provider presence distinguishes at least `PRESENT`, `NOT_AT_EXPECTED_PATH`, `ACCESS_DENIED`, `UNKNOWN` and `ABSENT_CONFIRMED`. A `404` or missing path does not prove destruction of the intended immutable object when rename/move, ACL loss or unstable paths are possible.

Use provider stable object identity when available, with path as an attribute. Async create/copy/delete operations use a durably pre-recorded target/idempotency identity and are reconciled to completion. Only provider-specific evidence sufficient for that adapter may produce `ABSENT_CONFIRMED`.

---

## 19. Recovery

### REC-001 — standalone original recovery
Given all required POS objects for a set, independent tooling must recover exact original bytes and report:

- missing/mixed parts;
- structural corruption;
- recovered byte length;
- internal whole-file hash result.

It does not claim hostile-provider author authenticity without an external trusted digest/signature anchor.

### REC-002 — app reinstall with private state
With `RecoverySecret`, rebuild private catalog/journal then hydrate thumbnails/previews lazily. With no `RecoverySecret`, originals remain recoverable from POS; private organization may be lost.

### REC-003 — restore safety vs timeline readiness
A partially hydrated restored gallery may display placeholders early, but destructive actions remain disabled until required control state, copy locations, evidence and writer history are fully reconstructed.

### REC-004 — authoritative-original GC requires lifecycle evidence
**MUST.** A sealed exact POS object is never garbage-collected solely because it is unreachable from the current private catalog. Distinguish incomplete staging, sealed-but-uncommitted candidate originals, committed Gallery originals and Cold Archive originals.

After ambiguous failure, sealed candidates are reconciled by immutable operation/object identity and then adopted, quarantined or explicitly retired. Archive originals require explicit lifecycle evidence plus current writer/reservation checks before GC. A grace period reduces races but is not a proof.

### REC-005 — snapshot/journal/tombstone GC
Before deleting history covered by a new private snapshot: upload and verify the snapshot, bind it to one accepted journal head, prove it can be restored under the supported recovery path, and retain at least one previous usable checkpoint according to policy. Tombstone/history GC must account for old devices and known branches/forks, not age alone.

### REC-006 — authenticated snapshot bootstrap
A fast-restore snapshot has an authenticated bootstrap manifest containing snapshot identity, accepted root/journal head, shard identities/hashes, counts and the timeline ranges each shard covers. Shards are independently authenticated before their rows are published; journal tail replay is relative to that exact accepted head.

`RestoreProgress`, visible timeline readiness and `SafetyHydrated` are separate. Missing referenced authoritative original is backup damage; missing derivative is degraded viewer readiness. No GC/Free Up runs from a partially hydrated safety model.

---

## 20. State-machine test obligations

Before implementation freeze/release, automated model-based/property tests must cover at least:

1. zero-byte, one-byte, unknown-size and huge inputs; hash only known at EOF;
2. incomplete POS, missing manifest, missing part, mixed set IDs, duplicate/reordered parts;
3. independent decoder on another platform with no Room/private vault state;
4. POS + internal hashes maliciously replaced: consistency distinguished from trusted authenticity;
5. resume after source changed with same size / misleading generation;
6. process death and **power loss** at every external-effect boundary;
7. control commit without catalog projection and vice versa;
8. two valid next writer heads, old offline writer, takeover during user consent;
9. Gallery delete and Archive delete racing;
10. long Android consent while source changes / proof expires;
11. Trash expiry when Gallery is last copy or policy changed since deletion;
12. restore request after purge already dispatched;
13. MediaStore full→partial, removable storage offline, MediaStore DB reset;
14. exact-source request returning a transcoded/redacted representation;
15. share reopen after app lock/process recreation;
16. destination export truncation/mutation after writer close;
17. HTTP wrong `Content-Range`, transparent encoding, mixed revisions;
18. video long GOP/B-frames/VFR/container metadata at tail;
19. cache shared-request cancellation and partial/full-range overlap;
20. timezone/DST/backdated timeline inserts;
21. decode finishing after app lock;
22. 200MP RAW, malformed XMP, giant-dimension/decode-bomb inputs.
23. source access full→partial/no-access and MediaStore provider-epoch reset without false absence;
24. exact-share direct URI returning a different/redacted/transcoded recipient view;
25. export stream succeeds but destination reread is truncated/mutated;
26. provider path disappears while stable object identity is still present or access is denied;
27. fMP4/proxy without usable seek index; long GOP/B-frame/VFR random seeks;
28. partial pack ranges followed by whole-pack completion and shared-request cancellation;
29. same content re-import after identity tombstone/exclusion;
30. private decode/player task completes after lock with stale `UnlockSessionId`;
31. snapshot shard/head mix from different checkpoints;
32. timeline bucket continuity under mixed timezone provenance and filter-specific anchors.

### TEST-001 — vertical safety path first
Implement/test in this order:

`exact source -> POS upload -> remote recovery readback -> trusted digest match -> committed Gallery state -> Free Up -> later exact recovery/export`

Start with still + unknown binary, then multi-resource asset, then multi-part huge video.

### TEST-002 — independent failure metrics
Track total uploaded/downloaded verification bytes. Two uploads + two full readbacks can approach ~4× source bytes before retries; this is a deliberate strict-safety cost unless a verified server-copy/evidence optimization is proven.

---

## 21. Implementation gates — not yet frozen

The architecture is **not implementation-final** until these P0 gates close:

1. exact POS v1 byte format: header/record/footer/manifest canonical encoding;
2. exact public reversible transform and random-access mapping;
3. part-size/record-size semantics (sizes may be policy values but representation must encode them);
4. reference decoder + cross-implementation golden fixtures;
5. provider-specific atomic reservation/CAS strategy for destructive cloud operations;
6. exact source-representation proof on supported Android versions;
7. remote verification policy proving when `SERVER_DIGEST` or `VERIFIED_SERVER_COPY` may safely substitute for full recovery readback;
8. private Gallery Blob v1 crypto serialization/nonce/KDF details;
9. private journal/snapshot recovery, trust bootstrap/rotation and fork-resolution protocol;
10. Android `ExactSourceResolver` contracts and MediaStore/source-epoch behavior for supported API levels;
11. exact-share/proxy-FD contract and video-proxy seekability on the pinned Media3/platform decoder set.

No pack/cache/codec micro-optimization is allowed to weaken these gates. Release builds also pin a tested Room/SQLite/SQLCipher/Media3 dependency set; “latest/current” is not a correctness contract.

---

## 22. Audit reconciliation — complete v26 audit

| Audit finding | Decision in v29 | Normative resolution |
|---|---|---|
| A01 E2E vs keyless recovery | **Accepted** | PROD-002/003, PRIV-001/003: authoritative originals are recoverable by a holder of POS objects; only private Gallery state/derivatives are secret-key E2E. |
| A02 no single active requirements set | **Accepted** | v29 is the sole normative source; rule classes and explicit versioning replace chronological supersedes. |
| A03 SHA in initial header | **Accepted** | DATA-004, POS-002/004: final length/hash appear only after EOF. |
| A04 one POB vs RemotePart | **Accepted** | POS-001/005 define a portable set; all required parts recover without private catalog state. |
| A05 strict order vs one read | **Accepted** | UP-001..004 separate deletion policy from transfer strategy. |
| A06 internal hash != provenance | **Accepted** | POS-006, VER-002/005 separate consistency, trusted-source match and authenticity. |
| A07 writerEpoch not a lock | **Accepted with platform limit** | WR-001..005 define accepted heads/forks and capability-gated fencing. |
| A08 two DBs / NORMAL durability | **Accepted** | DB-001..005 make one FULL safety-control DB authoritative and catalog replayable. |
| A09 resume crypto state | **Accepted** | UP-005/006 + DB-004: immutable/digest-guarded resume; changed plaintext never reuses nonce schedule; source snapshot guarantee is explicit. |
| A10 lease after proof | **Accepted** | DEL-002/003/009: reservation precedes proof, covers the whole affected set and remains through reconciliation. |
| A11 proof TTL vs long Android consent | **Accepted with platform limit** | DEL-007/010: preflight guarantee is explicit; direct delete is revalidated where possible; system-dialog race is not hidden. |
| A12 combined state enums | **Accepted** | DATA-005 + VER/UX projections store independent facts, not one lossy safety enum. |
| A13 double-counted ExactCopies | **Accepted** | COPY-001..003 count unique physical locations separately from logical state/failure domains. |
| A14 Trash expiry vs last-copy rule | **Accepted** | TRASH-002/003/005/006 freeze authorization, block unauthorized last-copy purge and define ≥60-day timing. |
| A15 orphan GC vs standalone archive | **Accepted** | REC-004/005 forbid catalog-reachability GC of sealed originals and define safe checkpoint/history GC. |
| B01 inaccessible != absent | **Accepted** | DATA-005/006 model access/scope/volume states and require a complete same-scope scan for `ABSENT_CONFIRMED`. |
| B02 MediaStore identity needs epoch | **Accepted** | DATA-006 includes provider database epoch/version and separates source binding from Resource identity. |
| B03 exact original acquisition incomplete | **Accepted as capability contract** | DATA-003 + MEDIA-005 + implementation gate 10 define `ExactSourceResolver` per API/provider representation. |
| B04 worker/private catalog coordination | **Accepted** | BG-004, DATA-007 and DB replay define locked-worker projections, outbox and compound commit groups. |
| B05 keyless files != Gallery semantics | **Accepted** | PROD-007/REC-002 explicitly split exact-byte recovery from private semantic recovery. |
| B06 trust bootstrap/rotation/rollback | **Accepted** | PRIV-005/006 define vault binding, rollback limit and separate rotation domains. |
| B07 deleting key/plaintext != forensic erasure | **Accepted** | PRIV-007 defines unlock-session invalidation, cache/task cleanup and best-effort erasure limits. |
| B08 tombstone/exclusion durability | **Accepted** | UX-005 + DB-004 make reinstall-surviving intent recoverable private state and distinguish identity/content suppression. |
| B09 export hash != destination proof | **Accepted** | SHARE-005 separates stream, commit and destination-readback verification. |
| B10 fast restore needs snapshot/head protocol | **Accepted, protocol still gated** | REC-003/005/006 define authenticated snapshot bootstrap and safety hydration; exact serialization remains implementation gate 9. |
| C01 missing path != deletion | **Accepted** | CLOUD-004, COPY-001 and DEL-004 separate path/access uncertainty from `ABSENT_CONFIRMED`. |
| C02 evidence must bind revision/downloadability | **Accepted** | VER-001/004/006 bind copy identity/revision, trusted digest and actual readability. |
| C03 Range contract underspecified | **Accepted** | READ-002/004 validate `Content-Range`, byte counts, encoding, revisions, redirects and explicit fallback/error states. |
| C04 direct MediaStore URI not exact-share guarantee | **Accepted** | SHARE-002/006 use an app-owned provider over fixed `ResourceVersion`; direct URI is capability-gated. |
| C05 fMP4 does not imply seekability | **Accepted as capability test** | MEDIA-006 requires demonstrated index/seek behavior on pinned muxer/extractor versions. |
| C06 proxy output length unknown | **Accepted** | PERF-007 uses derivative-specific footer/finalization or bounded encrypted staging and restart semantics. |
| C07 proxy FD for image decode | **Accepted as benchmark/capability candidate** | MEDIA-007 requires measurement before native-format-specific complexity. |
| C08 magic/spec ID insufficient for recovery | **Accepted** | POS-007/009 require a complete public decoding recipe, independent decoder/fixtures and non-executable bounded bootstrap metadata. |
| D01 prefetch can starve user action | **Accepted** | PERF-002/006 prioritize explicit action and add aging/minimum-progress fairness. |
| D02 verification total cost hidden | **Accepted** | TEST-002 + VER-003/004 + UX-006 expose upload/verification/safe-to-free stages and reuse valid evidence. |
| D03 offline thumbs != instant full viewer | **Accepted** | UX-006/PERF-001 separate local preview/offline original/remote readiness. |
| D04 partial pack can redownload bytes | **Accepted as benchmark** | PERF-008 defines reusable verified ranges and trace-driven completion policy. |
| D05 date bucket may not be contiguous | **Accepted** | PERF-009 requires grouping compatible with the full ordering key and generation-aware anchors. |
| D06 DB ThumbHash != RAM ThumbHash | **Accepted** | PERF-010 permits immediate geometry/day placeholder and delays per-item hash until loaded. |
| D07 memory budget must use peak | **Accepted** | PERF-011 + MEDIA-004 define global peak admission and hostile-input bounds. |
| D08 too many cache systems | **Accepted** | READ-001/PERF-012 unify verified range/storage infrastructure with logical quotas. |
| D09 journal object count | **Accepted as benchmark** | PERF-013 compares signed segments/head descriptors and measures provider API/recovery costs. |
| D10 singleton DB != one connection | **Accepted** | DB-006 allows a bounded reader pool with WAL/single-writer semantics. |
| D11 EXPLAIN `SCAN` is not a sufficient regression test | **Accepted** | PERF-014 defines behavioral/query-scaling regression criteria. |

---

## 23. Current readiness statement

v29 now reconciles the complete v26 audit finding-by-finding, but it remains a **normative architecture draft**, not a frozen storage protocol. Audit reconciliation removes known specification contradictions; it does not substitute for the protocol freezes, Android capability proofs, provider integration tests or benchmark evidence listed above.

Implementation may begin on reversible/non-destructive scaffolding (models, control/catalog projections, readers, test harness, provider probes and candidate derivative paths). Local-original deletion, remote destructive actions and claims of standalone/keyless recovery remain disabled until their corresponding section-21 gates and state-machine tests pass.
