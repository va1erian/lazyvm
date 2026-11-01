"""Differential test of the interpreter's tight loop on malformed bytecode (S11-05).

`interp/fast.rs` must hand every anomaly to the full path untouched, so malformed
code gives the error `-Xtrace:interp` (which turns the tight loop off) gives, and
never a panic. This builds a class of hand-assembled bad methods, runs each twice
(the second run meets resolved constants) with and without the trace, and requires
identical output, every case a `VerifyError`, and no panic.

    python tools/fastloop_test.py [--exe target/release/lazyvm.exe]
"""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import lazyrt  # noqa: E402
from common import BUILD, DIST, LAZYRT_CLASSES, ROOT, cargo_build_launcher, die  # noqa: E402

OUT = BUILD / "fastloop"


class Pool:
    def __init__(self) -> None:
        self.items: list[bytes] = []
        self.cache: dict[tuple, int] = {}

    def add(self, key: tuple, data: bytes) -> int:
        if key not in self.cache:
            self.items.append(data)
            self.cache[key] = len(self.items)
        return self.cache[key]

    def utf8(self, s: str) -> int:
        raw = s.encode()
        return self.add(("u", s), b"\x01" + struct.pack(">H", len(raw)) + raw)

    def klass(self, name: str) -> int:
        return self.add(("c", name), b"\x07" + struct.pack(">H", self.utf8(name)))

    def name_and_type(self, name: str, desc: str) -> int:
        return self.add(("n", name, desc),
                        b"\x0c" + struct.pack(">HH", self.utf8(name), self.utf8(desc)))

    def field(self, owner: str, name: str, desc: str) -> int:
        return self.add(("f", owner, name, desc), b"\x09" + struct.pack(
            ">HH", self.klass(owner), self.name_and_type(name, desc)))


def getstatic(index: int) -> bytes:
    return bytes([0xB2]) + struct.pack(">H", index)


