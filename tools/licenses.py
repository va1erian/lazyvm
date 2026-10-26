"""Check every non-workspace crate has an allowed license and a THIRD_PARTY.md row.

The allowed list is docs/decisions.md D2. A license expression passes when,
read as SPDX (`OR` picks one side, `AND` needs both), it can be satisfied with
allowed licenses only. `lazyvm-lazyos` alone may pull GPL crates (it is GPL
itself); those are listed in GPL_ONLY_FOR.

    python tools/licenses.py
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ROOT  # noqa: E402

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


def main() -> None:
    meta = json.loads(subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--locked"],
        cwd=ROOT, capture_output=True, check=True).stdout)
    members = set(meta["workspace_members"])
    listed = set(re.findall(r"^\| *`?([A-Za-z0-9_-]+)`? *\|",
                            (ROOT / "THIRD_PARTY.md").read_text(encoding="utf-8"), re.M))
    errors = []
    for pkg in meta["packages"]:
        if pkg["id"] in members:
            continue
        name, lic = pkg["name"], pkg.get("license") or ""
        if not satisfiable(lic):
            errors.append(f"{name} {pkg['version']}: license {lic!r} is not allowed")
        if name not in listed:
            errors.append(f"{name}: missing from THIRD_PARTY.md")
    for error in errors:
        print(error)
    deps = len(meta["packages"]) - len(members)
    print(f"licenses: {'FAIL' if errors else 'PASS'} ({deps} third-party crates)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
