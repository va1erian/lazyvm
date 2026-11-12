"""The Siesta suite (docs/siesta.md section 10).

    python tools/siesta_test.py [--filter text] [--bless] [--no-unit]

A test is `tests/siesta/<area>/<Name>.sie`; its top level is class `<Name>`.
Each is compiled once by `siestac` and run on LazyVM, whose transcript must
equal `<Name>.out`. When a Java 8 oracle is found (`LAZYVM_ORACLE_JAVA`, else
`java` on PATH if it is Java 8), the same classes also run there with
`-Xverify:all`, which checks every stack map siestac writes, and must print
the same. A `<Name>.java` beside the test is its mirror: compiled by javac and
run on the oracle, it must print the same too, which ties Siesta's semantics
to Java's. `--bless` writes `.out` from the oracle run, for review.

The oracle runs with `dist/lazyrt.jar` on its class path, after the JDK's
own classes, so `lazy.*` code written in Java (`lazy.async.Loop.home`) is
there. A test whose first lines say `// NEEDS: lazyvm` uses LazyVM's natives
(the loop) and runs on LazyVM only, against a reviewed golden.

A program of several files is a folder `tests/siesta/<area>/<Name>/`: its
`<Name>.sie` holds `main` and is given to siestac first, the other `.sie`
files in it follow, and its golden is `<Name>/<Name>.out`.

`tests/siesta/bad/<Name>.sie` must fail to compile with the messages in
`<Name>.err`, one `line:col: message` per line.

The Rust tests that need `dist/lazyrt.jar` (`cargo test -p siesta --
--ignored`) run first, after the jar is built, unless `--no-unit`.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import lazyrt  # noqa: E402
from common import BUILD, DIST, EXE, ROOT, die, lazyvm_exe  # noqa: E402
from testlib import transcript  # noqa: E402

TESTS = ROOT / "tests" / "siesta"
OUT = BUILD / "siesta"
TIMEOUT = 60
ORACLE_FLAGS = ["-Xmx256m", "-Xverify:all", "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:-ShowCodeDetailsInExceptionMessages"]


def siestac() -> Path:
    return ROOT / "target" / "debug" / f"siestac{EXE}"


def oracle_java() -> str | None:
    """The Java 8 oracle, or None when this machine has none (CI)."""
    import golden
    try:
        return golden.oracle(False)
    except SystemExit:
        return None


def run(cmd: list[str]) -> str:
    try:
        done = subprocess.run(cmd, capture_output=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return f"--- timeout after {TIMEOUT} s ---\n"
    return transcript(done.stdout.decode("utf-8", "replace"), done.stderr.decode("utf-8", "replace"),
                      done.returncode)


def is_program(src: Path) -> bool:
    """Whether `src` is the main file of a several-file program."""
    return src.parent.name == src.stem


def program_part(src: Path) -> bool:
    """Whether `src` is one of a program's other files, compiled with its main file."""
    # Compared by name, not exists(): Windows would find `Classes.sie` for `classes/classes.sie`.
    main = f"{src.parent.name}.sie"
    return src.name != main and any(p.name == main for p in src.parent.iterdir())


def compile_test(src: Path, out: Path) -> str | None:
    """Compiles one test; the compiler's messages on failure."""
    out.mkdir(parents=True, exist_ok=True)
    files = [src]
    if is_program(src):
        files += sorted(p for p in src.parent.glob("*.sie") if p != src)
    done = subprocess.run([str(siestac()), "-cp", str(DIST / "lazyrt.jar"), "-d", str(out), *map(str, files)],
                          capture_output=True)
    if done.returncode != 0:
        return done.stderr.decode("utf-8", "replace")
    return None


def messages(stderr: str, src: Path) -> list[str]:
    """`line:col: message` lines from siestac's output for `src`."""
    prefix = str(src) + ":"
    return [line[len(prefix):] for line in stderr.splitlines() if line.startswith(prefix)]


def check_bad(src: Path) -> str | None:
    done = subprocess.run([str(siestac()), "check", "-cp", str(DIST / "lazyrt.jar"), str(src)],
                          capture_output=True)
    if done.returncode == 0:
        return "compiled, but should not"
    got = messages(done.stderr.decode("utf-8", "replace"), src)
    want = [l for l in src.with_suffix(".err").read_text(encoding="utf-8").splitlines() if l.strip()]
    return None if got == want else f"messages\n  got:  {got}\n  want: {want}"


def mirror(src: Path, java: str) -> str | None:
    """The oracle transcript of the test's Java mirror, if it has one."""
    jsrc = src.with_suffix(".java")
    if not jsrc.exists():
        return None
    out = OUT / "mirror" / src.parent.name / src.stem
    javac.compile([jsrc], out)
    return run([java, *ORACLE_FLAGS, "-cp", str(out), src.stem])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--filter", default="")
    ap.add_argument("--bless", action="store_true")
    ap.add_argument("--no-unit", action="store_true")
    args = ap.parse_args()

    lazyrt.build()
    if subprocess.run(["cargo", "build", "-p", "siesta", "-p", "launcher", "--release"]).returncode != 0 \
            or subprocess.run(["cargo", "build", "-p", "siesta"]).returncode != 0:
        die("cargo build failed")
    if not args.no_unit and subprocess.run(["cargo", "test", "-p", "siesta", "--", "--ignored"]).returncode != 0:
        die("siesta's jar tests failed")
    vm = lazyvm_exe(True)
    java = oracle_java()
    if java is None:
        print("note: no Java 8 oracle; checking LazyVM against the goldens only")

    failed = 0
    tests = sorted(p for p in TESTS.rglob("*.sie")
                   if args.filter in str(p.relative_to(TESTS)) and not program_part(p))
    for src in tests:
        name = src.relative_to(TESTS).as_posix()
        if src.parent.name == "bad":
            problem = check_bad(src)
        else:
            problem = run_test(src, vm, java, args.bless)
        if problem:
            failed += 1
            print(f"FAIL {name}: {problem}")
        else:
            print(f"ok   {name}")
    print(f"{len(tests) - failed} passed, {failed} failed")
    sys.exit(1 if failed else 0)


def run_test(src: Path, vm: Path, java: str | None, bless: bool) -> str | None:
    out = OUT / "classes" / src.parent.name / src.stem
    err = compile_test(src, out)
    if err:
        return "compile error\n" + err
    golden = src.with_suffix(".out")
    lazyvm_only = "// NEEDS: lazyvm" in src.read_text(encoding="utf-8")[:400]
    cp = str(out) + os.pathsep + str(DIST / "lazyrt.jar")
    oracle = run([java, *ORACLE_FLAGS, "-cp", cp, src.stem]) if java and not lazyvm_only else None
    if bless and not lazyvm_only:
        if oracle is None:
            return "--bless needs the Java 8 oracle"
        golden.write_text(oracle, encoding="utf-8", newline="\n")
    if not golden.exists():
        return f"no golden {golden.name}: run with --bless on a machine with the oracle"
    want = golden.read_text(encoding="utf-8")
    if oracle is not None and oracle != want:
        return f"oracle (-Xverify:all) differs from the golden:\n{oracle}"
    if java is not None and not lazyvm_only:
        m = mirror(src, java)
        if m is not None and m != want:
            return f"the Java mirror prints something else:\n{m}"
    got = run([str(vm), f"-Xbootclasspath:{DIST / 'lazyrt.jar'}", "-cp", str(out), src.stem])
    if got != want:
        return f"LazyVM differs from the golden:\n{got}"
    return None


if __name__ == "__main__":
    main()