def cases(f: int, g: int) -> list[tuple[str, int, int, bytes]]:
    """(name, max_stack, max_locals, code); every method takes one int, local 0."""
    gs, ps = getstatic(f), bytes([0xB3]) + struct.pack(">H", f)
    gf, pf = bytes([0xB4]) + struct.pack(">H", g), bytes([0xB5]) + struct.pack(">H", g)
    ret = b"\xb1"
    return [
        # Stack overflow at max_stack.
        ("overflow_bipush", 1, 1, bytes([0x10, 1, 0x10, 1]) + ret),
        ("overflow_sipush", 1, 1, bytes([0x11, 0, 1, 0x11, 0, 1]) + ret),
        ("overflow_iconst", 1, 1, bytes([0x04, 0x04]) + ret),
        ("overflow_aconst_null", 1, 1, bytes([0x01, 0x01]) + ret),
        ("overflow_iload", 1, 1, bytes([0x1A, 0x1A]) + ret),
        ("overflow_iload_n", 1, 1, bytes([0x15, 0, 0x15, 0]) + ret),
        ("overflow_aload", 1, 2, bytes([0x01, 0x4B, 0x2A, 0x2A]) + ret),
        ("overflow_dup", 1, 1, bytes([0x04, 0x59]) + ret),
        ("overflow_getstatic", 1, 1, bytes([0x04]) + gs + ret),
        ("overflow_zero_stack", 0, 1, bytes([0x04]) + ret),
        # Empty stack.
        ("empty_iadd", 2, 1, bytes([0x60]) + ret),
        ("empty_iadd_one", 2, 1, bytes([0x04, 0x60]) + ret),
        ("empty_pop", 1, 1, bytes([0x57]) + ret),
        ("empty_dup", 1, 1, bytes([0x59]) + ret),
        ("empty_ifeq", 1, 1, bytes([0x99, 0, 3]) + ret),
        ("empty_if_icmpeq", 2, 1, bytes([0x04, 0x9F, 0, 3]) + ret),
        ("empty_istore", 1, 1, bytes([0x3B]) + ret),
        ("empty_iaload", 2, 1, bytes([0x2E]) + ret),
        ("empty_arraylength", 1, 1, bytes([0xBE]) + ret),
        ("empty_putstatic", 1, 1, ps + ret),
        ("empty_getfield", 1, 1, gf + ret),
        ("empty_ineg", 1, 1, bytes([0x74]) + ret),
        # Operands of the wrong kind.
        ("kind_aload_of_int", 1, 1, bytes([0x2A]) + ret),
        ("kind_iload_of_ref", 1, 2, bytes([0x01, 0x4C, 0x1B]) + ret),
        ("kind_iload_of_top", 1, 2, bytes([0x1B]) + ret),
        ("kind_iadd_ref", 2, 1, bytes([0x01, 0x04, 0x60]) + ret),
        ("kind_iadd_ref_deep", 2, 1, bytes([0x04, 0x01, 0x60]) + ret),
        ("kind_ifeq_ref", 1, 1, bytes([0x01, 0x99, 0, 3]) + ret),
        ("kind_ifnull_int", 1, 1, bytes([0x04, 0xC6, 0, 3]) + ret),
        ("kind_if_acmp_int", 2, 1, bytes([0x04, 0x04, 0xA5, 0, 3]) + ret),
        ("kind_istore_ref", 1, 2, bytes([0x01, 0x3C]) + ret),
        ("kind_astore_int", 1, 2, bytes([0x04, 0x4C]) + ret),
        ("kind_iinc_ref", 1, 2, bytes([0x01, 0x4C, 0x84, 1, 1]) + ret),
        ("kind_iinc_top", 1, 2, bytes([0x84, 1, 1]) + ret),
        ("kind_arraylength_int", 1, 1, bytes([0x04, 0xBE]) + ret),
        ("kind_iaload_int_array", 2, 1, bytes([0x04, 0x04, 0x2E]) + ret),
        ("kind_iaload_ref_index", 2, 1, bytes([0x01, 0x01, 0x2E]) + ret),
        ("kind_dup_top", 2, 2, bytes([0x84, 1, 1, 0x1B, 0x59]) + ret),
        ("kind_getfield_int", 1, 1, bytes([0x04]) + gf + ret),
        ("kind_putstatic_ref", 1, 1, bytes([0x01]) + ps + ret),
        ("kind_putfield_int_obj", 2, 1, bytes([0x04, 0x04]) + pf + ret),
        ("kind_getfield_null", 1, 1, bytes([0x01]) + gf + ret),
        # Operands cut off by the end of the code.
        ("cut_bipush", 1, 1, bytes([0x10])),
        ("cut_sipush", 1, 1, bytes([0x11, 0])),
        ("cut_iload", 1, 1, bytes([0x15])),
        ("cut_istore", 1, 1, bytes([0x04, 0x36])),
        ("cut_iinc", 1, 1, bytes([0x84, 0])),
        ("cut_goto", 1, 1, bytes([0xA7, 0])),
        ("cut_ifeq", 1, 1, bytes([0x04, 0x99, 0])),
        ("cut_if_icmpne", 2, 1, bytes([0x04, 0x04, 0xA0])),
        ("cut_getstatic", 1, 1, bytes([0xB2, 0])),
        ("cut_putfield", 2, 1, bytes([0xB5])),
        ("cut_getfield", 1, 1, bytes([0x01, 0xB4, 0])),
        ("no_return", 1, 1, bytes([0x04])),
        ("empty_code_path", 1, 1, bytes([0x00, 0x00])),
        # Branches that leave the code.
        ("branch_goto_far", 1, 1, bytes([0xA7, 0x00, 0x64]) + ret),
        ("branch_goto_back", 1, 1, bytes([0xA7, 0xFF, 0x9C]) + ret),
        ("branch_goto_end", 1, 1, bytes([0xA7, 0x00, 0x04])),
        ("branch_ifeq_far", 1, 1, bytes([0x03, 0x99, 0x7F, 0xFF]) + ret),
        ("branch_ifeq_back", 1, 1, bytes([0x03, 0x99, 0x80, 0x00]) + ret),
        ("branch_ifne_far", 1, 1, bytes([0x04, 0x9A, 0x7F, 0xFF]) + ret),
        ("branch_if_icmplt_far", 2, 1, bytes([0x03, 0x04, 0xA1, 0x40, 0x00]) + ret),
        ("branch_ifnull_far", 1, 1, bytes([0x01, 0xC6, 0x00, 0x7F]) + ret),
        ("branch_if_acmpeq_back", 2, 1, bytes([0x01, 0x01, 0xA5, 0xFF, 0x00]) + ret),
        # Local variable indexes past max_locals.
        ("local_iload", 1, 2, bytes([0x15, 200]) + ret),
        ("local_iload_n", 1, 2, bytes([0x1D]) + ret),
        ("local_aload_n", 1, 2, bytes([0x2D]) + ret),
        ("local_istore", 1, 2, bytes([0x04, 0x36, 200]) + ret),
        ("local_istore_n", 1, 2, bytes([0x04, 0x3E]) + ret),
        ("local_astore_n", 1, 2, bytes([0x01, 0x4E]) + ret),
        ("local_iinc", 1, 2, bytes([0x84, 200, 1]) + ret),
        ("local_iinc_edge", 1, 2, bytes([0x84, 2, 1]) + ret),
        # Not malformed: a taken backward branch and an unresolved static, as controls.
        ("control_loop", 2, 2, bytes([0x03, 0x3C, 0x84, 1, 1, 0x1B, 0x10, 50, 0xA1, 0xFF, 0xFA]) + ret),
    ]


