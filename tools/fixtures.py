"""Regenerate the checked-in class file fixtures of the Rust unit tests.

    python tools/fixtures.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
from common import ROOT  # noqa: E402

FIXTURES = [ROOT / "crates" / "classfile" / "tests" / "fixtures"]


def main() -> None:
    for folder in FIXTURES:
        javac.compile(sorted(folder.glob("*.java")), folder)
        print(f"compiled {folder.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
