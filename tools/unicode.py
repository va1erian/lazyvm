"""Generate crates/natives/data/unicode.bin from the oracle JVM (S5-08).

The tables are not downloaded: a small Java program runs on the Java 8 oracle
and dumps what `Character` and `String` answer for every code point, this tool
compresses the minimal set of properties into the file, then re-reads the file
with an independent decoder and compares every predicate for every code point
with the dump. The binary format is documented in crates/natives/src/unicode.rs.

    python tools/unicode.py              # dump, write, verify
    python tools/unicode.py --check      # re-verify the committed file only
"""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import golden  # noqa: E402
import javac  # noqa: E402
import unicode_codec as codec  # noqa: E402
import unicode_sigma  # noqa: E402
from common import BUILD, ROOT, die  # noqa: E402

WORK = BUILD / "unicode"
DUMP_SRC = ROOT / "tools" / "unicode_dump" / "Dump.java"
OUT = ROOT / "crates" / "natives" / "data" / "unicode.bin"
MAGIC = b"LZUC"
VERSION = 1
LIMIT = codec.LIMIT
DUMP_MAGIC = 0x4C5A4450
NSTATES = 14  # sigma automaton moves: 8 states before the sigma, 6 after it
RECORD = struct.Struct(">bbbiiiiI" + str(NSTATES) + "B")  # type dir digit36 numeric upper lower title preds, sigma

# Bits of the dumped predicate word.
PRED = ["white", "space", "mirrored", "ideo", "alpha", "letter", "digit", "letdig", "upper",
        "lower", "title", "jstart", "jpart", "ustart", "upart", "ignor", "iso", "defined"]
# Bits of the stored flags table: 1 mirrored, 2 ideographic, 4 alphabetic beyond letters and Nl,
# 8 lowercase beyond Ll, 16 uppercase beyond Lu, 32 Java identifier excluded although the
# category allows it.

# General categories as Character.getType numbers.
LU, LL, LT, LM, LO, MN, ME, MC, ND, NL, NO, ZS, ZL, ZP, CC, CF = 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
PC, SC = 23, 26
LETTERS = {LU, LL, LT, LM, LO}

class Dump:
    """What the oracle answered: per-code-point rows, the probe rows and the special mappings."""

    def __init__(self, path: Path):
        data = path.read_bytes()
        if struct.unpack_from(">i", data, 0)[0] != DUMP_MAGIC:
            die("bad dump magic")
        n = struct.unpack_from(">H", data, 4)[0]
        self.java_version = data[6:6 + n].decode()
        nb, na = struct.unpack_from(">ii", data, 6 + n)
        sigs = struct.unpack_from(f">{nb + na}i", data, 14 + n)
        pos = 14 + n + 4 * (nb + na)
        records = list(RECORD.iter_unpack(data[pos:pos + LIMIT * RECORD.size]))
        pos += LIMIT * RECORD.size
        self.rows = [r[:8] for r in records]
        self.sigma = unicode_sigma.Sigma([bytes(r[8:]) for r in records], nb, na,
                                         list(sigs[:nb]), list(sigs[nb:]))
        count = struct.unpack_from(">i", data, pos)[0]
        pos += 4
        self.probes = {}
        for _ in range(count):
            cp = struct.unpack_from(">i", data, pos)[0]
            self.probes[cp] = RECORD.unpack_from(data, pos + 4)[:8]
            pos += 4 + RECORD.size
        self.special_upper, pos = self._specials(data, pos)
        self.special_lower, pos = self._specials(data, pos)
        self.fuzz = []
        count = struct.unpack_from(">i", data, pos)[0]
        pos += 4
        for _ in range(count):
            n = struct.unpack_from(">i", data, pos)[0]
            vals = struct.unpack_from(f">{n + 1}i", data, pos + 4)
            self.fuzz.append((list(vals[:n]), vals[n]))
            pos += 4 + 4 * (n + 1)
        self.mismatches = struct.unpack_from(">i", data, pos)[0]

    @staticmethod
    def _specials(data: bytes, pos: int) -> tuple[list[tuple[int, list[int]]], int]:
        out = []
        count = struct.unpack_from(">i", data, pos)[0]
        pos += 4
        for _ in range(count):
            n = struct.unpack_from(">i", data, pos)[0]
            vals = struct.unpack_from(f">{n + 1}i", data, pos + 4)
            out.append((vals[0], list(vals[1:])))
            pos += 4 + 4 * (n + 1)
        return out, pos


