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
from common import BUILD, DIST, LAZYRT_CLASSES, cargo_build_launcher, die  # noqa: E402

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

    def method(self, owner: str, name: str, desc: str, interface: bool = False) -> int:
        tag = 11 if interface else 10
        return self.add(("m", tag, owner, name, desc), bytes([tag]) + struct.pack(
            ">HH", self.klass(owner), self.name_and_type(name, desc)))


# Methods the call cases invoke: (name, access, descriptor, max_stack, max_locals, code).
# A `new` or `invokestatic` operand of 0 is patched in by `helper_info`.
HELPERS = [
    ("h_int", 0x0009, "(I)I", 1, 1, bytes([0x1A, 0xAC])),
    ("h_none", 0x0009, "()I", 1, 0, bytes([0x04, 0xAC])),
    ("h_bad_ireturn", 0x0009, "(I)I", 1, 1, bytes([0x01, 0xAC])),
    ("h_bad_areturn", 0x0009, "(I)Ljava/lang/Object;", 1, 1, bytes([0x1A, 0xB0])),
    ("h_empty_ireturn", 0x0009, "(I)I", 1, 1, bytes([0xAC])),
    ("h_monitor", 0x0009, "(I)V", 2, 1, bytes([0xBB, 0, 0, 0x59, 0xC2, 0x57, 0xB1])),
    ("h_recurse", 0x0009, "(I)V", 1, 1, bytes([0x1A, 0xB8, 0, 0, 0xB1])),
    ("h_nine", 0x0009, "(IIIIIIIII)I", 1, 9, bytes([0x15, 8, 0xAC])),
    ("inst", 0x0001, "()I", 1, 1, bytes([0x10, 7, 0xAC])),
    ("run", 0x0001, "()V", 0, 1, bytes([0xB1])),
]


def getstatic(index: int) -> bytes:
    return bytes([0xB2]) + struct.pack(">H", index)


def call_cases(pool: Pool) -> list[tuple[str, int, int, bytes]]:
    """Invokes and returns, which the fast loop does itself when it can (fast_call.rs)."""
    def m(name: str, desc: str, owner: str = "Crafted", interface: bool = False) -> bytes:
        return struct.pack(">H", pool.method(owner, name, desc, interface))

    def st(name: str, desc: str) -> bytes:
        return bytes([0xB8]) + m(name, desc)

    new, ret = bytes([0xBB]) + struct.pack(">H", pool.klass("Crafted")), b"\xb1"
    run_ref = m("run", "()V", "java/lang/Runnable", True)
    pop = bytes([0x57])
    return [
        ("call_too_few_args", 1, 1, st("h_int", "(I)I") + pop + ret),
        ("call_static_of_instance", 2, 1, new + st("inst", "()I") + pop + ret),
        ("call_virtual_null", 1, 1, bytes([0x01, 0xB6]) + m("inst", "()I") + pop + ret),
        ("call_virtual_int_receiver", 1, 1, bytes([0x04, 0xB6]) + m("inst", "()I") + pop + ret),
        ("call_virtual_of_static", 1, 1, bytes([0x1A, 0xB6]) + m("h_int", "(I)I") + pop + ret),
        ("call_virtual_interface_ref", 1, 1, new + bytes([0xB6]) + run_ref + ret),
        ("call_interface_class_ref", 1, 1, new + bytes([0xB9]) + m("run", "()V") + bytes([1, 0]) + ret),
        ("call_interface_not_implemented", 1, 1,
         bytes([0x04, 0xBC, 10, 0xB9]) + run_ref + bytes([1, 0]) + ret),
        ("call_special_null", 1, 1, bytes([0x01, 0xB7]) + m("inst", "()I") + pop + ret),
        ("call_stack_overflow", 1, 1, bytes([0x1A]) + st("h_recurse", "(I)V") + ret),
        ("ret_wrong_kind", 1, 1, bytes([0x1A]) + st("h_bad_ireturn", "(I)I") + pop + ret),
        ("ret_ref_of_int", 1, 1, bytes([0x1A]) + st("h_bad_areturn", "(I)Ljava/lang/Object;") + pop + ret),
        ("ret_empty_stack", 1, 1, bytes([0x1A]) + st("h_empty_ireturn", "(I)I") + pop + ret),
        ("ret_into_full_stack", 1, 1, bytes([0x04]) + st("h_none", "()I") + pop + pop + ret),
        ("ret_holding_monitor", 1, 1, bytes([0x1A]) + st("h_monitor", "(I)V") + ret),
        ("control_call_static", 1, 1, bytes([0x1A]) + st("h_int", "(I)I") + pop + ret),
        ("control_call_virtual", 2, 1, new + bytes([0x59, 0xB6]) + m("inst", "()I") + pop + pop + ret),
        ("control_call_special", 1, 1, new + bytes([0xB7]) + m("inst", "()I") + pop + ret),
        ("control_call_interface", 1, 1, new + bytes([0xB9]) + run_ref + bytes([1, 0]) + ret),
        ("control_call_nine_args", 9, 1, bytes([0x1A] * 9) + st("h_nine", "(IIIIIIIII)I") + pop + ret),
    ]


