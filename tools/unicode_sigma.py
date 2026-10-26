"""The final-sigma automata of tools/unicode.py (format: crates/natives/src/unicode.rs).

The oracle decides whether a capital sigma lowercases to the final form from the text before it
and the text after it, and the answer depends on the neighbours, not on one label per character.
Both directions are small automata learned from the oracle: BEFORE is read left to right over the
text before the sigma and accepts when the sigma is preceded by a cased letter (skipping what the
oracle skips), AFTER is read over the text after it and is blocked by a cased letter. Each code
point has a class: where it moves each of the representative states of both automata.
"""

from __future__ import annotations

import unicode_codec as codec

SIGMA = 0x3A3


class Sigma:
    def __init__(self, columns: list[bytes], nb: int, na: int, bstate: list[int], astate: list[int]):
        self.nb, self.na = nb, na
        self.before_accept = sum(1 << i for i, sig in enumerate(bstate) if sig & 1)
        self.after_final = sum(1 << i for i, sig in enumerate(astate) if sig & 1)
        ids: dict[bytes, int] = {}
        self.per_cp = [ids.setdefault(cols, len(ids)) for cols in columns]
        self.table = list(ids)
        # A state is terminal when every class leaves it where it is.
        self.after_terminal = sum(
            1 << s for s in range(na) if all(c[nb + s] == s for c in self.table))

    def encode(self) -> bytes:
        out = bytearray()
        for v in (self.nb, self.na, self.before_accept, self.after_final, self.after_terminal,
                  len(self.table)):
            out += codec.varint(v)
        for cols in self.table:
            out += cols
        return bytes(out) + codec.runs(self.per_cp)


def decode(r: codec.Reader) -> tuple[Sigma, list[int]]:
    """The tables as a Sigma-like record (columns indexed by class) and the class of every cp."""
    nb, na = r.varint(), r.varint()
    sigma = Sigma.__new__(Sigma)
    sigma.nb, sigma.na = nb, na
    sigma.before_accept, sigma.after_final, sigma.after_terminal = r.varint(), r.varint(), r.varint()
    sigma.table = []
    for _ in range(r.varint()):
        sigma.table.append(bytes(r.data[r.pos:r.pos + nb + na]))
        r.pos += nb + na
    sigma.per_cp = codec.decode_runs(r)
    return sigma, sigma.per_cp


def is_final(s: Sigma, cps: list[int], at: int) -> bool:
    """Whether the sigma at index `at` of `cps` lowercases to the final form."""
    state = 0
    for j in range(at):
        state = s.table[s.per_cp[cps[j]]][state]
    if not s.before_accept >> state & 1:
        return False
    state = 0
    for cp in cps[at + 1:]:
        state = s.table[s.per_cp[cp]][s.nb + state]
        if s.after_terminal >> state & 1:
            break
    return bool(s.after_final >> state & 1)
