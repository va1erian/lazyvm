"""Self-contained executables (docs/bundle.md): build some with tools/bundle.py and run them.

    python tools/bundle_test.py [--exe <launcher>]

The application is the launcher test's (tests/launcher): app.jar, whose
`Class-Path` brings lib.jar. Each check runs a bundle from another directory, so
nothing is found beside it, and compares what it prints.
"""

from __future__ import annotations

import argparse
import ctypes
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import launcher_test  # noqa: E402
import pe  # noqa: E402
from common import BUILD, ROOT, cargo_build_launcher  # noqa: E402

OUT = BUILD / "bundle"
APP = launcher_test.OUT
# app.Main's output: lib.jar's class is found, and its java.lang.Evil stays refused.
MAIN_OUT = ["hello, jar", "args=3", "[fixed]", "[user]", "[two words]",
            "evil: java.lang.SecurityException: Prohibited package name: java.lang"]


def bundle(name: str, exe: Path, *args: str) -> Path:
    out = OUT / f"{name}.exe"
    cmd = [sys.executable, str(ROOT / "tools" / "bundle.py"), "--out", str(out),
           "--vm", str(exe), *args]
    subprocess.run(cmd, check=True, cwd=ROOT, stdout=subprocess.DEVNULL)
    return out


def run(exe: Path, *args: str, env: dict | None = None) -> tuple[int, list[str]]:
    p = subprocess.run([str(exe), *args], capture_output=True, text=True, cwd=OUT,
                       env={**os.environ, **(env or {})}, timeout=120)
    return p.returncode, (p.stdout + p.stderr).splitlines()


def has_icon(exe: Path) -> bool:
    k32 = ctypes.WinDLL("kernel32")
    k32.LoadLibraryExW.restype = ctypes.c_void_p
    k32.FindResourceW.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    k32.FreeLibrary.argtypes = [ctypes.c_void_p]
    module = k32.LoadLibraryExW(str(exe), None, 0x2)  # LOAD_LIBRARY_AS_DATAFILE
    try:
        return bool(module and k32.FindResourceW(module, 1, 14))  # RT_GROUP_ICON 1
    finally:
        if module:
            k32.FreeLibrary(module)


def checks(exe: Path) -> list[tuple[str, object, object]]:
    jar = str(APP / "app.jar")
    main = bundle("main", exe, "--jar", jar, "--vm-arg=-Dbundle.prop=set", "--", "fixed")
    got = [("args reach main after the fixed ones", run(main, "user", "two words"), (0, MAIN_OUT))]
    props = bundle("props", exe, "--cp", str(APP / "app"), "--main", "app.Props",
                   "--vm-arg=-Dbundle.prop=set", "--icon", "none", "--gui")
    got.append(("VM options from the bundle", run(props, "bundle.prop")[1], ["11 112 set"]))
    got.append(("--gui marks it windowed", pe.subsystem(props), 2))
    if os.name == "nt":
        got.append(("the icon is a resource", (has_icon(main), has_icon(props)), (True, False)))
    # A Class-Path directory (a relative URL, `%20` and all) is merged like a jar.
    spaced = OUT / "lib dir"
    shutil.rmtree(spaced, ignore_errors=True)
    shutil.copytree(APP / "lib", spaced)
    dir_jar = OUT / "dirapp.jar"
    launcher_test.jar(dir_jar, APP / "app",
                      "Manifest-Version: 1.0\r\nMain-Class: app.Main\r\nClass-Path: lib%20dir/\r\n\r\n")
    from_dir = bundle("dirapp", exe, "--jar", str(dir_jar), "--icon", "none")
    got.append(("a Class-Path directory is bundled", run(from_dir)[1][:1], ["hello, jar"]))
    got += dir_checks(exe)
    damaged = OUT / "damaged.exe"
    data = bytearray(main.read_bytes())
    data[-100] ^= 0xFF  # inside the packed payload
    damaged.write_bytes(data)
    code, lines = run(damaged)
    got.append(("a damaged payload is refused", (code, "damaged bundle" in " ".join(lines)), (1, True)))
    return got


def dir_checks(exe: Path) -> list[tuple[str, object, object]]:
    """A --dir is unpacked once into the cache and follows the bundle on the class path."""
    data = OUT / "data"
    (data / "sub").mkdir(parents=True, exist_ok=True)
    (data / "sub" / "note.txt").write_text("from the bundle", encoding="utf-8")
    cache = OUT / "cache"
    shutil.rmtree(cache, ignore_errors=True)  # so this run unpacks, not an earlier one
    files = bundle("files", exe, "--cp", str(APP / "app"), "--main", "app.Props",
                   "--dir", str(data), "--icon", "none")
    env = {"LOCALAPPDATA": str(cache), "XDG_CACHE_HOME": str(cache)}
    _, lines = run(files, "java.class.path", env=env)
    parts = lines[0].split(" ", 2)[2].split(os.pathsep) if lines else []
    unpacked = Path(parts[1]) if len(parts) == 2 else None
    note = unpacked / "sub" / "note.txt" if unpacked else None
    return [
        ("java.class.path is the bundle, then its directory", (parts[:1], len(parts)), ([str(files)], 2)),
        ("the directory is unpacked under the cache",
         note is not None and note.is_file() and cache in note.parents, True),
        ("its files are whole", note.read_text(encoding="utf-8") if note else None, "from the bundle"),
    ]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--exe", type=Path, help="the launcher to bundle (default: build lazyvm)")
    args = ap.parse_args()
    exe = args.exe or cargo_build_launcher(release=True)
    launcher_test.build()
    OUT.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, got, want in checks(exe.resolve()):
        ok = got == want
        failed += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}" + ("" if ok else f"\n  got  {got!r}\n  want {want!r}"))
    print(f"{failed} failed" if failed else "all bundle checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
