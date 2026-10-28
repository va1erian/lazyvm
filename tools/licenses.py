"""Check every non-workspace crate has an allowed license and a THIRD_PARTY.md row.

The allowed list is docs/decisions.md D2. A license expression passes when,
read as SPDX (`OR` picks one side, `AND` needs both), it can be satisfied with
allowed licenses only. `lazyvm-lazyos` alone may pull GPL crates (it is GPL
itself); those are listed in GPL_ONLY_FOR.

Only crates built for a shipped target count (SHIPPED_TARGETS), as
`cargo tree -e normal,build --all-features` resolves them: winit, for one,
names wasm, X11 and Wayland crates that never build for Windows or musl. Any
cargo failure, or a tree without the launcher, fails the check.

    python tools/licenses.py
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ROOT, die  # noqa: E402

ALLOWED = {
    "MIT", "MIT-0", "Apache-2.0", "Apache-2.0 WITH LLVM-exception", "BSD-2-Clause",
    "BSD-3-Clause", "Zlib", "ISC", "0BSD", "Unicode-3.0", "Unicode-DFS-2016",
    "CC0-1.0", "BSL-1.0", "Unlicense",
}


def satisfiable(expr: str) -> bool:
    """Whether an SPDX expression can be met with ALLOWED licenses."""
    expr = expr.replace("/", " OR ").strip()
    if expr.startswith("(") and expr.endswith(")") and balanced(expr[1:-1]):
        return satisfiable(expr[1:-1])
    for op, combine in ((" OR ", any), (" AND ", all)):
        parts = split_top(expr, op)
        if len(parts) > 1:
            return combine(satisfiable(p) for p in parts)
    return expr in ALLOWED


def balanced(s: str) -> bool:
    depth = 0
    for ch in s:
        depth += {"(": 1, ")": -1}.get(ch, 0)
        if depth < 0:
            return False
    return depth == 0


def split_top(expr: str, op: str) -> list[str]:
    """Split on `op` outside parentheses."""
    parts, depth, start, i = [], 0, 0, 0
    while i < len(expr):
        ch = expr[i]
        depth += {"(": 1, ")": -1}.get(ch, 0)
        if depth == 0 and expr.startswith(op, i):
            parts.append(expr[start:i].strip())
            i += len(op)
            start = i
            continue
        i += 1
    parts.append(expr[start:].strip())
    return parts


SHIPPED_TARGETS = ("x86_64-pc-windows-msvc", "x86_64-unknown-linux-musl")


def cargo(*args: str) -> str:
    """Runs cargo in the repo; any failure stops the check rather than shrinking it."""
    done = subprocess.run(["cargo", *args], cwd=ROOT, capture_output=True, text=True)
    if done.returncode != 0:
        die(f"cargo {' '.join(args)} failed:\n{done.stderr}")
    return done.stdout


def built(target: str) -> set[tuple[str, str]]:
    """(name, version) of every crate built for `target`: normal and build
    dependencies, proc-macros and their own dependencies, with every feature on
    so whatever the shipped binary enables (`desktop`) is covered."""
    out = cargo("tree", "--workspace", "--locked", "--all-features", "-e", "normal,build",
                "--target", target, "--prefix", "none", "--format", "{p}")
    crates = {tuple(line.split()[:2]) for line in out.splitlines() if line.strip()}
    if not any(name == "launcher" for name, _ in crates):
        die(f"cargo tree for {target} does not list the launcher; cannot trust it")
    return crates


def packages() -> tuple[list[dict], set[str]]:
    """Every package some shipped target builds, and the workspace members' ids."""
    meta = json.loads(cargo("metadata", "--format-version", "1", "--locked"))
    wanted: set[tuple[str, str]] = set()
    for target in SHIPPED_TARGETS:
        wanted |= built(target)
    pkgs = [p for p in meta["packages"] if (p["name"], "v" + p["version"]) in wanted]
    unmatched = wanted - {(p["name"], "v" + p["version"]) for p in pkgs}
    if unmatched:
        die(f"crates cargo tree builds but cargo metadata does not name: {sorted(unmatched)}")
    return pkgs, set(meta["workspace_members"])


def main() -> None:
    pkgs, members = packages()
    listed = set(re.findall(r"^\| *`?([A-Za-z0-9_-]+)`? *\|",
                            (ROOT / "THIRD_PARTY.md").read_text(encoding="utf-8"), re.M))
    errors = []
    for pkg in pkgs:
        if pkg["id"] in members:
            continue
        name, lic = pkg["name"], pkg.get("license") or ""
        if not satisfiable(lic):
            errors.append(f"{name} {pkg['version']}: license {lic!r} is not allowed")
        if name not in listed:
            errors.append(f"{name}: missing from THIRD_PARTY.md")
    for error in errors:
        print(error)
    deps = len(pkgs) - len(members)
    print(f"licenses: {'FAIL' if errors else 'PASS'} ({deps} third-party crates)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
