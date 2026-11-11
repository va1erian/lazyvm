"""Stage LazyVM's desktop apps for a LazyOS image (LOS-02): the musl `lazyvm`, the LazyOS
`lazyvm-ui` (tools/lazyos_ui.py), `lazyrt.jar` and the sample apps' jars, in the checkout's
default stage, `<LazyOS>/target/lazyvm/stage`:

    bin/lazyvm  bin/lazyvm-ui  lib/lazyrt.jar  apps/writer.jar  apps/notes.jar  tests/

LazyOS then packages the jars as the core packages `os.lazy.writerj` and `os.lazy.notesj`
(`abi = "lazyvm"`), and an image built with `LAZYOS_LAZYVM=1` on the desktop ships them:

    python tools/lazyos_demo.py --lazyos <LazyOS checkout>
    cd <LazyOS checkout>
    python tools/xui/core_packages.py
    LAZYOS_DESKTOP=1 LAZYOS_LAZYVM=1 LAZYOS_XUI_AUTOSTART=writerj cargo build
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lazyos_test  # noqa: E402
import lazyos_ui  # noqa: E402
import lazyrt  # noqa: E402
import sample  # noqa: E402
import size  # noqa: E402
from common import DIST, die  # noqa: E402

APPS = ["writer", "notes"]


def stage(lazyos: Path) -> Path:
    """Build everything and lay the stage out; returns it."""
    lazyrt.build()
    jars = [sample.build(name, rebuild_lazyrt=False) for name in APPS]
    vm = lazyos_test.build_musl()
    ui = lazyos_ui.build(lazyos)
    out = lazyos.resolve() / "target" / "lazyvm" / "stage"
    shutil.rmtree(out, ignore_errors=True)
    for sub in ("bin", "lib", "apps", "tests"):
        (out / sub).mkdir(parents=True)
    for built, name in ((vm, "lazyvm"), (ui, "lazyvm-ui")):
        # As shipped: without the debug info the release profile keeps.
        (out / "bin" / name).write_bytes(size.shipped_binary(built))
    shutil.copyfile(DIST / "lazyrt.jar", out / "lib" / "lazyrt.jar")
    for jar in jars:
        shutil.copyfile(jar, out / "apps" / jar.name)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lazyos", type=Path, default=os.environ.get("LAZYOS_DIR"),
                        help="the LazyOS checkout (or LAZYOS_DIR)")
    args = parser.parse_args()
    if args.lazyos is None:
        die("pass --lazyos <LazyOS checkout> or set LAZYOS_DIR")
    out = stage(args.lazyos)
    for path in sorted(p for p in out.rglob("*") if p.is_file()):
        print(f"{path.relative_to(out).as_posix()}: {path.stat().st_size:,} B")


if __name__ == "__main__":
    main()
