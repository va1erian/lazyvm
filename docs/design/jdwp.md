# The debugger agent (S11-04)

`-agentlib:jdwp=transport=dt_socket,...` (and the old `-Xrunjdwp:`) starts an
agent that speaks JDWP, the protocol IDEs debug over. It is in
`crates/vm/src/debug`; the blocking sockets are `platform::tcp`.

## Options

`transport=dt_socket` is the only transport. `server=y` listens (and prints
`Listening for transport dt_socket at address: <port>`, which IDEs wait for);
`server=n`, the default, connects to the debugger at `address`, which is what
an IDE's Debug button arranges. `suspend=y`, the default, keeps the program
from starting until the debugger resumes the VM. A bare port is the loopback
interface; `*:port` listens on every interface. Other options are accepted and
ignored.

## Who runs what

The VM is owned by one OS thread at a time (`thread/gil.rs`). The agent is one
more OS thread, a daemon with a thread-table entry but no `java.lang.Thread`:

- It blocks on its socket with no access to the VM. Why not the reactor of
  `platform::net`: that has a single inbox emptied by `lazy.async.Loop`, so a
  reader there would steal the Loop's completions and need a running Loop.
- For each command it takes a turn like any thread (`thread::outside::with_vm`),
  writes the reply while it still holds the VM, and gives the VM back. Writing
  the reply first is what the protocol asks for: a thread the command resumed
  cannot send an event ahead of the reply to that command.
- Whoever holds the VM is alone with it, so every other thread's frames are in
  its entry and stand still while a command reads them. No lock is added.

## Stopping threads

Suspends are counted, as in JDWP (`suspend.rs`): one counter for the whole VM
and one per thread. A thread checks its count whenever it gets the VM back
(`Vm::hold_if_suspended`, in `blocking`, `yield_thread`, a lost time slice and
at a new thread's start) and, if held, parks outside the VM until resumed.
Those are all off the hot path. The thread that hits a breakpoint sends its
event and then holds itself the same way.

## Breakpoints and steps

No bytecode is patched: methods are shared by threads, the verifier and the
tight loop. Instead, while any breakpoint or step request exists, `Vm::slow_interp`
is on, the interpreter uses its one-instruction-at-a-time path, and
`debug_before_instruction` checks the instruction about to run against the
breakpoint table (one hash lookup) and the thread's step. With none set the
flag is off and nothing runs per instruction (measured: `tools/bench.py`, no
change beyond noise). A thread inside the tight loop leaves it when it comes
back from giving up the VM (`Vm::safepoint` returns true), so a breakpoint set
meanwhile takes effect.

A step ends when the thread returns to a caller, enters a method with line
numbers (into), or reaches the first instruction of another line in the same
frame (over, into). The class filters of the request (an IDE's step filters)
apply, also on a return: a thread that comes back into excluded code runs on.
Like the oracle, a step request stays after its event and steps again from
there until the debugger clears it; a request dies with its thread.

A class prepared while a thread links it posts its event at once, but the
thread is only held before it marks the class as being initialized
(`Vm::initialize`), or at its next instruction for links that come from
elsewhere. Holding inside linking or inside `<clinit>` would let another thread
initialize the class meanwhile, or leave it blocked on a class the debugger
shows in a `<clinit>` frame.

## Ids and the collector

Types, methods and fields are numbers made of their class and index. Frames are
made of a thread index and a depth. Objects get ids from a table that keeps
them alive (`Ids::roots`, scanned by the collector) until the debugger disposes
of them; a cap stops a client from pinning the heap.

## Hostile input

Packets are capped at 1 MiB and every count and length is checked against the
bytes that are there before anything is allocated (`wire.rs`). A bad length or
a reply where a command belongs ends the connection, which resumes the VM. A
malformed command body is an error reply. Unknown commands are
`NOT_IMPLEMENTED`.

## Not there

Method entry and exit, exceptions, field watches, monitors, `SetValues`,
`InvokeMethod`, `PopFrames`, `Bytecodes`, class redefinition, `ThreadStart`/
`ThreadDeath` events (the requests are accepted and never fire). Generic
signatures are empty except a class's own.
