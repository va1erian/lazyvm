"""Bless `java.*` goldens on the oracle JVM (docs/decisions.md D9).

The oracle is `LAZYVM_ORACLE_JAVA` or `java` on PATH (Temurin 8 preferred,
JDK 25 accepted). Helpful NullPointerException messages are turned off so
messages match Java 8. `lazy.*` tests are refused: their goldens are
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
from common import die, find_tool  # noqa: E402

ORACLE_FLAGS = ["-XX:-ShowCodeDetailsInExceptionMessages", "-Xshare:auto",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"]


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
    java = find_tool("LAZYVM_ORACLE_JAVA", "java")
    for test in tests:
        result = testlib.run([java, *ORACLE_FLAGS], test)
        test.golden.write_text(result, encoding="utf-8", newline="\n")
        print(f"blessed {test.name}")


if __name__ == "__main__":
    main()
