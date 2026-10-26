"""Build the class library: java/lazyrt -> build/lazyrt/classes and dist/lazyrt.jar.

`lazyrt` is compiled against itself (`-bootclasspath`), never against the
real JDK, so it can only use what it defines (docs/decisions.md D3).

    python tools/lazyrt.py
"""

from __future__ import annotations

import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
from common import DIST, LAZYRT_CLASSES, LAZYRT_SRC, java_sources  # noqa: E402

MANIFEST = "Manifest-Version: 1.0\r\nCreated-By: lazyvm tools/lazyrt.py\r\n\r\n"


def build() -> Path:
    """Compile lazyrt from scratch and return the jar's path."""
    shutil.rmtree(LAZYRT_CLASSES, ignore_errors=True)
    LAZYRT_CLASSES.mkdir(parents=True)
    javac.compile(java_sources(LAZYRT_SRC), LAZYRT_CLASSES,
                  bootclasspath=LAZYRT_CLASSES, sourcepath=LAZYRT_SRC)
    return write_jar(LAZYRT_CLASSES, DIST / "lazyrt.jar")


def write_jar(classes: Path, jar: Path) -> Path:
    """Zip `classes` into `jar` with fixed timestamps, so the jar is reproducible."""
    jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo("META-INF/MANIFEST.MF", (1980, 1, 1, 0, 0, 0)), MANIFEST)
        for path in sorted(classes.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, path.read_bytes())
    return jar


def main() -> None:
    jar = build()
    count = sum(1 for p in LAZYRT_CLASSES.rglob("*.class"))
    print(f"lazyrt: {count} classes -> {jar.relative_to(jar.parents[1])}")


if __name__ == "__main__":
    main()
