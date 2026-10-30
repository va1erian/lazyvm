# `lazy.async` and `lazy.net`: the event loop and async TCP

Status: design for S6-06 (`lazy.async`) and S7-01 (`lazy.net`) in
[`plan.md`](plan.md). Code waits for S6-01 to S6-05 (threads, monitors,
`CompletableFuture`), which another branch is writing.

## Why and what

Small programs should do network I/O in a few readable lines, with no
threads to manage and nothing to learn but the standard `CompletionStage`,
which every operation returns. The whole public API is four types:

| Type | What it is |
|---|---|
| `lazy.async.Loop` | the process's one event loop: its executor and timers |
| `lazy.net.Tcp` | the front door: `connect` and `listen` |
| `lazy.net.Connection` | one TCP stream: `read`, `readAll`, `write`, `close`, `peer` |
| `lazy.net.Listener` | a listening socket: `port`, `close` |

A client:

```java
import lazy.net.Tcp;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Fetch {
    public static void main(String[] args) {
        byte[] request = "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n".getBytes(UTF_8);
        Tcp.connect("example.com", 80)
           .thenCompose(c -> c.write(request).thenCompose(v -> c.readAll()))
           .whenComplete((page, error) -> {
               if (error != null) System.err.println("fetch failed: " + error.getCause().getMessage());
               else System.out.println(page.length + " bytes");
           });
    }
}
```

A server:

```java
import lazy.net.Connection;
import lazy.net.Tcp;

public class Echo {
    public static void main(String[] args) {
        Tcp.listen("0.0.0.0", 7000, Echo::serve)
           .whenComplete((l, error) -> {
               if (error != null) System.err.println(error.getMessage());
               else System.out.println("echo on port " + l.port());
           });
    }

    static void serve(Connection c) {
        c.read().thenCompose(bytes -> bytes.length == 0
                ? c.close()
                : c.write(bytes).thenRun(() -> serve(c)))
         .exceptionally(error -> { c.close(); return null; });
    }
}
```

A timer:

```java
import lazy.async.Loop;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class Later {
    public static void main(String[] args) {
        Loop.delay(500, MILLISECONDS).thenRun(() -> System.out.println("half a second later"));
    }
}
```

`main` returns at once in all three; the program lives as long as the loop
has work ("Lifetime" below). A stage the API returns fails with the
`IOException` itself; a stage built from it (`thenCompose` and so on) fails
with a `CompletionException` around it, hence `getCause()` in `Fetch` and
not in `Echo`. `Echo` listens on every interface (`"0.0.0.0"`), which also
works on LazyOS ("LazyOS" below).

## `lazy.async.Loop`

A final class with static methods only; there is one loop per process,
started on first use.

- `static Executor executor()`: the loop's thread. Tasks run there one at a
  time in the order they were given; a task that throws is reported to the
  thread's uncaught exception handler and the loop goes on.
- `static CompletionStage<Void> delay(long time, TimeUnit unit)`: completes
  after at least that long (monotonic clock). A zero or negative delay
  completes on a later turn of the loop.

That is the whole class. Running a task on the loop is
`Loop.executor().execute(task)` or `CompletableFuture.runAsync(task, Loop.executor())`,
so `Loop` has no `submit` of its own.

**Where code runs.** Every stage this API returns is completed on the loop's
thread, and no stage is complete when the call that made it returns (a
second `close()` included: it returns a new stage that completes on the loop
after the first). Under
`CompletableFuture`'s rules, an action attached to a pending stage runs on
the thread that completes it, so on the loop; an action attached after the
stage completed runs at once on the attaching thread. Handlers attached
inside other handlers, as in `Echo`, are therefore always on the loop. A
program whose handlers share mutable state starts on the loop
(`Loop.executor().execute(() -> ...)` from `main`) so that every attach
happens there too. Code that must run elsewhere says so with the standard
`*Async(action, executor)` methods, for example `Ui.executor()` in S9.

One exception: a stage settled from outside (`cancel`, `complete` and the
rest, see "Cancellation") completes, and runs its dependents, on the thread
that settled it, since `cancel` must report the stage cancelled before it
returns. To keep handlers on the loop, settle from the loop:
`Loop.executor().execute(() -> f.cancel(true))`, as a timer's handler
already does.

