"""Run the differential suite on LazyVM and diff against the goldens.

Prints one `PASS name` or `FAIL name` line per test, a unified diff for each
failure, and exits 1 if any test failed or has no golden.

    python tools/test.py
    python tools/test.py --filter s1 lang.String
    python tools/test.py --exe target/x86_64-unknown-linux-musl/release/lazyvm --no-build
"""

from __future__ import annotations

import argparse
import difflib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import testlib  # noqa: E402
from common import DIST, LAZYRT_CLASSES, TESTS_CLASSES, cargo_build_launcher, die  # noqa: E402


def check(exe: Path, test: testlib.Test, classpath: Path, boot: Path) -> tuple[bool, str]:
    """Run one test; return whether it passed and the diff if it did not."""
    if not test.golden.is_file():
        return False, f"  no golden {test.golden.name}\n"
    cmd = [str(exe), f"-Xbootclasspath:{boot}"]
    actual = testlib.run(cmd, test, classpath)
    expected = test.golden.read_text(encoding="utf-8")
    if actual == expected:
        return True, ""
    diff = difflib.unified_diff(expected.splitlines(True), actual.splitlines(True),
                                "golden", "lazyvm")
    return False, "".join(diff)


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # diffs hold any character
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--filter", nargs="*", help="substrings of test class names")
    parser.add_argument("--exe", type=Path, help="a prebuilt lazyvm binary")
    parser.add_argument("--no-build", action="store_true", help="skip cargo and javac")
    parser.add_argument("--build-java-only", action="store_true",
                        help="build lazyrt and the tests but not the launcher (use with --exe)")
    parser.add_argument("--jar", action="store_true",
                        help="run from tests.jar with lazyrt.jar as the boot path (S3-02)")
    args = parser.parse_args()
    tests = [t for t in testlib.discover(args.filter) if t.needs != "lazyos-net"
             and (t.needs != "windows" or sys.platform == "win32")]
    if not args.no_build:
        testlib.build(tests)
    classpath, boot = TESTS_CLASSES, LAZYRT_CLASSES
    if args.jar:
        classpath, boot = testlib.tests_jar(), DIST / "lazyrt.jar"
    skip_cargo = args.no_build or args.build_java_only
    exe = args.exe or (None if skip_cargo else cargo_build_launcher())
    if exe is None:
        die("--no-build and --build-java-only need --exe")
    failed = 0
    for test in tests:
        ok, detail = check(exe, test, classpath, boot)
        print(f"{'PASS' if ok else 'FAIL'} {test.name}")
        if not ok:
            failed += 1
            print(detail, end="")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
