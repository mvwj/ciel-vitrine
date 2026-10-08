"""Decode both Kotlin and Python fixtures with the independent AES implementation."""
import hashlib
import io
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pos-reference-decoder" / "python"))
from ciel_pos2_decoder import recover

fixtures = list((ROOT / "build" / "pos2-fixtures").glob("*.cv2"))
assert len(fixtures) == 10, "Run :pos-format:test with the recovery dependency installed"
for fixture in fixtures:
    expected = fixture.with_name(fixture.name.split(".")[0] + ".original").read_bytes()
    output = io.BytesIO()
    recover(fixture, output, len(expected), hashlib.sha256(expected).hexdigest())
    assert output.getvalue() == expected
    print(f"PASS independent AES recovery: {fixture.name}, {len(expected)} exact bytes")
