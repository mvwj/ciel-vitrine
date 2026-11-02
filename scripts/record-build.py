"""Record source and APK identity without copying configuration, credentials or media."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args])


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--output", type=Path, default=Path("artifacts/build-receipt.json"))
    args = parser.parse_args()
    root = Path(git("rev-parse", "--show-toplevel").decode().strip())
    # Only public source candidates. Ignored OAuth/session/build/media files never enter this hash.
    paths = sorted(set(git("ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0")) - {b""})
    source_hash = hashlib.sha256()
    for name in paths:
        path = root / name.decode("utf-8")
        source_hash.update(name + b"\0")
        source_hash.update((digest_file(path) if path.is_file() else "deleted").encode() + b"\0")
    report = {
        "schema": 1,
        "recorded_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "commit": git("rev-parse", "HEAD").decode().strip(),
        "dirty": bool(git("status", "--porcelain")),
        "public_source_sha256": source_hash.hexdigest(),
        "apks": [{"role": role, "bytes": path.stat().st_size, "sha256": digest_file(path)}
                 for role, path in [("app", args.apk), ("test", args.test_apk)] if path is not None],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
