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
import shutil
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
    # Below 1 MiB the heap is refused; at it, thread.OutOfMemorySmallestHeap runs.
    "xmx_too_small": ["-Xmx1023k", "-cp", f"{J}/app.jar{SEP}{J}/lib.jar", "app.Main"],
    "xmx_one_byte": ["-Xmx1", "-cp", f"{J}/app.jar{SEP}{J}/lib.jar", "app.Main"],
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
    # An empty entry, or an empty class path, is the current directory; the property keeps the text.
    "cp_empty_value": ["-cp", "", "app.Props", "nosuch"],
    "cp_empty_property": ["-Djava.class.path=", "app.Props", "nosuch"],
    "cp_trailing_separator": [f"-Djava.class.path={J}/app{SEP}", "app.Props", "java.class.path"],
    "cp_leading_separator": ["-cp", f"{SEP}{J}/app", "app.Props", "java.class.path"],
    "cp_empty_entry": ["-cp", f"{J}/nothing{SEP}", "app.Props", "java.class.path"],
    "cp_property_after_cp": ["-cp", f"{J}/nothing", f"-Djava.class.path={J}/app", "app.Props", "java.class.path"],
    "cp_cp_after_property": [f"-Djava.class.path={J}/nothing", "-cp", f"{J}/app", "app.Props", "java.class.path"],
}
# Cases that must run from another directory (relative to the repo root).
RUN_IN = {"cp_empty_value": f"{J}/app", "cp_empty_property": f"{J}/app", "cp_empty_entry": f"{J}/app"}


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


def run(cmd: list[str], env: dict[str, str], cwd: Path = ROOT) -> str:
    done = subprocess.run(cmd, cwd=cwd, capture_output=True, env=env, timeout=60)
    return testlib.transcript(done.stdout.decode("utf-8", "replace"),
                              done.stderr.decode("utf-8", "replace"), done.returncode)


def props_line(text: str, value: str) -> str:
    """What app.Props prints for an argument: its length, its last UTF-16 unit and the property."""
    raw = text.encode("utf-16-le", "surrogatepass")
    return f"{len(raw) // 2} {int.from_bytes(raw[-2:], 'little')} {value}"


def fresh(path: Path, source: Path | None) -> Path:
    """`path` as a directory with a copy of `source`'s tree (or empty)."""
    if path.exists():
        shutil.rmtree(path)
    if source is None:
        path.mkdir()
    else:
        shutil.copytree(source, path)
    return path


NOT_FOUND = "Could not find or load main class app.Props"


def not_found(got: str) -> bool:
    return NOT_FOUND in got and got.endswith("--- exit 1 ---\n")


