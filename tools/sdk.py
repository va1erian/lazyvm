"""Lay out `dist/lazyvm-sdk`, a JDK-shaped folder IntelliJ can register as an
SDK (docs/decisions.md D3, docs/intellij.md):

    bin/java(.exe)     the LazyVM launcher
    jre/lib/rt.jar     lazyrt, the class library (what completion shows)
    lib/lazyrt.jar     the same jar, for build tools
    src.zip            lazyrt's sources, for navigation in the IDE
    release            JAVA_VERSION="1.8.0", which IDEs read

    python tools/sdk.py
"""

from __future__ import annotations

import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lazyrt  # noqa: E402
from common import DIST, EXE, LAZYRT_SRC, cargo_build_launcher  # noqa: E402

SDK = DIST / "lazyvm-sdk"

RELEASE = 'JAVA_VERSION="1.8.0"\nIMPLEMENTOR="LazyVM"\nJAVA_RUNTIME_VERSION="1.8.0-lazyvm"\n'


def build() -> Path:
    exe = cargo_build_launcher()
    jar = lazyrt.build()
    shutil.rmtree(SDK, ignore_errors=True)
    for sub in ("bin", "jre/lib", "lib"):
        (SDK / sub).mkdir(parents=True)
    shutil.copy2(exe, SDK / "bin" / f"java{EXE}")
    shutil.copy2(jar, SDK / "jre" / "lib" / "rt.jar")
    shutil.copy2(jar, SDK / "lib" / "lazyrt.jar")
    (SDK / "release").write_text(RELEASE, encoding="utf-8", newline="\n")
    with zipfile.ZipFile(SDK / "src.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for src in sorted(LAZYRT_SRC.rglob("*.java")):
            z.write(src, src.relative_to(LAZYRT_SRC).as_posix())
    return SDK


def main() -> None:
    sdk = build()
    print(f"sdk: {sdk}")


if __name__ == "__main__":
    main()
