"""Enforce the mechanical parts of the shared agent rules (AGENTS.md).

* Every AGENTS.md / CLAUDE.md in the tree carries the shared rules block, and
  each copy is identical to the master in the root AGENTS.md. With
  `--lazyos <checkout>`, LazyOS's docs/agents/lazyvm.md is compared as well.
* No tracked .rs, .py or .java file is over 500 lines.
* Every `unsafe` block, fn or impl in Rust has a `// SAFETY:` comment within
  the three lines above it.

    python tools/check_rules.py [--lazyos ../lazyos] [--sync]

`--sync` rewrites every copy from the master before checking.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ROOT  # noqa: E402

BEGIN = "<!-- lazyvm-rules:begin -->"
END = "<!-- lazyvm-rules:end -->"
MAX_LINES = 500
UNSAFE = re.compile(r"\bunsafe\s*(\{|fn\b|impl\b|extern\b)")


def rules_block(path: Path) -> str | None:
    """The shared block of an agent file, markers included, or None."""
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    start, end = text.find(BEGIN), text.find(END)
    if start < 0 or end < start:
        return None
    return text[start:end + len(END)]


def tracked_files() -> list[Path]:
    """Tracked and untracked-but-not-ignored files, as git sees them."""
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=ROOT, capture_output=True, check=True).stdout.decode()
    return [ROOT / p for p in out.split("\0") if p and (ROOT / p).is_file()]


def check_blocks(files: list[Path], lazyos: Path | None) -> list[str]:
    master = rules_block(ROOT / "AGENTS.md")
    if master is None:
        return ["AGENTS.md: the shared rules block is missing"]
    copies = [f for f in files if f.name in ("AGENTS.md", "CLAUDE.md")]
    if lazyos is not None:
        copies.append(lazyos / "docs" / "agents" / "lazyvm.md")
    errors = []
    for path in copies:
        if not path.is_file():
            errors.append(f"{path}: missing")
            continue
        block = rules_block(path)
        if block is None:
            errors.append(f"{path}: the shared rules block is missing")
        elif block != master:
            errors.append(f"{path}: the shared rules block differs from AGENTS.md")
    return errors


def sync_blocks(files: list[Path], lazyos: Path | None) -> None:
    """Overwrite each copy's block with the master's."""
    master = rules_block(ROOT / "AGENTS.md")
    copies = [f for f in files if f.name in ("AGENTS.md", "CLAUDE.md")]
    if lazyos is not None:
        copies.append(lazyos / "docs" / "agents" / "lazyvm.md")
    for path in copies:
        block = rules_block(path) if path.is_file() else None
        if master is not None and block is not None and block != master:
            text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
            path.write_text(text.replace(block, master), encoding="utf-8", newline="\n")
            print(f"synced {path}")


def check_lengths(files: list[Path]) -> list[str]:
    errors = []
    for path in files:
        if path.suffix in (".rs", ".py", ".java"):
            count = len(path.read_text(encoding="utf-8", errors="replace").splitlines())
            if count > MAX_LINES:
                errors.append(f"{path.relative_to(ROOT)}: {count} lines (limit {MAX_LINES})")
    return errors


def check_safety(files: list[Path]) -> list[str]:
    errors = []
    for path in files:
        if path.suffix != ".rs":
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            code = line.split("//", 1)[0]
            if UNSAFE.search(code) and "unsafe_code" not in code:
                window = lines[max(0, i - 3):i + 1]
                if not any("SAFETY:" in w for w in window):
                    errors.append(f"{path.relative_to(ROOT)}:{i + 1}: unsafe without // SAFETY:")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lazyos", type=Path, help="a LazyOS checkout to compare too")
    parser.add_argument("--sync", action="store_true", help="copy the master block first")
    args = parser.parse_args()
    files = tracked_files()
    if args.sync:
        sync_blocks(files, args.lazyos)
    errors = check_blocks(files, args.lazyos) + check_lengths(files) + check_safety(files)
    for error in errors:
        print(error)
    print(f"check_rules: {'FAIL' if errors else 'PASS'} ({len(files)} files)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
