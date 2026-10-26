"""Run the differential suite inside a real LazyOS boot (plan S3-06).

Builds the static-musl `lazyvm`, stages it with `lazyrt.jar` and the tests
jar, writes one LazyOS command line per test, and calls LazyOS's
`tools/lazyvm/run.py` (docs/agents/lazyvm.md in LazyOS), which boots QEMU and
returns each command's stdout, stderr and exit status. Those are judged
against the same goldens as `tools/test.py`.

    python tools/lazyos_test.py --lazyos <LazyOS checkout> [--filter s1 lang] [--no-build]

The checkout comes from `--lazyos` or `LAZYOS_DIR`.
"""

from __future__ import annotations

import argparse
import difflib
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import testlib  # noqa: E402
from common import BUILD, DIST, ROOT, die  # noqa: E402

TARGET = "x86_64-unknown-linux-musl"
STAGE = BUILD / "lazyos" / "stage"
OS_ROOT = "/system/lazyvm"


def build_musl() -> Path:
    cmd = ["cargo", "build", "-q", "--release", "-p", "launcher", "--target", TARGET]
    if subprocess.run(cmd, cwd=ROOT).returncode != 0:
        die("musl build failed")
    return ROOT / "target" / TARGET / "release" / "lazyvm"


def stage(exe: Path) -> None:
    """The layout LazyOS embeds under /system/lazyvm."""
    shutil.rmtree(STAGE, ignore_errors=True)
    for sub in ("bin", "lib", "tests"):
        (STAGE / sub).mkdir(parents=True)
    shutil.copyfile(exe, STAGE / "bin" / "lazyvm")
    shutil.copyfile(DIST / "lazyrt.jar", STAGE / "lib" / "lazyrt.jar")
    shutil.copyfile(testlib.tests_jar(), STAGE / "tests" / "tests.jar")


def command(test: testlib.Test) -> str:
    argv = [f"{OS_ROOT}/bin/lazyvm", f"-Xbootclasspath:{OS_ROOT}/lib/lazyrt.jar", *test.vm_args,
            "-cp", f"{OS_ROOT}/tests/tests.jar", test.main_class, *test.args]
    return " ".join(shlex.quote(a) for a in argv)


def read_result(out: Path, n: int) -> str:
    """The transcript of command `n`, in `testlib.transcript` form."""
    def text(suffix: str) -> str:
        p = out / f"{n}.{suffix}"
        return p.read_bytes().decode("utf-8", "replace") if p.is_file() else ""
    code = text("exit").strip()
    if code == "124":
        return f"{testlib.TIMEOUT_MARK} inside LazyOS ---\n"
    return testlib.transcript(text("stdout"), text("stderr"), int(code) if code.isdigit() else -1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lazyos", type=Path, default=os.environ.get("LAZYOS_DIR"))
    parser.add_argument("--filter", nargs="*", help="substrings of test class names")
    parser.add_argument("--no-build", action="store_true", help="reuse the last image (same tests)")
    parser.add_argument("--timeout", type=int, default=120, help="seconds per test inside LazyOS")
    args = parser.parse_args()
    if args.lazyos is None or not (args.lazyos / "tools" / "lazyvm" / "run.py").is_file():
        die("pass --lazyos <LazyOS checkout with tools/lazyvm/run.py> or set LAZYOS_DIR")
    tests = [t for t in testlib.discover(args.filter) if t.golden.is_file()]
    if not args.no_build:
        testlib.build(tests)
        stage(build_musl())
    out = BUILD / "lazyos" / "out"
    out.mkdir(parents=True, exist_ok=True)
    commands = BUILD / "lazyos" / "commands.txt"
    commands.write_text("".join(command(t) + "\n" for t in tests), encoding="utf-8", newline="\n")
    run = [sys.executable, "tools/lazyvm/run.py", "--commands", str(commands), "--out", str(out),
           "--timeout", str(args.timeout)]
    if args.no_build:
        run.append("--no-build")
    env = dict(os.environ, LAZYOS_LAZYVM_STAGE=str(STAGE))
    if subprocess.run(run, cwd=args.lazyos, env=env).returncode != 0:
        die("LazyOS run failed (see its output above)")
    failed = 0
    for n, test in enumerate(tests, start=1):
        actual = read_result(out, n)
        expected = test.golden.read_text(encoding="utf-8")
        ok = actual == expected
        print(f"{'PASS' if ok else 'FAIL'} {test.name}")
        if not ok:
            failed += 1
            print("".join(difflib.unified_diff(expected.splitlines(True), actual.splitlines(True),
                                               "golden", "lazyos")), end="")
    print(f"{len(tests) - failed}/{len(tests)} passed inside LazyOS")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
