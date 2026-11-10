"""A small JDWP client, written from the protocol specification (S11-04).

Only what tools/jdwp_smoke.py needs: the handshake, packets, replies and the
event queue, and readers for the data types. Object, field, method, frame and
reference type ids are all eight bytes, which the test checks `IDSizes` for.
"""

from __future__ import annotations

import collections
import socket
import struct
import time

HANDSHAKE = b"JDWP-Handshake"
REPLY = 0x80
HEADER = 11

# Command sets and commands (the ones the client uses).
VM, REFTYPE, METHOD, OBJREF, STRREF, THREAD, ARRAYREF, EVENTREQ, FRAME, EVENT = 1, 2, 6, 9, 10, 11, 13, 15, 16, 64
# Event kinds.
SINGLE_STEP, BREAKPOINT, CLASS_PREPARE, VM_DEATH, VM_START = 1, 2, 8, 99, 90
# Error codes.
NOT_IMPLEMENTED = 99


class JdwpError(Exception):
    def __init__(self, code: int, what: str = "") -> None:
        super().__init__(f"JDWP error {code} {what}".strip())
        self.code = code


class Reader:
    """Reads the fields of a reply or event in order."""

    def __init__(self, data: bytes) -> None:
        self.data, self.pos = data, 0

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise ValueError("reply is shorter than its layout")
        out = self.data[self.pos:self.pos + n]
        self.pos += n
        return out

    def u8(self) -> int:
        return self.take(1)[0]

    def i32(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def i64(self) -> int:
        return struct.unpack(">q", self.take(8))[0]

    def id(self) -> int:
        return struct.unpack(">Q", self.take(8))[0]

    def string(self) -> str:
        return self.take(self.i32()).decode("utf-8")

    def location(self) -> tuple[int, int, int]:
        self.u8()  # type tag
        return self.id(), self.id(), self.i64()

    def value(self, tag: int | None = None):
        tag = self.u8() if tag is None else tag
        if tag in b"BZ":
            return self.u8() if tag == ord("B") else bool(self.u8())
        if tag == ord("C"):
            return chr(struct.unpack(">H", self.take(2))[0])
        if tag == ord("S"):
            return struct.unpack(">h", self.take(2))[0]
        if tag == ord("I"):
            return self.i32()
        if tag == ord("J"):
            return self.i64()
        if tag == ord("F"):
            return struct.unpack(">f", self.take(4))[0]
        if tag == ord("D"):
            return struct.unpack(">d", self.take(8))[0]
        if tag == ord("V"):
            return None
        return ("obj", chr(tag), self.id())  # an object of any kind

    def done(self) -> bool:
        return self.pos == len(self.data)


def string(s: str) -> bytes:
    raw = s.encode("utf-8")
    return struct.pack(">i", len(raw)) + raw


def u64(v: int) -> bytes:
    return struct.pack(">Q", v)


def i32(v: int) -> bytes:
    return struct.pack(">i", v)


class Connection:
    def __init__(self, sock: socket.socket) -> None:
        self.sock = sock
        self.next_id = 1
        self.events: collections.deque[tuple[int, list[dict]]] = collections.deque()

    def handshake(self) -> None:
        self.sock.sendall(HANDSHAKE)
        got = self.read_exactly(len(HANDSHAKE))
        if got != HANDSHAKE:
            raise JdwpError(-1, f"bad handshake {got!r}")

    def read_exactly(self, n: int) -> bytes:
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise EOFError("connection closed")
            out += chunk
        return out

    def send_raw(self, ident: int, flags: int, set_: int, cmd: int, data: bytes = b"") -> None:
        self.sock.sendall(struct.pack(">IIBBB", HEADER + len(data), ident, flags, set_, cmd) + data)

    def packet(self, timeout: float) -> tuple[int, int, int, int, bytes]:
        self.sock.settimeout(timeout)
        length, ident, flags, a, b = struct.unpack(">IIBBB", self.read_exactly(HEADER))
        return ident, flags, a, b, self.read_exactly(length - HEADER)

    def command(self, set_: int, cmd: int, data: bytes = b"", timeout: float = 30) -> Reader:
        """Sends a command and returns its reply, queueing any events seen first."""
        ident = self.next_id
        self.next_id += 1
        self.send_raw(ident, 0, set_, cmd, data)
        return self.reply_to(ident, timeout)

    def reply_to(self, ident: int, timeout: float) -> Reader:
        while True:
            got, flags, a, b, body = self.packet(timeout)
            if flags & REPLY:
                if got != ident:
                    raise JdwpError(-1, f"reply {got} while waiting for {ident}")
                code = (a << 8) | b
                if code:
                    raise JdwpError(code)
                return Reader(body)
            self.queue_event(body)

    def queue_event(self, body: bytes) -> None:
        r = Reader(body)
        policy = r.u8()
        events = []
        for _ in range(r.i32()):
            kind, request = r.u8(), r.i32()
            ev = {"kind": kind, "request": request}
            if kind in (SINGLE_STEP, BREAKPOINT):
                ev["thread"], ev["loc"] = r.id(), r.location()
            elif kind == CLASS_PREPARE:
                ev["thread"], _tag = r.id(), r.u8()
                ev["type"], ev["signature"], ev["status"] = r.id(), r.string(), r.i32()
            elif kind == VM_START:
                ev["thread"] = r.id()
            events.append(ev)
        self.events.append((policy, events))

    def next_event(self, timeout: float = 30) -> tuple[int, list[dict]]:
        """The next composite event: (suspend policy, events)."""
        deadline = time.monotonic() + timeout
        while not self.events:
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError("no event")
            got, flags, _a, _b, body = self.packet(left)
            if flags & REPLY:
                raise JdwpError(-1, f"stray reply {got}")
            self.queue_event(body)
        return self.events.popleft()
