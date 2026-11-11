"""JDWP smoke test (S11-04): a scripted debugger against `-agentlib:jdwp`.

Launches `tests/jdwp/JdwpTarget.java` with the agent, connects as a debugger and
walks the path IntelliJ takes: class prepare, a line breakpoint, frames, locals,
fields, a string and an array, step into, over and out, other threads standing
still while the VM is suspended, and a resume to exit. A second run feeds the
agent malformed packets (LazyVM only), and a third makes the VM connect out
(`server=n`, which is what an IDE's Debug button uses).

The client was checked against the Java 8 oracle: `--oracle` runs the same
walk on it (`LAZYVM_ORACLE_JAVA`), and the expected locations are the ones it
reports.

    python tools/jdwp_smoke.py [--exe <lazyvm>] [--oracle] [--no-build]
"""

from __future__ import annotations

import argparse
import random
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import jdwp_threads  # noqa: E402
import jdwplib as j  # noqa: E402
import lazyrt  # noqa: E402
from common import BUILD, DIST, LAZYRT_CLASSES, ROOT, cargo_build_launcher, die, find_tool  # noqa: E402
from jdwplib import Connection, JdwpError, i32, string, u64  # noqa: E402
from jdwpsession import DEPTH_INTO, DEPTH_OUT, DEPTH_OVER, STEP_MIN, Session, expect  # noqa: E402

SRC = ROOT / "tests" / "jdwp" / "JdwpTarget.java"
OUT = BUILD / "jdwp"
SIG = "LJdwpTarget;"
LINE_BY_MARK = {}

# Where each step lands, as (method, marker): the same on the oracle and on LazyVM.
EXPECTED_STEPS = [
    ("work", "call"),   # the breakpoint
    ("add", "add"),     # into
    ("add", "addret"),  # over
    ("work", "call"),   # out: back in the caller, after the call
    ("work", "loop"),   # over: the loop's increment
    ("work", "call"),   # over: the second pass
]


