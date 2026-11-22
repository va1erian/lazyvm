"""Build a self-contained executable: the LazyVM launcher, lazyrt.jar and an application.

    python tools/bundle.py --out dist/App.exe --jar app.jar
    python tools/bundle.py --out dist/App.exe --cp classes;lib/a.jar;res --main my.App
                           [--vm lazyvm|lazyvm-ui|PATH] [--icon app.ico | --icon none]
                           [--dir assets ...] [--gui] [--vm-arg=-Xmx512m ...]
                           [-- program arguments]

The application's class path (directories and jars, and with `--jar` that jar's
`Class-Path`) is merged into one jar, first entry winning as on a class path.
That jar and the class library are stored uncompressed and the two are packed
together as one deflate stream, which shares the redundancy between class files.
The payload goes at the end of a copy of the launcher, which finds and runs it
(crates/launcher/src/bundle.rs). Arguments given to the executable reach `main`
after the fixed ones. A `--dir` is for an application that opens its files by
path: it is packed too, unpacked to a per-build cache on the first run and put on
the class path after the application. Layout and options: docs/bundle.md.
"""

from __future__ import annotations

import argparse
import io
import os
import shutil
import struct
import sys
import urllib.parse
import zipfile
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pe  # noqa: E402
from common import DIST, ROOT, cargo_build_launcher, die  # noqa: E402

MAGIC = b"LZVMBND1"
DEFAULT_ICON = ROOT / "tools" / "bundle" / "lazyvm.ico"
# Fixed so that a bundle depends only on its inputs.
EPOCH = (1980, 1, 1, 0, 0, 0)
# Entries invalid once jars are merged: signatures cover their own jar's files only.
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
MAX_ENTRIES = 65535  # past it a zip needs zip64, which the VM's reader does not take
# What the launcher accepts (crates/launcher/src/bundle.rs, classfile's jar.rs).
MAX_UNPACKED = 1 << 30
MAX_ENTRY = 256 << 20


def manifest_attributes(text: str) -> dict[str, str]:
    """The main section of a manifest, continuation lines joined (JAR File Specification)."""
    attrs, last = {}, None
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if not line:
            break
        if line.startswith(" ") and last:
            attrs[last] += line[1:]
        elif ":" in line:
            last, value = line.split(":", 1)
            attrs[last] = value.strip()
    return {k.lower(): v for k, v in attrs.items()}