def run_oracle() -> Dump:
    WORK.mkdir(parents=True, exist_ok=True)
    javac.compile([DUMP_SRC], WORK)
    out = WORK / "dump.bin"
    java = golden.oracle(False)
    result = subprocess.run([java, "-cp", str(WORK), "Dump", str(out)], capture_output=True, text=True)
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        die("the dump program failed on the oracle")
    dump = Dump(out)
    if dump.mismatches:
        die(f"the oracle contradicts an assumption ({dump.mismatches} mismatches, see above)")
    return dump


# ---- the derivations: every predicate as a function of the type, the code point and
# ---- the stored flags. crates/natives/src/char_props.rs mirrors these exactly.

def derive(t: int, cp: int, stored: int) -> dict[str, bool]:
    ignorable = (0 <= cp <= 8) or (0xE <= cp <= 0x1B) or (0x7F <= cp <= 0x9F) or t == CF
    letter = t in LETTERS
    ident_start = letter or t in (NL, SC, PC)
    part_extra = t in (ND, MN, MC) or ignorable
    return {
        "white": (t in (ZS, ZL, ZP) and cp not in (0xA0, 0x2007, 0x202F)) or 9 <= cp <= 13 or 0x1C <= cp <= 0x1F,
        "space": t in (ZS, ZL, ZP),
        "mirrored": bool(stored & 1),
        "ideo": bool(stored & 2),
        "alpha": letter or t == NL or bool(stored & 4),
        "letter": letter,
        "digit": t == ND,
        "letdig": letter or t == ND,
        "upper": t == LU or bool(stored & 16),
        "lower": t == LL or bool(stored & 8),
        "title": t == LT,
        "jstart": ident_start and not stored & 32,
        "jpart": (ident_start or part_extra) and not stored & 32,
        "ustart": letter or t == NL,
        "upart": letter or t in (NL, PC) or part_extra,
        "ignor": ignorable,
        "iso": cp <= 0x9F and (cp >= 0x7F or cp >> 5 == 0),
        "defined": t != 0,
    }


def stored_flags(row: tuple, cp: int) -> int:
    """The stored bits: what the oracle says beyond what the category already implies."""
    preds = row[7]
    bit = lambda name: bool(preds >> PRED.index(name) & 1)  # noqa: E731
    base = derive(row[0], cp, 0)
    return ((1 if bit("mirrored") else 0) | (2 if bit("ideo") else 0)
            | (4 if bit("alpha") and not base["alpha"] else 0)
            | (8 if bit("lower") and not base["lower"] else 0)
            | (16 if bit("upper") and not base["upper"] else 0)
            | (32 if base["jstart"] and not bit("jstart") else 0))


# ---- encoding

def encode(dump: Dump) -> bytes:
    rows = dump.rows
    flags = [stored_flags(r, cp) for cp, r in enumerate(rows)]

    def case(col: int) -> list[tuple[int, int]]:  # stored relative to the code point
        return [(cp, r[col] - cp) for cp, r in enumerate(rows) if r[col] != cp]

    def absolute(col: int, default: int) -> list[tuple[int, int]]:
        return [(cp, r[col]) for cp, r in enumerate(rows) if r[col] != default]

    body = (MAGIC + struct.pack("<H", VERSION)
            + codec.runs([r[0] for r in rows]) + codec.runs([r[1] + 1 for r in rows])
            + codec.runs(flags) + dump.sigma.encode()
            + codec.sparse(case(4)) + codec.sparse(case(5)) + codec.sparse(case(6))
            + codec.sparse(absolute(2, -1)) + codec.sparse(absolute(3, -1))
            + codec.multi(dump.special_upper) + codec.multi(dump.special_lower))
    return body + struct.pack("<I", codec.fnv32(body))


