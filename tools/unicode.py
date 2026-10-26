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
from common import BUILD, ROOT, die  # noqa: E402

WORK = BUILD / "unicode"
OUT = ROOT / "crates" / "natives" / "data" / "unicode.bin"
MAGIC = b"LZUC"
VERSION = 1
LIMIT = 0x110000
DUMP_MAGIC = 0x4C5A4450
RECORD = struct.Struct(">bbbiiiiI")  # type dir digit36 numeric upper lower title preds
PROBES = [-1, -0x80000000, 0x110000, 0x7FFFFFFF, 0x10FFFF + 0x100000]

# Bits of the dumped predicate word.
PRED = ["white", "space", "mirrored", "ideo", "alpha", "letter", "digit", "letdig", "upper",
        "lower", "title", "jstart", "jpart", "ustart", "upart", "ignor", "iso", "defined",
        "scased", "sskip"]
# Bits of the stored flags table: 1 mirrored, 2 ideographic, 4 alphabetic beyond letters and Nl,
# 8 lowercase beyond Ll, 16 uppercase beyond Lu, 32 Java identifier excluded although the
# category allows it, 64 counts as cased and 128 is skipped when String.toLowerCase looks for
# the context of a final sigma.

# General categories as Character.getType numbers.
LU, LL, LT, LM, LO, MN, ME, MC, ND, NL, NO, ZS, ZL, ZP, CC, CF = 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
PC, SC = 23, 26
LETTERS = {LU, LL, LT, LM, LO}

JAVA = r"""
import java.io.*;

public class Dump {
    static int bad;

    static void check(boolean ok, String what, int cp) {
        if (!ok) {
            bad++;
            if (bad <= 20) System.err.println("mismatch " + what + " U+" + Integer.toHexString(cp));
        }
    }

    static boolean fin(String s) {
        return s.toLowerCase().indexOf('ς') >= 0;
    }

    // The final-sigma context as the oracle applies it. Before a sigma a supplementary letter
    // is only recognised at the start of the string, so those are probed as the first character.
    static boolean[] sigma(int cp) {
        if (cp < 0 || cp > 0x10FFFF) return new boolean[] {false, false};
        String c = new String(Character.toChars(cp)), sigma = "Σ";
        boolean cased = cp > 0xFFFF ? fin(c + sigma) : fin(" " + c + sigma);
        boolean skip = cp > 0xFFFF ? fin("A" + sigma + c) && !fin("A" + sigma + c + "B")
                                   : fin("A" + c + sigma) && !cased;
        return new boolean[] {cased, skip};
    }

    static int preds(int cp) {
        boolean[] s = sigma(cp);
        boolean[] p = {
            Character.isWhitespace(cp), Character.isSpaceChar(cp), Character.isMirrored(cp),
            Character.isIdeographic(cp), Character.isAlphabetic(cp), Character.isLetter(cp),
            Character.isDigit(cp), Character.isLetterOrDigit(cp), Character.isUpperCase(cp),
            Character.isLowerCase(cp), Character.isTitleCase(cp),
            Character.isJavaIdentifierStart(cp), Character.isJavaIdentifierPart(cp),
            Character.isUnicodeIdentifierStart(cp), Character.isUnicodeIdentifierPart(cp),
            Character.isIdentifierIgnorable(cp), Character.isISOControl(cp), Character.isDefined(cp),
            s[0], s[1]};
        int w = 0;
        for (int i = 0; i < p.length; i++) if (p[i]) w |= 1 << i;
        return w;
    }

    static void record(DataOutputStream o, int cp) throws IOException {
        o.writeByte(Character.getType(cp));
        o.writeByte(Character.getDirectionality(cp));
        o.writeByte(Character.digit(cp, 36));
        o.writeInt(Character.getNumericValue(cp));
        o.writeInt(Character.toUpperCase(cp));
        o.writeInt(Character.toLowerCase(cp));
        o.writeInt(Character.toTitleCase(cp));
        o.writeInt(preds(cp));
    }

    // digit(cp, radix) must be the radix-36 value when it is below the radix; the char
    // overloads must agree with the int ones.
    static void consistency(int cp) {
        int d = Character.digit(cp, 36);
        for (int r = 2; r <= 36; r++) check(Character.digit(cp, r) == (d >= 0 && d < r ? d : -1), "digit", cp);
        for (int r : new int[] {-1, 0, 1, 37, 99}) check(Character.digit(cp, r) == -1, "badradix", cp);
        if (cp > 0xFFFF) return;
        char c = (char) cp;
        check(Character.getType(c) == Character.getType(cp), "getType", cp);
        check(Character.toUpperCase(c) == (char) Character.toUpperCase(cp), "upper", cp);
        check(Character.toLowerCase(c) == (char) Character.toLowerCase(cp), "lower", cp);
        check(Character.toTitleCase(c) == (char) Character.toTitleCase(cp), "title", cp);
        check(Character.getNumericValue(c) == Character.getNumericValue(cp), "numeric", cp);
        check(Character.getDirectionality(c) == Character.getDirectionality(cp), "dir", cp);
        boolean[] a = {Character.isLetter(c), Character.isDigit(c), Character.isWhitespace(c),
            Character.isUpperCase(c), Character.isLowerCase(c), Character.isTitleCase(c),
            Character.isSpaceChar(c), Character.isMirrored(c), Character.isDefined(c),
            Character.isJavaIdentifierStart(c), Character.isJavaIdentifierPart(c),
            Character.isUnicodeIdentifierStart(c), Character.isUnicodeIdentifierPart(c),
            Character.isIdentifierIgnorable(c), Character.isISOControl(c),
            Character.isLetterOrDigit(c)};
        boolean[] b = {Character.isLetter(cp), Character.isDigit(cp), Character.isWhitespace(cp),
            Character.isUpperCase(cp), Character.isLowerCase(cp), Character.isTitleCase(cp),
            Character.isSpaceChar(cp), Character.isMirrored(cp), Character.isDefined(cp),
            Character.isJavaIdentifierStart(cp), Character.isJavaIdentifierPart(cp),
            Character.isUnicodeIdentifierStart(cp), Character.isUnicodeIdentifierPart(cp),
            Character.isIdentifierIgnorable(cp), Character.isISOControl(cp),
            Character.isLetterOrDigit(cp)};
        for (int i = 0; i < a.length; i++) check(a[i] == b[i], "char overload " + i, cp);
    }

    // Characters whose whole-string case mapping differs from the simple one.
    static int specials(DataOutputStream o, boolean upper) throws IOException {
        java.util.List<int[]> found = new java.util.ArrayList<>();
        for (int cp = 0; cp < 0x110000; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            String s = new String(Character.toChars(cp));
            int[] full = (upper ? s.toUpperCase() : s.toLowerCase()).codePoints().toArray();
            int simple = upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
            if (full.length != 1 || full[0] != simple) {
                int[] e = new int[full.length + 1];
                e[0] = cp;
                System.arraycopy(full, 0, e, 1, full.length);
                found.add(e);
            }
        }
        o.writeInt(found.size());
        for (int[] e : found) {
            o.writeInt(e.length - 1);
            for (int v : e) o.writeInt(v);
        }
        return found.size();
    }

    public static void main(String[] args) throws Exception {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[0]), 1 << 20));
        o.writeInt(0x4C5A4450);
        o.writeUTF(System.getProperty("java.version"));
        for (int cp = 0; cp < 0x110000; cp++) {
            record(o, cp);
            consistency(cp);
        }
        int[] probes = {%PROBES%};
        o.writeInt(probes.length);
        for (int cp : probes) {
            o.writeInt(cp);
            record(o, cp);
            for (int r = 2; r <= 36; r++) check(Character.digit(cp, r) == -1, "probe digit", cp);
        }
        int nu = specials(o, true), nl = specials(o, false);
        o.writeInt(bad);
        o.close();
        System.out.println("java " + System.getProperty("java.version") + ", mismatches: " + bad
            + ", special mappings: upper " + nu + ", lower " + nl);
    }
}
"""


