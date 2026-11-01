# Threads and monitors (S6-01, S6-02)

LazyVM runs each started `java.lang.Thread` on an OS thread of its own
(`std::thread`, the same code on Windows and on LazyOS). The threads share
one `Vm`, and only one of them runs bytecode at a time: decisions.md D5 chose
a single VM lock over parallel bytecode. This page says how that lock works,
how the collector stops the world, how monitors and class initialization
block, and what later items (S6-03 to S6-06) can rely on.

Code: `crates/vm/src/thread/` (`gil.rs` the lock, `mod.rs` the thread table
and parking, `monitor.rs` monitors, `spawn.rs` start,
end and VM exit),
`crates/vm/src/interp/sync.rs` (the bytecodes and `synchronized` methods),
`crates/vm/src/init.rs` (class initialization), `crates/platform/src/timer.rs` (timed parking), `crates/natives/src/lang_thread.rs`,
and in `lazyrt` `Thread`, `ThreadGroup`, `ThreadLocal`,
`InheritableThreadLocal` and the package-private `Shutdown`.

## The VM lock: handing the `Vm` over

All VM code takes `&mut Vm`. Rather than share one `Vm` between threads
(which needs `unsafe` or interior mutability everywhere), the `Vm` *value*
moves between OS threads. A thread that gives the VM up moves it into a slot
(`Gil`, a `Mutex<Option<Vm>>` and a condition variable per thread) and leaves an empty
placeholder `Vm` in its own `&mut Vm`; when its turn comes it moves the real
one back. So a `&mut Vm` is always exclusive, and the VM core stays safe
Rust: no new `unsafe` block was needed.

- `Vm::blocking(f)` is the one way to give the VM up: it releases the VM,
  runs `f` (which cannot see the VM), and takes it back. Sleeping, waiting,
  blocking on a monitor or a class being initialized, `Thread.yield` and a
  console read all go through it.
- Who goes next is below, in "Turns".
- Fields of `Vm` that belong to one thread (`frames`, `roots`, the nesting
  and frame-slot counters, `creating_exception`, `loading`) describe the
  thread holding the VM. `blocking` moves them into the thread's
  `ThreadEntry` before releasing, and back afterwards.

### Safepoints and time slices

The interpreter polls at every backward branch and every call (`Vm::safepoint`,
called from `op_branch` and `call`): one relaxed atomic load of a flag that says
a thread is in line. With none, that is all a safepoint costs. With some, the
holder times its own slice: every 1024 polls it looks at the clock, and gives up
the VM when 5 ms have passed since the slice began and someone can take it, so
the slice is 5 ms on every platform (`Slice`: mean run 5.0 ms). It used to be a
waiting thread that noticed, on an OS timer, and that took 15.6 ms on Windows.
Yielding at every poll whenever anyone waits was tried first and spent all
the time handing over (8 allocating threads made almost no progress), so a
slice lasts 5 ms, like a classic interpreter lock.

That count times a slice only while polls come quickly. Between two polls there
can be a native of any length, so a slice was 1024 of them: a daemon thread
looping on `System.arraycopy` of 16 MB kept `Thread.sleep(1)` on another thread
waiting 596 ms on average. So one helper thread, the *watch* (`Gil::keep_watch`),
wakes when the slice is due to end, and if someone can take over, sets a flag that
the next poll honours by looking at the clock at once. It sleeps with
`std::thread::sleep`, late by 0.5 ms at most on Windows (see "Timed waits"), so
this holds on every platform: a slice ends within about a slice's length plus the
one native that is running, which nothing can interrupt. The flag is read only on
the path taken with threads in line, so one thread pays nothing, and the watch
starts when a thread first has to wait for the VM: one that finds it free, as a
lone thread back from a sleep does, calls nobody. With the line empty for 8 looks in a
row it sleeps until someone has to wait: rousing it takes a system call, which threads
that only hop on a lock must not pay at each hop. While it times, it wakes about
once per slice, 200 times a second, and takes the slot's mutex once. Inside LazyOS, `Thread.sleep(1)` beside `arraycopy` of 16 MB takes 10.4 ms on average, and of 1 MB 8.1 ms.
If it cannot be started, slices are as long as the polls make them. `gil_tests.rs` pins that a
holder with slow polls is told, and that the watch comes back after sleeping.

