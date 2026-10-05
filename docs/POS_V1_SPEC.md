# PortableOriginalSet (POS) v1 — Wire Format & Protocol Specification

**Status:** P0 Protocol Specification (Normative for Ciel Vitrine v29)  
**Specifies:** POS-001, POS-002, POS-003, POS-004, POS-005, POS-006, POS-007, POS-008, POS-009, READ-003.

---

## 1. Overview and Public Contract

A **PortableOriginalSet** (POS) is a public, self-describing, streamable, and reversible container format designed to durably preserve authoritative original files without requiring secret keys, database schemas, or platform dependencies.

### Invariants:
1. **Keyless Recoverability (PROD-002):** An independent decoder with zero proprietary code or secrets can recover byte-identical original files from a complete POS object or set of parts.
2. **Public Nature (PROD-003):** POS is not encrypted. It provides integrity verification and bounded streaming, not confidentiality.
3. **No Final Hash in Header (POS-002):** The header is written before the stream starts. Total length and SHA-256 appear only in the footer emitted after EOF (POS-004).
4. **Deterministic Random Access (READ-003):** Original byte offset $O$ maps directly to record sequence $S = \lfloor O / C \rfloor$ (where $C$ is the fixed chunk size), enabling bounded range reads for cloud video/export without downloading entire files.
5. **Bounded Parser Safety (POS-009):** All headers, counts, and string buffers are strictly bounded. Unknown format versions fail closed. No executable code is evaluated.

---

## 2. Binary Layout

A single-part POS file or part container has the following binary layout:

```text
+-----------------------------------------------------------+
| Bootstrap Header (Fixed 128 bytes)                        |
|   Magic: 'CIELPOS1' (8 bytes)                             |
|   Format version, Set ID, Part Index, Chunk Size, etc.   |
+-----------------------------------------------------------+
| Streamed Record 0                                         |
|   Magic: 'PREC' (4 bytes) | Seq: 0 | Offset: 0            |
|   Payload Length (4 bytes) | CRC-32 (4 bytes)             |
|   Transformed Payload (e.g. 65,536 bytes)                 |
+-----------------------------------------------------------+
| Streamed Record 1                                         |
|   ...                                                     |
+-----------------------------------------------------------+
| Streamed Record N - 1 (Last record, up to Chunk Size)     |
+-----------------------------------------------------------+
| Canonical Completion Footer (Fixed 160 bytes)             |
|   Magic: 'CIELEND1' (8 bytes)                             |
|   Total Original Length | Total SHA-256 (32 bytes)        |
|   Part Original Length  | Part SHA-256  (32 bytes)        |
|   Record Count          | Container SHA-256 (32 bytes)    |
|   Marker: 'COMPLETE' (8 bytes)                            |
+-----------------------------------------------------------+
```

---

## 3. Bootstrap Header Specification (128 bytes)

Byte order is big-endian (network byte order).

| Offset | Type | Field Name | Description |
|---|---|---|---|
| 0..7 | `char[8]` | `magic` | Exact ASCII `'CIELPOS1'` (`0x43 0x49 0x45 0x4C 0x50 0x4F 0x53 0x31`) |
| 8..9 | `uint16` | `formatVersion` | Normative version: `1` |
| 10..11 | `uint16` | `headerLength` | Header length: `128` |
| 12..27 | `uint8[16]` | `setId` | 128-bit UUID identifying the logical POS set |
| 28..31 | `uint32` | `partIndex` | 0-indexed part number |
| 32..35 | `uint32` | `partCount` | Expected total parts (`0` if streaming unknown) |
| 36..43 | `uint64` | `originalStartOffset` | Starting byte offset of this part in the whole original |
| 44..51 | `uint64` | `plannedPartLength` | Expected original byte count for this part (`0` if unknown) |
| 52..55 | `uint32` | `transformId` | Reversible transform algorithm: `0` = Identity, `1` = POS-XOR-V1 |
| 56..59 | `uint32` | `recordChunkSize` | Fixed nominal chunk size in bytes (normative default: `65536` = 64 KiB) |
| 60..63 | `uint32` | `flags` | Bit 0: `FLAG_IS_FINAL_PART_KNOWN`, Bit 1: `FLAG_ORIGINAL_LENGTH_KNOWN` |
| 64..95 | `char[32]` | `bootstrapDescriptor` | Non-executable ASCII string: `"CIEL_VITRINE_POS_V1_STANDARD\0..."` |
| 96..127 | `uint8[32]` | `reserved` | Reserved for future backward-compatible flags; must be zeroed |

---

## 4. Streamed Record Specification

