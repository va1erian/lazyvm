"""Byte-level helpers shared by tools/unicode.py: varints, run tables, sparse maps (format in
crates/natives/src/unicode.rs), each with its decoder."""

from __future__ import annotations

LIMIT = 0x110000


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def zigzag(n: int) -> bytes:
    return varint((n << 1) ^ (n >> 63))


def runs(values: list[int]) -> bytes:
    """Run-length table over every code point: count, then (length, value) pairs."""
    pairs: list[list[int]] = []
    for v in values:
        if pairs and pairs[-1][1] == v:
            pairs[-1][0] += 1
        else:
            pairs.append([1, v])
    return varint(len(pairs)) + b"".join(varint(n) + varint(v) for n, v in pairs)


def sparse_entries(items: list[tuple[int, int]]) -> list[tuple[int, int, int, int, int]]:
    """Greedy arithmetic runs (start, count, stride, first, step) over sorted (cp, value)."""
    out: list[list[int]] = []
    for cp, v in items:
        if out:
            start, count, stride, first, step = out[-1]
            if count == 1:
                out[-1] = [start, 2, cp - start, first, v - first]
                continue
            if cp == start + count * stride and v == first + count * step:
                out[-1][1] += 1
                continue
        out.append([cp, 1, 1, v, 0])
    return [tuple(e) for e in out]


def sparse(items: list[tuple[int, int]]) -> bytes:
    entries = sparse_entries(items)
    out = bytearray(varint(len(entries)))
    prev_end = 0
    for start, count, stride, first, step in entries:
        out += varint(start - prev_end) + varint(count - 1)
        if count > 1:
            out += varint(stride - 1)
        out += zigzag(first)
        if count > 1:
            out += zigzag(step)
        prev_end = start + (count - 1) * stride + 1
    return bytes(out)


def multi(entries: list[tuple[int, list[int]]]) -> bytes:
    """Special mappings: count, then (distance from the previous code point, n, n code points)."""
    out = bytearray(varint(len(entries)))
    prev = 0
    for cp, cps in entries:
        out += varint(cp - prev) + varint(len(cps)) + b"".join(varint(c) for c in cps)
        prev = cp
    return bytes(out)


def fnv32(data: bytes) -> int:
    h = 0x811C9DC5
    for b in data:
        h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    return h




class Reader:
    def __init__(self, data: bytes, pos: int = 0):
        self.data, self.pos = data, pos

    def varint(self) -> int:
        n = shift = 0
        while True:
            b = self.data[self.pos]
            self.pos += 1
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return n

    def zigzag(self) -> int:
        n = self.varint()
        return (n >> 1) ^ -(n & 1)


def decode_runs(r: Reader) -> list[int]:
    out: list[int] = []
    for _ in range(r.varint()):
        n = r.varint()
        out += [r.varint()] * n
    assert len(out) == LIMIT, "run table does not cover every code point"
    return out


def decode_sparse(r: Reader, relative: bool) -> dict[int, int]:
    out: dict[int, int] = {}
    prev_end = 0
    for _ in range(r.varint()):
        start = prev_end + r.varint()
        count = r.varint() + 1
        stride = r.varint() + 1 if count > 1 else 1
        first = r.zigzag()
        step = r.zigzag() if count > 1 else 0
        for i in range(count):
            cp = start + i * stride
            out[cp] = first + i * step + (cp if relative else 0)
        prev_end = start + (count - 1) * stride + 1
    return out


def decode_multi(r: Reader) -> list[tuple[int, list[int]]]:
    out, prev = [], 0
    for _ in range(r.varint()):
        cp = prev + r.varint()
        out.append((cp, [r.varint() for _ in range(r.varint())]))
        prev = cp
    return out