| Mean `Thread.sleep(1)` beside a thread looping on (`SlowPoll`), ms | count alone | with the watch | oracle |
|---|---|---|---|
| `arraycopy` of 16 MB | 498-596 | 9.6 | 1.4 |
| `arraycopy` of 64 MB | 2396 | 21.8 | |
| `clone` of 16 MB | 261 | 7.8 | |
| `arraycopy` of 1 MB | 43 | 5.4 | |
| `System.gc()` with 16 MB of objects | 40 | 5.5 | 29 |
| nothing | 5.1 | 5.1 | |

With fast polls the watch changes nothing that can be measured: `Spin2`, 100M calls,
5 runs interleaved with the build before it, 19.7-20.2 s against 19.8-20.0 s on one
busy thread and 20.2-20.8 s against 20.2-20.8 s on two, and the same for `TimedHop` and
`StartLoop`. Sharing the VM costs 2% to 4% over one thread doing the same work (the
looks, the mutex and the 400 switches a second), not the under 1% once claimed for the
looks alone.

### Turns

The line has two parts: *preempted* threads, which were busy (their slice ended,
or `Thread.yield`), and *woken* threads, which were blocked or have just started.
The rules, in `gil.rs`:

1. A thread that blocks before its slice is spent leaves the rest of the slice
   to the woken line, and the preempted line waits for it to be spent.
2. Whoever wakes a parked thread holds the VM, and names the thread in the
   woken line there and then (`Gil::expect`), before the OS has woken it. Should
   the VM come free before it arrives, it stays idle for it, at worst until the
   slice is spent, which the first preempted thread times. `ThreadEntry::parked`
   says the thread is sure to come.
3. A spent slice goes to the oldest preempted thread if the woken line had the
   last one, otherwise to the woken line, among the threads that were waiting:
   the thread whose slice ended does not count, even if it would be alone in
   the preempted line (`Slot::preempt_holder`). So a preempted thread is never
   passed for more than one slice per thread ahead of it, and the woken line
   waits at most one slice for its next turn.
4. A thread preempted on a woken slice, having had less than a slice of VM time
   since it blocked, stays in the woken line, at the back. Threads handing a
   lock around share one slice, and when it ended during one's turn, that one
   went behind every busy thread and the others stopped until it came round.
   With 200 busy threads that was a second, every few slices. Once it has had a
   whole slice since it blocked it is busy too, so blocking once buys one slice.

A switch notifies only the thread that goes next, on its own condition variable,
or, if that one is on its way, the first preempted thread, so that it can time
the slice. A thread that gets to the slot and finds that another should go next
(its arrival or the time that passed changed that) wakes it in turn. Every step
takes constant time: the preempted line is a queue, arrived threads a heap by the
place they were named in, and a thread's own entry says if it has a place.

Before this, turns were first come, first served, and a thread woken from a
monitor queued behind every busy thread. Each lock handover then cost a whole
round of slices, during which the next waiter grew older than the 5 ms that
makes a release hand over and makes `Turnstile` close to newcomers, so the
next handover was forced too. A convoy formed and did not dissolve. Counted on
`LinkedBlockingQueue` with 3 spinning threads: about 115 VM switches and 28
monitor handovers a second, for tens of seconds or longer, against a round of
under 200 ms when no convoy formed, so it was slowness and not a livelock, but
as good as one. A woken thread going in the same slice takes the wait out, and
threads handing a lock around share one slice however many switches they make.

| 3 spinning and 3 contending threads, `Convoy2 3 5000`, per round | before | after | oracle |
|---|---|---|---|
| plain monitor | 50-62 ms | 26-45 ms | 1-4 ms |
| `LinkedBlockingQueue` offer and poll | over 280 s | 97-221 ms | 3 ms |
| `ConcurrentHashMap` put | not reached | 82-104 ms | 3 ms |