def marks() -> dict[str, int]:
    found = {}
    for number, text in enumerate(SRC.read_text(encoding="utf-8").splitlines(), 1):
        if "// @" in text:
            found[text.split("// @", 1)[1].strip()] = number
    return found


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Target:
    """The VM under test and what it printed."""

    def __init__(self, cmd: list[str]) -> None:
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.out = bytearray()
        self.reader = threading.Thread(target=self.drain, daemon=True)
        self.reader.start()

    def drain(self) -> None:
        assert self.proc.stdout is not None
        for chunk in iter(lambda: self.proc.stdout.read1(4096), b""):
            self.out += chunk

    def text(self) -> str:
        return self.out.decode("utf-8", "replace")

    def finish(self, timeout: float = 30) -> int:
        try:
            code = self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            die(f"the VM did not exit; it printed:\n{self.text()}")
        self.reader.join(5)
        return code

    def kill(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()


def check_locals_and_fields(s: Session, thread: int, cls: int) -> None:
    c = s.c
    r = c.command(j.THREAD, 7, u64(thread))
    expect(r.i32() == 2, "work and main are the two frames")
    r = c.command(j.THREAD, 6, u64(thread) + i32(0) + i32(-1))
    frames = [(r.id(), r.location()) for _ in range(r.i32())]
    expect(len(frames) == 2, "Frames lists two frames")
    expect(s.method_name(frames[0][1]) == "work" and s.method_name(frames[1][1]) == "main", "frame order")
    work = s.methods(cls)["work"][0]
    names = s.variables(cls, work)
    expect({"this", "n", "total", "i"} <= set(names), f"variable table {names}")
    slots = [names["n"], names["total"], names["i"]]
    req = u64(thread) + u64(frames[0][0]) + i32(3) + b"".join(i32(x) + bytes([ord("I")]) for x in slots)
    r = c.command(j.FRAME, 1, req)
    r.i32()
    got = [r.value() for _ in range(3)]
    expect(got == [2, 0, 0], f"locals n, total, i are {got}")
    r = c.command(j.FRAME, 3, u64(thread) + u64(frames[0][0]))
    this = r.value()
    expect(this[0] == "obj" and this[2] != 0, "this is an object")
    ids = s.fields(cls)
    order = ["field", "label", "data", "big", "flag", "ratio"]
    r = c.command(j.OBJREF, 2, u64(this[2]) + i32(len(order)) + b"".join(u64(ids[n]) for n in order))
    expect(r.i32() == len(order), "GetValues count")
    vals = [r.value() for _ in order]
    expect(vals[0] == 7 and vals[3] == 1 << 40 and vals[4] is True and vals[5] == 2.5, f"fields {vals}")
    text = c.command(j.STRREF, 1, u64(vals[1][2])).string()
    expect(text == "hello", f"label is {text!r}")
    expect(c.command(j.ARRAYREF, 1, u64(vals[2][2])).i32() == 3, "array length")
    r = c.command(j.ARRAYREF, 2, u64(vals[2][2]) + i32(0) + i32(3))
    tag, count = r.u8(), r.i32()
    expect(tag == ord("I") and count == 3, "array region header")
    expect([r.value(tag) for _ in range(count)] == [10, 20, 30], "array contents")
    r = c.command(j.OBJREF, 1, u64(this[2]))
    r.u8()
    expect(r.id() == cls, "ReferenceType of this is JdwpTarget")


def walk_tiny(s: Session, thread: int, cls: int, first: dict) -> None:
    """Lines of one instruction, where a step that skipped an instruction would skip a line."""
    seen = [first["loc"], s.step(thread, DEPTH_OVER)["loc"], s.step(thread, DEPTH_OVER)["loc"]]
    seen.append(s.step(thread, DEPTH_OVER, STEP_MIN)["loc"])
    got = [(s.method_name(loc), s.line_of(loc)) for loc in seen]
    want = [("tiny", LINE_BY_MARK[m]) for m in ("tiny", "tiny2", "tiny3", "tinyret")]
    expect(got == want, f"steps through one-instruction lines {got}, expected {want}")


def walk_steps(s: Session, thread: int, cls: int, first: dict) -> None:
    seen = [first["loc"]]
    for depth in (DEPTH_INTO, DEPTH_OVER, DEPTH_OUT, DEPTH_OVER, DEPTH_OVER):
        seen.append(s.step(thread, depth)["loc"])
    got = [(s.method_name(loc), line) for loc in seen for line in [s.line_of(loc)]]
    want = [(m, LINE_BY_MARK[mark]) for m, mark in EXPECTED_STEPS]
    expect(got == want, f"step locations {got}, expected {want}")


def threads_stand_still(s: Session, cls: int) -> None:
    ticks = s.fields(cls)["ticks"]
    a = s.static_int(cls, ticks)
    time.sleep(0.3)
    expect(s.static_int(cls, ticks) == a, "the ticker thread ran while the VM was suspended")
    ticker = s.thread_named("jdwp-ticker")
    r = s.c.command(j.THREAD, 4, u64(ticker))
    r.i32()
    expect(r.i32() & 1, "the ticker is not reported suspended")
    s.c.command(j.THREAD, 3, u64(ticker))  # resume that thread alone
    time.sleep(0.3)
    expect(s.static_int(cls, ticks) > a, "the ticker did not run after its own resume")
    s.c.command(j.THREAD, 2, u64(ticker))  # and stop it again


def debug_run(conn: Connection) -> None:
    s = Session(conn)
    r = conn.command(j.VM, 7)
    expect([r.i32() for _ in range(5)] == [8] * 5, "all id sizes are 8")
    conn.command(j.VM, 17)
    _policy, events = conn.next_event(10)
    expect(events[0]["kind"] == j.VM_START, f"the VM did not greet the debugger with VMStart: {events}")
    s.request(j.VM_DEATH, 0)
    s.request(j.CLASS_PREPARE, 2, bytes([5]) + string("JdwpTarget"))
    s.request(j.CLASS_PREPARE, 1, bytes([5]) + string("JdwpTarget$Once"))
    s.resume()
    ev = s.stop(j.CLASS_PREPARE)
    expect(ev["signature"] == SIG, f"class prepare for {ev['signature']}")
    cls = s.class_id(SIG)
    expect(cls == ev["type"], "the prepared type is the class found by signature")
    methods = s.methods(cls)
    tiny_bp = s.breakpoint(cls, methods["tiny"][0], LINE_BY_MARK["tiny"])
    s.breakpoint(cls, methods["work"][0], LINE_BY_MARK["call"])
    s.breakpoint(cls, methods["main"][0], LINE_BY_MARK["print"])
    s.resume()
    tiny_first = s.stop(j.BREAKPOINT)
    walk_tiny(s, tiny_first["thread"], cls, tiny_first)
    s.clear(j.BREAKPOINT, tiny_bp)
    s.resume()
    first = s.stop(j.BREAKPOINT)
    thread = first["thread"]
    check_locals_and_fields(s, thread, cls)
    walk_steps(s, thread, cls, first)
    s.resume()
    s.stop(j.BREAKPOINT)  # at the print line
    expect(s.line_of(first["loc"]) == LINE_BY_MARK["call"], "first stop was at the call")
    threads_stand_still(s, cls)
    conn.command(j.EVENTREQ, 3)  # ClearAllBreakpoints
    s.resume()
    _policy, events = conn.next_event()
    expect(events[0]["kind"] == j.VM_DEATH, f"expected VM death, got {events}")


def attach_and_run(cmd_for, oracle: bool) -> None:
    port = free_port()
    target = Target(cmd_for(f"transport=dt_socket,server=y,suspend=y,address={port}"))
    try:
        if not oracle:
            # A port probe that never shakes hands must not cost the real debugger its VMStart.
            connect(port, target).sock.close()
        conn = connect(port, target)
        conn.handshake()
        debug_run(conn)
        code = target.finish()
    except SystemExit:
        print("the VM printed:\n" + target.text(), file=sys.stderr)
        raise
    finally:
        target.kill()
    expect(code == 0, f"exit status {code}")
    expect("result=15 once=1" in target.text(), f"program output {target.text()!r}")
    if not oracle:
        expect(f"Listening for transport dt_socket at address: {port}" in target.text(), "listening line")


def connect(port: int, target: Target) -> Connection:
    end = time.monotonic() + 30
    while time.monotonic() < end:
        try:
            return Connection(socket.create_connection(("127.0.0.1", port), timeout=5))
        except OSError:
            if target.proc.poll() is not None:
                die(f"the VM ended before listening: {target.text()}")
            time.sleep(0.1)
    die("could not connect to the VM")
    raise AssertionError


def on_lazyos(lazyos: Path) -> None:
    """The walk against a LazyVM inside LazyOS: QEMU's user network lets the guest reach this
    machine at 10.0.2.2, so the VM connects out (`server=n`) to a listener here, and this
    script is the debugger. The guest has no loopback, so it cannot be the other way round."""
    import lazyos_test  # noqa: PLC0415 (only this run needs a LazyOS checkout)

    jar = lazyrt.write_jar(OUT / "classes", OUT / "target.jar")
    lazyos_test.stage(lazyos_test.build_musl(), jar)
    outcome: dict[str, BaseException] = {}
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def debugger() -> None:
            try:
                listener.settimeout(900)
                conn = Connection(listener.accept()[0])
                conn.handshake()
                debug_run(conn)
            except BaseException as e:  # noqa: BLE001 (the main thread reports it)
                outcome["error"] = e

        thread = threading.Thread(target=debugger, daemon=True)
        thread.start()
        agent = f"-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=10.0.2.2:{port},timeout=60000"
        line = lazyos_test.command_line("JdwpTarget", [], [agent])
        out = lazyos_test.run_commands(lazyos, [line], 900, False, net=True)
        thread.join(30)

    def text(name: str) -> str:
        path = out / name
        return path.read_bytes().decode("utf-8", "replace") if path.is_file() else ""

    if "error" in outcome:
        print(f"inside LazyOS the VM printed:\n{text('1.stdout')}{text('1.stderr')}", file=sys.stderr)
        raise outcome["error"]
    expect(not thread.is_alive(), "the debugger side did not finish")
    expect(text("1.exit").strip() == "0", f"exit status {text('1.exit')!r}: {text('1.stderr')}")
    expect("result=15 once=1" in text("1.stdout"), f"program output {text('1.stdout')!r}")
    print("jdwp_smoke: ok inside LazyOS")


def threads_walk(cmd_for) -> None:
    """`JdwpThreads`: idle threads, step filters, a step request left on a dead thread."""
    port = free_port()
    target = Target(cmd_for(f"transport=dt_socket,server=y,suspend=y,address={port}", "JdwpThreads"))
    try:
        conn = connect(port, target)
        conn.handshake()
        jdwp_threads.run(conn)
        code = target.finish()
    except SystemExit:
        print("the VM printed:\n" + target.text(), file=sys.stderr)
        raise
    finally:
        target.kill()
    expect(code == 0 and "threads done 3" in target.text(), f"JdwpThreads: status {code}, output {target.text()!r}")


def vm_connects_out(cmd_for) -> None:
    """`server=n`: the VM dials the debugger, as an IDE's Debug button arranges."""
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        target = Target(cmd_for(f"transport=dt_socket,server=n,suspend=y,address=127.0.0.1:{port}"))
        try:
            listener.settimeout(30)
            conn = Connection(listener.accept()[0])
            conn.handshake()
            r = conn.command(j.VM, 1)
            r.string()
            expect(r.i32() == 1 and r.i32() >= 6, "JDWP version 1.6 or later")
            Session(conn).resume()
            expect(target.finish() == 0, "exit status after a server=n run")
        finally:
            target.kill()
    expect("result=15" in target.text(), "program ran after a server=n resume")


def valid_ids(conn: Connection) -> list[int]:
    """Ids the VM really knows, so random bodies get past the first check and into the rest."""
    s = Session(conn)
    ids = [s.class_id("Ljava/lang/Object;"), s.class_id("Ljava/lang/String;")]
    ids += [(ids[0] << 16) | k for k in range(3)]  # member ids of Object
    r = conn.command(j.VM, 4)
    ids += [r.id() for _ in range(r.i32())]
    r = conn.command(j.VM, 5)
    ids += [r.id() for _ in range(r.i32())]
    ids.append(conn.command(j.VM, 11, string("text")).id())
    return ids


def garbage(conn: Connection) -> None:
    """Random bodies into every command, built from real ids, small numbers and noise: each gets
    a reply, success or an error code."""
    rng = random.Random(20240611)
    ids = valid_ids(conn)

    def token() -> bytes:
        pick = rng.randrange(5)
        if pick == 0:
            return u64(rng.choice(ids))
        if pick == 1:
            return i32(rng.choice((0, 1, 2, 3, -1, 0x7FFFFFFF)))
        if pick == 2:
            return bytes([rng.randrange(256)])
        if pick == 3:
            return string(rng.choice(("JdwpTarget", "*", "java.*", "")))
        return u64(rng.getrandbits(64))

    for set_ in (j.VM, j.REFTYPE, 3, j.METHOD, j.OBJREF, j.STRREF, j.THREAD, 12, j.ARRAYREF, j.EVENTREQ, j.FRAME, 17):
        for cmd in range(1, 23):
            if (set_, cmd) in ((j.VM, 6), (j.VM, 10)):  # Dispose and Exit end the session
                continue
            for _ in range(12):
                body = b"".join(token() for _ in range(rng.randrange(0, 6)))
                try:
                    conn.command(set_, cmd, body, timeout=10)
                except JdwpError:
                    pass  # an error reply is the right answer


def bad_handshake(cmd_for) -> None:
    """A debugger that does not answer the handshake: with `server=n` the VM has no one to wait for."""
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        target = Target(cmd_for(f"transport=dt_socket,server=n,suspend=y,address=127.0.0.1:{listener.getsockname()[1]}"))
        try:
            listener.settimeout(30)
            peer = listener.accept()[0]
            peer.sendall(b"not the handshake")
            code = target.finish()
            peer.close()
        finally:
            target.kill()
    expect(code == 2, f"exit status {code} after a failed handshake, expected 2")


def hostile(cmd_for) -> None:
    """Malformed packets get errors, never a crash, and a bad length ends only the connection."""
    port = free_port()
    target = Target(cmd_for(f"transport=dt_socket,server=y,suspend=y,address={port}"))
    try:
        conn = connect(port, target)
        conn.handshake()
        for set_, cmd, body, what in [
            (99, 1, b"", "unknown command set"),
            (j.VM, 77, b"", "unknown command"),
            (j.VM, 2, i32(1 << 30) + b"abc", "string longer than the packet"),
            (j.VM, 14, i32(0x7FFFFFFF), "huge count"),
            (j.VM, 2, b"\x00", "truncated"),
            (j.REFTYPE, 4, u64(0xFFFFFFFFFFFFFFFF), "bad reference type id"),
            (j.OBJREF, 2, u64(12345) + i32(1) + u64(1), "unknown object"),
            (j.FRAME, 1, u64(1) + u64(2) + i32(1) + i32(0) + b"I", "unknown frame"),
            (j.ARRAYREF, 2, u64(5) + i32(0) + i32(1), "unknown array"),
            (j.EVENTREQ, 1, bytes([2, 2]) + i32(1) + bytes([7]), "truncated location modifier"),
            (j.EVENTREQ, 1, bytes([2, 9]) + i32(0), "bad suspend policy"),
        ]:
            try:
                conn.command(set_, cmd, body)
            except JdwpError as e:
                expect(e.code != 0, f"{what}: error 0")
                if what == "unknown command set" or what == "unknown command":
                    expect(e.code == j.NOT_IMPLEMENTED, f"{what}: error {e.code}, not NOT_IMPLEMENTED")
            else:
                die(f"jdwp_smoke: {what} was accepted")
        conn.command(j.VM, 1)  # still answering
        garbage(conn)
        conn.command(j.VM, 1)
        # A length that cannot be right: the agent drops the connection and lets the VM run.
        conn.sock.sendall(struct.pack(">IIBBB", 0xFFFFFFF0, 9, 0, 1, 1))
        code = target.finish()
    finally:
        target.kill()
    expect(code == 0, f"exit status {code} after the debugger left")
    expect("result=15 once=1" in target.text(), "the program did not finish after a bad packet")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--exe", help="the lazyvm binary (default: build target/release)")
    ap.add_argument("--oracle", action="store_true", help="run the walk on the Java 8 oracle instead")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--only-threads", action="store_true", help="just the JdwpThreads walk")
    ap.add_argument("--lazyos", type=Path, help="run the walk inside LazyOS (QEMU) from this checkout instead")
    args = ap.parse_args()
    LINE_BY_MARK.update(marks())
    classes = OUT / "classes"
    if not args.no_build:
        lazyrt.build()
    javac.compile([SRC, jdwp_threads.SRC], classes, bootclasspath=LAZYRT_CLASSES)
    if args.lazyos:
        return on_lazyos(args.lazyos)
    if args.oracle:
        java = find_tool("LAZYVM_ORACLE_JAVA", "java")

        def cmd_for(agent: str, main: str = "JdwpTarget") -> list[str]:
            return [java, "-Xmx512m", f"-agentlib:jdwp={agent}", "-cp", str(classes), main]
    else:
        exe = Path(args.exe) if args.exe else cargo_build_launcher()

        def cmd_for(agent: str, main: str = "JdwpTarget") -> list[str]:
            boot = f"-Xbootclasspath:{DIST / 'lazyrt.jar'}"
            return [str(exe), boot, f"-agentlib:jdwp={agent}", "-cp", str(classes), main]
    if args.only_threads:
        return threads_walk(cmd_for)
    attach_and_run(cmd_for, args.oracle)
    threads_walk(cmd_for)
    vm_connects_out(cmd_for)
    bad_handshake(cmd_for)
    if not args.oracle:
        hostile(cmd_for)
    print("jdwp_smoke: ok")


if __name__ == "__main__":
    main()
