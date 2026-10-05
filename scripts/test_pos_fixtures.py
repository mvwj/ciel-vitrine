"""Run after :pos-format:test. Decodes actual production Kotlin output offline."""
import contextlib
import hashlib
import io
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pos-reference-decoder" / "python"))
from ciel_pos_decoder import decode_pos_file


def main():
    fixtures = list((ROOT / "build" / "pos-fixtures").glob("*.pos"))
    if len(fixtures) != 4:
        raise AssertionError("Run ./gradlew :pos-format:test --rerun-tasks first")
    with tempfile.TemporaryDirectory() as temp:
        for fixture in fixtures:
            output = Path(temp) / (fixture.stem + ".restored")
            with contextlib.redirect_stdout(io.StringIO()):
                result = decode_pos_file(str(fixture), str(output))
            expected = fixture.with_suffix(".original").read_bytes()
            assert output.read_bytes() == expected
            assert result["sha256"] == hashlib.sha256(expected).hexdigest()
            for mutation in (fixture.read_bytes()[:-1], fixture.read_bytes() + b"garbage"):
                broken = Path(temp) / "broken.pos"
                broken.write_bytes(mutation)
                try:
                    with contextlib.redirect_stdout(io.StringIO()):
                        decode_pos_file(str(broken))
                except ValueError:
                    pass
                else:
                    raise AssertionError("Corruption was accepted")
            print(f"PASS independent recovery: {fixture.name}, {len(expected)} exact bytes")


if __name__ == "__main__":
    main()
