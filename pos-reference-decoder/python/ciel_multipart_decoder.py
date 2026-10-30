"""Independent CVMP1 recovery from a directory of downloaded .bin objects.

No Android account, application key, journal or encoder modules are required.
The destination is created exclusively and removed on any failed verification.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
from ciel_pos2_decoder import recover, _json_pairs

ID = re.compile(r"[0-9a-f]{32}")
SHA = re.compile(r"[0-9a-f]{64}")


def check(value, message):
    if not value:
        raise ValueError(message)


class BoundedBuffer(io.BytesIO):
    def write(self, data):
        check(self.tell() + len(data) <= 262144, "Manifest too large")
        return super().write(data)


def decode_object(directory, ref, output):
    check(isinstance(ref, dict) and ID.fullmatch(ref["id"]), "Invalid object ID")
    proof = ref["proof"]
    check(ID.fullmatch(proof["bundleId"]) and SHA.fullmatch(proof["originalSha256"]) and SHA.fullmatch(proof["containerSha256"]), "Invalid proof")
    path = directory / (ref["id"] + ".bin")
    check(path.stat().st_size == proof["containerBytes"], "Container length mismatch")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    check(digest.hexdigest() == proof["containerSha256"], "Container hash mismatch")
    result = recover(path, output, proof["originalBytes"], proof["originalSha256"])
    check(result["bundle_id"] == proof["bundleId"], "Container identity mismatch")
    dates = proof.get("times")
    if dates is not None:
        fields = {"modifiedSeconds": "mtime_seconds", "modifiedNanos": "mtime_nanos", "createdMillis": "creation_millis", "addedMillis": "media_added_millis", "takenMillis": "media_taken_millis"}
        for key, field in fields.items():
            check(result.get(field, "") == ("" if dates.get(key) is None else str(dates[key])), "Original dates mismatch")
    return result


def decode_manifest(directory, ref):
    check(0 < ref["proof"]["originalBytes"] <= 262144, "Invalid manifest bounds")
    buf = BoundedBuffer()
    decode_object(directory, ref, buf)
    return json.loads(buf.getvalue().decode("utf-8"), object_pairs_hook=_json_pairs)


def restore(root_file, directory, output_file, expected_sha=None):
    root_file, directory, output_file = Path(root_file), Path(directory), Path(output_file)
    check(root_file.resolve() != output_file.resolve(), "Source cannot be overwritten")
    root_buffer = BoundedBuffer()
    recover(root_file, root_buffer)
    root = json.loads(root_buffer.getvalue().decode("utf-8"), object_pairs_hook=_json_pairs)
    check(set(root) == {"format", "job", "name", "mime", "zone", "passport", "lastPage"} and root["format"] == "CVMP1", "Unknown root")
    check(ID.fullmatch(root["job"]) and root["zone"] in ("COLD", "GALLERY", "VIEW"), "Invalid root identity")
    p = root["passport"]
    size, chunk, count = p["bytes"], p["partBytes"], p["parts"]
    check(type(size) is int and 0 <= size <= 9223372036854775807 and type(chunk) is int and 65536 <= chunk <= 8388608, "Invalid original bounds")
    check(count == max(1, (size + chunk - 1) // chunk) and SHA.fullmatch(p["sha"]), "Invalid original passport")
    check(expected_sha is None or p["sha"] == expected_sha, "Root does not match trusted original")
    pages = (count + 63) // 64
    current, page_index, position = root["lastPage"], pages - 1, size
    created = False
    try:
        with output_file.open("x+b") as output:
            created = True
            while current is not None:
                check(page_index >= 0, "Extra or cyclic pages")
                page = decode_manifest(directory, current)
                check(set(page) == {"format", "job", "sha", "index", "previous", "parts"}, "Invalid page fields")
                check(page["format"] == "CVMP1-page" and page["job"] == root["job"] and page["sha"] == p["sha"] and page["index"] == page_index, "Wrong page identity or order")
                check((page_index == 0) == (page["previous"] is None), "Incomplete page chain")
                entries = page["parts"]
                check(isinstance(entries, list) and len(entries) == min(64, count - page_index * 64), "Missing or duplicate parts")
                for i in range(len(entries) - 1, -1, -1):
                    part = entries[i]
                    check(set(part) == {"index", "offset", "bytes", "sha", "objectRef"}, "Invalid part fields")
                    index = page_index * 64 + i
                    length = min(chunk, size - index * chunk)
                    check(part["index"] == index and part["offset"] == index * chunk and part["bytes"] == length and part["offset"] == position - length, "Gap, overlap or out-of-order part")
                    proof = part["objectRef"]["proof"]
                    check(proof["originalBytes"] == length and proof["originalSha256"] == part["sha"] and proof.get("times") == p.get("times"), "Wrong part passport")
                    output.seek(part["offset"])
                    decode_object(directory, part["objectRef"], output)
                    position = part["offset"]
                current, page_index = page["previous"], page_index - 1
            check(position == 0 and page_index == -1 and output.seek(0, 2) == size, "Incomplete original")
            output.seek(0)
            sha = hashlib.sha256()
            for block in iter(lambda: output.read(65536), b""):
                sha.update(block)
            check(sha.hexdigest() == p["sha"], "Whole original hash mismatch")
            output.flush(); os.fsync(output.fileno())
        dates = p.get("times") or {}
        if dates.get("modifiedSeconds") is not None:
            ns = dates["modifiedSeconds"] * 1000000000 + dates["modifiedNanos"]
            os.utime(output_file, ns=(output_file.stat().st_atime_ns, ns))
        return root
    except BaseException:
        if created:
            output_file.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root")
    parser.add_argument("--objects", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    value = restore(args.root, args.objects, args.output, args.expected_sha256)
    print(f"Recovered {value['passport']['bytes']} bytes, SHA-256 {value['passport']['sha']}")
