"""The differential test model shared by golden.py and test.py (docs/decisions.md D9).

A test is `tests/java/<pkg>/<Name>.java` declaring `package <pkg>;` (dots for
nested directories) with a `main`. Its result is a transcript: stdout, then
stderr and the exit status when they are not empty or zero. The golden is
that transcript, in `<Name>.out` next to the source.

Optional header lines in the source, before `package`:

    // ARGS: a b c          program arguments, split on spaces
    // VMARGS: -Xmx16m      extra arguments for both VMs
    // TIMEOUT: 120         seconds, default 60
"""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import lazyrt  # noqa: E402
from common import LAZYRT_CLASSES, TESTS_CLASSES, TESTS_SRC, die, java_sources  # noqa: E402

DEFAULT_TIMEOUT = 60
TIMEOUT_MARK = "--- timeout"


@dataclass
class Test:
    source: Path
    main_class: str
    args: list[str] = field(default_factory=list)
    vm_args: list[str] = field(default_factory=list)
    timeout: int = DEFAULT_TIMEOUT

    @property
    def golden(self) -> Path:
        return self.source.with_suffix(".out")

    @property
    def name(self) -> str:
        return self.main_class

    @property
    def is_lazy(self) -> bool:
        """`lazy.*` tests have hand-written goldens and never run on the oracle."""
        return self.main_class.startswith("lazy.")


def parse_test(source: Path) -> Test:
    if not source.is_relative_to(TESTS_SRC):
        die(f"{source} is not under {TESTS_SRC}")
    rel =source.relative_to(TESTS_SRC).with_suffix("")
    test = Test(source, ".".join(rel.parts))
    for line in source.read_text(encoding="utf-8").splitlines():
        if not line.startswith("//"):
            break
        key, _, value = line[2:].strip().partition(":")
        if key == "ARGS":
            test.args = value.split()
        elif key == "VMARGS":
            test.vm_args = value.split()
        elif key == "TIMEOUT":
            if not value.strip().isdigit():
                die(f"{source}: TIMEOUT must be whole seconds, got {value.strip()!r}")
            test.timeout = int(value)
    return test


def discover(filters: list[str] | None = None, paths: list[Path] | None = None) -> list[Test]:
    """Tests under tests/java whose class name contains one of `filters`."""
    sources = java_sources(TESTS_SRC) if not paths else [
        s for p in paths for s in ([p] if p.is_file() else java_sources(p))]
    tests = [parse_test(s.resolve()) for s in sources]
    if filters:
        tests = [t for t in tests if any(f in t.main_class for f in filters)]
    return tests


def build(tests: list[Test], rebuild_lazyrt: bool = True) -> None:
    """Build lazyrt, then compile every test against it, never the JDK."""
    if rebuild_lazyrt or not LAZYRT_CLASSES.is_dir():
        lazyrt.build()
    javac.compile([t.source for t in tests], TESTS_CLASSES,
                  bootclasspath=LAZYRT_CLASSES, sourcepath=TESTS_SRC)


def close_line(text: str) -> str:
    """End `text` on a line boundary so the next marker starts its own line."""
    if not text or text.endswith("\n"):
        return text
    return text + "\n--- no newline at end ---\n"


def transcript(stdout: str, stderr: str, code: int) -> str:
    """The comparable result of one run, with line endings normalized."""
    out = stdout.replace("\r\n", "\n")
    err = stderr.replace("\r\n", "\n")
    if err:
        out = close_line(out) + "--- stderr ---\n" + err
    if code != 0:
        out = close_line(out)
        out += f"--- exit {code} ---\n"
    return out


def run(cmd: list[str], test: Test) -> str:
    """Run one VM command line for `test` and return its transcript."""
    try:
        done = subprocess.run([*cmd, *test.vm_args, "-cp", str(TESTS_CLASSES),
                               test.main_class, *test.args],
                              capture_output=True, timeout=test.timeout)
    except subprocess.TimeoutExpired:
        return f"{TIMEOUT_MARK} after {test.timeout} s ---\n"
    return transcript(done.stdout.decode("utf-8", "replace"),
                      done.stderr.decode("utf-8", "replace"), done.returncode)
