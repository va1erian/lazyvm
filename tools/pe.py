"""Edits to a Windows executable for `tools/bundle.py` (docs/bundle.md).

`set_icon` replaces the icon Explorer shows, through the Win32 resource update
API, so it runs on Windows only. `set_gui` flips the subsystem in the PE header,
so the program starts without a console window.
"""

from __future__ import annotations

import ctypes
import struct
from pathlib import Path

RT_ICON = 3
RT_GROUP_ICON = 14
SUBSYSTEM_GUI = 2
SUBSYSTEM_CONSOLE = 3
LANG_NEUTRAL = 0


def ico_images(ico: bytes) -> list[tuple[bytes, bytes]]:
    """An .ico's images: each entry's 12 directory bytes (without the offset) and its data."""
    if len(ico) < 6:
        raise ValueError("not an .ico file")
    reserved, kind, count = struct.unpack_from("<HHH", ico, 0)
    if reserved != 0 or kind != 1 or count == 0:
        raise ValueError("not an .ico file")
    images = []
    for i in range(count):
        entry = ico[6 + 16 * i: 6 + 16 * i + 16]
        if len(entry) != 16:
            raise ValueError("truncated .ico directory")
        size, offset = struct.unpack_from("<II", entry, 8)
        data = ico[offset: offset + size]
        if len(data) != size:
            raise ValueError("truncated .ico image")
        images.append((entry[:12], data))
    return images


def group_icon(images: list[tuple[bytes, bytes]]) -> bytes:
    """The RT_GROUP_ICON directory: the .ico one, with resource ids for offsets (ids 1..n)."""
    out = struct.pack("<HHH", 0, 1, len(images))
    for i, (head, _) in enumerate(images):
        out += head + struct.pack("<H", i + 1)
    return out


def set_icon(exe: Path, ico: Path) -> None:
    """Make `ico` the icon of `exe` (Windows only). Run it before appending data to the file:
    the resource update rewrites the file and drops what follows the image."""
    images = ico_images(ico.read_bytes())
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.BeginUpdateResourceW.restype = ctypes.c_void_p
    k32.BeginUpdateResourceW.argtypes = [ctypes.c_wchar_p, ctypes.c_int]
    k32.UpdateResourceW.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                                    ctypes.c_ushort, ctypes.c_char_p, ctypes.c_uint]
    k32.EndUpdateResourceW.argtypes = [ctypes.c_void_p, ctypes.c_int]
    handle = k32.BeginUpdateResourceW(str(exe), 0)
    if not handle:
        raise OSError(ctypes.get_last_error(), f"cannot open {exe} for resource update")
    ok = all(k32.UpdateResourceW(handle, RT_ICON, i + 1, LANG_NEUTRAL, data, len(data))
             for i, (_, data) in enumerate(images))
    group = group_icon(images)
    ok = ok and k32.UpdateResourceW(handle, RT_GROUP_ICON, 1, LANG_NEUTRAL, group, len(group))
    if not k32.EndUpdateResourceW(handle, 0 if ok else 1) or not ok:
        raise OSError(ctypes.get_last_error(), f"cannot write the icon into {exe}")


def subsystem_offset(data: bytes) -> int:
    """Where a PE file's Subsystem field is: after the signature, the COFF
    header, and 68 bytes into the optional header (the same for PE32 and PE32+)."""
    if len(data) < 0x40:
        raise ValueError("not a PE file")
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe: pe + 4] != b"PE\0\0" or len(data) < pe + 4 + 20 + 70:
        raise ValueError("not a PE file")
    return pe + 4 + 20 + 68


def subsystem(exe: Path) -> int:
    data = exe.read_bytes()
    return struct.unpack_from("<H", data, subsystem_offset(data))[0]


def set_gui(exe: Path) -> None:
    """Mark `exe` as a windowed program, so Windows opens no console for it."""
    data = bytearray(exe.read_bytes())
    at = subsystem_offset(data)
    if struct.unpack_from("<H", data, at)[0] not in (SUBSYSTEM_GUI, SUBSYSTEM_CONSOLE):
        raise ValueError(f"{exe}: unexpected subsystem")
    struct.pack_into("<H", data, at, SUBSYSTEM_GUI)
    exe.write_bytes(data)