Without spinners the queue round is 42-46 ms, before and after. `SpinnerConvoy`
pins this: it takes seconds now, and hits the test's time limit if a handover
waits behind busy threads again. `Fairness` still bounds starvation on a lock.
Naming the woken thread is not pinned by a test, as the VM only loses speed
without it: a thread that gets back before its waker blocks needs no name, and
one that does not waits out a slice. The same goes for rule 4, starting threads
in the woken line and timing the slice at the poll: `gil_tests.rs` pins who goes
next, as far as the line alone shows it, and `Fairness` hangs if a holder never
yields, but the time lost is seen only by measuring. Per hop, at 3ca290d and now,
with the oracle for comparison:

| With N spinning threads | 0 | 3 | 30 | 200 | oracle at 30 |
|---|---|---|---|---|---|
| `wait`/`notify` hop, before, us | 1.4 | 10 | 288 | 1230 | 3.5 |
| the same, now, us | 1.5 | 3.1 | 3.3 | 2.6 | |
| `LinkedBlockingQueue` hop, before, us | 15 | 151 | 2216 | 37700 | 3.4 |
| the same, now, us | 15 | 33 | 43 | 42 | |
| unpark, p99, before, ms | 1.3 | 59 | 469 | 3146 | 24 |
| the same, now, ms | 0.2 | 5 | 0.1 | 5 | |

(`Sched`; the worst of it is the woken line's share: with spinners it runs every
other slice, 5 ms on and 5 ms off, so hops happen in bursts.) Releasing and
joining 3000 parked threads (`Probe4 parked`) took 14.5 s and takes 40 ms; the
oracle takes about 100 ms. `Convoy2 30 500`, a round: 472 ms before, 157 ms now, oracle
28 ms, as a thread that used its whole slice waits for 30 others. `Pair 3000 3
pair`: 186728 hops before, 511472 now, oracle 594129, and no spinner goes more
than 30 ms without a turn. Machine load moved single runs of one build by up to a factor
of 5 while these were taken, so read them as orders of magnitude.

All of this runs only when a thread gives up the VM or someone is in line, so one
thread pays nothing for it: by the method below, against the commit before it,
`ScannerLong` -1.0%, `RegexDepth` -1.9%, `RegexMemoStarts` -0.6%, `CharacterTables`
+0.9%, `GcChurn` -1.0%, `CaseMapping` -2.6% and 80M uncontended monitor entries -3.6%
(best of 3 at 3ca290d, with other sessions running on the machine), all within what runs
of one build differ by.

### Timed waits

On Windows a timed wait on a lock or on a thread's token ends on the system tick,
15.6 ms apart, whatever it asks for (measured: `park_timeout` and `wait_timeout`
of 1 ms and of 5 ms all took 15.6 ms; the tick is confirmed as the cause), so
`Thread.sleep(1)` took 16 ms. `std::thread::sleep` is finer there (to the next
0.5 ms, on a high-resolution timer) but cannot be woken. So the last 20 ms of any
timed wait (`sleep`, `wait`, `join`, `parkNanos`) are timed by one helper thread,
`platform/src/timer.rs`: the waiting thread parks, and the helper sleeps and unparks it
when its time comes, yielding through the last 100 us. A thread woken earlier takes
its entry back: left there, the entries of threads hopping on `wait(10)` piled up
and kept the helper busy, and a hop cost 3.5 us against 1.7 for `wait(1000)`
(`TimedHop`; now 1.6 for both). It holds no VM, it starts at
the first such wait, and with none left it waits on a condition variable. It needs
no `unsafe` and no `timeBeginPeriod`, which would raise the tick for the process.
`Thread.sleep(1)` in a loop: mean 1.03 ms, 0.1 to 0.2 s of CPU per 2000 sleeps;
the oracle here: 1.43 ms, 0.13 s of CPU. If the helper cannot be started, a wait uses
the OS timer. Elsewhere, LazyOS included, `park_timeout` is used as it is: on LazyOS
`Thread.sleep(1)` has a median of 1.03 ms, `wait(1)` 1.10 ms and `parkNanos(100000)` 0.13 ms,
and two spinning threads take turns of 10 ms for the pair (QEMU, WHPX).