class Dump:
    """What the oracle answered: per-code-point rows, the probe rows and the special mappings."""

    def __init__(self, path: Path):
        data = path.read_bytes()
        if struct.unpack_from(">i", data, 0)[0] != DUMP_MAGIC:
            die("bad dump magic")
        n = struct.unpack_from(">H", data, 4)[0]
        self.java_version = data[6:6 + n].decode()
        pos = 6 + n
        self.rows = list(RECORD.iter_unpack(data[pos:pos + LIMIT * RECORD.size]))
        pos += LIMIT * RECORD.size
        count = struct.unpack_from(">i", data, pos)[0]
        pos += 4
        self.probes = {}
        for _ in range(count):
            cp = struct.unpack_from(">i", data, pos)[0]
            self.probes[cp] = RECORD.unpack_from(data, pos + 4)
            pos += 4 + RECORD.size
        self.special_upper, pos = self._specials(data, pos)
        self.special_lower, pos = self._specials(data, pos)
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
    src = WORK / "Dump.java"
    src.write_text(JAVA.replace("%PROBES%", ", ".join(str(p) for p in PROBES)), encoding="utf-8")
    javac.compile([src], WORK)
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
        "scased": bool(stored & 64),
        "sskip": bool(stored & 128),
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
            | (32 if base["jstart"] and not bit("jstart") else 0)
            | (64 if bit("scased") else 0) | (128 if bit("sskip") else 0))


# ---- encoding

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


def encode(dump: Dump) -> bytes:
    rows = dump.rows
    flags = [stored_flags(r, cp) for cp, r in enumerate(rows)]

    def case(col: int) -> list[tuple[int, int]]:  # stored relative to the code point
        return [(cp, r[col] - cp) for cp, r in enumerate(rows) if r[col] != cp]

    def absolute(col: int, default: int) -> list[tuple[int, int]]:
        return [(cp, r[col]) for cp, r in enumerate(rows) if r[col] != default]

    body = (MAGIC + struct.pack("<H", VERSION)
            + runs([r[0] for r in rows]) + runs([r[1] + 1 for r in rows]) + runs(flags)
            + sparse(case(4)) + sparse(case(5)) + sparse(case(6))
            + sparse(absolute(2, -1)) + sparse(absolute(3, -1))
            + multi(dump.special_upper) + multi(dump.special_lower))
    return body + struct.pack("<I", fnv32(body))


# ---- independent decoder and exhaustive comparison

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


def verify(data: bytes, dump: Dump) -> None:
    assert data[:4] == MAGIC and struct.unpack_from("<H", data, 4)[0] == VERSION
    assert struct.unpack_from("<I", data, len(data) - 4)[0] == fnv32(data[:-4]), "checksum"
    r = Reader(data[:-4], 6)
    types, dirs, flags = decode_runs(r), decode_runs(r), decode_runs(r)
    upper, lower, title = (decode_sparse(r, True) for _ in range(3))
    digit, numeric = decode_sparse(r, False), decode_sparse(r, False)
    special_upper, special_lower = decode_multi(r), decode_multi(r)
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
    if special_upper != dump.special_upper: fail(0, "special upper")
    if special_lower != dump.special_lower: fail(0, "special lower")
    if bad:
        die(f"{bad} differences between the file and the oracle: {tally}")
    print(f"verified {LIMIT} code points x {len(PRED) + 7} properties, {len(dump.probes)} probes, "
          f"{len(special_upper)}+{len(special_lower)} special mappings")


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