# ---- independent decoder and exhaustive comparison

def verify(data: bytes, dump: Dump) -> None:
    assert data[:4] == MAGIC and struct.unpack_from("<H", data, 4)[0] == VERSION
    assert struct.unpack_from("<I", data, len(data) - 4)[0] == codec.fnv32(data[:-4]), "checksum"
    r = codec.Reader(data[:-4], 6)
    types, dirs, flags = codec.decode_runs(r), codec.decode_runs(r), codec.decode_runs(r)
    sigma, classes = unicode_sigma.decode(r)
    upper, lower, title = (codec.decode_sparse(r, True) for _ in range(3))
    digit, numeric = codec.decode_sparse(r, False), codec.decode_sparse(r, False)
    special_upper, special_lower = codec.decode_multi(r), codec.decode_multi(r)
    assert r.pos == len(data) - 4, "trailing bytes"
    bad = 0
    tally: dict[str, int] = {}

    def fail(cp: int, what: str) -> None:
        nonlocal bad
        bad += 1
        tally[what] = tally.get(what, 0) + 1
        if tally[what] <= 3:
            print(f"verify: U+{cp:04X} {what} differs")

    for cp, row in enumerate(dump.rows):
        t, d, dig, num, up, lo, ti, preds = row
        if types[cp] != t: fail(cp, "type")
        if dirs[cp] - 1 != d: fail(cp, "directionality")
        if digit.get(cp, -1) != dig: fail(cp, "digit")
        if numeric.get(cp, -1) != num: fail(cp, "numeric")
        if upper.get(cp, cp) != up: fail(cp, "upper")
        if lower.get(cp, cp) != lo: fail(cp, "lower")
        if title.get(cp, cp) != ti: fail(cp, "title")
        for name, value in derive(types[cp], cp, flags[cp]).items():
            if value != bool(preds >> PRED.index(name) & 1):
                fail(cp, name)
    for cp, row in dump.probes.items():
        t, d, dig, num, up, lo, ti, preds = row
        if (t, d, dig, num, up, lo, ti) != (0, -1, -1, -1, cp, cp, cp):
            fail(cp, "probe defaults")
        for name, value in derive(0, cp, 0).items():
            if value != bool(preds >> PRED.index(name) & 1):
                fail(cp, "probe " + name)
    if (sigma.table, sigma.per_cp, sigma.before_accept, sigma.after_final, sigma.after_terminal) != (
            dump.sigma.table, dump.sigma.per_cp, dump.sigma.before_accept, dump.sigma.after_final,
            dump.sigma.after_terminal):
        fail(0, "sigma tables")
    for cps, mask in dump.fuzz:
        for i, cp in enumerate(cps):
            if cp == unicode_sigma.SIGMA and unicode_sigma.is_final(sigma, cps, i) != bool(mask >> i & 1):
                fail(cp, "final sigma in " + " ".join(f"{c:x}" for c in cps))
    if special_upper != dump.special_upper: fail(0, "special upper")
    if special_lower != dump.special_lower: fail(0, "special lower")
    if bad:
        die(f"{bad} differences between the file and the oracle: {tally}")
    print(f"verified {LIMIT} code points x {len(PRED) + 7} properties, {len(dump.probes)} probes, "
          f"{len(special_upper)}+{len(special_lower)} special mappings, {len(sigma.table)} sigma classes, "
          f"{len(dump.fuzz)} sigma fuzz strings")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="verify the committed file only")
    args = parser.parse_args()
    dump = run_oracle()
    if args.check:
        data = OUT.read_bytes()
    else:
        data = encode(dump)
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_bytes(data)
        print(f"wrote {OUT.relative_to(ROOT)}: {len(data)} bytes")
    verify(data, dump)


if __name__ == "__main__":
    main()