**Do not block on the loop's thread.** A handler that sleeps, joins or does
blocking file I/O holds up every other completion. Blocking work goes to
another executor (`CompletableFuture.supplyAsync(work)`); the loop does not
count it as pending, and the common pool's threads are daemons, so `main`
`join`s such work or runs it on a non-daemon executor of its own.

## `lazy.net`

### `Tcp`

- `static CompletionStage<Connection> connect(String host, int port)`:
  resolves `host` (a name, or an IPv4 or IPv6 literal) and tries each address
  in the order the resolver gives them until one connects. Addresses of a
  family the platform lacks are skipped. Each attempt gives up after 30 s;
  the stage fails with the last attempt's error (naming `host:port`), or
  `no usable address: host` when every address was skipped.
- `static CompletionStage<Listener> listen(String host, int port, Consumer<Connection> onAccept)`:
  binds every address `host` resolves to, on one port (`"localhost"` is
  127.0.0.1 and ::1, `"0.0.0.0"` every IPv4 interface). With port 0 the
  first address gets a free port and the others bind that port; if one of
  them finds it taken, everything is closed and the whole bind is retried
  with a new free port, up to 8 times. An address family the platform lacks
  is skipped; any other error closes what was bound and fails the stage, as
  does having nothing to bind. The stage completes before the first `onAccept`,
  which then runs on the loop's thread for each connection, in arrival
  order, until the listener is closed. An `onAccept` that throws is reported
  to the uncaught exception handler, that connection is closed, and the
  listener goes on.

Arguments are checked in the call: a null `host` or handler is a
`NullPointerException`, a port outside 0-65535 (1-65535 for `connect`) an
`IllegalArgumentException`. Everything that needs the network fails the
stage instead.

### `Connection`

Safe to use from any thread.

- `CompletionStage<byte[]> read()`: the next bytes that arrive, 1 to 64 KiB
  of them, or an empty array at end of stream, every time once the stream
  has ended. One `read` or `readAll` may be pending at a time; starting a
  second is an `IllegalStateException`. The slot is free again before the
  stage completes, so a handler may start the next read at once.
- `CompletionStage<byte[]> readAll()`: everything up to end of stream. Past
  16 MiB it closes the connection and fails, so a hostile peer cannot fill
  the heap; bigger bodies are read in a loop with `read`.
- `CompletionStage<Void> write(byte[] bytes)`: completes when all the bytes
  are handed to the operating system. Writes may overlap; they go out in
  call order. The array is copied in the call, so the caller may reuse it.
- `CompletionStage<Void> close()`: no more reads or writes start (they fail
  with `IOException`, `closed`). A pending `read` completes with end of
  stream; a pending `readAll` fails, since its body is cut short. Queued
  writes are sent, then the sending side is shut down, and what the peer
  still sends is discarded until it closes too, so our last bytes are not
  lost to a reset. After 10 s, or at once if the stage is cancelled, the
  socket is closed as it stands and writes not yet sent fail. The stage
  completes when the socket is closed. A second `close()` returns a new
  stage that completes after the first.
- `String peer()`: the remote address as `host:port` with the numeric host
  (`127.0.0.1:52144`, `[::1]:7000`).

There is no half-close (`shutdownOutput`) yet: HTTP/1.0 and line protocols
do not need it, and S15's `Socket` brings it.

`Connection` is not `AutoCloseable`: its `close` returns a stage, and
try-with-resources does not fit code that returns before the I/O is done.

### `Listener`

- `int port()`: the bound port (useful after `listen` on port 0).
- `CompletionStage<Void> close()`: stops accepting. Connections already
  handed to `onAccept` stay open.

## Rules

**Backpressure.** Nothing is read until the program calls `read`. A peer
that sends faster than the program reads fills the operating system's
buffers and then waits; memory does not grow. On the write side, the stage
is the signal: a writer that waits for each `write` before the next stays
within one buffer.

