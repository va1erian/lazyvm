"""Bless `java.*` goldens on the oracle JVM (docs/decisions.md D9).

The oracle is Java 8: `LAZYVM_ORACLE_JAVA` (Temurin 8), else `java` on PATH
if that is a Java 8. A test with `// ORACLE: modern` is blessed on JDK 19 or
newer instead (`LAZYVM_ORACLE_JAVA_MODERN`, else `java` on PATH), for the
float printing exception in D9. `lazy.*` tests are refused: their goldens are
hand-written.

    python tools/golden.py --bless tests/java/lang/StringBasics.java
    python tools/golden.py --bless tests/java/s1
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import testlib  # noqa: E402
import re  # noqa: E402
import subprocess  # noqa: E402

from common import die, find_tool  # noqa: E402

# IgnoreUnrecognizedVMOptions lets JDK 8, which has no helpful NPE messages, accept the flag.
ORACLE_FLAGS = ["-XX:+IgnoreUnrecognizedVMOptions", "-XX:-ShowCodeDetailsInExceptionMessages",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                # The OS regional settings must not leak into goldens: LazyVM defaults to en-US.
                "-Duser.language=en", "-Duser.country=US", "-Duser.variant=", "-Duser.script="]


def java_major(java: str) -> int:
    """The feature version of a `java` binary: 8 for 1.8, 25 for 25."""
    out = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
    m = re.search(r'version "(?:1\.)?(\d+)', out)
    if not m:
        die(f"cannot tell the version of {java}")
    return int(m.group(1))


def oracle(modern: bool) -> str:
    """The oracle binary for a test, checked to be the right version."""
    env = "LAZYVM_ORACLE_JAVA_MODERN" if modern else "LAZYVM_ORACLE_JAVA"
    java = find_tool(env, "java")
    major = java_major(java)
    if modern and major < 19:
        die(f"{java} is Java {major}; ORACLE: modern needs 19 or newer ({env})")
    if not modern and major != 8:
        die(f"{java} is Java {major}; the oracle must be Java 8 (set {env})")
    return java


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bless", nargs="+", type=Path, required=True,
                        help="test sources or directories")
    args = parser.parse_args()
    tests = testlib.discover(paths=[p.resolve() for p in args.bless])
    lazy = [t.name for t in tests if t.is_lazy]
    if lazy:
        die("lazy.* goldens are hand-written, not blessed: " + ", ".join(lazy))
    if not tests:
        die("no tests found")
    testlib.build(tests)
    for test in tests:
        result = testlib.run([oracle(test.modern_oracle), *ORACLE_FLAGS], test)
        if result.startswith(testlib.TIMEOUT_MARK):
            die(f"{test.name} timed out on the oracle; not blessed")
        if testlib.HUNG_MARK in result:
            die(f"{test.name} hung on the oracle; not blessed: {result[result.index(testlib.HUNG_MARK):]}")
        test.golden.write_text(result, encoding="utf-8", newline="\n")
        print(f"blessed {test.name}")


if __name__ == "__main__":
    main()
