"""A small assembler for the class files javac will not write: tests/verify/*/*.jasm.

A file holds one or more classes, the first the one that runs:

    class a/Sub super a/Base flags public super
    interfaces a/I
    version 52
    field protected x I
    method public static main ([Ljava/lang/String;)V stack 2 locals 1
      iconst_0
      ifeq L1
      nop
    L1:
      frame L1 locals [Ljava/lang/String; stack
      return
      handler L0 L1 L2 java/lang/Exception     ; `any` catches everything
    end

Lines starting with `;` are comments; `; expect:` and `; reason:` are read by
tools/verify_test.py. `flags` takes names or one hex number. A stack map is one
`full_frame` per `frame` line, whose types are int float long double null top
uninitThis, uninit:<label>, or a class name or array descriptor. `rawframes <hex>`
replaces the attribute's bytes, and `bytes <hex>` puts raw bytes in the code.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field

SIMPLE = """nop aconst_null iconst_m1 iconst_0 iconst_1 iconst_2 iconst_3 iconst_4 iconst_5 lconst_0
lconst_1 fconst_0 fconst_1 fconst_2 dconst_0 dconst_1""".split()
OPCODES = {name: i for i, name in enumerate(SIMPLE)}
_NAMED = {
    0x1a: "iload_0 iload_1 iload_2 iload_3 lload_0 lload_1 lload_2 lload_3 fload_0 fload_1 fload_2 fload_3 "
          "dload_0 dload_1 dload_2 dload_3 aload_0 aload_1 aload_2 aload_3 iaload laload faload daload aaload "
          "baload caload saload",
    0x3b: "istore_0 istore_1 istore_2 istore_3 lstore_0 lstore_1 lstore_2 lstore_3 fstore_0 fstore_1 fstore_2 "
          "fstore_3 dstore_0 dstore_1 dstore_2 dstore_3 astore_0 astore_1 astore_2 astore_3 iastore lastore "
          "fastore dastore aastore bastore castore sastore pop pop2 dup dup_x1 dup_x2 dup2 dup2_x1 dup2_x2 swap "
          "iadd ladd fadd dadd isub lsub fsub dsub imul lmul fmul dmul idiv ldiv fdiv ddiv irem lrem frem drem "
          "ineg lneg fneg dneg ishl lshl ishr lshr iushr lushr iand land ior lor ixor lxor",
    0x85: "i2l i2f i2d l2i l2f l2d f2i f2l f2d d2i d2l d2f i2b i2c i2s lcmp fcmpl fcmpg dcmpl dcmpg",
    0xac: "ireturn lreturn freturn dreturn areturn return",
    0xbe: "arraylength athrow",
    0xc2: "monitorenter monitorexit",
}
for _start, _names in _NAMED.items():
    OPCODES.update({name: _start + i for i, name in enumerate(_names.split())})
LOCALS = {"iload": 0x15, "lload": 0x16, "fload": 0x17, "dload": 0x18, "aload": 0x19,
          "istore": 0x36, "lstore": 0x37, "fstore": 0x38, "dstore": 0x39, "astore": 0x3a}
BRANCHES = {name: 0x99 + i for i, name in enumerate(
    "ifeq ifne iflt ifge ifgt ifle if_icmpeq if_icmpne if_icmplt if_icmpge if_icmpgt if_icmple if_acmpeq if_acmpne "
    "goto jsr".split())}
BRANCHES.update({"ifnull": 0xc6, "ifnonnull": 0xc7})
FIELDS = {"getstatic": 0xb2, "putstatic": 0xb3, "getfield": 0xb4, "putfield": 0xb5}
INVOKES = {"invokevirtual": 0xb6, "invokespecial": 0xb7, "invokestatic": 0xb8, "invokeinterface": 0xb9}
CLASS_OPS = {"new": 0xbb, "anewarray": 0xbd, "checkcast": 0xc0, "instanceof": 0xc1}
ATYPES = {"boolean": 4, "char": 5, "float": 6, "double": 7, "byte": 8, "short": 9, "int": 10, "long": 11}
FLAGS = {"public": 1, "private": 2, "protected": 4, "static": 8, "final": 0x10, "super": 0x20, "synchronized": 0x20,
         "volatile": 0x40, "bridge": 0x40, "transient": 0x80, "varargs": 0x80, "native": 0x100, "interface": 0x200,
         "abstract": 0x400, "strict": 0x800, "synthetic": 0x1000, "annotation": 0x2000, "enum": 0x4000}
SIMPLE_TYPES = {"top": 0, "int": 1, "float": 2, "double": 3, "long": 4, "null": 5, "uninitThis": 6}


class AsmError(Exception):
    pass


class Pool:
    def __init__(self) -> None:
        self.entries: list[bytes] = []
        self.index: dict[bytes, int] = {}
        self.count = 1

    def add(self, entry: bytes, wide: bool = False) -> int:
        if entry in self.index:
            return self.index[entry]
        at = self.count
        self.index[entry] = at
        self.entries.append(entry)
        self.count += 2 if wide else 1
        return at

    def utf8(self, s: str) -> int:
        data = s.encode("utf-8")
        return self.add(b"\x01" + struct.pack(">H", len(data)) + data)

    def cls(self, name: str) -> int:
        return self.add(b"\x07" + struct.pack(">H", self.utf8(name)))

    def string(self, s: str) -> int:
        return self.add(b"\x08" + struct.pack(">H", self.utf8(s)))

    def integer(self, v: int) -> int:
        return self.add(b"\x03" + struct.pack(">i", v))

    def long(self, v: int) -> int:
        return self.add(b"\x05" + struct.pack(">q", v), wide=True)

    def name_type(self, name: str, desc: str) -> int:
        return self.add(b"\x0c" + struct.pack(">HH", self.utf8(name), self.utf8(desc)))

    def member(self, tag: int, cls: str, name: str, desc: str) -> int:
        return self.add(bytes([tag]) + struct.pack(">HH", self.cls(cls), self.name_type(name, desc)))

    def write(self) -> bytes:
        return struct.pack(">H", self.count) + b"".join(self.entries)


@dataclass
class Method:
    flags: int
    name: str
    desc: str
    stack: int
    locals: int
    lines: list[list[str]] = field(default_factory=list)


@dataclass
class Class:
    name: str
    super_name: str = "java/lang/Object"
    flags: int = 0x21
    version: int = 52
    interfaces: list[str] = field(default_factory=list)
    fields: list[tuple[int, str, str]] = field(default_factory=list)
    methods: list[Method] = field(default_factory=list)


def parse_flags(tokens: list[str]) -> int:
    out = 0
    for t in tokens:
        out |= int(t, 16) if t.startswith("0x") else FLAGS[t]
    return out


def parse(text: str) -> list[Class]:
    classes: list[Class] = []
    method: Method | None = None
    for raw in text.splitlines():
        tokens = raw.split(";;")[0].strip().split()
        if not tokens or raw.lstrip().startswith(";"):
            continue
        head = tokens[0]
        if method is not None:
            if head == "end":
                method = None
            else:
                method.lines.append(tokens)
        elif head == "class":
            classes.append(class_header(tokens))
        elif head == "interfaces":
            classes[-1].interfaces = tokens[1:]
        elif head == "version":
            classes[-1].version = int(tokens[1])
        elif head == "field":
            classes[-1].fields.append((parse_flags(tokens[1:-2]), tokens[-2], tokens[-1]))
        elif head == "method":
            method = method_header(tokens)
            classes[-1].methods.append(method)
        else:
            raise AsmError(f"unknown line: {raw}")
    return classes


def class_header(tokens: list[str]) -> Class:
    cls = Class(tokens[1])
    rest = tokens[2:]
    if "flags" in rest:
        at = rest.index("flags")
        cls.flags = parse_flags(rest[at + 1:])
        rest = rest[:at]
    if rest[:1] == ["super"]:
        cls.super_name = rest[1]
    return cls


def method_header(tokens: list[str]) -> Method:
    at = tokens.index("stack") if "stack" in tokens else len(tokens)
    head = tokens[1:at]
    stack = int(tokens[at + 1]) if at < len(tokens) else 0
    locals_ = int(tokens[at + 3]) if at + 3 < len(tokens) else 0
    return Method(parse_flags(head[:-2]), head[-2], head[-1], stack, locals_)


def switch_operands(op: str, args: list[str], pc: int, labels: dict[str, int]) -> bytes:
    pad = b"\0" * (3 - pc % 4)
    jump = lambda label: struct.pack(">i", labels[label] - pc)  # noqa: E731
    if op == "tableswitch":
        low = int(args[0])
        targets = args[1:-1]
        return pad + jump(args[-1]) + struct.pack(">ii", low, low + len(targets) - 1) + b"".join(map(jump, targets))
    pairs = [a.split(":") for a in args[:-1]]
    body = b"".join(struct.pack(">i", int(k)) + jump(v) for k, v in pairs)
    return pad + jump(args[-1]) + struct.pack(">i", len(pairs)) + body


def invoke_size(desc: str) -> int:
    """Argument slots of a method descriptor, plus the receiver."""
    size, at = 1, 1
    while desc[at] != ")":
        size += 2 if desc[at] in "JD" else 1
        while desc[at] == "[":
            at += 1
            size_fix = desc[at] in "JD"
            if size_fix:
                size -= 1
        if desc[at] == "L":
            at = desc.index(";", at)
        at += 1
    return size


def instruction(tokens: list[str], pc: int, pool: Pool, labels: dict[str, int]) -> bytes:
    op, args = tokens[0], tokens[1:]
    if op == "bytes":
        return bytes.fromhex("".join(args))
    if op in OPCODES:
        return bytes([OPCODES[op]])
    if op in LOCALS:
        return bytes([LOCALS[op], int(args[0])])
    if op in BRANCHES:
        return bytes([BRANCHES[op]]) + struct.pack(">h", labels[args[0]] - pc)
    if op == "goto_w":
        return b"\xc8" + struct.pack(">i", labels[args[0]] - pc)
    if op in FIELDS:
        return bytes([FIELDS[op]]) + struct.pack(">H", pool.member(9, args[0], args[1], args[2]))
    if op in INVOKES:
        tag = 11 if op == "invokeinterface" or args[0] == "interface" else 10
        args = args[1:] if args[0] == "interface" else args
        out = bytes([INVOKES[op]]) + struct.pack(">H", pool.member(tag, args[0], args[1], args[2]))
        return out + bytes([invoke_size(args[2]), 0]) if op == "invokeinterface" else out
    if op in CLASS_OPS:
        return bytes([CLASS_OPS[op]]) + struct.pack(">H", pool.cls(args[0]))
    simple = {"bipush": lambda: b"\x10" + struct.pack(">b", int(args[0])),
              "sipush": lambda: b"\x11" + struct.pack(">h", int(args[0])),
              "iinc": lambda: bytes([0x84, int(args[0])]) + struct.pack(">b", int(args[1])),
              "newarray": lambda: bytes([0xbc, ATYPES[args[0]]]),
              "multianewarray": lambda: b"\xc5" + struct.pack(">H", pool.cls(args[0])) + bytes([int(args[1])]),
              "ldc": lambda: b"\x13" + struct.pack(">H", ldc_index(args, pool)),
              "ldc2_w": lambda: b"\x14" + struct.pack(">H", pool.long(int(args[0])))}
    if op in simple:
        return simple[op]()
    if op == "tableswitch":
        return b"\xaa" + switch_operands(op, args, pc, labels)
    if op == "lookupswitch":
        return b"\xab" + switch_operands(op, args, pc, labels)
    raise AsmError(f"unknown instruction {op}")


def ldc_index(args: list[str], pool: Pool) -> int:
    kind, value = args[0], " ".join(args[1:])
    if kind == "int":
        return pool.integer(int(value))
    if kind == "string":
        return pool.string(value)
    if kind == "class":
        return pool.cls(value)
    raise AsmError(f"ldc {kind}")


def code_lines(m: Method) -> tuple[list[list[str]], list[list[str]], list[list[str]], str | None]:
    """Splits a body into instructions and labels, frames, handlers, raw frames."""
    code, frames, handlers, raw = [], [], [], None
    for tokens in m.lines:
        if tokens[0] == "frame":
            frames.append(tokens[1:])
        elif tokens[0] == "handler":
            handlers.append(tokens[1:])
        elif tokens[0] == "rawframes":
            raw = "".join(tokens[1:])
        else:
            code.append(tokens)
    return code, frames, handlers, raw


def assemble_code(code: list[list[str]], pool: Pool) -> tuple[bytes, dict[str, int]]:
    """Two passes: the first finds labels with placeholder targets."""
    labels: dict[str, int] = {}
    for tokens in code:
        if tokens[0].endswith(":"):
            labels[tokens[0][:-1]] = 0
    for _ in range(2):
        out = b""
        for tokens in code:
            if tokens[0].endswith(":"):
                labels[tokens[0][:-1]] = len(out)
            else:
                out += instruction(tokens, len(out), pool, labels)
    return out, labels


def frame_types(tokens: list[str], pool: Pool, labels: dict[str, int]) -> bytes:
    out = struct.pack(">H", len(tokens))
    for t in tokens:
        if t in SIMPLE_TYPES:
            out += bytes([SIMPLE_TYPES[t]])
        elif t.startswith("uninit:"):
            out += b"\x08" + struct.pack(">H", labels[t[7:]])
        else:
            out += b"\x07" + struct.pack(">H", pool.cls(t))
    return out


def stack_map(frames: list[list[str]], pool: Pool, labels: dict[str, int]) -> bytes:
    """`frame <label> locals a b stack c`: one full_frame each, sorted by offset."""
    decoded = []
    for f in frames:
        at = f.index("stack")
        decoded.append((labels[f[0]], f[2:at], f[at + 1:]))
    decoded.sort(key=lambda d: d[0])
    out, last = struct.pack(">H", len(decoded)), -1
    for offset, locals_, stack in decoded:
        out += b"\xff" + struct.pack(">H", offset - last - 1)
        out += frame_types(locals_, pool, labels) + frame_types(stack, pool, labels)
        last = offset
    return out


def method_bytes(m: Method, pool: Pool) -> bytes:
    head = struct.pack(">HHH", m.flags, pool.utf8(m.name), pool.utf8(m.desc))
    if not m.lines:
        return head + struct.pack(">H", 0)
    code, frames, handlers, raw = code_lines(m)
    body, labels = assemble_code(code, pool)
    table = struct.pack(">H", len(handlers))
    for h in handlers:
        catch = 0 if h[3] == "any" else pool.cls(h[3])
        table += struct.pack(">HHHH", labels[h[0]], labels[h[1]], labels[h[2]], catch)
    attr = b""
    if raw is not None or frames:
        data = bytes.fromhex(raw) if raw is not None else stack_map(frames, pool, labels)
        attr = struct.pack(">HI", pool.utf8("StackMapTable"), len(data)) + data
    inner = struct.pack(">HHI", m.stack, m.locals, len(body)) + body + table
    inner += struct.pack(">H", 1 if attr else 0) + attr
    return head + struct.pack(">HHI", 1, pool.utf8("Code"), len(inner)) + inner


def class_bytes(c: Class) -> bytes:
    pool = Pool()
    this, sup = pool.cls(c.name), pool.cls(c.super_name)
    ifaces = [pool.cls(i) for i in c.interfaces]
    body = struct.pack(">HHH", c.flags, this, sup) + struct.pack(">H", len(ifaces))
    body += b"".join(struct.pack(">H", i) for i in ifaces)
    body += struct.pack(">H", len(c.fields))
    for flags, name, desc in c.fields:
        body += struct.pack(">HHHH", flags, pool.utf8(name), pool.utf8(desc), 0)
    body += struct.pack(">H", len(c.methods))
    for m in c.methods:
        body += method_bytes(m, pool)
    body += struct.pack(">H", 0)
    return b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, c.version) + pool.write() + body


def assemble(text: str) -> dict[str, bytes]:
    """Every class in `text`: its internal name and class file."""
    return {c.name: class_bytes(c) for c in parse(text)}


def main_class(text: str) -> str:
    return parse(text)[0].name.replace("/", ".")