LazyOS's monotonic clock is the count of 10 ms timer ticks plus the calibrated TSC's
progress within the tick, so its resolution is 1 ns, or 10 ms if the TSC could not be
calibrated. It never goes back, but it can stand still: the fraction is capped just
below one tick, so with the timer interrupt late (a stalled guest), it waits for the
tick and then catches up. No test line can flip on that for being too short: `sleep`,
timed `wait` and `parkNanos` loop until `Instant` has passed the deadline, whatever
the OS wait did, and `System.nanoTime` reads the same `Instant`, so a lower bound on
the elapsed time of such a wait holds by construction (`LockEdges` "flagged park",
`LockSupports` "permits do not stack" and "parkUntil waits", `Interrupts` "timed wait",
`TimeUnits` "sleep small"). `parkUntil` starts from the wall clock, read twice a few
instructions apart: only stepping the clock in between could shorten it.

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
  main thread's, with the same `Options::max_nesting` (2400, decisions.md D15) for
  calls from Rust into Java: one such call was measured at under 8 KiB of stack, and 3.6 MiB
  for all 2400 on Windows, so reflective recursion goes as deep on a started thread
  (`DeepThread`, `lazy/internal/CallNesting`).
- Java frames use no OS stack, and each thread may have 16384
  (`Options::max_frames`, decisions.md D15; `-Xss` sets it, at 16384 per MiB, so
  `-Xss1m` is the default). A frame of a small method costs about 245 bytes of Rust
  allocations (`DeepThreads`: 406 MB for 100 threads at the bottom at once, 1982 MB for
  500; the oracle 143 MB and 604 MB). So a thread at the limit holds about 4 MB, and
  after it unwinds keeps the 1.8 MB or so of its frame table until it ends.
  `max_frame_slots` bounds a thread with very large frames, at 4M slots of 16 bytes.
  An overflow takes 2.1 ms (oracle 0.9). A collection takes 128 ns per frame on the stack: 2 ms
  with one thread 16000 frames down (`DeepGc`; `System.gc()` on the oracle is about as long at
  any depth), and it comes only as allocation asks for it: 20000 allocations of 64 KiB
  take the same at depth 10 and at 16000. Inside LazyOS (QEMU, WHPX, 1 GiB), an overflow takes
  2.7 ms and a collection at 16000 frames 3.1 ms; 120 threads at the limit at once work, and
  300 end the process (status 137, for lack of memory): nothing in the suite goes there.
- An exception out of the uncaught exception handler is reported on stderr, in the
  oracle's words, by `lazy.internal.VM.handlerFailed`, a native that allocates nothing,
  as the handler may well have failed for lack of memory (`HandlerFailsThreads`). The main
  thread's goes back to the VM (`Vm::report_handler_failure`, from `finish` in the
  launcher), because with the heap full it can come before any handler has started:
  `Thread.currentThread` may have to make the main thread's object (`HandlerFailsFullHeap`,
  `HandlerFailsMain`, and `HandlerReturnsFullHeap`, where nothing failed and nothing is said).
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
- A thread can read another's stack (`Thread.getStackTrace`, `Vm::thread_backtrace`)
  without stopping it, because the reader holds the VM and so every other thread's
  frames sit still in its entry. Natives have no frames: a blocked thread's top frame is
  still at the call, and the trace adds the native from that (`Thread.sleep(Native
  Method)`). `getAllStackTraces` reads one thread at a time, so it is no one instant.
- A `ThreadGroup` lists its started threads and its subgroups, in plain arrays, and a
  thread leaves before its joiners wake. There is no `DestroyJavaVM` thread: after main
  has returned, `Thread.activeCount` in another thread is one lower than on the oracle
  (which lists it there, observed). It and the system group's own VM threads are why the
  oracle's main and system groups refuse `destroy`; here that is a plain check. One
  difference stays: a daemon main group with nothing else in it ends with main on the
  oracle, and the process then exits at once, with no wait for other threads and no
  shutdown hooks (observed). Here it stays and the process ends as usual.
