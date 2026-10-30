# Threads and monitors (S6-01, S6-02)

LazyVM runs each started `java.lang.Thread` on an OS thread of its own
(`std::thread`, the same code on Windows and on LazyOS). The threads share
one `Vm`, and only one of them runs bytecode at a time: decisions.md D5 chose
a single VM lock over parallel bytecode. This page says how that lock works,
how the collector stops the world, how monitors and class initialization
block, and what later items (S6-03 to S6-06) can rely on.

Code: `crates/vm/src/thread/` (`gil.rs` the lock, `mod.rs` the thread table
and parking, `monitor.rs` monitors, `spawn.rs` start, end and VM exit),
`crates/vm/src/interp/sync.rs` (the bytecodes and `synchronized` methods),
`crates/vm/src/init.rs` (class initialization), `crates/natives/src/lang_thread.rs`,
and in `lazyrt` `Thread`, `ThreadGroup`, `ThreadLocal`,
`InheritableThreadLocal` and the package-private `Shutdown`.

## The VM lock: handing the `Vm` over

All VM code takes `&mut Vm`. Rather than share one `Vm` between threads
(which needs `unsafe` or interior mutability everywhere), the `Vm` *value*
moves between OS threads. A thread that gives the VM up moves it into a slot
(`Gil`, a `Mutex<Option<Vm>>` and a condition variable) and leaves an empty
placeholder `Vm` in its own `&mut Vm`; when its turn comes it moves the real
one back. So a `&mut Vm` is always exclusive, and the VM core stays safe
Rust: no new `unsafe` block was needed.

- `Vm::blocking(f)` is the one way to give the VM up: it releases the VM,
  runs `f` (which cannot see the VM), and takes it back. Sleeping, waiting,
  blocking on a monitor or a class being initialized, `Thread.yield` and a
  console read all go through it.
- Turns are first come, first served, by ticket.
- Fields of `Vm` that belong to one thread (`frames`, `roots`, the nesting
  and frame-slot counters, `creating_exception`, `loading`) describe the
  thread holding the VM. `blocking` moves them into the thread's
  `ThreadEntry` before releasing, and back afterwards.

### Safepoints and time slices

The interpreter polls at every backward branch and every call (`Flow::Poll`
in `interp/mod.rs`): one relaxed atomic load. A thread that has waited a whole
slice (5 ms) for the VM sets the flag, and the holder yields at its next poll.
Yielding at every poll whenever anyone waits was tried first and spent all
the time handing over (8 allocating threads made almost no progress), so the
slice gives each holder at least 5 ms, like a classic interpreter lock.

### Stopping the world

The collector runs on whichever thread holds the VM, under the lock, so every
other thread is stopped by construction, at a point where it gave the VM up.
Roots (`gc.rs`) are the holder's frames and `roots`, plus, for every other
live thread, the frames and roots parked in its entry, its `Thread` object,
the objects with blocked or waiting threads (`Vm::monitors`), statics,
interned strings and pool constants. A frame also roots the objects whose
monitors it holds.