**Errors.** A failed operation completes its stage exceptionally with a
`java.io.IOException` whose message names what failed and where. Errors
of `connect` and `listen` name the host and port as the caller gave them
(`connection refused: example.com:81`, `connect timed out: example.com:80`,
`unknown host: nosuch.invalid`), so goldens do not depend on what a name
resolves to; errors on a connection name its `peer()`
(`connection reset: 10.0.0.2:7000`); and some name no address
(`too many sockets`). The text is ours and the same on both platforms, so
`lazy.*` goldens can print it. `lazy.net` declares no exception types and
uses no `java.net` ones (S15 adds those).

**Cancellation.** `stage.toCompletableFuture().cancel(true)` reaches the
platform: a cancelled `delay` removes its timer, a cancelled `connect`
abandons the attempt (or drops its lookup's answer) and closes its socket,
and a cancelled `read`, `readAll` or `write` aborts the connection (a
half-done read or write leaves the stream in no useful state): the socket
is closed at once, without the `close()` sequence, and queued writes fail. The same
holds for `complete`, `completeExceptionally`, `obtrudeValue` and
`obtrudeException` called on a returned stage: an operation finished from
outside is withdrawn and stops counting as pending. Cancelling a stage made
from one of these (`thenApply` and so on) cancels only that stage, as
`CompletableFuture` defines. A timeout on a read is a timer that cancels it:

```java
CompletableFuture<byte[]> line = c.read().toCompletableFuture();
CompletableFuture<Void> timer = Loop.delay(5, SECONDS).toCompletableFuture();
timer.thenRun(() -> line.cancel(true));
line.whenComplete((bytes, error) -> timer.cancel(true));
```

**Lifetime.** The loop keeps the VM running while it has work pending: a
task, a timer, a connect, a read or write in flight, a close still sending,
or an open listener. An open connection with nothing pending does not. When
nothing is pending and `main` has returned (and no other non-daemon thread
runs), the VM exits as usual. Handles that are never closed stay open until
the VM exits, as files do (`design/files.md`).

## Inside

```
 Java threads ──natives──► reactor (Rust) ──completions──► dispatcher "lazy-loop" (Java, daemon)
                             │  ▲                              completes futures, runs tasks
                             ▼  │
                       resolvers (Rust, blocking DNS)       keeper (Java, non-daemon while pending)
```

- **The reactor** is a native thread that never runs bytecode and makes every
  socket system call. It creates, on itself after it starts, a `mio::Poll`, the sockets (in a numbered
  handle table, like `platform::files`), a min-heap of timers, and a queue of
  requests the natives push and wake it for (`mio::Waker`). It parks in
  `Poll::poll` with the next timer as its timeout, so an idle loop uses no
  CPU. `mio` reports edges, so the reactor remembers each socket's readiness
  and tries a requested read or write at once when the socket is already
  ready. An `accept` that fails for lack of descriptors (`EMFILE`, `ENFILE`)
  is retried on a 100 ms timer, since no new edge would come.
- **The resolvers** are up to four native threads, started on demand by
  names that are not literals, that run the platform's blocking lookup
  (`ToSocketAddrs`: the Windows resolver; on LazyOS musl's, querying the DNS
  servers `netd` writes to `/etc/resolv.conf`) and post the addresses back to
  the reactor. Four keep one dead server from stalling every other lookup;
  there is no cache.
- **The dispatcher** is the Java thread behind `Loop.executor()`, one daemon
  thread for the life of the VM, so `Thread.currentThread()` and
  `ThreadLocal`s on the loop stay put. It blocks in a native `take` (a
  GC-safe native region, S6-01) until the reactor posts completions or Java
  code queues a task, completes the matching futures, runs the tasks, and
  catches what they throw.
- **The keeper** makes "Lifetime" hold with no special case in the VM. One
  lock guards a `pending` count. Starting an operation or queueing a task
  takes it, adds one, and starts a keeper if none is alive, before the call
  returns. The dispatcher subtracts only after a completion's handlers have
  run, so work a handler starts is counted first. A keeper is a non-daemon
  thread that waits on the lock until `pending` is 0, marks itself gone and
  ends; it touches nothing else, so a new one overlapping an old one is
  harmless.

Every stage is a `lazy.internal.LoopFuture`, a `CompletableFuture` subclass
that overrides `cancel`, `complete`, `completeExceptionally`,
`obtrudeValue` and `obtrudeException`. Each operation settles exactly once:

- Operation ids come from a 64-bit counter and are never reused. One table
  maps ids to their futures, under the same lock as `pending`.
- Whoever removes the id from the table settles it: the dispatcher on a
  completion, or an override called from outside. On a future that is
  already done, the overrides keep `CompletableFuture`'s contract by
  delegating to `super` (so a second `cancel` returns true, `complete`
  returns false, and `obtrude*` force the result); while the id is gone but
  the future is not yet done, `cancel`, `complete` and
  `completeExceptionally` return false.
- An override that wins calls `Reactor.cancel(id)`, completes the future
  through `super`, and subtracts from `pending` only after that returns, so
  work its handlers start is counted first. The dispatcher completes through
  `super` too.
- A completion whose id is gone still carries what the reactor made, and
  the dispatcher releases all of it: it closes a connected, accepted or
  listening socket, so a cancel that races a success leaks nothing. For a
  `read`, `readAll` or `write` it aborts the connection (and frees the read
  slot), since the bytes are lost or were sent after all; either way the
  cancel's promise, an aborted connection, holds whoever won.
  `Reactor.cancel` of an id the reactor has already finished does nothing.

S6-05's `CompletableFuture` must not call its own public completion methods
(`complete`, `completeExceptionally`, `cancel`, `obtrude*`) from one another
through virtual dispatch, or these overrides would run for its internal
completions too; the S6-05 item says so.

The natives are in `lazy.internal.Reactor`, used by `lazy.async` and
`lazy.net` only: each request (`connect`, `listen`, `accept`, `read`,
`write`, `close`, `timer`) returns an operation id, `cancel(id)` withdraws
one, and `take` fills arrays with a batch of `(id, status, value, payload)`
completions, where the payload is the bytes of a read or the text of an
error. Read bytes are copied once, from the reactor's buffer into the new
`byte[]`; the reactor reads only for a pending `read`, which is what makes
backpressure hold.

### Windows

`mio` on IOCP, as published. Nothing is Windows-only in our code. Closing a
socket that still holds unread input makes Windows send a reset, which the
`close()` sequence avoids by reading to the peer's end first.

### LazyOS

The same `mio`, through the Linux ABI: `epoll_create1`, `epoll_ctl`,
`epoll_wait` and an `eventfd` for the waker (LazyOS
`docs/architecture/processes.md`). Threads that `std` spawns share one
descriptor table (`CLONE_FILES`, same document), which the waker needs: Java
threads write the reactor's `eventfd`. LazyOS `networking.md` ("Not done")
still says a thread gets a table of its own; the two documents disagree, so
the S6-06 `Timers` test on LazyOS is the evidence either way. Sockets are non-blocking `AF_INET`
sockets served by `netd`; a non-blocking `connect` returns `EINPROGRESS` and
reports through `epoll` and `SO_ERROR`, which is what `mio` expects
(`docs/architecture/networking.md`). What differs from Windows, from that
document:

- **No IPv6.** `AF_INET6` is refused, so IPv6 addresses fail and `connect`
  moves on to the next; an IPv6 literal fails the stage, and `listen`
  binds IPv4 only.
- **No loopback.** A program cannot connect to its own machine, so tests
  that listen and connect in one process run on Windows only; on LazyOS
  they talk to the harness's host peers instead (see "Tests").
- **One socket table.** 64 sockets for the whole system, shared with every
  other program, and a closed socket counts until `netd` has finished it;
  past that, `connect` and `listen` fail with `too many sockets`. (The 8 per
  owner of `networking.md` is for Messenger socket clients, not Linux-ABI
  sockets.) A listener queues at most 16 connections.
- **Ports below 1024** are refused to every program, so `listen` on one
  fails.
- **Closing hands over to `netd`.** Dropping the descriptor is always
  graceful there: `netd` sends what was written and finishes the close in
  the background, so a socket closed after the 10 s limit or by an abort
  may still drain on the wire.
- **A closed host port is silence, not a refusal**, under QEMU's user
  networking on a Windows host, so such a connect ends in the 30 s timeout.

**Messenger joins in S10.** LazyOS's single park, one `wait` over
`WAIT_CALLS`, Messenger endpoints and an epoll `WAIT_FD` (LazyOS
`docs/architecture/call-completion.md`, `endpoint-fd.md`), is for Messenger
calls, and S6-06 and S7-01 make none. Completion queues and handle tables
belong to one thread, so in S10-02 the reactor thread owns every Messenger
handle and begins, drains and cancels every call itself; Java threads queue
call requests to it like socket requests. Its `Poll::poll` becomes that
`wait`, naming `mio`'s epoll descriptor (`Poll` is `AsRawFd`) as the
`WAIT_FD` and the next timer as the deadline, then drains `mio` with a zero
timeout. Because that code is GPL and lives in `crates/lazyvm-lazyos`,
`crates/platform` defines the park as a trait the reactor calls, its `mio`
implementation is the default, and the launcher installs
`lazyvm-lazyos`'s on LazyOS. It stays one reactor and one park.

## Dependencies and size

`mio` (MIT), with the `os-poll` and `net` features; on Windows the
`windows-sys` it brings (MIT or Apache-2.0; its `THIRD_PARTY.md` row gains
`platform` beside `gfx`), and on the musl build `libc` (MIT or Apache-2.0,
a new row). S6-06 records them in
`THIRD_PARTY.md`, passes `tools/licenses.py`, and reports the
`tools/size.py` delta in its PR; it is expected to be tens of kilobytes.

## Files

- `java/lazyrt/src/lazy/async/Loop.java`; `java/lazyrt/src/lazy/net/{Tcp,Connection,Listener}.java`;
  `java/lazyrt/src/lazy/internal/{Reactor,LoopFuture}.java`. The `lazy.*`
  API lives in `lazyrt` beside `lazy.gfx` until D10's `java/lazy-api`
  exists ([`gfx.md`](gfx.md), "Layout").
- `crates/platform/src/reactor.rs` (thread, poll, timers, requests, the park
  trait), `crates/platform/src/net.rs` (sockets, resolvers);
  `crates/natives/src/lazy_async.rs` (the `Reactor` natives).
- `tools/lazyos_test.py --net` (S7-01): boots the image with LazyOS's
  networking switch, adds the QEMU `hostfwd` rule that maps a host port to
  the guest's 47773 (as LazyOS `tools/net/run.py` does), runs the host peers
  of LazyOS `tools/net/hostpeers.py` (an echo server the guest reaches at
  `10.0.2.2:47771`, and the inbound client, started when the guest test
  prints its `listening` marker), and passes the addresses to the tests as
  system properties.

## Tests

Hand-written goldens (`lazy.*`, D9). "Both" means Windows and LazyOS.

- S6-06, both: `tests/java/lazy/async/Timers` (delays given out of order fire in
  time order; a zero delay runs after the call returns; delays set from
  `main` wake a reactor parked on a later timer, which on LazyOS also proves
  the shared `eventfd`), `Cancel` (a cancelled delay never runs, `complete` on one withdraws
  it, and each `cancel` or `complete` from outside reaches its `LoopFuture`
  override exactly once, which checks S6-05's no-self-call rule), `ExecutorOrder` (tasks run in the order
  given, on one thread, and a throwing task does not stop the loop), `Idle`
  (the reactor's CPU time over an idle 2 s is at most two ticks of the
  platform's thread clock, read by `Reactor.cpuNanos`, a test-only native:
  `GetThreadTimes` on Windows, the reactor itself calling
  `getrusage(RUSAGE_THREAD)` on LazyOS, whose `clock_gettime` has no
  thread clock), and the S6 gate `Pipeline`.
- S7-01, Windows: `tests/java/lazy/net/AsyncEcho` (listen on port 0, connect,
  echo, close both ends; each next `read` is started inside the previous
  one's handler), `ManyClients` (500 at once), `ConnectRefused` (a
  port whose listener was just closed), `Backpressure` (a peer that writes 8
  MiB to a reader that waits still completes, with bounded heap),
  `CancelRead`, `ReadTimeout` (the pattern above), `Ipv6Loopback`.
- S7-01, LazyOS (`--net`): `tests/java/lazy/net/HostEcho` (connect to the host
  echo server, echo 1 MiB, close), `HostInbound` (listen on `0.0.0.0:47773`,
  print `listening`, and serve the host's client). Both use literal
  addresses; name resolution on LazyOS is shown by the S7 gate, which
  connects to a live host by name.