def method_info(pool: Pool, name: str, stack: int, locals_: int, code: bytes) -> bytes:
    body = struct.pack(">HHI", stack, locals_, len(code)) + code + struct.pack(">HH", 0, 0)
    attr = struct.pack(">HI", pool.utf8("Code"), len(body)) + body
    return struct.pack(">HHHH", 0x0009, pool.utf8(name), pool.utf8("(I)V"), 1) + attr


def crafted_class() -> tuple[bytes, list[str]]:
    pool = Pool()
    this, sup = pool.klass("Crafted"), pool.klass("java/lang/Object")
    field = pool.field("Crafted", "f", "I")
    inst = pool.field("Crafted", "g", "I")
    field_info = struct.pack(">HHHH", 0x0009, pool.utf8("f"), pool.utf8("I"), 0)
    field_info += struct.pack(">HHHH", 0x0001, pool.utf8("g"), pool.utf8("I"), 0)
    methods = [method_info(pool, *c) for c in cases(field, inst)]
    names = [c[0] for c in cases(field, inst)]
    head = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(pool.items) + 1)
    tail = struct.pack(">HHHHH", 0x0021, this, sup, 0, 2) + field_info
    tail += struct.pack(">H", len(methods)) + b"".join(methods) + struct.pack(">H", 0)
    return head + b"".join(pool.items) + tail, names


def build_driver(names: list[str]) -> None:
    """javac needs the methods to exist: compile against a stub Crafted, then replace its class
    file with the assembled one. Direct calls keep the traced run short (reflection traces long)."""
    calls = "".join(f"""        attempt("{n}", 1, () -> Crafted.{n}(5));
        attempt("{n}", 2, () -> Crafted.{n}(5));
""" for n in names)
    stubs = "".join(f"    public static void {n}(int x) {{}}\n" for n in names)
    src = OUT / "src"
    src.mkdir(parents=True, exist_ok=True)
    (src / "Crafted.java").write_text(f"public class Crafted {{\n{stubs}}}\n", encoding="utf-8")
    (src / "Driver.java").write_text(f"""public class Driver {{
    static void attempt(String name, int round, Runnable r) {{
        String result;
        try {{
            r.run();
            result = "returned";
        }} catch (Throwable t) {{
            result = t.getClass().getName() + ": " + t.getMessage();
        }}
        System.out.println(name + " #" + round + " " + result);
    }}

    public static void main(String[] args) {{
{calls}    }}
}}
""", encoding="utf-8")
    javac.compile([src / "Crafted.java", src / "Driver.java"], OUT, bootclasspath=LAZYRT_CLASSES)


def run(exe: Path, extra: list[str], stderr: int) -> subprocess.CompletedProcess:
    cmd = [str(exe), f"-Xbootclasspath:{DIST / 'lazyrt.jar'}", *extra, "-cp", str(OUT), "Driver"]
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=stderr, timeout=900)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--exe", type=Path)
    args = parser.parse_args()
    lazyrt.build()
    OUT.mkdir(parents=True, exist_ok=True)
    data, names = crafted_class()
    build_driver(names)
    (OUT / "Crafted.class").write_bytes(data)
    exe = args.exe or cargo_build_launcher()
    # The trace is far too long to keep; the exit status still shows a panic there.
    fast = run(exe, [], subprocess.PIPE)
    full = run(exe, ["-Xtrace:interp"], subprocess.DEVNULL)
    problems = []
    if b"panicked" in fast.stderr:
        problems.append("fast run panicked")
    for label, done in (("fast", fast), ("trace", full)):
        if done.returncode != 0:
            problems.append(f"{label} run exited {done.returncode}")
    a, b = fast.stdout.decode().splitlines(), full.stdout.decode().splitlines()
    if a != b:
        problems += [f"fast: {x!r}\n  trace: {y!r}" for x, y in zip(a, b) if x != y] or ["line counts differ"]
    # Every case is a VerifyError except a getfield on null, which is a plain NullPointerException.
    wanted = {"kind_getfield_null": "java.lang.NullPointerException", "control_loop": "returned"}
    for n in names:
        for i in (1, 2):
            line = next((x for x in a if x.startswith(f"{n} #{i} ")), "")
            if wanted.get(n, "java.lang.VerifyError") not in line:
                problems.append(f"{n} #{i}: unexpected result {line!r}")
    if problems:
        die("fast loop differs from the full path:\n  " + "\n  ".join(problems))
    print(f"fastloop: PASS ({len(names)} methods, identical to -Xtrace:interp)")


if __name__ == "__main__":
    main()