def cases(f: int, g: int, c: int) -> list[tuple[str, int, int, bytes]]:
    """(name, max_stack, max_locals, code); every method takes one int, local 0."""
    gs, ps = getstatic(f), bytes([0xB3]) + struct.pack(">H", f)
    gf, pf = bytes([0xB4]) + struct.pack(">H", g), bytes([0xB5]) + struct.pack(">H", g)
    ret = b"\xb1"
    new = bytes([0xBB]) + struct.pack(">H", c)
    gf_static = bytes([0xB4]) + struct.pack(">H", f)  # getfield of a static fieldref
    gs_inst = bytes([0xB2]) + struct.pack(">H", g)  # getstatic of an instance fieldref
    ps_inst = bytes([0xB3]) + struct.pack(">H", g)
    pf_static = bytes([0xB5]) + struct.pack(">H", f)
    ints, bytes_, chars = bytes([0xBC, 10]), bytes([0xBC, 8]), bytes([0xBC, 5])
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
        # Real arrays and objects: elements of the wrong kind, indexes out of range, fields of the
        # wrong kind or the wrong staticness, and half of a wide local overwritten.
        ("array_load_byte_of_int", 2, 1, bytes([0x04]) + ints + bytes([0x03, 0x33]) + ret),
        ("array_load_char_of_int", 2, 1, bytes([0x04]) + ints + bytes([0x03, 0x34]) + ret),
        ("array_load_short_of_int", 2, 1, bytes([0x04]) + ints + bytes([0x03, 0x35]) + ret),
        ("array_load_int_of_byte", 2, 1, bytes([0x04]) + bytes_ + bytes([0x03, 0x2E]) + ret),
        ("array_load_int_of_char", 2, 1, bytes([0x04]) + chars + bytes([0x03, 0x2E]) + ret),
        ("array_load_ref_of_int", 2, 1, bytes([0x04]) + ints + bytes([0x03, 0x32]) + ret),
        ("array_store_char_into_int", 3, 1, bytes([0x04]) + ints + bytes([0x03, 0x04, 0x55]) + ret),
        ("array_store_short_into_int", 3, 1, bytes([0x04]) + ints + bytes([0x03, 0x04, 0x56]) + ret),
        ("array_store_int_into_char", 3, 1, bytes([0x04]) + chars + bytes([0x03, 0x04, 0x4F]) + ret),
        ("array_store_int_into_byte", 3, 1, bytes([0x04]) + bytes_ + bytes([0x03, 0x04, 0x4F]) + ret),
        ("array_store_ref_value", 3, 1, bytes([0x04]) + ints + bytes([0x03, 0x01, 0x4F]) + ret),
        ("array_load_oob", 2, 1, bytes([0x04]) + ints + bytes([0x04, 0x2E]) + ret),
        ("array_load_oob_far", 2, 1, bytes([0x04]) + ints + bytes([0x10, 100, 0x2E]) + ret),
        ("array_load_negative", 2, 1, bytes([0x04]) + ints + bytes([0x02, 0x2E]) + ret),
        ("array_store_oob", 3, 1, bytes([0x04]) + ints + bytes([0x04, 0x04, 0x4F]) + ret),
        ("array_store_negative", 3, 1, bytes([0x04]) + ints + bytes([0x02, 0x04, 0x4F]) + ret),
        ("array_length_of_object", 1, 1, new + bytes([0xBE]) + ret),
        ("array_load_of_object", 2, 1, new + bytes([0x03, 0x2E]) + ret),
        ("obj_putfield_ref_into_int", 2, 1, new + bytes([0x01]) + pf + ret),
        ("obj_putfield_int_into_int_array", 2, 1, bytes([0x04]) + ints + bytes([0x04]) + pf + ret),
        ("obj_getfield_of_array", 1, 1, bytes([0x04]) + ints + gf + ret),
        ("obj_getfield_static_ref", 1, 1, new + gf_static + ret),
        ("obj_putfield_static_ref", 2, 1, new + bytes([0x04]) + pf_static + ret),
        ("obj_getstatic_instance_ref", 1, 1, gs_inst + ret),
        ("obj_putstatic_instance_ref", 1, 1, bytes([0x04]) + ps_inst + ret),
        ("wide_istore_over_lstore", 2, 3, bytes([0x09, 0x40, 0x04, 0x3D, 0x1F]) + ret),
        ("wide_astore_over_lstore", 2, 3, bytes([0x09, 0x40, 0x01, 0x4E, 0x1F]) + ret),
        ("wide_iinc_over_lstore", 2, 3, bytes([0x09, 0x40, 0x84, 2, 1, 0x1F]) + ret),
        # Not malformed: controls that must run.
        ("control_array_roundtrip", 4, 1, bytes([0x04]) + ints + bytes([0x59, 0x03, 0x10, 7, 0x4F, 0x03, 0x2E, 0x57]) + ret),
        ("control_object_roundtrip", 3, 1, new + bytes([0x59, 0x10, 5]) + pf + gf + bytes([0x57]) + ret),
        ("control_wide_local", 2, 3, bytes([0x09, 0x40, 0x04, 0x3B, 0x1F, 0x58]) + ret),
        ("control_static_roundtrip", 2, 1, bytes([0x10, 9]) + ps + gs + bytes([0x57]) + ret),
        ("control_loop", 2, 2, bytes([0x03, 0x3C, 0x84, 1, 1, 0x1B, 0x10, 50, 0xA1, 0xFF, 0xFA]) + ret),
    ]


