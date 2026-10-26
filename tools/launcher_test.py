"""Launcher checks the differential suite cannot express: `-jar` with a
manifest `Class-Path`, jar errors, and an application class in `java.*`.

Each case is a command line; its transcript must equal
`tests/launcher/expected/<case>.out`, blessed on the Java 8 oracle with
`--bless` (the same command line, minus LazyVM's boot path, which comes from
`LAZYVM_BOOTCLASSPATH`).

    python tools/launcher_test.py [--bless] [--exe <lazyvm>]
"""

from __future__ import annotations

import argparse
import os
import subprocess
import warnings
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import lazyrt  # noqa: E402
import testlib  # noqa: E402
from common import BUILD, DIST, LAZYRT_CLASSES, ROOT, cargo_build_launcher, die, find_tool  # noqa: E402

SRC = ROOT / "tests" / "launcher"
OUT = BUILD / "launcher"
EXPECTED = SRC / "expected"
SEP = os.pathsep

# Paths are relative to the repo root, as a user would type them.
J = "build/launcher"
CASES = {
    "jar": ["-jar", f"{J}/app.jar", "x", "y z", ""],
    "jar_missing": ["-jar", f"{J}/missing.jar"],
    "jar_no_main": ["-jar", f"{J}/nomain.jar"],
    "jar_corrupt": ["-jar", f"{J}/corrupt.jar"],
    "classpath_jars": ["-cp", f"{J}/app.jar{SEP}{J}/lib.jar", "app.Main", "plain"],
    "bad_cp_entry": ["-cp", f"{J}/corrupt.jar{SEP}{J}/missing.jar{SEP}{J}/app.jar{SEP}{J}/lib.jar", "app.Main"],
    "jar_prefixed": ["-jar", f"{J}/prefixed.jar"],
    "jar_transitive": ["-jar", f"{J}/chain.jar"],
    "duplicate_entry": ["-cp", f"{J}/dup.jar{SEP}{J}/app.jar{SEP}{J}/lib.jar", "app.Probe"],
    "manifest_no_space": ["-jar", f"{J}/nospace.jar"],
    "cp_follows_class_path": ["-cp", f"{J}/chain.jar", "app.Probe"],
    "class_path_loop": ["-jar", f"{J}/loop.jar"],
    # order_a.jar's Class-Path jar is searched before the next -cp entry.
    "class_path_order": ["-cp", f"{J}/order_a.jar{SEP}{J}/order_c.jar{SEP}{J}/app.jar{SEP}{J}/lib.jar", "app.Probe"],
}


def jar(path: Path, classes: Path, manifest: str | None) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        if manifest is not None:
            z.writestr("META-INF/MANIFEST.MF", manifest)
        for f in sorted(classes.rglob("*.class")):
            z.write(f, f.relative_to(classes).as_posix())


def build() -> None:
    lazyrt.build()
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ("lib", "evil", "app"):
        cp = [OUT / "lib"] if name == "app" else None
        javac.compile(sorted((SRC / name).rglob("*.java")), OUT / name, bootclasspath=LAZYRT_CLASSES, classpath=cp)
    jar(OUT / "lib.jar", OUT / "lib", "Manifest-Version: 1.0\r\n\r\n")
    with zipfile.ZipFile(OUT / "lib.jar", "a") as z:
        z.write(OUT / "evil" / "java" / "lang" / "Evil.class", "java/lang/Evil.class")
    jar(OUT / "app.jar", OUT / "app",
        "Manifest-Version: 1.0\r\nMain-Class: app.Main\r\nClass-Path: lib.jar\r\n\r\n")
    jar(OUT / "nomain.jar", OUT / "app", "Manifest-Version: 1.0\r\n\r\n")
    (OUT / "corrupt.jar").write_bytes(b"PK\x03\x04 this is not a zip archive")
    # A launcher script in front of the archive (offsets are relative to its end).
    script = b"#!/bin/sh\nexec java -jar \"$0\"\n"
    (OUT / "prefixed.jar").write_bytes(script + (OUT / "app.jar").read_bytes())
    # Class-Path is followed transitively: chain.jar -> chain1.jar -> lib.jar.
    jar(OUT / "chain.jar", OUT / "app",
        "Manifest-Version: 1.0\r\nMain-Class: app.Probe\r\nClass-Path: chain1.jar\r\n\r\n")
    with zipfile.ZipFile(OUT / "chain1.jar", "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nClass-Path: lib.jar\r\n\r\n")
        z.writestr("r.txt", "from chain1")
    # The same entry name twice: the later one wins.
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        with zipfile.ZipFile(OUT / "dup.jar", "w") as z:
            z.writestr("r.txt", "first")
            z.writestr("r.txt", "second")
    jar(OUT / "nospace.jar", OUT / "app", "Manifest-Version: 1.0\r\nMain-Class:app.Main\r\n\r\n")
    for name, manifest, resource in [
        ("order_a", "Manifest-Version: 1.0\r\nClass-Path: order_b.jar\r\n\r\n", None),
        ("order_b", None, "from b"),
        ("order_c", None, "from c"),
    ]:
        with zipfile.ZipFile(OUT / f"{name}.jar", "w") as z:
            if manifest:
                z.writestr("META-INF/MANIFEST.MF", manifest)
            if resource:
                z.writestr("r.txt", resource)
    # A Class-Path that names its own jar through `..` must not loop.
    jar(OUT / "loop.jar", OUT / "app", "Manifest-Version: 1.0\r\nMain-Class: app.Main\r\n"
        "Class-Path: lib.jar x/../loop.jar y/../loop.jar\r\n\r\n")


def run(cmd: list[str], env: dict[str, str]) -> str:
    done = subprocess.run(cmd, cwd=ROOT, capture_output=True, env=env, timeout=60)
    return testlib.transcript(done.stdout.decode("utf-8", "replace"),
                              done.stderr.decode("utf-8", "replace"), done.returncode)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bless", action="store_true")
    parser.add_argument("--exe", type=Path)
    args = parser.parse_args()
    build()
    env = dict(os.environ, LAZYVM_BOOTCLASSPATH=str(DIST / "lazyrt.jar"))
    if args.bless:
        exe = find_tool("LAZYVM_ORACLE_JAVA", "java")
        EXPECTED.mkdir(exist_ok=True)
    else:
        exe = str(args.exe or cargo_build_launcher())
    failed = 0
    for name, case in CASES.items():
        got = run([exe, *case], env)
        golden = EXPECTED / f"{name}.out"
        if args.bless:
            golden.write_text(got, encoding="utf-8", newline="\n")
            print(f"blessed {name}")
        elif golden.is_file() and got == golden.read_text(encoding="utf-8"):
            print(f"PASS {name}")
        else:
            failed += 1
            print(f"FAIL {name}\n--- expected\n{golden.read_text(encoding='utf-8') if golden.is_file() else '(none)'}--- got\n{got}")
    if failed:
        die(f"{failed} launcher case(s) failed")


if __name__ == "__main__":
    main()
