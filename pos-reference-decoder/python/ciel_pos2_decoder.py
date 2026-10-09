"""Independent CVPOS2/CVPOS3 reader/writer. The bootstrap is data and is never executed.

Requires standard AES-GCM from cryptography. The public recovery key is in the file.
Output is created exclusively and removed on failure; pre-existing files are preserved.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import struct
import uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"CVPOS2\r\n"
MAGIC3 = b"CVPOS3\r\n"
TIME_KEYS = {"mtime_seconds", "mtime_nanos", "creation_millis", "media_added_millis", "media_taken_millis"}

def validate_times(meta):
    values = {}
    for field in TIME_KEYS:
        text = meta[field]
        if text == "": values[field] = None; continue
        if not re.fullmatch(r"0|-?[1-9][0-9]{0,17}", text): raise ValueError("Invalid source time")
        values[field] = int(text)
    s, ns = values["mtime_seconds"], values["mtime_nanos"]
    if (s is None) != (ns is None): raise ValueError("Incomplete mtime")
    if s is not None and not (-62135596800 <= s <= 253402300799 and 0 <= ns <= 999999999): raise ValueError("Invalid mtime")
    for field in TIME_KEYS - {"mtime_seconds", "mtime_nanos"}:
        if values[field] is not None and not 0 <= values[field] <= 253402300799999: raise ValueError("Invalid date")
MAX_CHUNK = 1048576
MAX_RECORDS = 4294967294


def _json_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate bootstrap key")
        result[key] = value
    return result


def read_bootstrap(stream):
    fixed = stream.read(12)
    if len(fixed) != 12 or fixed[:8] not in (MAGIC, MAGIC3):
        raise ValueError("Not CVPOS2/3")
    v3 = fixed[:8] == MAGIC3
    size = struct.unpack(">I", fixed[8:])[0]
    if not 1 <= size <= 65536:
        raise ValueError("Invalid bootstrap size")
    raw = stream.read(size)
    if len(raw) != size:
        raise ValueError("Truncated bootstrap")
    meta = json.loads(raw.decode("utf-8"), object_pairs_hook=_json_pairs)
    if not isinstance(meta, dict) or any(isinstance(v, (list, dict)) for v in meta.values()):
        raise ValueError("Bootstrap must be flat")
    if meta.get("format") != ("CVPOS3" if v3 else "CVPOS2") or meta.get("version") != (3 if v3 else 2) or meta.get("cipher") != "AES-256-GCM":
        raise ValueError("Unknown format")
    keys = {"format", "version", "cipher", "key_hex", "nonce_prefix_hex", "bundle_id", "chunk_bytes", "name", "mime", "recipe"}
    if set(meta) != keys | (TIME_KEYS if v3 else set()) or any(type(v) is not (int if k in ("version", "chunk_bytes") else str) for k, v in meta.items()):
        raise ValueError("Invalid bootstrap fields")
    for field, length in (("key_hex", 64), ("nonce_prefix_hex", 8), ("bundle_id", 32)):
        if not re.fullmatch(r"[0-9a-fA-F]{" + str(length) + r"}", meta[field]):
            raise ValueError("Invalid hex field")
    key = bytes.fromhex(meta["key_hex"])
    nonce = bytes.fromhex(meta["nonce_prefix_hex"])
    bundle = bytes.fromhex(meta["bundle_id"])
    if len(key) != 32 or len(nonce) != 4 or len(bundle) != 16:
        raise ValueError("Invalid bootstrap lengths")
    if type(meta["chunk_bytes"]) is not int or not 65536 <= meta["chunk_bytes"] <= MAX_CHUNK:
        raise ValueError("Invalid chunk size")
    if not isinstance(meta["recipe"], str) or not meta["recipe"].startswith("CVPOS3 byte layout v3." if v3 else "CVPOS2 byte layout v2."):
        raise ValueError("Missing recovery recipe")
    if len(meta["name"].encode("utf-16-le")) > 2048 or len(meta["mime"].encode("utf-16-le")) > 512:
        raise ValueError("Oversized metadata strings")
    if v3: validate_times(meta)
    return meta, fixed + raw, key, nonce


def recover(source, destination, expected_bytes=None, expected_sha256=None):
    original = hashlib.sha256()
    prefix = hashlib.sha256()
    offset = count = 0
    short_seen = False
    with open(source, "rb") as stream:
        meta, header, key, nonce = read_bootstrap(stream)
        header_hash = hashlib.sha256(header).digest()
        prefix.update(header)
        aes = AESGCM(key)
        while True:
            record = stream.read(24)
            if len(record) != 24:
                raise ValueError("Missing/truncated record")
            kind, sequence, position, length = struct.unpack(">4sQQI", record)
            if sequence != count or position != offset:
                raise ValueError("Wrong sequence/offset")
            if kind == b"DATA":
                if count >= MAX_RECORDS or short_seen or not 1 <= length <= meta["chunk_bytes"]:
                    raise ValueError("Invalid DATA bounds")
                if expected_bytes is not None and length > expected_bytes - offset:
                    raise ValueError("Original exceeds trusted length")
            elif kind == b"DONE":
                if length != 64:
                    raise ValueError("Invalid DONE bounds")
            else:
                raise ValueError("Unknown record")
            encrypted = stream.read(length + 16)
            if len(encrypted) != length + 16:
                raise ValueError("Truncated ciphertext")
            plain = aes.decrypt(nonce + struct.pack(">Q", count), encrypted, header_hash + record)
            if kind == b"DATA":
                prefix.update(record); prefix.update(encrypted)
                original.update(plain)
                destination.write(plain)
                offset += length; count += 1; short_seen = length < meta["chunk_bytes"]
            else:
                if plain[:32] != original.digest() or plain[32:] != prefix.digest() or stream.read(1):
                    raise ValueError("Digest/trailing data mismatch")
                if expected_bytes is not None and offset != expected_bytes:
                    raise ValueError("Trusted length mismatch")
                if expected_sha256 is not None and original.hexdigest() != expected_sha256.lower():
                    raise ValueError("Trusted hash mismatch")
                return dict(meta, original_bytes=offset, original_sha256=original.hexdigest())


def encode(source, destination, recipe_source):
    with open(recipe_source, "rb") as stream:
        template = read_bootstrap(stream)[0]; recipe = template["recipe"]
    key, nonce = os.urandom(32), os.urandom(4)
    meta = dict(format="CVPOS2", version=2, cipher="AES-256-GCM", key_hex=key.hex(),
                nonce_prefix_hex=nonce.hex(), bundle_id=uuid.uuid4().hex, chunk_bytes=65536,
                name=Path(source).name, mime="application/octet-stream", recipe=recipe)
    if template["version"] == 3:
        meta.update(format="CVPOS3", version=3, **{k: template[k] for k in TIME_KEYS})
    raw = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    header = (MAGIC3 if meta["version"] == 3 else MAGIC) + struct.pack(">I", len(raw)) + raw
    header_hash = hashlib.sha256(header).digest()
    original, prefix = hashlib.sha256(), hashlib.sha256(header)
    aes = AESGCM(key)
    count = offset = 0
    with open(source, "rb") as stream:
        destination.write(header)
        while True:
            plain = stream.read(65536)
            if not plain:
                break
            if count >= MAX_RECORDS:
                raise ValueError("Too many records")
            record = struct.pack(">4sQQI", b"DATA", count, offset, len(plain))
            encrypted = aes.encrypt(nonce + struct.pack(">Q", count), plain, header_hash + record)
            destination.write(record); destination.write(encrypted)
            prefix.update(record); prefix.update(encrypted); original.update(plain)
            count += 1; offset += len(plain)
        record = struct.pack(">4sQQI", b"DONE", count, offset, 64)
        destination.write(record)
        destination.write(aes.encrypt(nonce + struct.pack(">Q", count), original.digest() + prefix.digest(), header_hash + record))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("--output", required=True)
    parser.add_argument("--encode", action="store_true")
    parser.add_argument("--recipe-source")
    args = parser.parse_args()
    output = Path(args.output)
    created = False
    try:
        with output.open("xb") as destination:
            created = True
            if args.encode:
                if not args.recipe_source:
                    raise ValueError("Encoding requires a full recipe source")
                encode(args.source, destination, args.recipe_source)
            else:
                result = recover(args.source, destination)
                print(f"Recovered {result['original_bytes']} bytes, SHA-256 {result['original_sha256']}")
            destination.flush(); os.fsync(destination.fileno())
        if not args.encode and result["version"] == 3:
            if result["mtime_seconds"]:
                modified_ns = int(result["mtime_seconds"]) * 1000000000 + int(result["mtime_nanos"])
                try:
                    os.utime(output, ns=(output.stat().st_atime_ns, modified_ns))
                    if output.stat().st_mtime_ns != modified_ns: print("Destination mtime precision differs; original dates remain in the container.")
                except OSError: print("Destination cannot restore mtime; original dates remain in the container.")
            print("Original date observations: " + json.dumps({k: result[k] for k in sorted(TIME_KEYS)}))
    except Exception:
        if created:
            output.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    main()
