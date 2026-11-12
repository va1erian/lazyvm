"""Build `lazyvm-ui` for LazyOS: `crates/lazyvm-lazyos`, whose `lazy.ui` windows are xuid
clients through the LazyOS checkout's `xui-app` (docs/ui.md, "Backends"; S9-04).

The crate is GPL-3.0-or-later and its own workspace. It reaches the checkout through
`build/lazyos-checkout`, a link this tool makes (a junction on Windows; `build/` is git-ignored),
starts from the checkout's `xui-app/Cargo.lock` so it builds against the versions LazyOS pins,
and uses the toolchain the checkout pins (LazyOS's code needs its nightly).

    python tools/lazyos_ui.py --lazyos <LazyOS checkout>        # prints the binary's path
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import BUILD, ROOT, die  # noqa: E402

CRATE = ROOT / "crates" / "lazyvm-lazyos"
TARGET = "x86_64-unknown-linux-musl"
TARGET_DIR = ROOT / "target" / "lazyos-ui"


def link(lazyos: Path) -> None:
    """Point `build/lazyos-checkout` at the checkout, replacing an old link."""
    path = BUILD / "lazyos-checkout"
    path.parent.mkdir(parents=True, exist_ok=True)
    if os.path.lexists(path):
        if not (path.is_symlink() or path.is_junction()):
            die(f"{path} exists and is not a link")
        # A junction or a directory link goes with rmdir; it leaves the target alone.
        os.rmdir(path) if sys.platform == "win32" else path.unlink()
    if sys.platform == "win32":
        done = subprocess.run(["cmd", "/c", "mklink", "/J", str(path), str(lazyos)], capture_output=True)
        if done.returncode != 0:
            die(f"cannot link {path}: {done.stderr.decode(errors='replace')}")
    else:
        path.symlink_to(lazyos, target_is_directory=True)


def toolchain(lazyos: Path) -> str:
    """The channel the checkout's `rust-toolchain.toml` pins."""
    text = (lazyos / "rust-toolchain.toml").read_text(encoding="utf-8")
    for line in text.splitlines():
        key, _, value = line.partition("=")
        if key.strip() == "channel":
            return value.strip().strip('"')
    die(f"{lazyos}/rust-toolchain.toml names no channel")


def build(lazyos: Path) -> Path:
    """Build the release binary and return its path."""
    lazyos = lazyos.resolve()
    if not (lazyos / "xui-app" / "Cargo.toml").is_file():
        die(f"{lazyos} is not a LazyOS checkout (no xui-app/Cargo.toml)")
    link(lazyos)
    lock = CRATE / "Cargo.lock"
    if not lock.is_file():
        shutil.copyfile(lazyos / "xui-app" / "Cargo.lock", lock)
    cmd = ["cargo", f"+{toolchain(lazyos)}", "build", "-q", "--release", "--manifest-path", str(CRATE / "Cargo.toml"),
           "--target", TARGET, "--target-dir", str(TARGET_DIR)]
    if subprocess.run(cmd, cwd=ROOT).returncode != 0:
        die("cargo build of crates/lazyvm-lazyos failed")
    return TARGET_DIR / TARGET / "release" / "lazyvm-ui"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lazyos", type=Path, default=os.environ.get("LAZYOS_DIR"),
                        help="the LazyOS checkout (or LAZYOS_DIR)")
    args = parser.parse_args()
    if args.lazyos is None:
        die("pass --lazyos <LazyOS checkout> or set LAZYOS_DIR")
    print(build(args.lazyos))


if __name__ == "__main__":
    main()
