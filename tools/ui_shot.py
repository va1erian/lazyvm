"""Screenshot a lazy.ui program's real window on Windows (S9-03, docs/ui.md "Evidence").

Starts `lazyvm-ui` with `-Dlazy.ui.stats=true` and the given program, waits for its window,
optionally resizes it from outside, captures it to a PNG, checks that it is not blank, closes
it with the close button's message, and prints the stats line the run ends with. Uses only
ctypes and the Win32 API.

    python tools/ui_shot.py --out shot.png -- -jar dist/samples/writer.jar
    python tools/ui_shot.py --out shot.png --resize 100 -- -cp build/samples/counter Counter
"""

from __future__ import annotations

import argparse
import ctypes
import struct
import subprocess
import sys
import time
import zlib
from ctypes import wintypes
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import DIST, die, lazyvm_exe  # noqa: E402

user32 = ctypes.WinDLL("user32", use_last_error=True) if sys.platform == "win32" else None
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True) if sys.platform == "win32" else None
WM_CLOSE = 0x0010
PW_RENDERFULLCONTENT = 2


def find_window(pid: int) -> int | None:
    """The largest visible top-level window of process `pid` (toolkits make small helpers)."""
    found: list[tuple[int, int]] = []
    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def visit(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            rect = wintypes.RECT()
            user32.GetClientRect(hwnd, ctypes.byref(rect))
            area = (rect.right - rect.left) * (rect.bottom - rect.top)
            if area > 0:
                found.append((area, hwnd))
        return True

    user32.EnumWindows(proc(visit), 0)
    return max(found)[1] if found else None


def capture(hwnd: int) -> tuple[int, int, bytes]:
    """The window's client pixels as (width, height, BGRA rows top-down)."""
    rect = wintypes.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(rect))
    w, h = rect.right - rect.left, rect.bottom - rect.top
    screen = user32.GetDC(hwnd)
    mem = gdi32.CreateCompatibleDC(screen)
    bitmap = gdi32.CreateCompatibleBitmap(screen, w, h)
    gdi32.SelectObject(mem, bitmap)
    user32.PrintWindow(hwnd, mem, PW_RENDERFULLCONTENT | 1)  # 1: the client area only
    header = struct.pack("<IiiHHIIiiII", 40, w, -h, 1, 32, 0, 0, 0, 0, 0, 0)
    info = ctypes.create_string_buffer(header + b"\0" * 16)
    pixels = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(mem, bitmap, 0, h, pixels, info, 0)
    gdi32.DeleteObject(bitmap)
    gdi32.DeleteDC(mem)
    user32.ReleaseDC(hwnd, screen)
    return w, h, pixels.raw


def write_png(path: Path, w: int, h: int, bgra: bytes) -> None:
    rows = b"".join(b"\0" + bytes(c for px in range(w) for c in (
        bgra[(y * w + px) * 4 + 2], bgra[(y * w + px) * 4 + 1], bgra[(y * w + px) * 4])) for y in range(h))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def non_blank(bgra: bytes) -> float:
    """The share of pixels that differ from the first one."""
    first = bgra[:3]
    count = len(bgra) // 4
    return sum(1 for i in range(count) if bgra[i * 4:i * 4 + 3] != first) / max(count, 1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--exe", type=Path, default=lazyvm_exe(name="lazyvm-ui"))
    parser.add_argument("--boot", type=Path, default=DIST / "lazyrt.jar", help="the class library")
    parser.add_argument("--out", type=Path, required=True, help="the PNG to write")
    parser.add_argument("--wait", type=float, default=2.0, help="seconds to wait once the window shows")
    parser.add_argument("--resize", type=int, default=0, help="resize the window this many times first")
    parser.add_argument("--min-non-blank", type=float, default=0.01)
    parser.add_argument("program", nargs=argparse.REMAINDER, help="-- then the VM's arguments")
    args = parser.parse_args()
    if sys.platform != "win32":
        die("ui_shot.py drives Windows windows")
    program = args.program[1:] if args.program[:1] == ["--"] else args.program
    # Real pixels: otherwise Windows scales sizes for this tool and crops the capture.
    user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # per monitor, v2
    started = time.perf_counter()
    proc = subprocess.Popen([str(args.exe), f"-Xbootclasspath:{args.boot}", "-Dlazy.ui.stats=true", *program],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    hwnd = None
    while hwnd is None and time.perf_counter() - started < 30 and proc.poll() is None:
        hwnd = find_window(proc.pid)
        time.sleep(0.01)
    if hwnd is None:
        out, err = proc.communicate(timeout=5) if proc.poll() is None else proc.communicate()
        die(f"no window appeared\n{out.decode(errors='replace')}{err.decode(errors='replace')}")
    print(f"window after {(time.perf_counter() - started) * 1000:.0f} ms")
    time.sleep(args.wait)
    for i in range(args.resize):
        rect = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))
        w, h = 700 + (i % 10) * 20, 480 + (i % 7) * 15
        user32.SetWindowPos(hwnd, 0, rect.left, rect.top, w, h, 0x0004 | 0x0010)  # NOZORDER, NOACTIVATE
        time.sleep(0.02)
    if args.resize:
        time.sleep(0.5)
    w, h, pixels = capture(hwnd)
    write_png(args.out, w, h, pixels)
    share = non_blank(pixels)
    print(f"{args.out}: {w}x{h}, {share:.1%} non-blank")
    user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    try:
        out, err = proc.communicate(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
        out, err = proc.communicate()
    sys.stdout.write(out.decode(errors="replace"))
    sys.stdout.write(err.decode(errors="replace"))
    print(f"exit {proc.returncode}")
    if share < args.min_non_blank:
        die(f"the window is blank ({share:.1%} non-blank)")


if __name__ == "__main__":
    main()
