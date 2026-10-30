"""Regenerate the checked-in class file fixtures of the Rust unit tests.

    python tools/fixtures.py
"""

from __future__ import annotations

import struct
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
from common import LAZYRT_CLASSES, ROOT, TESTS_SRC, die  # noqa: E402

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


def pool_end_and_classes(data: bytes) -> tuple[int, dict[int, str]]:
    """Where the constant pool of class file `data` ends, and its class names by index (JVMS 4.4)."""
    count = struct.unpack(">H", data[8:10])[0]
    at, index = 10, 1
    utf8: dict[int, str] = {}
    classes: dict[int, int] = {}
    while index < count:
        tag = data[at]
        if tag == 1:
            length = struct.unpack(">H", data[at + 1:at + 3])[0]
            utf8[index] = data[at + 3:at + 3 + length].decode("latin1")
            at += 3 + length
        elif tag == 7:
            classes[index] = struct.unpack(">H", data[at + 1:at + 3])[0]
            at += 3
        elif tag in (3, 4, 9, 10, 11, 12, 18):
            at += 5
        elif tag in (5, 6):
            at += 9
            index += 1
        elif tag in (8, 16):
            at += 3
        elif tag == 15:
            at += 4
        else:
            die(f"constant pool tag {tag}")
        index += 1
    return at, {i: utf8[name] for i, name in classes.items()}


def write_crafted_atomics() -> None:
    """A class that passes natives arguments javac and a verifier would refuse: its casts to
    Field and to array types are overwritten with `nop`. The VM has no verifier yet (S8)."""
    source = ROOT / "tools" / "crafted" / "CraftedAtomics.java"
    with tempfile.TemporaryDirectory() as out:
        javac.compile([source], Path(out), bootclasspath=LAZYRT_CLASSES)
        data = bytearray((Path(out) / "lazy" / "atomics" / "CraftedAtomics.class").read_bytes())
    end, classes = pool_end_and_classes(bytes(data))
    forged = {i for i, name in classes.items() if name == "java/lang/reflect/Field" or name.startswith("[")}
    patched = 0
    for at in range(end, len(data) - 2):
        if data[at] == 0xC0 and (data[at + 1] << 8 | data[at + 2]) in forged:
            data[at:at + 3] = b"\0\0\0"
            patched += 1
    (TESTS_SRC / "lazy" / "atomics" / "CraftedAtomics.class").write_bytes(data)
    print(f"crafted CraftedAtomics.class: {patched} casts removed")


def main() -> None:
    for folder in FIXTURES:
        javac.compile(sorted(folder.glob("*.java")), folder)
        write_sample_jar(folder)
        print(f"compiled {folder.relative_to(ROOT)}")
    write_crafted_atomics()


if __name__ == "__main__":
    main()
