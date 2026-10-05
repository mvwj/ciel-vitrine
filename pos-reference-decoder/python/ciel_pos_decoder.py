#!/usr/bin/env python3
"""
Ciel Vitrine — PortableOriginalSet (POS) v1 Standalone Reference Decoder
Normative reference implementation (POS-007, POS-009).

Requires only Python 3.6+ standard library (struct, hashlib, zlib, os, sys, argparse).
Zero external dependencies. Fully offline.
"""

import sys
import os
import struct
import hashlib
import zlib
import argparse

HEADER_MAGIC = b'CIELPOS1'
RECORD_MAGIC = b'PREC'
FOOTER_MAGIC = b'CIELEND1'
MARKER_COMPLETE = b'COMPLETE'

HEADER_LENGTH = 128
RECORD_HEADER_LENGTH = 28
FOOTER_LENGTH = 160

TRANSFORM_IDENTITY = 0
TRANSFORM_POS_XOR_V1 = 1


def inverse_transform(transform_id, set_id, orig_offset, payload):
    """Reverses the public POS transform."""
    if transform_id == TRANSFORM_IDENTITY:
        return payload
    elif transform_id == TRANSFORM_POS_XOR_V1:
        out = bytearray(len(payload))
        for i, byte in enumerate(payload):
            curr_offset = orig_offset + i
            set_id_byte = set_id[curr_offset % 16]
            offset_mod = curr_offset % 251
            key = (set_id_byte ^ offset_mod) & 0xFF
            out[i] = byte ^ key
        return bytes(out)
    else:
        raise ValueError(f"Unsupported POS transform ID: {transform_id}")


