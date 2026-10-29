# `lazy.async` and `lazy.net`: the event loop and async TCP

Status: design for S6-06 (`lazy.async`) and S7-01 (`lazy.net`) in
[`plan.md`](plan.md). Code waits for S6-01 to S6-05 (threads, monitors,
`CompletableFuture`), which another branch is writing.

## Why and what

Small programs should do network I/O in a few readable lines, with no
threads to manage and nothing to learn but the standard
`CompletionStage`, which every operation returns. The whole public API is
four types:

| Type | What it is |
|---|---|
| `lazy.async.Loop` | the process's one event loop: its executor and timers |
| `lazy.net.Tcp` | the front door: `connect` and `listen` |
| `lazy.net.Connection` | one TCP stream: `read`, `readAll`, `write`, `close` |
| `lazy.net.Listener` | a listening socket: `port`, `close` |

A client:

```java
import lazy.net.Tcp;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Fetch {
    public static void main(String[] args) {
        Tcp.connect("example.com", 80)
           .thenCompose(c -> c.write("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n".getBytes(UTF_8))
                              .thenCompose(v -> c.readAll()))
           .thenAccept(page -> System.out.println(page.length + " bytes"));
    }
}
```

A server:

```java
import lazy.net.Connection;
import lazy.net.Tcp;

public class Echo {
    public static void main(String[] args) {
        Tcp.listen("localhost", 7000, Echo::serve);
    }

    static void serve(Connection c) {
        c.read().thenAccept(bytes -> {
            if (bytes.length == 0) c.close();
            else c.write(bytes).thenRun(() -> serve(c));
        });
    }
}
```

A timer:

```java
Loop.delay(500, MILLISECONDS).thenRun(() -> System.out.println("half a second later"));
```

`main` returns at once in all three; the program lives as long as the loop
has work (see "Lifetime").

## `lazy.async.Loop`

A final class with static methods only; there is one loop per process,
started on first use.

- `static Executor executor()`: the loop's thread. Every stage this API
  returns completes there, so dependent actions (`thenApply`, `thenAccept`,
  ...) run there too, one at a time, in completion order. Code that must run
  elsewhere says so with the standard `*Async(action, executor)` methods, for
  example `Ui.executor()` in S9.
- `static CompletionStage<Void> delay(long time, TimeUnit unit)`: completes
  after at least that long (monotonic clock). A zero or negative delay
  completes on the next turn of the loop, never inside the call.

That is the whole class. Running a task on the loop is
`CompletableFuture.runAsync(task, Loop.executor())`, and blocking work goes
to `CompletableFuture.supplyAsync(work)` (the common pool), so `Loop` has no
`submit` of its own.

**Do not block on the loop's thread.** A handler that sleeps, joins or does
blocking file I/O holds up every other completion. The loop does not detect
it; tests and samples follow the rule.

## `lazy.net`

### `Tcp`

- `static CompletionStage<Connection> connect(String host, int port)`:
  resolves `host` (a name, or an IPv4 or IPv6 literal) and tries each address
  in the order the resolver gives them until one connects.
- `static CompletionStage<Listener> listen(String host, int port, Consumer<Connection> onAccept)`:
  binds `host:port` (`"localhost"` for this machine only, `"0.0.0.0"` for
  every interface; port 0 picks a free one) and calls `onAccept` on the
  loop's thread for each connection, in arrival order, until closed.

Arguments are checked in the call: a null `host` or handler is a
`NullPointerException`, a port outside 0-65535 (1-65535 for `connect`) an
`IllegalArgumentException`. Everything that needs the network fails the
stage instead.

### `Connection`

Safe to use from any thread.

- `CompletionStage<byte[]> read()`: the next bytes that arrive, 1 to 64 KiB
  of them, or an empty array at end of stream (the peer closed its side).
  At most one read may be pending; a second is an `IllegalStateException`.
- `CompletionStage<byte[]> readAll()`: everything up to end of stream. It
  fails with an `IOException` past 16 MiB, so a hostile peer cannot fill the
  heap; bigger bodies are read in a loop with `read`.
- `CompletionStage<Void> write(byte[] bytes)`: completes when all the bytes
  are handed to the operating system. Writes may overlap; they go out in
  call order. The array is copied in the call, so the caller may reuse it.
- `CompletionStage<Void> close()`: sends what is still queued, then closes.
  A pending read completes with end of stream; later reads and writes fail.
  Closing twice returns the same stage.
- `String peer()`: the remote address as `host:port` with the numeric host
  (`127.0.0.1:52144`, `[::1]:7000`).

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
`java.io.IOException` whose message names what failed and the address:
`connection refused: example.com:81`, `unknown host: nosuch.invalid`,
`connection reset: 10.0.0.2:7000`. The reason text is ours and the same on
both platforms, so `lazy.*` goldens can print it. `lazy.net` declares no
exception types and uses no `java.net` ones (S15 adds those).

**Cancellation.** `stage.toCompletableFuture().cancel(true)` reaches the
platform: a cancelled `delay` removes its timer, a cancelled `connect`
abandons the attempt and closes its socket, and a cancelled `read`,
`readAll` or `write` closes the connection (a half-done read or write leaves
the stream in no useful state). Cancelling a stage made from one of these
(`thenApply` and so on) cancels only that stage, as `CompletableFuture`
defines.

