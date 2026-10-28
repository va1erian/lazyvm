"""Generate java.nio's Short-, Int-, Long-, Float- and DoubleBuffer from one template.

The five classes differ only in their element type, so they are written once
here and generated, instead of being kept in step by hand. CharBuffer and
ByteBuffer have extra API and are written by hand.

    python tools/buffers.py          # rewrite the generated sources
    python tools/buffers.py --check  # fail if they are out of date
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import LAZYRT_SRC, die  # noqa: E402

# type, class, size in bytes, ByteBuffer accessor suffix, equality, comparison
KINDS = [
    ("short", "ShortBuffer", 2, "Short", "a == b", "a - b"),
    ("int", "IntBuffer", 4, "Int", "a == b", "Integer.compare(a, b)"),
    ("long", "LongBuffer", 8, "Long", "a == b", "Long.compare(a, b)"),
    ("float", "FloatBuffer", 4, "Float", "a == b || (a != a && b != b)", "floatOrder(a, b)"),
    ("double", "DoubleBuffer", 8, "Double", "a == b || (a != a && b != b)", "floatOrder(a, b)"),
]

FLOAT_ORDER = """
    // Spec NB5: NaN sorts last and -0.0 equals 0.0.
    private static int floatOrder(double a, double b) {
        if (a < b) {
            return -1;
        }
        if (a > b) {
            return 1;
        }
        if (a == b) {
            return 0;
        }
        return a != a ? (b != b ? 0 : 1) : -1;
    }
"""

TEMPLATE = Path(__file__).resolve().parent / "buffer_template.java.txt"


SPLIT = "//---- $WIN.java ----" + chr(10)


def render(t: str, cls: str, size: int, acc: str, eq: str, cmp: str) -> dict[str, str]:
    """The public class and its implementation class (top-level, so toString shows a plain name)."""
    text = TEMPLATE.read_text(encoding="utf-8")
    extra = FLOAT_ORDER if t in ("float", "double") else ""
    win = acc + "Window"
    for key, value in (("$EXTRA", extra), ("$CLS", cls), ("$WIN", win), ("$T", t), ("$SIZE", str(size)),
                       ("$ACC", acc), ("$EQ", eq), ("$CMP", cmp)):
        text = text.replace(key, value)
    public, _, impl = text.partition(SPLIT.replace("$WIN", win))
    return {cls: public, win: impl}


def main() -> None:
    check = "--check" in sys.argv[1:]
    stale = []
    for kind in KINDS:
        for name, text in render(*kind).items():
            path = LAZYRT_SRC / "java" / "nio" / f"{name}.java"
            if check:
                if not path.is_file() or path.read_text(encoding="utf-8") != text:
                    stale.append(path.name)
            else:
                path.write_bytes(text.encode("utf-8"))
    if stale:
        die("out of date, run python tools/buffers.py: " + ", ".join(stale))


if __name__ == "__main__":
    main()