def checks(exe: str, env: dict[str, str]) -> list[tuple[str, str, object]]:
    """Hand-written cases (name, got, expected: the exact text, or a predicate on it) that the
    oracle cannot bless: java.exe reads its command line through the ANSI code page, so the
    arguments below never reach it intact, and some of these names exist only here."""
    app = OUT / "app"
    no_cp = {k: v for k, v in env.items() if k != "CLASSPATH"}
    mark = chr(0x10FFFF)  # the VM's own escape marker, a valid character
    lone = chr(0xD800)  # not Unicode: a Windows command line can still carry it
    keys = ["k" + mark, "k" + mark + "m"]
    none = "6 104 null\n"  # app.Props nosuch
    nothing = OUT / "nothing"  # never created
    # Directories and a jar whose names hold the marker character, and a lone surrogate beside
    # a decoy named like that surrogate's escape (which must never be looked in).
    marked = fresh(OUT / ("m" + mark), app)
    marked_jar = OUT / ("j" + mark + ".jar")
    shutil.copyfile(OUT / "app.jar", marked_jar)
    lone_empty = fresh(OUT / ("z" + lone), None)
    fresh(OUT / ("z" + mark + "xd800"), app)
    lone_full = fresh(OUT / ("y" + lone), app)
    fresh(OUT / ("y" + mark + "xd800"), None)
    jar_ref = run([exe, "-jar", str(OUT / "app.jar"), "x"], env)
    missing = chr(0xFFFD)
    # A decoy class named app.Pr<U+FFFD>: app.Props with its name patched (same byte length).
    decoy = fresh(OUT / "decoy", None)
    (decoy / "app").mkdir()
    props = (app / "app" / "Props.class").read_bytes()
    (decoy / "app" / ("Pr" + missing + ".class")).write_bytes(
        props.replace(b"app/Props", b"app/Pr" + missing.encode("utf-8")))
    return [
        # -D keys are stored the way getProperty looks them up, marker character included.
        ("property_marker_keys",
         run([exe, f"-D{keys[0]}=mark", f"-D{keys[1]}=spelled", "-cp", str(app), "app.Props", *keys], env),
         props_line(keys[0], "mark") + "\n" + props_line(keys[1], "spelled") + "\n"),
        # A lone surrogate in an argument is data, not a crash.
        ("lone_surrogate_argument",
         run([exe, f"-D{lone}=lone", "-cp", str(app), "app.Props", lone], env),
         props_line(lone, "lone") + "\n"),
        # Paths are the exact characters given: the marker is a character like any other ...
        ("marker_classpath_dir", run([exe, "-cp", str(marked), "app.Props", "nosuch"], env), none),
        ("marker_jar", run([exe, "-jar", str(marked_jar), "x"], env), jar_ref),
        # ... and a lone surrogate names its own directory, never the decoy spelled like its escape.
        ("lone_classpath_dir_is_exact", run([exe, "-cp", str(lone_empty), "app.Props", "nosuch"], env), not_found),
        ("lone_classpath_dir_works", run([exe, "-cp", str(lone_full), "app.Props", "nosuch"], env), none),
        # A missing main class is named as written, with no internal spelling.
        ("main_class_message",
         run([exe, "-cp", str(app), "app.Q" + lone], env),
         testlib.transcript("", f"Error: Could not find or load main class app.Q{missing}\n", 1)),
        # ... and never resolves to the class its lossy spelling names.
        ("main_class_lone_not_decoy",
         run([exe, "-cp", str(decoy), "app.Pr" + lone, "nosuch"], env),
         testlib.transcript("", f"Error: Could not find or load main class app.Pr{missing}\n", 1)),
        ("main_class_decoy_exists", run([exe, "-cp", str(decoy), "app.Pr" + missing, "nosuch"], env), none),
        # "." is the current directory, whether given or the default.
        ("classpath_dot", run([exe, "-cp", ".", "app.Props", "nosuch"], env, app), none),
        ("classpath_default", run([exe, "app.Props", "nosuch"], no_cp, app), none),
        # -cp and -Djava.class.path are the same setting: the last one given wins, for loading
        # and for the property alike.
        ("property_classpath", run([exe, f"-Djava.class.path={app}", "app.Props", "nosuch"], no_cp), none),
        ("cp_then_property_fails",
         run([exe, "-cp", str(app), f"-Djava.class.path={nothing}", "app.Props", "nosuch"], env), not_found),
        ("property_then_cp_works",
         run([exe, f"-Djava.class.path={nothing}", "-cp", str(app), "app.Props", "java.class.path"], env),
         props_line("java.class.path", str(app)) + "\n"),
        ("cp_then_property_works",
         run([exe, "-cp", str(nothing), f"-Djava.class.path={app}", "app.Props", "java.class.path"], env),
         props_line("java.class.path", str(app)) + "\n"),
        ("two_properties_last_wins",
         run([exe, f"-Djava.class.path={nothing}", f"-Djava.class.path={app}", "app.Props", "nosuch"], env), none),
        ("two_properties_first_loses",
         run([exe, f"-Djava.class.path={app}", f"-Djava.class.path={nothing}", "app.Props", "nosuch"], env),
         not_found),
    ]


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
        got = run([exe, *case], env, ROOT / RUN_IN.get(name, "."))
        golden = EXPECTED / f"{name}.out"
        if args.bless:
            golden.write_text(got, encoding="utf-8", newline="\n")
            print(f"blessed {name}")
        elif golden.is_file() and got == golden.read_text(encoding="utf-8"):
            print(f"PASS {name}")
        else:
            failed += 1
            print(f"FAIL {name}\n--- expected\n{golden.read_text(encoding='utf-8') if golden.is_file() else '(none)'}--- got\n{got}")
    if not args.bless:
        for name, got, expected in checks(exe, env):
            if (expected(got) if callable(expected) else got == expected):
                print(f"PASS {name}")
            else:
                failed += 1
                print(f"FAIL {name}\n--- expected\n{expected}--- got\n{got}")
    if failed:
        die(f"{failed} launcher case(s) failed")


if __name__ == "__main__":
    main()