**Lifetime.** The loop keeps the VM running while it has work pending: a
timer, a connect, a read or write in flight, or an open listener. An open
connection with nothing pending does not. When nothing is pending and
`main` has returned (and no other non-daemon thread runs), the VM exits as
usual. Handles that are never closed stay open until the VM exits, as files
do (`design/files.md`).

## Inside

Three threads, one of them Java:

```
 Java threads ──natives──► reactor (Rust) ──completions──► dispatcher "lazy-loop" (Java)
                             │  ▲                              runs CompletableFuture completions
                             ▼  │                              and Loop.executor() tasks
                           resolver (Rust, blocking DNS)
```

- **The reactor** is a native thread that never runs bytecode. It owns a
  `mio::Poll`, the sockets (in a numbered handle table, like
  `platform::files`), a min-heap of timers, and a queue of requests the
  natives push and wake it for (`mio::Waker`). It parks in `Poll::poll` with
  the next timer as its timeout, so an idle loop uses no CPU.
- **The resolver** is a native thread, started by the first name that is not
  a literal, that runs the platform's blocking lookup (`ToSocketAddrs`: the
  Windows resolver, musl's on LazyOS through `netd`'s DNS) and posts the
  addresses back to the reactor. Lookups queue in order; there is no cache.
- **The dispatcher** is the Java thread behind `Loop.executor()`. It blocks
  in a native `take` (a GC-safe native region, S6-01) until the reactor
  posts completions or Java code queues a task, then completes the matching
  `CompletableFuture`s and runs the tasks. It is non-daemon while work is
  pending and exits when none is, which is how "Lifetime" holds with no
  special case in the VM; the next operation starts a new one.

The natives are in `lazy.internal.Reactor` (static, package-private use by
`lazy.async` and `lazy.net`): each request (`connect`, `listen`, `accept`,
`read`, `write`, `close`, `timer`) returns an operation id, `cancel(id)`
withdraws one, and `take` fills arrays with a batch of `(id, status, value,
payload)` completions, where the payload is the bytes of a read or the text
of an error. Read bytes are copied once, from the reactor's buffer into the
new `byte[]`; the reactor reads only for a pending `read`, which is what
makes backpressure hold.

### Windows

`mio` on IOCP, as published. Nothing is Windows-only in our code.

### LazyOS

The same `mio`, through the Linux ABI: `epoll_create1`, `epoll_ctl`,
`epoll_wait` and an `eventfd` for the waker, all of which the ABI has. Its
sockets are non-blocking `AF_INET` sockets served by `netd`; a non-blocking
`connect` returns `EINPROGRESS` and reports through `epoll` and `SO_ERROR`,
which is what `mio` expects (LazyOS `docs/architecture/networking.md`).
Two differences from Windows:

- **No IPv6 yet.** LazyOS refuses `AF_INET6`, so IPv6 addresses fail and
  `connect` moves on to the next address; an IPv6 literal fails the stage.
  S7-01's IPv6 checks run on Windows only until LazyOS adds it.
- **Messenger joins in S10.** LazyOS's single park, one `wait` over
  `WAIT_CALLS`, Messenger endpoints and an epoll `WAIT_FD` (LazyOS
  `docs/architecture/call-completion.md`, `endpoint-fd.md`), is for
  Messenger calls, and S6-06 and S7-01 have none. S10-02 replaces the
  reactor's `Poll::poll` with that `wait`, naming `mio`'s epoll descriptor
  (`Poll` is `AsRawFd`) as the `WAIT_FD` and the next timer as the deadline,
  then drains `mio` with a zero timeout. Endpoint descriptors can also sit in
  `mio` directly (`SourceFd`). Either way it stays one reactor and one park.

## Dependencies and size

`mio` (MIT), with the `os-poll` and `net` features, and on Windows the
`windows-sys` it already brings (MIT or Apache-2.0). Both are recorded in
`THIRD_PARTY.md` and pass `tools/licenses.py`. The S6-06 PR reports the
`tools/size.py` delta; it is expected to be tens of kilobytes.

## Files

- `java/lazyrt/src/lazy/async/Loop.java`; `java/lazyrt/src/lazy/net/{Tcp,Connection,Listener}.java`;
  `java/lazyrt/src/lazy/internal/Reactor.java`. The `lazy.*` API lives in
  `lazyrt` beside `lazy.gfx`; D10's separate `java/lazy-api` does not exist.
- `crates/platform/src/reactor.rs` (thread, poll, timers, requests),
  `crates/platform/src/net.rs` (sockets, resolver);
  `crates/natives/src/lazy_async.rs` (the `Reactor` natives).

## Tests

Hand-written goldens (`lazy.*`, D9), on both platforms unless noted:

- S6-06: `tests/lazy/async/Timers` (delays given out of order fire in time
  order; a zero delay runs after the call returns), `Cancel` (a cancelled
  delay never runs), `ExecutorOrder` (tasks and completions on one thread, in
  order), `Idle` (the reactor's CPU time over an idle 2 s stays under 1 %,
  read through a test-only `Reactor.cpuNanos`), and the S6 gate `Pipeline`.
- S7-01: `tests/lazy/net/AsyncEcho` (listen on port 0, connect, echo, close
  both ends), `ManyClients` (500 at once), `ConnectRefused` (a port whose
  listener was just closed), `Backpressure` (a peer that writes 8 MiB to a
  reader that waits still completes, with bounded heap), `CancelRead`,
  `Ipv6Loopback` (Windows only, see above).
