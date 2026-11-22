"""Draw LazyVM's icon: a sleepy crescent moon and a trail of z's on a violet tile.

    python tools/icon.py                 # writes tools/bundle/lazyvm.ico
    python tools/icon.py --png out.png   # also the 256 px image, to look at

Each size is drawn on its own from signed distance functions, so small sizes stay
sharp instead of being scaled down. Pure Python, so the output is reproducible
without an imaging library. `tools/bundle.py` puts it in executables that name no
other icon (docs/bundle.md).
"""

from __future__ import annotations

import argparse
import math
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ROOT  # noqa: E402

ICON = ROOT / "tools" / "bundle" / "lazyvm.ico"
SIZES = (16, 24, 32, 48, 64, 128, 256)

TOP = (124, 92, 255)      # tile gradient, top
BOTTOM = (45, 27, 128)    # and bottom
MOON = (255, 236, 179)
Z = (255, 255, 255)

# Shapes in a unit square, y down. Each z is a top bar, a diagonal, a bottom bar.
MOON_OUTER = (0.42, 0.58, 0.27)          # centre x, y, radius
MOON_BITE = (0.53, 0.49, 0.23)
ZS = ((0.60, 0.17, 0.15), (0.77, 0.36, 0.11), (0.86, 0.55, 0.075))  # left, top, size


def tile_sdf(x: float, y: float) -> float:
    """Rounded square inset by 4 % with a 22 % corner radius."""
    r, half = 0.22, 0.46
    qx, qy = abs(x - 0.5) - (half - r), abs(y - 0.5) - (half - r)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    return outside + min(max(qx, qy), 0.0) - r


def circle(x: float, y: float, c: tuple[float, float, float]) -> float:
    return math.hypot(x - c[0], y - c[1]) - c[2]


def segment(x: float, y: float, ax: float, ay: float, bx: float, by: float) -> float:
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - ax - t * dx, y - ay - t * dy)


def z_sdf(x: float, y: float, left: float, top: float, s: float) -> float:
    w = s * 0.13  # stroke half width
    right, bottom = left + s, top + s
    return min(segment(x, y, left, top, right, top),
               segment(x, y, right, top, left, bottom),
               segment(x, y, left, bottom, right, bottom)) - w


def coverage(d: float, px: float) -> float:
    """Antialiased coverage of a shape at distance `d`, for pixels `px` wide."""
    return max(0.0, min(1.0, 0.5 - d / px))


def over(dst: tuple, src: tuple, a: float) -> tuple:
    return tuple(s * a + d * (1 - a) for s, d in zip(src, dst))


def pixel(x: float, y: float, px: int, size: int) -> tuple[int, int, int, int]:
    p = 1.0 / size
    tile = coverage(tile_sdf(x, y), p)
    if tile == 0:
        return (0, 0, 0, 0)
    t = y
    rgb = tuple(a + (b - a) * t for a, b in zip(TOP, BOTTOM))
    moon = max(circle(x, y, MOON_OUTER), -circle(x, y, MOON_BITE))
    shadow = max(circle(x - 0.012, y - 0.02, MOON_OUTER), -circle(x - 0.012, y - 0.02, MOON_BITE))
    rgb = over(rgb, (20, 10, 60), 0.35 * coverage(shadow, p * 3))
    rgb = over(rgb, MOON, coverage(moon, p))
    # The smallest sizes keep only the two larger z's: a third would be a smudge.
    for left, top, s in ZS[: 2 if size < 32 else 3]:
        rgb = over(rgb, Z, coverage(z_sdf(x, y, left, top, s), p))
    return (*(round(c) for c in rgb), round(255 * tile))


def render(size: int) -> bytes:
    rows = []
    for j in range(size):
        row = bytearray([0])  # filter: none
        for i in range(size):
            row += bytes(pixel((i + 0.5) / size, (j + 0.5) / size, 1, size))
        rows.append(bytes(row))
    return png(size, b"".join(rows))


def png(size: int, raw: bytes) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def ico(images: list[tuple[int, bytes]]) -> bytes:
    """An .ico of PNG images (allowed since Windows Vista)."""
    out = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    for size, data in images:
        dim = size % 256  # 0 means 256
        out += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    return out + b"".join(data for _, data in images)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, default=ICON)
    ap.add_argument("--png", type=Path, help="also write the 256 px image here")
    args = ap.parse_args()
    images = [(s, render(s)) for s in SIZES]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(ico(images))
    if args.png:
        args.png.write_bytes(images[-1][1])
    print(f"wrote {args.out} ({args.out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