Each record encapsulates a contiguous chunk of original source bytes.

| Offset | Type | Field Name | Description |
|---|---|---|---|
| 0..3 | `char[4]` | `recordMagic` | ASCII `'PREC'` (`0x50 0x52 0x45 0x43`) |
| 4..11 | `uint64` | `recordSeq` | 0-indexed sequential record counter within the part |
| 12..19 | `uint64` | `originalOffset` | Byte offset of this record's start in the whole original |
| 20..23 | `uint32` | `payloadLength` | Actual payload bytes following (1 to `recordChunkSize`) |
| 24..27 | `uint32` | `payloadCrc32` | IEEE 802.3 CRC-32 of the stored payload bytes |
| 28.. | `uint8[]` | `payload` | Transformed bytes of length `payloadLength` |

Framing overhead per record is $4 + 8 + 8 + 4 + 4 = 28$ bytes.

### Random Access Formula (READ-003):
For any original byte offset $O$ within part with `originalStartOffset` $O_{start}$:
- Record sequence $S = \lfloor (O - O_{start}) / \text{recordChunkSize} \rfloor$
- File offset in POS container for record $S$ (for all records $S < \text{last}$):
  $$\text{POS\_File\_Offset}(S) = \text{headerLength} + S \times (28 + \text{recordChunkSize})$$
- Record internal offset: $R_{offset} = (O - O_{start}) \pmod{\text{recordChunkSize}}$

---

## 5. Reversible Transforms (POS-003)

The transform is strictly public and deterministic:

### Transform 0: `TRANSFORM_IDENTITY`
$$C_i = P_i$$
No transformation is applied.

### Transform 1: `TRANSFORM_POS_XOR_V1`
$$C_i = P_i \oplus K_i$$
where keystream byte $K_i$ at original file byte offset $O_i$ is generated by:
$$K_i = (\text{setId}[\text{offset} \pmod{16}] \oplus (O_i \pmod{251})) \ \& \ 0\text{xFF}$$
Because XOR is its own inverse, recovery is simply:
$$P_i = C_i \oplus K_i$$
This satisfies obfuscation/portable transformation requirements without requiring cryptographic secrets or complex state machines.

---

## 6. Canonical Completion Footer Specification (160 bytes)

Emitted after reaching EOF of the source stream:

| Offset | Type | Field Name | Description |
|---|---|---|---|
| 0..7 | `char[8]` | `footerMagic` | ASCII `'CIELEND1'` (`0x43 0x49 0x45 0x4C 0x45 0x4E 0x44 0x31`) |
| 8..9 | `uint16` | `formatVersion` | Must match header `formatVersion` (`1`) |
| 10..13 | `uint32` | `partIndex` | Must match header `partIndex` |
| 14..21 | `uint64` | `totalOriginalByteLength`| Total original byte length across the entire set |
| 22..53 | `uint8[32]` | `totalOriginalSha256` | Full SHA-256 digest of original bytes across the set |
| 54..61 | `uint64` | `partOriginalByteLength` | Original bytes contributed by this specific part |
| 62..93 | `uint8[32]` | `partOriginalSha256` | SHA-256 of original bytes contributed by this part |
| 94..101 | `uint64` | `recordCount` | Exact number of records in this part |
| 102..133 | `uint8[32]` | `containerSha256` | SHA-256 digest of all preceding container bytes (header + all records) |
| 134..151 | `uint8[18]` | `reserved` | Zero-padded reserved bytes |
| 152..159 | `char[8]` | `completionMarker` | ASCII `'COMPLETE'` (`0x43 0x4F 0x4D 0x50 0x4C 0x45 0x54 0x45`) |

---

## 7. Multi-Part Set Manifest (POS-005)

For multi-part sets, a canonical JSON set manifest file (`<setId>.pos_manifest.json`) is generated:

```json
{
  "format": "CIEL_POS_SET_MANIFEST_V1",
  "setId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "totalOriginalByteLength": 104857600,
  "totalOriginalSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "partCount": 2,
  "parts": [
    {
      "partIndex": 0,
      "originalRange": [0, 52428800],
      "partOriginalByteLength": 52428800,
      "partOriginalSha256": "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
      "partFileName": "3fa85f64-5717-4562-b3fc-2c963f66afa6.part0.pos"
    },
    {
      "partIndex": 1,
      "originalRange": [52428800, 104857600],
      "partOriginalByteLength": 52428800,
      "partOriginalSha256": "ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39d",
      "partFileName": "3fa85f64-5717-4562-b3fc-2c963f66afa6.part1.pos"
    }
  ]
}
```
