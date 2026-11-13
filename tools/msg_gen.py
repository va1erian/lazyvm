"""Regenerate (or check) the Messenger classes the lazy.msg tests use (docs/msg.md).

    python tools/msg_gen.py --lazyos <LazyOS checkout> [--check]

LazyOS's `midlc --java` writes, under tests/msg/gen, the classes of the
interfaces below and of tests/msg/demo.midl, and its `conformance_java.py` writes the classes of its
conformance corpus there too, with tests/java/lazy/msg/Conformance.java. They
are checked in, so the test suite and CI need no LazyOS checkout; `--check`
fails when they are not what the given checkout generates.
"""

from __future__ import annotations

import argparse
import filecmp
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ROOT, TESTS_SRC, die  # noqa: E402

GEN = ROOT / "tests" / "msg" / "gen"
CONFORMANCE = TESTS_SRC / "lazy" / "msg" / "Conformance.java"
# The LazyOS interfaces the tests call, serve or subscribe to, and the tests' own.
IDLS = ["echo.midl", "notify.midl", "confd.midl", "healthd.midl"]
DEMO = ROOT / "tests" / "msg" / "demo.midl"


def generate(lazyos: Path, gen: Path, test: Path) -> None:
    midlc = lazyos / "tools" / "midlc"
    idls = [str(lazyos / "idl" / i) for i in IDLS] + [str(DEMO)]
    run([sys.executable, str(midlc / "midlc.py"), "--java", str(gen), *idls])
    run([sys.executable, str(midlc / "conformance_java.py"), "--test", str(test), "--gen", str(gen)])


def run(cmd: list[str]) -> None:
    done = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if done.returncode != 0:
        die(f"{' '.join(cmd[1:2])} failed:\n{done.stderr}")


def same_tree(a: Path, b: Path) -> list[str]:
    """Relative paths that differ between two trees, or exist in only one."""
    files_a = {p.relative_to(a) for p in a.rglob("*") if p.is_file()}
    files_b = {p.relative_to(b) for p in b.rglob("*") if p.is_file()} if b.is_dir() else set()
    differ = [str(p) for p in files_a ^ files_b]
    differ += [str(p) for p in files_a & files_b if not filecmp.cmp(a / p, b / p, shallow=False)]
    return sorted(differ)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lazyos", type=Path, required=True, help="a LazyOS checkout")
    parser.add_argument("--check", action="store_true", help="fail if the checked-in files are stale")
    args = parser.parse_args()
    if not (args.lazyos / "tools" / "midlc" / "midlc.py").is_file():
        die(f"{args.lazyos} is not a LazyOS checkout")
    with tempfile.TemporaryDirectory() as tmp:
        gen, test = Path(tmp) / "gen", Path(tmp) / "Conformance.java"
        generate(args.lazyos, gen, test)
        if args.check:
            stale = same_tree(gen, GEN)
            if not CONFORMANCE.is_file() or not filecmp.cmp(test, CONFORMANCE, shallow=False):
                stale.append(str(CONFORMANCE.relative_to(ROOT)))
            if stale:
                die("stale generated Messenger classes (run tools/msg_gen.py):\n  " + "\n  ".join(stale))
            print(f"msg_gen: up to date ({sum(1 for _ in GEN.rglob('*.java'))} classes)")
            return
        shutil.rmtree(GEN, ignore_errors=True)
        shutil.copytree(gen, GEN)
        CONFORMANCE.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(test, CONFORMANCE)
    print(f"msg_gen: wrote {sum(1 for _ in GEN.rglob('*.java'))} classes and {CONFORMANCE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
