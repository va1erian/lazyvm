"""Regenerate the checked-in class file fixtures of the Rust unit tests.

    python tools/fixtures.py
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
from common import ROOT  # noqa: E402

FIXTURES = [ROOT / "crates" / "classfile" / "tests" / "fixtures"]


# A continuation line in the manifest exercises the JAR spec's line folding.
MANIFEST = "Manifest-Version: 1.0\r\nMain-Class: app.VeryLongPackage\r\n Name.Main\r\n\r\n"


def write_sample_jar(folder: Path) -> None:
    """sample.jar: the manifest, Sample.class deflated and a stored text entry."""
    fixed = (1980, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(folder / "sample.jar", "w") as z:
        for name, data, method in [("META-INF/MANIFEST.MF", MANIFEST.encode(), zipfile.ZIP_DEFLATED),
                                   ("Sample.class", (folder / "Sample.class").read_bytes(), zipfile.ZIP_DEFLATED),
                                   ("stored.txt", b"stored entry\n", zipfile.ZIP_STORED)]:
            info = zipfile.ZipInfo(name, fixed)
            info.compress_type = method
            z.writestr(info, data)


def main() -> None:
    for folder in FIXTURES:
        javac.compile(sorted(folder.glob("*.java")), folder)
        write_sample_jar(folder)
        print(f"compiled {folder.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
