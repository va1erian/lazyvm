"""Report one time window of a `prof` run (plan S11-06, crates/vm/src/prof).

Build the profiling VM and run with snapshots every 10 seconds:

    cargo build --release -p launcher --features vm/prof
    LAZYVM_PROF=out.txt LAZYVM_PROF_EVERY=10 target/release/lazyvm ...

Then report the samples taken between two snapshots (or from the start, `-`):

    python tools/prof.py out.txt 180 210 [--top 40]
    python tools/prof.py out.txt - end         # the whole run, from out.txt.raw
"""

from __future__ import annotations

import argparse
import collections
from pathlib import Path

PHASES = ["fast", "step", "native", "gc"]


def load(base: str, at: str) -> collections.Counter:
    counts: collections.Counter = collections.Counter()
    if at == "-":
        return counts
    path = Path(base + ".raw") if at == "end" else Path(f"{base}.raw.{int(at):05}")
    for line in path.read_text(encoding="utf-8").splitlines():
        method, phase, op, n = line.split("\t")
        counts[(int(method), int(phase), int(op))] = int(n)
    return counts


def report(names: dict[str, str], window: dict, top: int) -> None:
    total = sum(window.values()) or 1
    phases: collections.Counter = collections.Counter()
    methods: dict[int, list[int]] = collections.defaultdict(lambda: [0, 0, 0, 0])
    ops: collections.Counter = collections.Counter()
    owners: collections.Counter = collections.Counter()
    for (method, phase, op), n in window.items():
        phase = min(phase, 3)
        phases[phase] += n
        methods[method][phase] += n
        if phase == 1:
            ops[op] += n
        owner = names.get(str(method), "?").split("(")[0].rsplit(".", 1)[0]
        owners[owner] += n
    print(f"samples {total}  " + "  ".join(f"{PHASES[p]} {100 * phases[p] / total:.1f}%" for p in range(4)))
    print("\n== step time by opcode ==")
    for op, n in ops.most_common(15):
        print(f"  {op:#04x} {100 * n / total:5.1f}%")
    print("\n== classes (self) ==")
    for owner, n in owners.most_common(15):
        print(f"  {100 * n / total:5.1f}%  {owner}")
    print("\n== methods (self): total fast step native gc ==")
    for method, p in sorted(methods.items(), key=lambda kv: -sum(kv[1]))[:top]:
        cols = " ".join(f"{100 * x / total:4.0f}" for x in p)
        print(f"  {100 * sum(p) / total:5.1f}% {cols}  {names.get(str(method), '?')}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("base", help="the LAZYVM_PROF file")
    ap.add_argument("start", help="snapshot seconds, or - for the start")
    ap.add_argument("end", help="snapshot seconds, or end for the final samples")
    ap.add_argument("--top", type=int, default=40)
    args = ap.parse_args()
    names = dict(line.split("\t", 1) for line in
                 Path(args.base + ".names").read_text(encoding="utf-8").splitlines())
    before, after = load(args.base, args.start), load(args.base, args.end)
    window = {k: n - before.get(k, 0) for k, n in after.items() if n > before.get(k, 0)}
    report(names, window, args.top)


if __name__ == "__main__":
    main()
