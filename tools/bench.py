"""Benchmark suite runner (plan S11-05): build, run, record, compare.

The programs are `bench/bench/*.java`, our own code. Each prints one line,
`BENCH <name> checksum=<n> ns=<n>`, timed inside the program so VM start-up is
not measured. The release profile is always used.

    python tools/bench.py run --out base.json [--runs 5] [--filter blit calls] [--scale 1]
    python tools/bench.py run --lazyos <LazyOS checkout> --out base-os.json   # inside QEMU
    python tools/bench.py compare base.json new.json [--threshold 10]

`run` reports the median and the spread ((max - min) / median) of the runs.
`compare` exits 1 when a benchmark's median is slower than the baseline by
more than the threshold percentage, or when a checksum differs (the program
changed behaviour, so the numbers are not comparable). A threshold below the
recorded spreads would flag noise, so compare prints the spreads too.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import lazyos_test  # noqa: E402
import lazyrt  # noqa: E402
from common import BUILD, DIST, LAZYRT_CLASSES, ROOT, cargo_build_launcher, die  # noqa: E402

SRC = ROOT / "bench"
CLASSES = BUILD / "bench" / "classes"
LAZYOS_WORK = BUILD / "bench" / "lazyos"  # its own stage: tests.jar there is the bench jar
LINE = re.compile(r"^BENCH (\w+) checksum=(-?\d+) ns=(\d+)\r?$", re.M)
DEFAULT_THRESHOLD = 10.0
TIMEOUT = 600


def programs() -> dict[str, str]:
    """Benchmark name to main class: every public class in bench/bench except the helper."""
    found = {}
    for path in sorted((SRC / "bench").glob("*.java")):
        if path.stem != "Report":
            found[path.stem.lower()] = f"bench.{path.stem}"
    return found


def build_jar() -> Path:
    """Compile the benchmarks against lazyrt, never the JDK, and zip them."""
    sources = sorted(SRC.rglob("*.java"))
    javac.compile(sources, CLASSES, bootclasspath=LAZYRT_CLASSES, sourcepath=SRC)
    return lazyrt.write_jar(CLASSES, CLASSES.parent / "bench.jar")


def parse(stdout: str, name: str) -> tuple[int, int]:
    """The checksum and nanoseconds of the benchmark's result line."""
    for found, checksum, ns in LINE.findall(stdout):
        if found == name:
            return int(checksum), int(ns)
    die(f"no result line for {name} in its output:\n{stdout[-500:]}")
    raise AssertionError


def run_local(exe: Path, jar: Path, names: dict[str, str], runs: int, scale: int) -> dict:
    samples: dict[str, list[tuple[int, int]]] = {n: [] for n in names}
    for i in range(runs):
        for name, main in names.items():
            cmd = [str(exe), f"-Xbootclasspath:{DIST / 'lazyrt.jar'}", "-cp", str(jar), main, str(scale)]
            done = subprocess.run(cmd, capture_output=True, timeout=TIMEOUT)
            if done.returncode != 0:
                die(f"{name} exited {done.returncode}: {done.stderr.decode('utf-8', 'replace')[-500:]}")
            samples[name].append(parse(done.stdout.decode("utf-8", "replace"), name))
            print(f"  run {i + 1}/{runs} {name}: {samples[name][-1][1] / 1e6:.0f} ms", flush=True)
    return samples


def run_lazyos(lazyos: Path, jar: Path, names: dict[str, str], runs: int, scale: int,
               no_build: bool) -> dict:
    """One QEMU boot runs every command; the stage is rebuilt unless `no_build`."""
    if not (lazyos / "tools" / "lazyvm" / "run.py").is_file():
        die(f"{lazyos} has no tools/lazyvm/run.py")
    if not no_build:
        lazyos_test.stage(lazyos_test.build_musl(), jar, LAZYOS_WORK)
    order = [(name, main) for _ in range(runs) for name, main in names.items()]
    lines = [lazyos_test.command_line(main, [str(scale)], []) for _, main in order]
    out = lazyos_test.run_commands(lazyos, lines, TIMEOUT, no_build, LAZYOS_WORK)
    samples: dict[str, list[tuple[int, int]]] = {n: [] for n in names}
    for n, (name, _) in enumerate(order, start=1):
        code = (out / f"{n}.exit").read_text().strip() if (out / f"{n}.exit").is_file() else "?"
        text = (out / f"{n}.stdout").read_bytes().decode("utf-8", "replace") if (out / f"{n}.stdout").is_file() else ""
        if code != "0":
            die(f"{name} exited {code} inside LazyOS:\n{text[-500:]}")
        samples[name].append(parse(text, name))
    return samples


