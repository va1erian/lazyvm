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
WORK = BUILD / "lazyos"  # stage, command list and results; bench.py uses its own, so neither overwrites the other
OS_ROOT = "/system/lazyvm"


def build_musl(package: str = "launcher") -> Path:
    """The musl `lazyvm`, or `lazyvm-ui` (headless there) with `package="launcher-ui"`."""
    cmd = ["cargo", "build", "-q", "--release", "-p", package, "--target", TARGET]
    if subprocess.run(cmd, cwd=ROOT).returncode != 0:
        die("musl build failed")
    name = "lazyvm-ui" if package == "launcher-ui" else "lazyvm"
    return ROOT / "target" / TARGET / "release" / name


def stage(exe: Path, tests_jar: Path | None = None, work: Path = WORK,
          ui_exe: Path | None = None) -> None:
    """The layout LazyOS embeds under /system/lazyvm; `tests_jar` is the jar of programs to run,
    and `ui_exe` the `lazyvm-ui` beside `lazyvm` (LOS-02)."""
    stage_dir = work / "stage"
    shutil.rmtree(stage_dir, ignore_errors=True)
    for sub in ("bin", "lib", "tests"):
        (stage_dir / sub).mkdir(parents=True)
    shutil.copyfile(exe, stage_dir / "bin" / "lazyvm")
    if ui_exe is not None:
        shutil.copyfile(ui_exe, stage_dir / "bin" / "lazyvm-ui")
    shutil.copyfile(DIST / "lazyrt.jar", stage_dir / "lib" / "lazyrt.jar")
    shutil.copyfile(tests_jar or testlib.tests_jar(), stage_dir / "tests" / "tests.jar")


def command_line(main_class: str, args: list[str], vm_args: list[str], exe: str = "lazyvm") -> str:
    argv = [f"{OS_ROOT}/bin/{exe}", f"-Xbootclasspath:{OS_ROOT}/lib/lazyrt.jar", *vm_args,
            "-cp", f"{OS_ROOT}/tests/tests.jar", main_class, *args]
    return " ".join(shlex.quote(a) for a in argv)


def command(test: testlib.Test, extra: list[str]) -> str:
    """The shell line for `test`, with `extra` VM options before its own."""
    exe = "lazyvm-ui" if test.needs == "ui" else "lazyvm"
    return command_line(test.main_class, test.args, extra + test.vm_args, exe)


def run_commands(lazyos: Path, lines: list[str], timeout: int, no_build: bool, work: Path = WORK,
                 net: bool = False) -> Path:
    """Boot LazyOS once over `lines` (staged by `stage`); the directory holding N.stdout, N.stderr
    and N.exit for command N. Exits when the run itself fails."""
    out = work / "out"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    commands = work / "commands.txt"
    commands.write_text("".join(line + "\n" for line in lines), encoding="utf-8", newline="\n")
    run = [sys.executable, "tools/lazyvm/run.py", "--commands", str(commands), "--out", str(out),
           "--timeout", str(timeout),
           "--image", str(work / "lazyos.img")]
    if no_build:
        run.append("--no-build")
    if net:
        run.append("--net")
    env = dict(os.environ, LAZYOS_LAZYVM_STAGE=str(work / "stage"))
    if subprocess.run(run, cwd=lazyos, env=env).returncode != 0:
        die("LazyOS run failed (see its output above)")
    return out


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
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # diffs hold any character
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lazyos", type=Path, default=os.environ.get("LAZYOS_DIR"))
    parser.add_argument("--filter", nargs="*", help="substrings of test class names")
    parser.add_argument("--no-build", action="store_true", help="reuse the last image (same tests)")
    parser.add_argument("--timeout", type=int, default=120, help="seconds per test inside LazyOS")
    parser.add_argument("--jit-all", action="store_true",
                        help="compile every verified method at its first run (-Xjit:all)")
    parser.add_argument("--net", action="store_true",
                        help="only the tests that need the network, on an image with netd and a card")
    args = parser.parse_args()
    if args.lazyos is None or not (args.lazyos / "tools" / "lazyvm" / "run.py").is_file():
        die("pass --lazyos <LazyOS checkout with tools/lazyvm/run.py> or set LAZYOS_DIR")
    wanted = ("net", "lazyos-net") if args.net else ("", "ui")
    tests = [t for t in testlib.discover(args.filter) if t.golden.is_file() and t.needs in wanted]
    if not args.no_build:
        testlib.build(tests)
        ui = build_musl("launcher-ui") if any(t.needs == "ui" for t in tests) else None
        stage(build_musl(), ui_exe=ui)
    jit = ["-Xjit:all"] if args.jit_all else []
    out = run_commands(args.lazyos, [command(t, jit) for t in tests], args.timeout, args.no_build,
                       net=args.net)
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
