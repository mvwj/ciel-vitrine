"""Check tracked source and optional reachable history without printing secret values.

Complements Gitleaks; this deliberately narrow check is not a security audit.
Run from any working directory inside the repository.
"""
import argparse
import pathlib
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args])


def check():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all-history", action="store_true")
    args = parser.parse_args()
    rules = {
        "absolute user directory": re.compile(rb"(?i)(?:[A-Z]:[/\\]+Users[/\\]+|/Users/|/home/)[a-z0-9_.~-]+"),
        "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "credential-shaped value": re.compile(rb"(?:y0_[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
        "hardcoded OAuth registration": re.compile(rb'val yandexClientId = "(?!0{32}")[a-f0-9]{32}"'),
    }
    forbidden = re.compile(r"(?i)(?:^|/)(?:\.env(?:\..+)?|oauth\.properties|local\.properties|[^/]+\.(?:keystore|jks|p12|pfx|pem|key|pyc|apk|aab))$")
    findings = set()

    def scan(path, data):
        if forbidden.search(path) and not path.endswith((".example", ".sample")):
            findings.add((path, "private/generated file"))
        for label, pattern in rules.items():
            if pattern.search(data):
                findings.add((path, label))

    root = pathlib.Path(git("rev-parse", "--show-toplevel").decode().strip())
    paths = git("ls-files", "-z").decode().split("\0")
    for path in filter(None, paths):
        file = root / path
        if file.is_file():
            scan(path, file.read_bytes())

    objects = {}
    if args.all_history:
        for line in git("rev-list", "--objects", "--all").decode().splitlines():
            oid, _, path = line.partition(" ")
            objects[oid] = path
        proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        try:
            for oid, path in objects.items():
                proc.stdin.write((oid + "\n").encode())
                proc.stdin.flush()
                header = proc.stdout.readline().split()
                if len(header) != 3:
                    raise RuntimeError("Cannot inspect history object")
                data = proc.stdout.read(int(header[2]))
                proc.stdout.read(1)
                if header[1] == b"blob":
                    scan(path, data)
                elif header[1] in (b"commit", b"tag"):
                    for address in re.findall(rb"(?m)^(?:author|committer|tagger) .* <([^>]+)>", data):
                        if not address.lower().endswith(b"@users.noreply.github.com"):
                            findings.add(("commit metadata", "use a GitHub noreply address"))
        finally:
            proc.stdin.close()
            proc.wait()

    for path, label in sorted(findings):
        print(f"FAIL {label}: {path}")
    print(f"Checked {len(list(filter(None, paths)))} tracked paths and {len(objects)} history objects; {len(findings)} findings.")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(check())
