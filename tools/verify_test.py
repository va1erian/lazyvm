"""The verifier's checks (plan S8-02): every shipped class verifies, every bad one is rejected.

* `lazyvm -Xverify:check` over lazyrt.jar and all compiled tests finds no failure
  but the classes in EXPECTED_BAD.
* Each `tests/verify/bad/*.jasm` is one rule: assembled by tools/jasm.py, it must
  fail with the `; expect:` class, and on LazyVM with `; reason:` in the message.
  `; oracle: differs, ...` says why the oracle is not asked.
* Each `tests/verify/good/*.jasm` is a near miss of a rule: it must print `ok`.

    python tools/verify_test.py                 # on LazyVM (CI)
    python tools/verify_test.py --oracle        # the .jasm files on the Java 8 oracle too
    python tools/verify_test.py --filter Uninit --no-build
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import golden  # noqa: E402
import jasm  # noqa: E402
import testlib  # noqa: E402
from common import BUILD, DIST, ROOT, TESTS_CLASSES, cargo_build_launcher, die  # noqa: E402

CASES = ROOT / "tests" / "verify"
OUT = BUILD / "verify"
TIMEOUT = 60
# Built with the casts removed so natives can be tested on what a verifier refuses
# (docs/design/atomics.md); tests/java/lazy/atomics/Intrinsics runs it with -Xverify:none.
EXPECTED_BAD = {"lazy/atomics/CraftedAtomics"}


def header(text: str, key: str) -> str | None:
    for line in text.splitlines():
        if line.startswith(f"; {key}:"):
            return line.split(":", 1)[1].strip()
    return None


def build_case(source: Path) -> tuple[Path, str, str]:
    text = source.read_text(encoding="utf-8")
    out = OUT / source.parent.name / source.stem
    shutil.rmtree(out, ignore_errors=True)
    for name, data in jasm.assemble(text).items():
        path = out / f"{name}.class"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return out, jasm.main_class(text), text


def run_case(cmd: list[str], out: Path, main: str) -> tuple[str, int]:
    done = subprocess.run([*cmd, "-cp", str(out), main], capture_output=True, timeout=TIMEOUT)
    text = (done.stdout + done.stderr).decode("utf-8", "replace").replace("\r\n", "\n")
    return text, done.returncode


def judge(source: Path, cmd: list[str], is_lazyvm: bool) -> str | None:
    """Why this case failed on this VM, or None if it passed."""
    out, main, text = build_case(source)
    if not is_lazyvm and header(text, "oracle"):
        return None
    actual, code = run_case(cmd, out, main)
    if code == 70 or "panicked" in actual or "internal error" in actual:
        return f"the VM failed: {actual}"
    if source.parent.name == "good":
        return None if actual.strip() == "ok" and code == 0 else f"expected ok, got: {actual}"
    expect = header(text, "expect")
    if expect is None:
        return "no `; expect:` line"
    if expect not in actual:
        return f"expected {expect}, got: {actual}"
    reason = header(text, "reason")
    if is_lazyvm and reason and reason not in actual:
        return f"expected the reason {reason!r}, got: {actual}"
    return None


def run_cases(name: str, cmd: list[str], filters: list[str] | None, is_lazyvm: bool) -> int:
    failed = total = 0
    for source in sorted(CASES.glob("*/*.jasm")):
        if filters and not any(f in source.stem for f in filters):
            continue
        total += 1
        why = judge(source, cmd, is_lazyvm)
        rel = f"{source.parent.name}/{source.stem}"
        print(f"{'PASS' if why is None else 'FAIL'} {name} {rel}")
        if why is not None:
            failed += 1
            print(f"  {why}")
    print(f"{name}: {total - failed}/{total} cases passed")
    return failed


def check_all_classes(exe: Path, boot: Path) -> int:
    """Everything we ship or test verifies."""
    done = subprocess.run([str(exe), f"-Xbootclasspath:{boot}", "-Xverify:check", "-cp", str(TESTS_CLASSES)],
                          capture_output=True, text=True, timeout=600)
    lines = done.stdout.splitlines()
    failures = [line for line in lines if line.startswith("FAIL ")]
    named = {line.split()[1].rstrip(":") for line in failures}
    unexpected = [line for line in failures if line.split()[1].rstrip(":") not in EXPECTED_BAD]
    print(lines[-1] if lines else f"no output: {done.stderr}")
    for line in unexpected:
        print(line)
    missing = EXPECTED_BAD - named
    for name in sorted(missing):
        print(f"FAIL {name} should not verify")
    return len(unexpected) + len(missing) + (0 if lines else 1)


def check_limits(exe: Path, boot: Path) -> int:
    """S8-04: a class limit or a code limit at any value is an error, never an abort."""
    source = CASES / "good" / "NewStoredInLocal.jasm"
    out, main_name, _ = build_case(source)
    failed = 0
    base = [str(exe), f"-Xbootclasspath:{boot}"]
    for limit in [1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233]:
        text, code = run_case([*base, f"-Xmaxclasses:{limit}"], out, main_name)
        if code == 70 or "panicked" in text or "internal error" in text:
            print(f"FAIL -Xmaxclasses:{limit}: {text}")
            failed += 1
    text, code = run_case([*base, "-Xmaxclasses:100000"], out, main_name)
    if text.strip() != "ok":
        print(f"FAIL a high class limit: {text}")
        failed += 1
    # Any method in the class library is longer than this, so nothing can start.
    text, code = run_case([*base, "-Xmaxcode:1"], out, main_name)
    if code in (0, 70) or "-Xmaxcode" not in text:
        print(f"FAIL -Xmaxcode:1 (exit {code}): {text}")
        failed += 1
    text, code = run_case([*base, "-Xmaxcode:65535"], out, main_name)
    if text.strip() != "ok":
        print(f"FAIL -Xmaxcode:65535: {text}")
        failed += 1
    print(f"limits: {failed} failed")
    return failed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--oracle", action="store_true", help="also run the .jasm files on the Java 8 oracle")
    parser.add_argument("--exe", type=Path, help="a prebuilt lazyvm binary")
    parser.add_argument("--no-build", action="store_true", help="skip cargo and javac")
    parser.add_argument("--filter", nargs="*", help="substrings of case names; skips the whole-class-path check")
    args = parser.parse_args()
    if not args.no_build:
        testlib.build(testlib.discover())
    exe = args.exe or (None if args.no_build else cargo_build_launcher())
    if exe is None:
        die("--no-build needs --exe")
    boot = DIST / "lazyrt.jar"
    failed = 0 if args.filter else check_all_classes(exe, boot) + check_limits(exe, boot)
    failed += run_cases("lazyvm", [str(exe), f"-Xbootclasspath:{boot}"], args.filter, True)
    if args.oracle:
        failed += run_cases("oracle", [golden.oracle(False), "-Xverify:all"], args.filter, False)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
