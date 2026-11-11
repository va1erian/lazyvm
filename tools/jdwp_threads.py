"""The second walk of tools/jdwp_smoke.py: `tests/jdwp/JdwpThreads.java`.

Threads that have not started or have ended, step filters when a step returns into
excluded code, and a step request left set on a thread that ends while another one
starts. The expected answers are the Java 8 oracle's (the walk passes there too).
"""

from __future__ import annotations

import re
from pathlib import Path

import jdwplib as j
from common import ROOT, die
from jdwplib import Connection, JdwpError, i32, string, u64
from jdwpsession import DEPTH_OVER, STEP_LINE, Session, expect

SRC = ROOT / "tests" / "jdwp" / "JdwpThreads.java"
MAIN = "JdwpThreads"
EXCLUDES = ("JdwpThreads$Lib", "java.*", "lazy.*", "sun.*")


def marks() -> dict[str, int]:
    return {m.group(1): n for n, text in enumerate(Path(SRC).read_text(encoding="utf-8").splitlines(), 1)
            for m in [re.search(r"// @(\w+)", text)] if m}


def static_objects(s: Session, cls: int, names: list[str]) -> list[int]:
    ids = s.fields(cls)
    r = s.c.command(j.REFTYPE, 6, u64(cls) + i32(len(names)) + b"".join(u64(ids[n]) for n in names))
    r.i32()
    return [r.value()[2] for _ in names]


def check_idle_threads(s: Session, cls: int) -> None:
    """A thread not started has status -1 and its group; an ended one status 0 and no group;
    neither has frames, and suspending them counts for nothing."""
    unstarted, finished = static_objects(s, cls, ["unstarted", "finished"])
    for name, thread, status, has_group in (("unstarted", unstarted, -1, True), ("finished", finished, 0, False)):
        c = s.c
        expect(c.command(j.THREAD, 1, u64(thread)).string() == name, f"{name}: the name")
        r = c.command(j.THREAD, 4, u64(thread))
        expect((r.i32(), r.i32()) == (status, 0), f"{name}: status")
        expect((c.command(j.THREAD, 5, u64(thread)).id() != 0) == has_group, f"{name}: group")
        expect(c.command(j.THREAD, 12, u64(thread)).i32() == 0, f"{name}: suspend count")
        # The oracle's order matters: until the thread has been suspended and resumed,
        # FrameCount says "not suspended" (13), and Frames after that "invalid thread" (10).
        expect_error(c, j.THREAD, 7, u64(thread), 13, f"{name}: FrameCount")
        c.command(j.THREAD, 2, u64(thread))
        c.command(j.THREAD, 3, u64(thread))
        expect_error(c, j.THREAD, 6, u64(thread) + i32(0) + i32(-1), 10, f"{name}: Frames")


def expect_error(c: Connection, set_: int, cmd: int, body: bytes, code: int, what: str) -> None:
    try:
        c.command(set_, cmd, body)
    except JdwpError as e:
        expect(e.code == code, f"{what} gave error {e.code}, expected {code}")
    else:
        die(f"jdwp_smoke: {what} was accepted")


def excluded_step(s: Session, thread: int, depth: int, filters: bool = True) -> int:
    mods = [bytes([10]) + u64(thread) + i32(STEP_LINE) + i32(depth)]
    mods += [bytes([6]) + string(p) for p in EXCLUDES if filters]
    return s.request(j.SINGLE_STEP, 2, *mods)


def check_filtered_returns(s: Session, thread: int, lines: dict[str, int]) -> None:
    """Stepping out of the callee into the excluded `Lib.each` must run on to the caller of that."""
    s.c.command(j.EVENTREQ, 3)  # no more breakpoints
    want = [("run", lines["cbend"]), ("main", lines["after"])]
    got = []
    for _ in want:
        rid = excluded_step(s, thread, DEPTH_OVER)
        s.resume()
        ev = s.stop(j.SINGLE_STEP)
        s.clear(j.SINGLE_STEP, rid)
        got.append((s.method_name(ev["loc"]), s.line_of(ev["loc"])))
    expect(got == want, f"steps through an excluded caller {got}, expected {want}")


def check_dead_step_target(s: Session, thread: int) -> None:
    """A step request left set on a thread that ends must not fire in the thread that starts next."""
    s.c.command(j.EVENTREQ, 3)
    excluded_step(s, thread, DEPTH_OVER, filters=False)
    s.resume()
    while True:
        _policy, events = s.c.next_event(30)
        for ev in events:
            if ev["kind"] == j.VM_DEATH:
                return
            expect(ev["kind"] != j.SINGLE_STEP or ev["thread"] == thread,
                   f"a step requested for thread A fired in another thread: {ev}")
        s.resume()


def run(conn: Connection) -> None:
    s = Session(conn)
    _policy, events = conn.next_event(10)
    expect(events[0]["kind"] == j.VM_START, "no VMStart")
    lines = marks()
    s.request(j.VM_DEATH, 0)
    s.request(j.CLASS_PREPARE, 2, bytes([5]) + string(MAIN + "*"))
    s.resume()
    where = {f"L{MAIN};": ("main", "each"), f"L{MAIN}$Cb;": ("run", "cb"), f"L{MAIN}$A;": ("run", "a")}
    main_class = 0
    while True:
        _policy, events = conn.next_event(60)
        ev = events[0]
        if ev["kind"] == j.CLASS_PREPARE and ev["signature"] in where:
            method, mark = where[ev["signature"]]
            main_class = ev["type"] if ev["signature"] == f"L{MAIN};" else main_class
            s.breakpoint(ev["type"], s.methods(ev["type"])[method][0], lines[mark])
        elif ev["kind"] == j.BREAKPOINT:
            line = s.line_of(ev["loc"])
            if line == lines["each"]:
                check_idle_threads(s, main_class)
            elif line == lines["cb"]:
                check_filtered_returns(s, ev["thread"], lines)
            elif line == lines["a"]:
                return check_dead_step_target(s, ev["thread"])
        elif ev["kind"] == j.VM_DEATH:
            die("jdwp_smoke: the program ended before the thread checks were done")
        s.resume()