- `ThreadGroup.destroy` and a daemon group's end follow the oracle, including its odd
  parts (`tests/java/thread/GroupDestroy`, `GroupDaemon`). A thread counts as unstarted
  from the top of its constructor, even one that then fails, and holds a daemon group
  until it starts; an explicit `destroy` ignores it, and the thread's `start` then throws.
  A `destroy` that meets a busy subgroup throws but is not undone: the group stays
  destroyed and listed in its parent, with its unreached subgroups cut off; listing
  through it restarts `enumerate`'s count from 0, and only `getAllStackTraces`, which asks
  the VM and not the groups, still finds the threads below. `list` and `interrupt` hold
  a group's lock while they call a subclass's `toString` or `interrupt`, and read the
  list live, as the oracle does. Otherwise
  locks are taken one at a time, so `destroy` going down and a thread's end going up cannot
  deadlock. So a subgroup that ends itself just as its parent's `destroy` reaches it makes
  that `destroy` throw.
- The main thread's context class loader is the system loader; a new thread takes its
  parent's through `getContextClassLoader`, as subclasses can see. It survives the thread.
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
- **Fairness.** A release normally frees the monitor and wakes the oldest
  entrant to compete; it stays first on the list until it gets in, and its own
  release wakes the next. That alone starves them: the releasing thread keeps
  the VM, so a thread looping on `synchronized` re-enters before a woken
  entrant ever runs, and when it finally yields at a safepoint it usually owns
  the monitor again (3 threads looping on one lock got 12912, 1 and 1 turns in
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
`NoClassDefFoundError: Could not initialize class ...`, except for a library
class that ran out of heap or stack ("Out of memory", below). A thread waiting for
another thread's initializer reports `RUNNABLE`, before and after an
interrupt, as Java 8 does (`ClinitState`); the wait is not interruptible.

## Out of memory

An allocating bytecode that cannot fit even after collecting (soft references
included) is `OutOfMemoryError`, on any thread. The limit is a count of bytes, not
the memory itself, so nothing is set aside. Whatever a program does, the heap stays
below twice the limit (`Heap::alloc`), plus the handler's room and the one exception
below, a fixed small amount. So the *heap's* host memory is bounded for a given `-Xmx`.
What `-Xmx` does not count: thread stacks (64 MiB reserved per thread), frames (up to
16384 per thread, about 4 MB, see "Thread lifecycle"), and the VM's Rust allocations,
such as classes, the slot table and the thread table.

- **The error itself.** Observed on Java 8, and the same here (`OutOfMemoryErrors`,
  in the test's first lines): the first two `OutOfMemoryError`s the VM makes are new
  objects, whatever their message, with a trace of the innermost 32 frames. After that
  there is one shared object per message (`Java heap space`, `Requested array size
  exceeds VM limit`), with no trace, and its trace cannot be set or filled in. A
  program that catches and keeps them gets nothing new to keep. On all of them, and on
  a VM-made `StackOverflowError`, the cause is null and cannot be set (`initCause`
  throws `IllegalStateException`), and `addSuppressed` does nothing.
- **The shared objects** are made at the first shortage (`Vm::shared_oom`), of library
  classes only, with no limit at all for that one call (`Heap::unlimited`), so that
  they exist from then on, wherever that shortage struck: in a native that took the
  heap to twice the limit, say (`OutOfMemoryInNative`).
- **Any other exception** the heap has no room for turns into that error, as on the
  oracle (`NullPointerException`, `StackOverflowError` lines of `OutOfMemoryErrors`).
  The same goes for a shortage while an exception is made, so a shortage cannot nest
  and exception creation can always end. Nothing else gets past the limit.
- **A trace** holds the innermost 1024 frames at most, as on the oracle, so its size
  has nothing to do with the depth of the stack or with `-Xss` (`OutOfMemoryDeepStack`).
- **Library class initializers** have the limit too. A program that retains memory
  run from one, as `ForkJoinPool`'s does through its factory property, gets no pass
  (`OutOfMemoryErrors`, "stayed within the limit"). One that fails for lack of memory,
  or of stack, goes back to not being initialized, so the next use tries again: it
  would otherwise be broken for good, while on the oracle such classes are mostly
  initialized at startup. That takes the shortage arriving as such: a plain pattern
  compiled near the end of the stack gets the `StackOverflowError`, not a syntax error
  (`lazy/internal/InitOverflow`, and docs/regex.md for the oracle's band of syntax errors). A `println` as the first output, in a handler at the bottom
  of a stack overflow, would have ended the program with no output at all
  (`StackOverflowFirstUse`). Only library code can tell, and JVMS 5.5 steps 10 to 12 still hold for a
  program's own classes: an initializer that runs out of memory throws that error and
  leaves its class erroneous, and later uses throw `NoClassDefFoundError`, as on the
  oracle (`Fails`, `FailsToo` in `OutOfMemory`).
- **A handler that fails**: its line on stderr names the thread in modified UTF-8, as on the
  oracle: a NUL in two bytes, a surrogate pair as two surrogates of three bytes each, a lone one
  as it is (`HandlerFailsThreads`, "odd name ended").
- **The handler**: an `OutOfMemoryError` makes 64 KiB more available, counted from
  the limit or from what the heap holds if a native took it past that, as a handler
  usually reports what it caught. Each collection brings that down to 64 KiB past what
  it found live, and at most to the limit: so a native's peak does not stay available to
  bytecode (`OutOfMemoryHandlerRoom`, and in the heap's tests
  `the_handler_has_room_wherever_a_native_took_the_heap`,
  `the_handler_room_follows_what_a_collection_finds_live`). On the oracle, slack in its generations gives the same. It is one
  fixed amount: a loop that catches the error and retains more runs out, and
  so does a handler reached after library code swallowed earlier errors
  (`Retain composeDone` gets a second, uncaught `OutOfMemoryError` there).

A collection is always some way off after one (`MIN_GC_STEP`), even when live
data is over the limit, or every instruction would collect until it was not.

Below 1 MiB, the launcher refuses a heap as the oracle does, with its message
(`tests/launcher`: `xmx_too_small`, `xmx_one_byte`); 1 MiB is enough to start, run
out and report it (`OutOfMemorySmallestHeap`).

Not the same as the oracle, and not pinned:

- After a native overshoots the limit, the heap can stay above what the oracle retains: at `-Xmx32m`, natives reach 62 MB, and the program can then hold 40 MB, where the oracle holds 28 MB. Bytecode adds nothing past that.

- A retried library initializer runs again, with any program code it calls. The common
  pool's `threadFactory` property names such code: run out of memory in its constructor,
  and here the next `commonPool()` runs it again, and may then work, where the oracle
  ran it once and throws `NoClassDefFoundError` from then on. Kept on purpose: the same
  retry is what keeps `println` working after a shortage, and it cannot tell the cases
  apart. A partial initialization by such code can be seen twice.
- Its class loading needs the Java heap, so a class first *loaded* with the heap full
  fails to load, without being marked. Here loading uses no heap, so its initializer
  runs and the class may become erroneous, or may work. The test loads its classes
  first.
- With the heap full, the handler room can make an exception here that the oracle
  replaces with its error (`ArithmeticException` against `OutOfMemoryError` in the same
  place), and a library class it initialized at startup can make `println` fail here
  with the heap held full (no test keeps it so).
- Its messages may say `GC overhead limit exceeded`, so no test pins one for small
  objects, and when it stops making new errors could in principle depend on more than
  the count seen here; two is what every run showed.

## Cost on one thread

Threads add little work on one thread: a relaxed load at back-branches and
calls, a test of the method's `synchronized` flag on a call, a test of the
frame's monitors on a return, a larger `Frame`, and a real `synchronized`.
S6-01/02 nevertheless made one thread 3.4% to 5.7% slower, and almost none of
that was this work. Most of it was the compiler's response to it, and that is
now removed.

CPU time, best of 5, all builds interleaved in one run, pinned to one P-core
(logical CPU 12) at high priority, Windows release builds. "S6" is be48d6e,
"fixed" is that with the changes below, and both are against efd9986. Other
sessions' builds and tests were running on the machine throughout; runs of
one build usually stayed within 2% to 3%, with the odd slower outlier, which
best-of discards:

| Test | main | S6 | fixed |
|---|---|---|---|
| `ScannerLong` | 6.47 s | +3.4% | -4.3% |
| `RegexDepth` | 9.16 s | +5.1% | -3.2% |
| `RegexMemoStarts` | 10.50 s | +5.7% | -1.9% |
| `CharacterTables` | 9.00 s | +5.4% | -1.4% |
| `GcChurn` | 7.84 s | +4.4% | -0.6% |
| `CaseMapping` | 35.91 s | +3.9% | -1.4% |

Part of "fixed" is the release profile's one codegen unit, which speeds up
main too: against main built the same way, fixed is -3.6% to +0.7% (by median,
-2.7% to -0.4%). So the threads cost itself is gone from these tests, and
inlining more of the loop than main did more than pays for it.

### The cause

Callgrind counts on the Linux build, which do not depend on the machine's
load, showed the cost was real instructions: +4.06% on `ScannerLong`, +6.28%
on `CharacterTables`, +3.55% on `RegexDepth`.

- **Inlining tipped over.** Bisecting a tenth-size `ScannerLong` on
  instructions, over the commits squashed into be48d6e, gave +0.72% at bf9616e
  and +4.18% at 875a147, whose only change on the hot path moved a
  `synchronized` check into `lock_pushed_frame`. That function stayed out of
  line, and the same build's dispatch loop lost `op_array`, `Frame::push`,
  `Frame::u1` and `current_class`, on paths with no connection to it: 13M calls
  to `push` became 20M.
- **`Flow::Poll`.** A third result the loop tested after every instruction,
  though only branches and calls ever return it. Polling where the safepoint
  is, in `op_branch` and `call`, returns `Flow` to two variants and takes 0.8%
  of the instructions off the short `ScannerLong`.
- **Code splitting.** The rest did not show as instructions. With instructions
  level with main (+0.04%), `CharacterTables` still took 3.5% longer on Linux
  and 5% on Windows. Block alignment forced on (`-align-all-nofallthru-blocks=6`)
  brought that to +2.1%, and one codegen unit to 0.0%. With the default 16
  units, the split of the interpreter between them moves with unrelated changes.

Not the cause: the class library (`ScannerLong` on the new VM with main's
`lazyrt` costs the same), and, measured before this work, the 8 bytes per heap
slot.

### The fix

- The flag tests on calls and returns are `#[inline(always)]`
  (`lock_pushed_frame`, `lock_for_call`, `check_balanced`), with the locking in
  `#[cold]` functions. `safepoint` is `#[inline]` with a `#[cold]` yield.
- `Frame`'s dispatch accessors, `Value`'s typed reads, `pop_frame`, `branch_if`
  and `current_class` are `#[inline]`. These are hints: they make the loop much
  less sensitive to the inliner's budget, but nothing enforces the result.
- `Flow::Poll` is gone.
- `codegen-units = 1` in the release profile: smaller too (`lazyvm.exe` 1.78 MB
  to 1.66 MB), for about 2 s more build.

### Measuring it next time

Timing here shifts with core type (E-cores are 20% slower) and load, which is
why an earlier session saw 0.3% to 1.4% for nearly the same code. Pin to a
P-core, interleave the builds, and check instructions with callgrind on the
static Linux build. Instructions and time can disagree, as above, so one does
not replace the other.

Monitors themselves do cost something: a loop doing nothing but `synchronized`
methods and blocks (80M entries) is 9.9% slower than main, where a monitor was
a null check, and it was 14.9% before this fix.

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
