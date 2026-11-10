"""A debugger's view of a JDWP connection, for tools/jdwp_smoke.py: the lookups and requests
it makes, in the debugger's words."""

from __future__ import annotations

import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import jdwplib as j  # noqa: E402
from common import die  # noqa: E402
from jdwplib import Connection, i32, string, u64  # noqa: E402

STEP_MIN, STEP_LINE, DEPTH_INTO, DEPTH_OVER, DEPTH_OUT = 0, 1, 0, 1, 2


class Session:
    """Helpers over a connection, in the debugger's words."""

    def __init__(self, conn: Connection) -> None:
        self.c = conn

    def class_id(self, signature: str) -> int:
        r = self.c.command(j.VM, 2, string(signature))
        if r.i32() != 1:
            die(f"{signature} is not loaded exactly once")
        r.u8()
        return r.id()

    def methods(self, cls: int) -> dict[str, tuple[int, str]]:
        r = self.c.command(j.REFTYPE, 15, u64(cls))
        out = {}
        for _ in range(r.i32()):
            mid, name, sig, _gen, _mods = r.id(), r.string(), r.string(), r.string(), r.i32()
            out[name] = (mid, sig)
        return out

    def fields(self, cls: int) -> dict[str, int]:
        r = self.c.command(j.REFTYPE, 4, u64(cls))
        out = {}
        for _ in range(r.i32()):
            fid, name, _sig, _mods = r.id(), r.string(), r.string(), r.i32()
            out[name] = fid
        return out

    def line_table(self, cls: int, method: int) -> list[tuple[int, int]]:
        r = self.c.command(j.METHOD, 1, u64(cls) + u64(method))
        r.i64(), r.i64()
        return [(r.i64(), r.i32()) for _ in range(r.i32())]

    def variables(self, cls: int, method: int) -> dict[str, int]:
        r = self.c.command(j.METHOD, 2, u64(cls) + u64(method))
        r.i32()
        out = {}
        for _ in range(r.i32()):
            r.i64()
            name, _sig = r.string(), r.string()
            r.i32()
            out[name] = r.i32()
        return out

    def code_index(self, cls: int, method: int, line: int) -> int:
        return min(i for i, n in self.line_table(cls, method) if n == line)

    def line_of(self, loc: tuple[int, int, int]) -> int:
        cls, method, index = loc
        table = [(i, n) for i, n in self.line_table(cls, method) if i <= index]
        return max(table)[1]

    def method_name(self, loc: tuple[int, int, int]) -> str:
        return next(n for n, (m, _s) in self.methods(loc[0]).items() if m == loc[1])

    def request(self, kind: int, policy: int, *mods: bytes) -> int:
        return self.c.command(j.EVENTREQ, 1, bytes([kind, policy]) + i32(len(mods)) + b"".join(mods)).i32()

    def clear(self, kind: int, request: int) -> None:
        self.c.command(j.EVENTREQ, 2, bytes([kind]) + i32(request))

    def resume(self) -> None:
        self.c.command(j.VM, 9)

    def stop(self, kind: int) -> dict:
        """The next event of `kind`, which must stop the VM. Events that arrive
        together (a step ending on a breakpoint) come in one composite."""
        while True:
            _policy, events = self.c.next_event()
            events = [e for e in events if e["kind"] != j.VM_START]
            if not events:
                continue
            if any(e["kind"] == j.CLASS_PREPARE and e["signature"].endswith("$Once;") for e in events):
                # Stopped while the class is being linked: hold the world a while, as a debugger may.
                time.sleep(0.3)
                self.resume()
                continue
            for ev in events:
                if ev["kind"] == kind:
                    return ev
            die(f"expected event kind {kind}, got {events}")

    def breakpoint(self, cls: int, method: int, line: int) -> int:
        loc = bytes([1]) + u64(cls) + u64(method) + struct.pack(">q", self.code_index(cls, method, line))
        return self.request(j.BREAKPOINT, 2, bytes([7]) + loc)

    def step(self, thread: int, depth: int, size: int = STEP_LINE) -> dict:
        how = bytes([10]) + u64(thread) + i32(size) + i32(depth)
        rid = self.request(j.SINGLE_STEP, 2, how)
        self.resume()
        ev = self.stop(j.SINGLE_STEP)
        self.clear(j.SINGLE_STEP, rid)
        return ev

    def thread_named(self, name: str) -> int:
        r = self.c.command(j.VM, 4)
        for _ in range(r.i32()):
            t = r.id()
            if self.c.command(j.THREAD, 1, u64(t)).string() == name:
                return t
        die(f"no thread named {name}")
        raise AssertionError

    def static_int(self, cls: int, field: int) -> int:
        r = self.c.command(j.REFTYPE, 6, u64(cls) + i32(1) + u64(field))
        r.i32()
        return r.value()


def expect(cond: bool, what: str) -> None:
    if not cond:
        die(f"jdwp_smoke: {what}")