**Rooting rule (extends D5):** a `Ref` held in Rust across `blocking`, or
across anything that may call into Java or block, must be rooted
(`with_root`, a frame, or the call's own arguments). Where a call may block
before it runs, the arguments must already be rooted: `Vm::invoke` puts them
on `roots` first, the interpreter leaves a native's arguments on the caller's
operand stack, and a `synchronized` Java method is locked only after its frame
is pushed, so its locals hold them while it waits. A blocked thread holds no
raw heap pointer: references are slot indices, and `blocking`'s closure has
no access to the VM.

Two roots are, today, implied by others: a parked thread's `roots` entries
are also the arguments in the frames its calls pushed (javac never
overwrites a constructor's `this`), and every object in `Vm::monitors` is
also on a blocked thread's stack or in its locals. They stay as roots
because nothing in the VM promises that, and bytecode that overwrites its
argument locals could break it.

## Lock order

There is one VM-level lock, so there is no order among the heap, the class
table, the intern table, the thread table and the monitors: they are plain
fields of `Vm`, all guarded by holding it. The rules are:

1. **The VM lock** is held while running bytecode or touching any VM state.
2. **Leaf locks** may be taken while holding the VM and are never held while
   waiting for it: the `Gil` slot mutex (inside `release`/`acquire` only),
   the stdout/stderr locks in `platform`, and `std`'s park token.
3. Never block indefinitely while holding the VM, except through
   `Vm::blocking`. Writes to stdout and stderr are the one exception: they
   stay under the VM lock, so output keeps program order.

Java monitors are not OS locks: a thread blocked on one has released the VM.

## Thread lifecycle

- `Thread.start` (Java) checks the state, marks the thread started and calls
  `start0`, which adds a `ThreadEntry` and spawns the OS thread. The new
  thread waits for the VM, then calls `Thread.runByVm`: `run()`, the uncaught
  exception handler, then `exitByVm` (state terminated, `notifyAll` for
  joiners). The VM then drops the entry.
- `join` is Java: `wait` on the thread object while it is alive.
- A started thread's stack is 64 MiB (`THREAD_STACK_BYTES`), a quarter of the
  main thread's, with the same `Options::max_nesting` (1024) for calls from
  Rust into Java: one such call was measured at under 8 KiB of stack, so
  1000-deep reflective recursion works on a started thread too
  (`DeepThread`).
- The main thread is entry 0. Its `Thread` object is made the first time
  Java asks for it (`currentThread`), so a program that never does pays
  nothing.
- Interrupts are a flag in the entry; `interrupt` also unparks the thread.
  `sleep` and `wait` check it before blocking and after each wakeup.
- `getState` is cheap: Java knows new and terminated, the entry records what
  a live thread is blocked on.
- Priorities are stored and reported, not given to the OS scheduler: the
  lock decides who runs.
- Stack traces of a started thread start at `run`: the VM's entry frame
  (`runByVm`) is left out, as the oracle shows no frame below `run`.
- `ThreadLocal` values live in an identity map on the `Thread`. The map holds
  its `ThreadLocal` keys strongly, so a value lives until `remove` or the end
  of its thread (the Java 8 implementation lets an unreachable key go).

## VM exit and shutdown hooks

After `main` returns, or its uncaught exception was reported through the
main thread's handler (exit status 1), the launcher calls
`Vm::shutdown_after_main`: `exitByVm` for the main thread, a wait until no
other non-daemon thread is alive, then `Shutdown.runHooks` if the program
loaded `Shutdown` (no hooks, no cost). The hooks are started as threads and
joined. `System.exit` runs the hooks on the calling thread, then halts with
its status, even when a hook cannot be started (one that already ran);
after `main`, such a hook ends the hook sequence and the VM exits quietly
with main's status. Both are what Java 8 does. A `System.exit` while the
hooks run blocks forever. `Runtime.halt` halts at once. Halting is `VmError::Exit`: on the main thread
it unwinds to the launcher; on another thread `exit_process` flushes the
console and ends the process from there, while that thread holds the VM so
no bytecode runs elsewhere. Daemon threads simply end with the process.

## Monitors

The owner and entry count of an object's monitor live in its heap slot
(`heap::LockWord`), so an uncontended `synchronized` touches nothing else.
Only when a thread must block (entering an owned monitor, or `wait`) does the
monitor get an entry in `Vm::monitors`, listing its entrants and wait set.

- `monitorenter` keeps the object on the operand stack while it blocks, so
  it stays rooted; `monitorexit` must name a monitor this frame entered,
  otherwise `IllegalMonitorStateException` (JVMS 2.11.10 rule 2). A return
  with monitors still entered by `monitorenter` throws the same (rule 1).
  Popping a frame by an exception releases what it holds.
- A `synchronized` method locks its receiver, or the class's `Class` for a
  static method, once its frame is pushed (a native: before the call, its
  arguments still on the caller's stack), and releases it when the frame is
  popped.
- **Fairness.** A release normally frees the monitor and wakes the entrants
  to compete. That alone starves them: the releasing thread keeps the VM, so
  a thread looping on `synchronized` re-enters before a woken entrant ever
  runs, and when it finally yields at a safepoint it usually owns the
  monitor again (3 threads looping on one lock got 12912, 1 and 1 turns in
  1.5 s). So once the oldest entrant has waited 5 ms (one slice of the VM
  lock), the release hands it the monitor directly, with the count it asked
  for. Handing over at every release would be fairer still but would switch
  threads at every `monitorexit`; the 5 ms rule keeps the uncontended and
  briefly contended cases cheap and bounds the wait (`Fairness`).
- `wait` releases the monitor fully, waits for a `notify`, an interrupt or
  the timeout, then takes the monitor back with its old count. A thread that
  was both notified and interrupted returns normally with the interrupt
  still pending, so a notification is never lost (JLS 17.2.4).
- `IllegalMonitorStateException` from `wait`/`notify` has no message, as on
  the oracle.

## Class initialization

`InitState::Initializing` names the initializing thread. JVMS 5.5 asks for a
lock per class; the VM lock plays that part. A thread that finds another
thread initializing the class parks until some initialization finishes, then
looks again; the initializing thread itself proceeds (a recursive request).
A failure leaves the class erroneous, and every later use throws
`NoClassDefFoundError: Could not initialize class ...`. A thread waiting for
another thread's initializer reports `RUNNABLE`, before and after an
interrupt, as Java 8 does (`ClinitState`); the wait is not interruptible.

## Cost on one thread

Before threads, `monitorenter` was a null check. Now the interpreter polls at
branches and calls, frames carry their monitors, and `synchronized` does real
work. Against main at efd9986 (CPU time, best of 5, the two builds run
interleaved, Windows), the branch is slower on one thread by:

| Test | main | branch | cost |
|---|---|---|---|
| `CharacterTables` | 10.70 s | 11.42 s | +6.7% |
| `GcChurn` | 9.47 s | 10.00 s | +5.6% |
| `ScannerLong` | 7.44 s | 7.88 s | +5.9% |
| `RegexDepth` | 11.81 s | 12.84 s | +8.7% |
| `RegexMemoStarts` | 13.98 s | 15.08 s | +7.8% |
| `CaseMapping` | 49.09 s | 52.33 s | +6.6% |

An earlier, quieter session measured 0.3% to 1.4% for nearly the same code,
so the figure moves with the machine's state; these are the numbers to beat.
Experiments to locate it: dropping the safepoint poll, or the `synchronized`
checks on the call and return path, each won back under 2 points; a larger
heap slot alone (main with 8 more bytes per slot) cost nothing; an inlined
fast path for class initialization changed nothing. The rest is not
located yet (the interpreter loop's code layout is a suspect). A loop doing
nothing but `synchronized` blocks and calls is about 5% slower again on top.

## For S6-03 to S6-06

- **Memory model (S6-03):** every handover goes through a mutex, so it orders
  all memory effects; with one thread running bytecode at a time, `volatile`
  needs nothing extra, and an atomic intrinsic is atomic if its native does
  not call `blocking`. See [`atomics.md`](atomics.md).
- **`LockSupport` (S6-04):** `Vm::park_thread` with a permit in `ThreadEntry`
  gives park/unpark; executors and queues can be built on monitors. The locks
  and `Semaphore` park in `lazy.internal.Turnstile`, whose rule for overtaking
  a queue is the "Fairness" one above: a newcomer may pass a head younger than
  one VM lock slice, because the thread that would otherwise switch still has
  its slice to run.
- **The reactor (S6-06):** a reactor OS thread does not run Java and must
  not take the VM to deliver completions. It should hand completions to a
  Java thread (for example by unparking a Java thread waiting in a native
  that blocks through `Vm::blocking`). Taking the VM from an OS thread that
  is not a Java thread would need an entry in the thread table first.
- **`lazy.gfx` (S9):** windows live in a thread-local of the OS thread that
  opened them, so a window must be used from the thread that created it.
