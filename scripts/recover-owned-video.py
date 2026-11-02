"""Recover only the pinned public 4K benchmark export, without exporting an OAuth session."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time

EXPECTED_SHA = "107383fd8921a882181c07ae494a57a828a091f52c414540e0314727e38ac129"
EXPECTED_BYTES = 1_001_836_975
APP = "com.cielvitrine.app"


def check(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    report = json.loads(subprocess.check_output(adb + ["shell", "run-as", APP, "cat", "files/owned-video-readback.json"]))
    check(report["phase"] == "export" and report["fixture"] == "video" and report["passed"], "No completed owned export")
    check(report["stage"] == "complete" and report["expected_sha256"] == EXPECTED_SHA, "Wrong fixture identity")
    check(report["original_bytes"] == EXPECTED_BYTES, "Wrong fixture length")
    directory, root_name = report["export_directory"], report["root_file"]
    check(re.fullmatch(r"[0-9]+", directory) and re.fullmatch(r"[0-9a-f]{32}\.(bin|cv2)", root_name), "Invalid export path")
    args.output_dir.mkdir(parents=True, exist_ok=False)
    archive = args.output_dir / "containers.tar"
    with archive.open("xb") as output:
        subprocess.run(adb + ["exec-out", "run-as", APP, "tar", "-C", "files/owned-video-export/" + directory, "-cf", "-", "."],
                       stdout=output, check=True, timeout=300)
    objects = args.output_dir / "objects"
    objects.mkdir()
    total, count = 0, 0
    with tarfile.open(archive) as source:
        for member in source:
            if member.isdir() and member.name in (".", "./"):
                continue
            name = member.name.removeprefix("./")
            check(member.isfile() and re.fullmatch(r"[0-9a-f]{32}\.(bin|cv2)", name), "Unexpected archive member")
            total += member.size
            count += 1
            check(total <= EXPECTED_BYTES + 8 * 1024 * 1024 and count <= 125, "Export exceeds fixture bounds")
            with source.extractfile(member) as incoming, (objects / name).open("xb") as output:
                shutil.copyfileobj(incoming, output, 1024 * 1024)
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pos-reference-decoder/python"))
    from ciel_multipart_decoder import restore
    restored = args.output_dir / "recovered-public-4k.mp4"
    start = time.monotonic()
    root = restore(objects / root_name, objects, restored, EXPECTED_SHA)
    digest = hashlib.sha256()
    with restored.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    check(restored.stat().st_size == EXPECTED_BYTES and digest.hexdigest() == EXPECTED_SHA, "Recovered original differs")
    dates = root["passport"].get("times") or {}
    expected_mtime = None
    if dates.get("modifiedSeconds") is not None:
        expected_mtime = dates["modifiedSeconds"] * 1_000_000_000 + dates["modifiedNanos"]
    result = {
        "passed": True, "original_bytes": restored.stat().st_size, "sha256": digest.hexdigest(),
        "independent_decoder_ms": round((time.monotonic() - start) * 1000),
        "downloaded_objects": count, "source_date_observations": dates,
        "destination_mtime_matches": None if expected_mtime is None else restored.stat().st_mtime_ns == expected_mtime,
        "cloud_export_report": report,
    }
    (args.output_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
