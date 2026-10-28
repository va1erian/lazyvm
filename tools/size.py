"""Check the size budget: the VM binary and its class library, compressed (decisions.md D14).

The measure is what a user downloads: the release binary with its debug info
stripped and gzipped (-9), plus `dist/lazyrt.jar`, which is already deflated.
The sum must stay under BUDGET bytes. A Windows binary keeps its debug info in
the `.pdb`, so it is measured as built; an ELF binary is stripped first with
`strip` or `llvm-strip` from PATH.

    python tools/size.py                                   # the host release binary
    python tools/size.py --exe target/x86_64-unknown-linux-musl/release/lazyvm
"""

from __future__ import annotations

import argparse
import gzip
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import DIST, die, lazyvm_exe  # noqa: E402

BUDGET = 4_000_000
WARN_AT = 0.8


def shipped_binary(exe: Path) -> bytes:
    """The binary as shipped: ELF files lose their debug info and symbols, others stay as built."""
    data = exe.read_bytes()
    if not data.startswith(b"\x7fELF"):
        return data
    tool = shutil.which("strip") or shutil.which("llvm-strip")
    if not tool:
        die("an ELF binary needs strip or llvm-strip on PATH to be measured")
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / exe.name
        if subprocess.run([tool, "-o", str(out), str(exe)]).returncode != 0:
            die(f"{tool} failed on {exe}")
        return out.read_bytes()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--exe", type=Path, default=lazyvm_exe())
    parser.add_argument("--jar", type=Path, default=DIST / "lazyrt.jar")
    args = parser.parse_args()
    for path in (args.exe, args.jar):
        if not path.is_file():
            die(f"{path} does not exist; build it first (cargo build --release, tools/lazyrt.py)")
    binary = len(gzip.compress(shipped_binary(args.exe), 9))
    jar = args.jar.stat().st_size
    total = binary + jar
    print(f"size: binary {binary:,} B gzipped + lazyrt.jar {jar:,} B = {total:,} B "
          f"of {BUDGET:,} B ({total / BUDGET:.0%})")
    if total >= BUDGET:
        die("over the size budget (decisions.md D14): shrink something before adding more")
    if total > BUDGET * WARN_AT:
        print(f"size: warning: above {WARN_AT:.0%} of the budget")


if __name__ == "__main__":
    main()
