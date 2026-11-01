"""The one wrapper around javac (docs/decisions.md D3).

Every Java build in this repo goes through `compile()`, so the flags that make
Java 8 class files live here and nowhere else. The javac comes from
`LAZYVM_JAVAC` or `PATH`; JDK 8 to 25 all work.

    python tools/javac.py --selftest
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import die, find_tool  # noqa: E402

# -J-Xmx: left alone, javac's heap may grow to a quarter of the machine's memory.
FLAGS = ["-J-Xmx1g", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-Xlint:-options", "-g"]


def compile(sources: list[Path], out: Path, bootclasspath: Path | None = None,
            classpath: list[Path] | None = None, sourcepath: Path | None = None) -> None:
    """Compile `sources` into `out` as major-52 class files, or exit."""
    if not sources:
        return
    out.mkdir(parents=True, exist_ok=True)
    cmd = [find_tool("LAZYVM_JAVAC", "javac"), *FLAGS, "-d", str(out)]
    if bootclasspath is not None:
        cmd += ["-bootclasspath", str(bootclasspath)]
    if classpath:
        cmd += ["-cp", os.pathsep.join(map(str, classpath))]
    if sourcepath is not None:
        cmd += ["-sourcepath", str(sourcepath)]
    # An argument file keeps long source lists under the Windows command limit.
    with tempfile.NamedTemporaryFile("w", suffix=".args", delete=False, encoding="utf-8") as f:
        for src in sources:
            f.write('"' + str(src).replace("\\", "/") + '"\n')
        argfile = f.name
    try:
        result = subprocess.run([*cmd, "@" + argfile])
    finally:
        Path(argfile).unlink(missing_ok=True)
    if result.returncode != 0:
        die("javac failed")


def class_major(path: Path) -> int:
    """The major version of a class file (JVMS 4.1)."""
    data = path.read_bytes()
    if data[:4] != b"\xca\xfe\xba\xbe":
        die(f"{path} is not a class file")
    return int.from_bytes(data[6:8], "big")


def selftest() -> None:
    """Compile a lambda and check the class file is version 52."""
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "Probe.java"
        src.write_text("public class Probe { Runnable r = () -> {}; }\n", encoding="utf-8")
        compile([src], Path(tmp) / "out")
        major = class_major(Path(tmp) / "out" / "Probe.class")
    if major != 52:
        die(f"javac emitted major version {major}, expected 52")
    print("javac selftest: PASS (major 52)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