def method_info(pool: Pool, name: str, stack: int, locals_: int, code: bytes,
                access: int = 0x0009, desc: str = "(I)V") -> bytes:
    body = struct.pack(">HHI", stack, locals_, len(code)) + code + struct.pack(">HH", 0, 0)
    attr = struct.pack(">HI", pool.utf8("Code"), len(body)) + body
    return struct.pack(">HHHH", access, pool.utf8(name), pool.utf8(desc), 1) + attr


def helper_info(pool: Pool, name: str, access: int, desc: str, stack: int, locals_: int,
                code: bytes) -> bytes:
    if name == "h_monitor":
        code = code[:1] + struct.pack(">H", pool.klass("Crafted")) + code[3:]
    if name == "h_recurse":
        code = code[:2] + struct.pack(">H", pool.method("Crafted", "h_recurse", "(I)V")) + code[4:]
    return method_info(pool, name, stack, locals_, code, access, desc)


def crafted_class() -> tuple[bytes, list[str]]:
    pool = Pool()
    this, sup = pool.klass("Crafted"), pool.klass("java/lang/Object")
    field = pool.field("Crafted", "f", "I")
    inst = pool.field("Crafted", "g", "I")
    field_info = struct.pack(">HHHH", 0x0009, pool.utf8("f"), pool.utf8("I"), 0)
    field_info += struct.pack(">HHHH", 0x0001, pool.utf8("g"), pool.utf8("I"), 0)
    runnable = pool.klass("java/lang/Runnable")
    all_cases = cases(field, inst, this) + call_cases(pool)
    methods = [method_info(pool, *c) for c in all_cases]
    methods += [helper_info(pool, *h) for h in HELPERS]
    names = [c[0] for c in all_cases]
    head = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(pool.items) + 1)
    tail = struct.pack(">HHHHHH", 0x0021, this, sup, 1, runnable, 2) + field_info
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
    # -Xverify:none: these classes must reach the interpreter, as they can for any user who turns it off.
    cmd = [str(exe), f"-Xbootclasspath:{DIST / 'lazyrt.jar'}", "-Xverify:none", *extra, "-cp", str(OUT), "Driver"]
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
    # Every case is a VerifyError except those that throw a real Java exception, and the controls.
    aioobe, icce = "java.lang.ArrayIndexOutOfBoundsException", "java.lang.IncompatibleClassChangeError"
    npe = "java.lang.NullPointerException"
    wanted = {"kind_getfield_null": "java.lang.NullPointerException",
              "array_load_oob": aioobe, "array_load_oob_far": aioobe, "array_load_negative": aioobe,
              "array_store_oob": aioobe, "array_store_negative": aioobe,
              "obj_getfield_static_ref": icce, "obj_putfield_static_ref": icce,
              "obj_getstatic_instance_ref": icce, "obj_putstatic_instance_ref": icce,
              "call_static_of_instance": icce, "call_virtual_null": npe, "call_virtual_of_static": icce,
              "call_interface_not_implemented": icce, "call_special_null": npe,
              "call_stack_overflow": "java.lang.StackOverflowError",
              "ret_holding_monitor": "java.lang.IllegalMonitorStateException"}
    for n in names:
        for i in (1, 2):
            line = next((x for x in a if x.startswith(f"{n} #{i} ")), "")
            expect = "returned" if n.startswith("control_") else wanted.get(n, "java.lang.VerifyError")
            if expect not in line:
                problems.append(f"{n} #{i}: unexpected result {line!r}")
    if problems:
        die("fast loop differs from the full path:\n  " + "\n  ".join(problems))
    print(f"fastloop: PASS ({len(names)} methods, identical to -Xtrace:interp)")


if __name__ == "__main__":
    main()