def decode_pos_file(input_path, output_path=None):
    """
    Decodes a POS v1 file into its exact original bytes.
    Validates record CRC-32, container completion, byte length, and SHA-256.
    """
    if output_path and os.path.realpath(input_path) == os.path.realpath(output_path):
        raise ValueError("Input and output must differ")
    with open(input_path, 'rb') as f:
        # 1. Read and parse 128-byte header
        header_bytes = f.read(HEADER_LENGTH)
        if len(header_bytes) < HEADER_LENGTH:
            raise ValueError(f"Truncated file: cannot read {HEADER_LENGTH} bytes header")

        magic, version, h_len = struct.unpack('>8sHH', header_bytes[0:12])
        if magic != HEADER_MAGIC:
            raise ValueError(f"Invalid POS header magic: {magic}")
        if version != 1:
            raise ValueError(f"Unsupported POS format version: {version}")

        set_id = header_bytes[12:28]
        part_idx, part_cnt, start_offset, planned_len, transform_id, chunk_size, flags = struct.unpack(
            '>IIQQIII', header_bytes[28:64]
        )
        if h_len != 128 or not 1 <= chunk_size <= 4194304 or transform_id not in (0, 1):
            raise ValueError("Invalid header bounds/transform")
        if (part_cnt and part_idx >= part_cnt) or flags & ~7 or any(header_bytes[96:128]):
            raise ValueError("Invalid part/flags/reserved bytes")

        desc_raw = header_bytes[64:96]
        desc = desc_raw.split(b'\x00')[0].decode('ascii', errors='ignore')
        if desc_raw != b'CIEL_VITRINE_POS_V1_STANDARD'.ljust(32, b'\x00'):
            raise ValueError("Unsupported bootstrap descriptor")

        print(f"[POS-Decoder] Header OK: SetID={set_id.hex()}, Part={part_idx}/{part_cnt}, Transform={transform_id}, Desc='{desc}'")

        # Prepare output
        out_f = open(output_path, 'wb') if output_path else None
        hasher = hashlib.sha256()
        record_count = 0
        total_recovered_bytes = 0
        container_hasher = hashlib.sha256(header_bytes)
        short_record_seen = False

        try:
            while True:
                probe = f.read(8)
                if len(probe) < 8:
                    raise ValueError("Unexpected EOF before completing POS file")

                # Check if we hit the footer
                if probe == FOOTER_MAGIC:
                    # Seek back the 8 bytes of the probe
                    f.seek(-8, os.SEEK_CUR)
                    break

                # Otherwise this is a record
                f.seek(-8, os.SEEK_CUR)
                rec_hdr_bytes = f.read(RECORD_HEADER_LENGTH)
                if len(rec_hdr_bytes) < RECORD_HEADER_LENGTH:
                    raise ValueError("Truncated record header")

                r_magic, r_seq, r_offset, r_len, r_crc = struct.unpack('>4sQQII', rec_hdr_bytes)
                if r_magic != RECORD_MAGIC:
                    raise ValueError(f"Corrupted record magic: {r_magic}")
                if r_seq != record_count:
                    raise ValueError(f"Out of order record sequence: expected {record_count}, got {r_seq}")
                if short_record_seen or not 1 <= r_len <= chunk_size or r_offset != start_offset + total_recovered_bytes:
                    raise ValueError("Invalid record length/offset")
                short_record_seen = r_len < chunk_size

                r_payload = f.read(r_len)
                if len(r_payload) < r_len:
                    raise ValueError(f"Truncated payload in record {r_seq}")
                container_hasher.update(rec_hdr_bytes)
                container_hasher.update(r_payload)

                # Verify CRC32
                computed_crc = zlib.crc32(r_payload) & 0xFFFFFFFF
                if computed_crc != r_crc:
                    raise ValueError(f"CRC-32 mismatch in record {r_seq}: expected {r_crc:#010x}, got {computed_crc:#010x}")

                # Inverse transform
                plain_bytes = inverse_transform(transform_id, set_id, r_offset, r_payload)
                hasher.update(plain_bytes)
                total_recovered_bytes += len(plain_bytes)
                record_count += 1

                if out_f:
                    out_f.write(plain_bytes)

            # 2. Read and parse 160-byte footer
            footer_bytes = f.read(FOOTER_LENGTH)
            if len(footer_bytes) < FOOTER_LENGTH:
                raise ValueError("Truncated POS footer")

            f_magic, f_ver, f_part_idx, total_len, total_sha, part_len, part_sha, f_rec_count, cont_sha = struct.unpack(
                '>8sHIQ32sQ32sQ32s', footer_bytes[0:134]
            )
            marker = footer_bytes[152:160]

            if f_magic != FOOTER_MAGIC:
                raise ValueError(f"Corrupted footer magic: {f_magic}")
            if f_ver != version or f_part_idx != part_idx or any(footer_bytes[134:152]):
                raise ValueError("Footer identity/reserved mismatch")
            if container_hasher.digest() != cont_sha or f.read(1):
                raise ValueError("Container digest mismatch or trailing bytes")
            if marker != MARKER_COMPLETE:
                raise ValueError(f"Missing COMPLETE marker: container is incomplete or corrupted")
            if f_rec_count != record_count:
                raise ValueError(f"Record count mismatch: footer says {f_rec_count}, read {record_count}")
            if part_len != total_recovered_bytes:
                raise ValueError(f"Length mismatch: footer says {part_len}, recovered {total_recovered_bytes}")

            actual_sha = hasher.digest()
            if part_cnt == 1 and (part_idx != 0 or start_offset != 0 or total_len != total_recovered_bytes or total_sha != actual_sha):
                raise ValueError("Whole original mismatch")
            if actual_sha != part_sha:
                raise ValueError(f"SHA-256 digest mismatch!\nExpected: {part_sha.hex()}\nActual:   {actual_sha.hex()}")

            print(f"[POS-Decoder] SUCCESS: Recovered {total_recovered_bytes} bytes in {record_count} records.")
            print(f"[POS-Decoder] SHA-256 verified: {actual_sha.hex()}")

            return {
                "recovered_bytes": total_recovered_bytes,
                "record_count": record_count,
                "sha256": actual_sha.hex(),
                "verified": True
            }

        finally:
            if out_f:
                out_f.close()


def main():
    parser = argparse.ArgumentParser(description="Ciel Vitrine POS v1 Reference Decoder")
    parser.add_argument("input", help="Path to input .pos file")
    parser.add_argument("-o", "--output", help="Path to output recovered original file")
    args = parser.parse_args()

    try:
        decode_pos_file(args.input, args.output)
        sys.exit(0)
    except Exception as e:
        print(f"[POS-Decoder] ERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