def summarize(samples: dict[str, list[tuple[int, int]]]) -> dict:
    results = {}
    for name, runs in samples.items():
        sums = {c for c, _ in runs}
        if len(sums) != 1:
            die(f"{name}: the checksum changed between runs {sorted(sums)}; the run is not deterministic")
        times = [ns / 1e6 for _, ns in runs]
        median = statistics.median(times)
        results[name] = {"checksum": sums.pop(), "median_ms": round(median, 2),
                         "spread_pct": round((max(times) - min(times)) / median * 100, 1),
                         "runs_ms": [round(t, 2) for t in times]}
    return results


def table(results: dict) -> str:
    rows = [f"{'benchmark':<10} {'median ms':>10} {'spread':>7}  checksum"]
    for name, r in results.items():
        rows.append(f"{name:<10} {r['median_ms']:>10.1f} {r['spread_pct']:>6.1f}%  {r['checksum']}")
    return "\n".join(rows)


def cmd_run(args: argparse.Namespace) -> None:
    names = programs()
    if args.filter:
        names = {n: m for n, m in names.items() if any(f in n for f in args.filter)}
    if not names:
        die("no benchmark matches the filter")
    if not args.no_build:
        lazyrt.build()
    jar = build_jar()
    if args.lazyos:
        samples = run_lazyos(args.lazyos, jar, names, args.runs, args.scale, args.no_build)
        platform = "lazyos"
    else:
        exe = args.exe or (cargo_build_launcher(release=True) if not args.no_build else None)
        if exe is None:
            die("--no-build needs --exe")
        samples = run_local(exe, jar, names, args.runs, args.scale)
        platform = sys.platform
    results = summarize(samples)
    print(table(results))
    if args.out:
        args.out.write_text(json.dumps({"platform": platform, "runs": args.runs, "scale": args.scale,
                                        "results": results}, indent=2) + "\n", encoding="utf-8")


def compare(base: dict, new: dict, threshold: float) -> tuple[list[str], list[str]]:
    """The report lines and the failures of `new` against `base`."""
    lines, failures = [f"{'benchmark':<10} {'base ms':>9} {'new ms':>9} {'change':>8}  spreads"], []
    for name, old in base["results"].items():
        cur = new["results"].get(name)
        if cur is None:
            failures.append(f"{name}: missing from the new results")
            continue
        change = (cur["median_ms"] / old["median_ms"] - 1) * 100
        lines.append(f"{name:<10} {old['median_ms']:>9.1f} {cur['median_ms']:>9.1f} {change:>+7.1f}%  "
                     f"{old['spread_pct']:.1f}% / {cur['spread_pct']:.1f}%")
        if old["checksum"] != cur["checksum"]:
            failures.append(f"{name}: checksum {old['checksum']} became {cur['checksum']}")
        elif change > threshold:
            failures.append(f"{name}: {change:+.1f}% is slower than the {threshold:.0f}% threshold")
    lines += [f"{name:<10} new in the second file, nothing to compare" for name in new["results"]
              if name not in base["results"]]
    return lines, failures


def cmd_compare(args: argparse.Namespace) -> None:
    base, new = (json.loads(p.read_text(encoding="utf-8")) for p in (args.base, args.new))
    for key in ("scale", "platform"):
        if base[key] != new[key]:
            die(f"the two files differ in {key}: {base[key]!r} and {new[key]!r}")
    lines, failures = compare(base, new, args.threshold)
    print("\n".join(lines))
    for failure in failures:
        print(f"REGRESSION {failure}")
    sys.exit(1 if failures else 0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="build and run the suite")
    run.add_argument("--runs", type=int, default=5)
    run.add_argument("--scale", type=int, default=1, help="multiplies each benchmark's work")
    run.add_argument("--filter", nargs="*", help="substrings of benchmark names")
    run.add_argument("--out", type=Path, help="write the results as JSON")
    run.add_argument("--exe", type=Path, help="a prebuilt release lazyvm")
    run.add_argument("--no-build", action="store_true", help="skip cargo and lazyrt (LazyOS: reuse the image)")
    run.add_argument("--lazyos", type=Path, help="run inside LazyOS from this checkout")
    cmp = sub.add_parser("compare", help="compare two JSON files; exit 1 on a regression")
    cmp.add_argument("base", type=Path)
    cmp.add_argument("new", type=Path)
    cmp.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                     help="percent slower that counts as a regression (default 10)")
    args = parser.parse_args()
    (cmd_run if args.command == "run" else cmd_compare)(args)


if __name__ == "__main__":
    main()