def jar_attributes(jar: Path) -> dict[str, str]:
    with zipfile.ZipFile(jar) as z:
        try:
            return manifest_attributes(z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace"))
        except KeyError:
            return {}


def expand_jar(jar: Path, seen: set[Path]) -> list[Path]:
    """`jar`, then the jars and directories its `Class-Path` names (relative URLs),
    depth first and each once. Missing entries are skipped, as the launcher skips them."""
    jar = jar.resolve()
    if jar in seen or not jar.is_file():
        return []
    seen.add(jar)
    out = [jar]
    for rel in jar_attributes(jar).get("class-path", "").split():
        target = (jar.parent / urllib.parse.unquote(rel)).resolve()
        if target.is_dir():
            if target not in seen:
                seen.add(target)
                out.append(target)
        else:
            out += expand_jar(target, seen)
    return out


def entries(path: Path):
    """(name, bytes) of every file and directory under a class path entry."""
    if path.is_dir():
        for dirpath, dirnames, files in os.walk(path):
            dirnames.sort()
            rel = Path(dirpath).relative_to(path).as_posix()
            if rel != ".":
                yield rel + "/", b""
            for f in sorted(files):
                yield (Path(dirpath, f).relative_to(path).as_posix(),
                       Path(dirpath, f).read_bytes())
    elif path.is_file():
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                yield info.filename, (b"" if info.is_dir() else z.read(info))
    else:
        die(f"class path entry {path} does not exist")


def stored_jar(files: dict[str, bytes]) -> bytes:
    """A jar of `files` with nothing compressed: the payload is compressed as a whole.
    Similar files sit together (by extension, then name), which helps that compression."""
    if len(files) > MAX_ENTRIES:
        die(f"{len(files)} entries: a bundle holds at most {MAX_ENTRIES}")
    for name, data in files.items():
        if len(data) > MAX_ENTRY:
            die(f"{name}: {len(data)} bytes; an entry holds at most {MAX_ENTRY}")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name in sorted(files, key=lambda n: (Path(n).suffix, n)):
            info = zipfile.ZipInfo(name, EPOCH)
            info.external_attr = (0o40755 << 16 | 0x10) if name.endswith("/") else 0o644 << 16
            z.writestr(info, files[name])
    return buf.getvalue()


def merge(classpath: list[Path]) -> bytes:
    files: dict[str, bytes] = {}
    for path in classpath:
        for name, data in entries(path):
            upper = name.upper()
            if upper.startswith("META-INF/") and upper.endswith(SIGNATURE_SUFFIXES):
                continue
            files.setdefault(name, data)
    return stored_jar(files)


def pack(boot: bytes, app: bytes, files: bytes, args: list[str]) -> bytes:
    """The payload and its trailer (layout: crates/launcher/src/bundle.rs)."""
    if any("\0" in a for a in args):
        die("arguments cannot hold NUL characters")
    arg_bytes = b"".join(a.encode("utf-8") + b"\0" for a in args)
    unpacked = boot + app + files + arg_bytes
    if len(unpacked) > MAX_UNPACKED:
        die(f"{len(unpacked)} bytes unpacked; a bundle holds at most {MAX_UNPACKED}")
    deflate = zlib.compressobj(9, zlib.DEFLATED, -15, 9)
    packed = deflate.compress(unpacked) + deflate.flush()
    trailer = (len(packed), len(boot), len(app), len(files), len(arg_bytes), zlib.crc32(unpacked))
    return packed + struct.pack("<6Q", *trailer) + MAGIC


def launcher(choice: str, build: bool) -> Path:
    if choice in ("lazyvm", "lazyvm-ui"):
        if not build:
            from common import lazyvm_exe
            return lazyvm_exe(name=choice)
        return cargo_build_launcher(release=True, ui=choice == "lazyvm-ui")
    return Path(choice)


def plan(args) -> tuple[list[Path], str]:
    """The application class path and main class the options describe."""
    classpath, main = [], args.main
    if args.jar:
        classpath += expand_jar(args.jar, set())
        if not classpath:
            die(f"cannot read {args.jar}")
        main = main or jar_attributes(args.jar).get("main-class")
    for cp in args.cp:
        classpath += [Path(p) for p in cp.split(os.pathsep) if p]
    if not classpath and not args.dir:
        die("give the application with --jar, --cp or --dir")
    if not main:
        die("no main class: give --main, or a jar whose manifest has Main-Class")
    return classpath, main


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, required=True, help="the executable to write")
    ap.add_argument("--jar", type=Path, help="an application jar with a Main-Class")
    ap.add_argument("--cp", action="append", default=[], help="class path entries")
    ap.add_argument("--main", help="the main class (default: the jar's Main-Class)")
    ap.add_argument("--vm", default="lazyvm", help="lazyvm, lazyvm-ui or a launcher's path")
    ap.add_argument("--no-build", action="store_true", help="use the launcher as built")
    ap.add_argument("--lazyrt", type=Path, default=DIST / "lazyrt.jar")
    ap.add_argument("--icon", default=str(DEFAULT_ICON), help=".ico file, or 'none'")
    ap.add_argument("--dir", action="append", default=[], type=Path,
                    help="a directory the application reads as files (unpacked on first run)")
    ap.add_argument("--gui", action="store_true", help="no console window (Windows)")
    ap.add_argument("--vm-arg", action="append", default=[], help="a VM option, e.g. -Xmx1g")
    argv = sys.argv[1:]
    fixed = argv[argv.index("--") + 1:] if "--" in argv else []
    args = ap.parse_args(argv[: len(argv) - len(fixed) - (1 if "--" in argv else 0)])
    classpath, main_class = plan(args)
    if not args.lazyrt.is_file():
        die(f"{args.lazyrt} is missing: run python tools/lazyrt.py")
    vm = launcher(args.vm, not args.no_build)
    if not vm.is_file():
        die(f"launcher {vm} is missing")
    if vm.read_bytes()[-8:] == MAGIC:
        die(f"{vm} is already a bundle")
    for a in args.vm_arg:
        if not a.startswith("-"):
            die(f"--vm-arg {a}: VM options start with '-'")
    boot = merge([args.lazyrt])
    app = merge(classpath)
    for d in args.dir:
        if not d.is_dir():
            die(f"--dir {d} is not a directory")
    files = merge(args.dir) if args.dir else b""
    payload = pack(boot, app, files, args.vm_arg + [main_class] + fixed)
    write(args, vm, payload)
    print(f"wrote {args.out}: {args.out.stat().st_size} bytes "
          f"(launcher {vm.stat().st_size}, payload {len(payload)}; "
          f"unpacked class library {len(boot)}, application {len(app)}, files {len(files)})")
    return 0


def write(args, vm: Path, payload: bytes) -> None:
    """Launcher copy, then icon and subsystem, then the payload: a resource update
    rewrites the file and would drop anything already appended."""
    is_pe = vm.read_bytes()[:2] == b"MZ"
    icon = None if args.icon == "none" or not is_pe else Path(args.icon)
    if icon and os.name != "nt":
        die("icons are written with the Windows resource API: run on Windows, or --icon none")
    if icon and not icon.is_file():
        die(f"--icon {icon} does not exist")
    if args.gui and not is_pe:
        die("--gui is for Windows executables")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    tmp = args.out.with_name(args.out.name + ".tmp")
    shutil.copyfile(vm, tmp)
    try:
        if icon:
            pe.set_icon(tmp, icon)
        if args.gui:
            pe.set_gui(tmp)
        with open(tmp, "ab") as f:
            f.write(payload)
        os.replace(tmp, args.out)
    except (OSError, ValueError, struct.error) as e:
        tmp.unlink(missing_ok=True)
        die(f"cannot write {args.out}: {e}")


if __name__ == "__main__":
    sys.exit(main())
