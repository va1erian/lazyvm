# `lazy.msg`: Messenger for LazyVM programs

Status: accepted for S10 in [`plan-2.md`](plan-2.md), second draft
(2026-11-13; the owner's decisions are section 11). It replaces the
annotation sketch D8 first had (`@Service`, `@Subscribe`, `@Deadline`,
handlers found by reflection) with types generated from the IDL. The first
draft (2026-11-11) was written before LazyOS's client API plan landed; this one follows what that
plan built: `service`, `errors`, `deadline`, `retry`, `scope` and `event` in
MIDL, a generated client and server per interface, and one topic
subscriber. Messenger itself is LazyOS's (`docs/messenger.md`,
`docs/midl.md`, `docs/client-api-plan.md` there); this note only says how a
LazyVM program sees it. Examples are in Siesta, the language most LazyOS
apps on LazyVM will be written in ([`siesta.md`](siesta.md)); section 9
shows that Java reads the same.

**Built (2026-11-13), on Windows:** `midlc --java` in LazyOS (S10-01's
generator, and its corpus test), and the runtime of sections 2 to 6 over the
loopback broker: calling, serving, callbacks and topics, checked by
`tests/java/lazy/msg` and `tests/siesta/msg`. **Not built:** the LazyOS
transport (`crates/lazyvm-lazyos`, the park, the label spike of section 7),
closing with `Ui.run`, `lazy-api.jar` and packaging (section 8), and every
check that runs inside LazyOS.

## 1. Goals

1. **The IDL is the protocol, for us too.** Names, ids, deadlines, resend
   policy, errors, scopes and topics come from the `.midl` file through
   `midlc`, as they now do for Rust and Rhai. A LazyVM program never writes
   a method id, a tick count or a topic string a `.midl` declares.
2. **Consuming and publishing are symmetric.** A client calls an interface;
   a service implements the same methods; a callback channel is an object
   on both sides; a topic is one object to publish to or subscribe to.
3. **Native in Siesta.** Every call is a `CompletionStage`, so `await`
   reads it top to bottom; a service method is an `async` method; a
   handler is a trailing lambda. Nothing needs a language feature Siesta
   cut (anonymous or inner classes, checked exceptions, try-with-resources,
   wildcard capture), and `siestac` gets no Messenger-specific code.
4. **No reflection, no annotations, no thread per call.** The generated
   codec knows every method; calls are begun and completed on the
   `lazy.async` reactor ([`async.md`](async.md)).
5. **The same program runs on Windows** against an in-process loopback
   broker, so every test and demo but the ones naming real LazyOS services
   runs in CI.

## 2. Consuming a service

```siesta
import os.lazy.echo.v1.*;
import os.lazy.notify.v1.*;
import lazy.msg.MessengerException;

var echo = Echo.connect();                    // returns at once; resolves on first call
System.out.println(await echo.echo("hi", 3));  // "hi" three times, from os.lazy.echo
var quick = echo.within(200);                 // the same connection, a 200 ms deadline

try {
    var id = await Notify.connect().notify(new NotifyArgs().level(Level.INFO).title("Saved"));
    System.out.println("toast ${id}");
} catch (MessengerException e) {
    System.out.println(e.getMessage());       // "os.lazy.notify.v1.Notify: timed out (ETIMEDOUT)"
}
```

- **`Echo.connect()`** is a static method of the generated client class.
  It names the registry entry the IDL's `service` line declares
  (`os.lazy.echo`), so the program never spells it. `connect(String name)`
  reaches the same interface served under another name (an app's
  `app.<id>.<name>`), and a service family (`service
  "os.lazy.net.nic/{ifname}"`) gets `connectInstance(String instance)`.
  There is no `Messenger.connect(Class)`: one way, no reflection.
- **Deadlines** come from the IDL (`deadline=`, or the interface's
  `default`); a method that states none, and a `oneway`, has no bound. `within(ms)` returns a proxy over the same connection with
  another bound, and `within(0)` means none. No call parks a thread, so
  unlike xui's `check_call_bounds.py` nothing forbids an unbounded call on
  the UI thread. No 5 s guess of our own any more.
- **Resend and reconnect** follow the IDL's `retry=`: the runtime resends a
  request the kernel says was never queued, resends a `retry=safe` one after
  the peer died, and otherwise fails the stage and reconnects on the next
  call (LazyOS `midl.md`, "Resending"). A service that restarts costs at most
  one failed call.
- **Cancelling** a returned stage cancels the call (`msg_cancel`), as
  `lazy.net` does for sockets.
- **Threads.** The reactor thread owns every Messenger handle and begins,
  drains and cancels every call; the stage completes on the reactor and
  `await` resumes on the caller's home, the UI thread in a window
  ([`siesta-async.md`](siesta-async.md) section 2). A Java caller that does
  not `await` gets the stage on the reactor, as with `lazy.net`.

## 3. Generated types

`midlc --java` turns `os.lazy.<name>.vN` into package `os.lazy.<name>.vN`
(not `java.*`, `javax.*` or `lazy.*`, so the namespace rule is untouched)
holding top-level types, so one `import os.lazy.confd.v1.*;` brings them all:

| IDL | Java |
|---|---|
| `interface os.lazy.confd.v1` | `Confd`: the client class (`connect()`, `connect(name)`, `within(ms)`, `serve(...)`, the `SERVICE` and `INTERFACE` constants); its name is the segment before the version |
| the same, served | `ConfdServer`: one method per call (not the `event`s), with the client's parameters |
| events, or an interface a `Channel<I>` carries | `<Base>Listener` (`EventsListener` for `os.lazy.notify.events.v1`): one `void` method per `event` or `oneway`, a `default` no-op unless it is the only one |
| `errors { NotFound = 1001, ... }` | `ConfdError` (an enum with `code()` and its sentence, the first line of its doc) and `ConfdException extends MessengerException` |
| `struct`, `enum` | a final class with public fields, fluent setters, `equals`, `hashCode`, `toString`; a Java enum (`OnAir` is `ON_AIR`) |
| `topic "system/confd/changed/{path...}" : Change` | `ConfdTopics.systemConfdChanged(String path)` and `systemConfdChangedFilter(String path)`, named after the literal segments (section 6) |
| a method with more than four parameters | `<M>Args`, a record with fluent setters; optional fields are left unset |
| a reply with several fields | `<M>Reply`, a record |
| the wire | `ConfdCodec`, public for the runtime, documented as not API |

Methods are the IDL names in lowerCamelCase. A reply of one field is that
field's type, of none `Void`, of several the `<M>Reply`; a `oneway` call
returns `CompletionStage<Void>` completed when the request is queued (so
`await` reports a dead peer, and fire-and-forget is just not awaiting).
Types: `Bool` is `boolean`, `I32` is `int`, `I64`, `U32` and `U64` are
`long` (`U64` read as unsigned, as `Long.toUnsignedString` prints it), `F64`
is `double`, `String` is `String`, `Bytes` is `byte[]`, `Array<T>` is
`java.util.List<T>`, `Option<T>` is a nullable box, an enum field is the Java
enum, `Buffer` is `lazy.msg.Buffer` (a shared range with a `ByteBuffer`
view), and `Channel<I>` is `I`'s listener (section 5). An absent field reads
as its zero: `""`, an empty list, the first variant, a fresh struct, null for
an option. Generic types are plain (`List<Action>`, never a wildcard), so
Siesta's one-way inference needs no casts. A name that is a Java keyword
gets a trailing `_`, and so does a method that would clash with a member of
`Object` or of the generated classes (`Wait(ms)` is `wait_`, beside
`Object.wait(long)`). A method with a `Ring` parameter is left out of the
Java classes: no LazyVM program needs bulk rings yet. A declared type that
would take a generated name (`ConfdCodec`) is a `midlc` error.

The codec is checked against LazyOS's conformance corpus
(`idl/conformance/`): `tests/java/lazy/msg/Conformance`, written by LazyOS's
`conformance_java.py`, encodes every sample to the corpus bytes and decodes
and re-encodes the corpus bytes. Samples that carry kernel objects are
skipped there until a transport gives objects their meaning.

## 4. Errors

Every failure completes the stage with `lazy.msg.MessengerException`
(`extends IOException`): `errno()` (`ETIMEDOUT`, `EPERM`, `EPIPE` for a dead
peer, `ENOSYS` for an unknown method, `EBADMSG` for a reply that does not
decode, `EINVAL` for a request that does not encode), and when the peer sent
the standard error field, `code()`, `hint()` and the peer's sentence. For an
interface with `errors`, a declared code arrives as its subclass, so the
common case is one `catch`:

```siesta
try {
    var v = await Confd.connect().get("sys/ui/theme");
} catch (ConfdException e) {
    switch (e.error()) {
        case NOT_FOUND -> useDefault();
        default -> status.text(e.getMessage());
    }
}
```

The message reads as LazyOS's: `os.lazy.confd.v1.Get: no such key
(NotFound)`. A cancelled call throws `CancellationException` from `await`,
which is not a failure.

## 5. Publishing a service

```siesta
import os.lazy.echo.v1.*;
import lazy.msg.*;

class EchoService implements EchoServer {
    async CompletionStage<String> echo(String text, long count) {
        var b = new StringBuilder();
        for (long i = 0; i < count; i++) b.append(text);
        return b.toString();
    }
    async CompletionStage<Boolean> ping() { return true; }
    async CompletionStage<Void> notify(Event e) { System.out.println(e.topic); }
}

var reg = Echo.serve("app.demo.echo", new EchoService());   // the IDL's name when omitted
```

- **`serve`** registers the name (an app may only take `app.<id>.<name>`;
  the IDL's `os.lazy.*` name is for platform services), and answers on the
  reactor through the codec: check the interface or scope id, decode, call,
  encode the stage's value, as LazyOS's generated `dispatch` does. Another
  interface or a bad body is `EINVAL`, an undeclared method `ENOSYS`, and a
  reply the service's value cannot be encoded into `EIO`. Serving a name that
  is taken throws an `UncheckedIOException` around an `EEXIST`.
- **An `async` method is the implementation.** A reply that comes later
  (LazyOS's C4b "held" replies: a `Stop` answered when the targets exit) is
  a stage that completes later, so no flag or transaction table is needed.
  Each call runs on the home of the thread that called `serve`, one at a
  time per service, so a service's state needs no lock.
- **`Caller.current()`** is the kernel-stamped credential (`uid`, `gid`,
  `caps`, `label`, `session`, `sender`) of the call being dispatched. It is
  not a parameter, so the many methods that never look at it stay short
  (the owner's choice, 2026-11-13). It is set while the method runs up to
  its first `await` and is `null` after, so a method that authorizes reads it
  first: `var who = Caller.current();`.
- **Faults.** A stage failing with a generated exception
  (`new ConfdException(ConfdError.NOT_FOUND)`) sends its code and sentence;
  `MessengerException.of(errno, message)` sends an errno; anything else sends
  `EIO` with its message, and the program goes on.
- **Every method is required**, as in LazyOS: the class implements the
  whole interface or does not compile. `event` methods are not in
  `<Base>Server`, so nothing needs an empty body for them.
- `reg.close()` withdraws the name, and calls in flight fail with `EPIPE`.
  An open registration keeps the program running, as a listening socket
  does.

**Callbacks and events.** A `Channel<I>` parameter is `I`'s listener type on
both sides. The caller passes an object; the runtime makes the channel pair,
moves one end and dispatches the events it receives to the object, on the
caller's home. The service receives an object of the same type that sends
each event as a `oneway`; `Peer.of(events)` gives its `close()` and a
`closed()` stage that completes when the client goes away:

```siesta
class Toasts implements EventsListener {             // os.lazy.notify.events.v1
    void activated(long id) { openDocument(id); }
    void closed(long id, long reason) { System.out.println("toast ${id} closed"); }
}
await Notify.connect().listen(new Toasts());

// A service of ours, sending events back to whoever listens:
async CompletionStage<Void> listen(EventsListener events) {
    listeners.add(events);
    Peer.of(events).closed().thenRun { listeners.remove(events); };
}
```

A listener with one event is a functional interface, so a trailing lambda
is the listener: `await svc.watch { change -> refresh(change); };`. Its
methods run on the client's home, in the order they were sent; once either
side closes the channel, further events are dropped.

## 6. Topics

```siesta
import os.lazy.healthd.v1.*;

var sub = HealthdTopics.systemHealthFilter(null).subscribe { e ->
    rows.put(e.topic(), e.value().status);
    if (e.value().status.equals("error")) Notify.connect().notify(new NotifyArgs().level(Level.ERROR).title(e.topic()));
};
await HealthdTopics.systemHealth("app.demo").publish(new HealthRecord().name("app.demo").status("ok"));
```

- Each `topic` declaration gives static methods named after its literal
  segments: `systemHealth(String name)` returns a `Topic<T>` (publish and
  subscribe), and, when the pattern has placeholders,
  `systemHealthFilter(String name)` a `TopicFilter<T>` (subscribe only),
  where a `null` placeholder is `+` and a `null` `{path...}` tail is `#`. A
  value with a wildcard character, or a `/` in a one-segment placeholder, is
  an `IllegalArgumentException`. A program never types a declared pattern.
- **`subscribe(handler)`** returns a `Subscription`; the handler gets an
  `Event<T>` (`topic()`, `value()`, `retained()`, `publisher()`) on the
  subscriber's home, one at a time, the retained value first. The QoS is the
  IDL's; `subscribe(Qos, handler)` overrides it: `LATEST` keeps only the
  newest event per topic not yet handled, `BUFFERED` keeps them all. A
  payload that does not decode is skipped with a line on stderr, as LazyOS's
  typed subscription consumes it.
- Delivery uses the broker's doorbell, as LazyOS's one subscriber does
  (`client-api-plan.md` 3.5): the reactor parks on the bell with its other
  endpoints and drains `NextEvent` only when it rang. Nothing polls.
- **`publish(value)`** returns a stage completed when the broker took it;
  `retained` is the IDL's. An app may publish only what its manifest grants
  (section 8).
- **Closing.** `Subscription.close()` ends one. An open subscription keeps
  the program running. Planned, not built: subscriptions and registrations
  made on the UI thread close when `Ui.run` returns, as a LazyRAD form's do.

There is one way to receive: a handler. A pull form (`await feed.next()` in
a loop) was considered and left out: it is a second way, and an `async`
handler already lets a step await.

## 7. The runtime

`java/lazyrt/src/lazy/msg/` holds the API (`MessengerException`, `Caller`,
`Peer`, `Buffer`, `Topic`, `TopicFilter`, `Event`, `Subscription`,
`Registration`, `Qos`) and `lazy/internal/msg/` what the generated classes
call: the wire (`WireWriter`, `WireReader`), the client (`Client`: deadlines,
the resend rule, cancelling), serving (`Server`), callback channels
(`Listeners`), topics (`Topics`, `TopicNames`) and the transport seam
(`Broker`). A `Broker` has two implementations:

- **LazyOS** (not built): `crates/lazyvm-lazyos`, over `libmessenger` and
  `lazyos-sys` (GPL, so it stays in that crate, D2), reached through a
  native seam in `crates/platform`. The reactor's park becomes LazyOS's one
  `wait` over `WAIT_CALLS`, the endpoints, the bells and `mio`'s epoll
  descriptor as `WAIT_FD` ([`async.md`](async.md)).
- **Loopback** (built, `Loopback.java`; the broker on Windows and wherever
  LazyOS's is absent): an in-process registry, calls in flight, topics and
  retained values in Java, with the rules a program can observe: an unknown
  name is `ENOENT`, a withdrawn one fails its calls with `EPIPE`, a retained
  value reaches a new subscriber first. It never pretends to be a LazyOS
  service: a `connect()` to a name nobody served here fails with `ENOENT`.
  Every caller is uid 0 there.

Stages complete on the loop's thread; deadlines are loop timers, cancelled
when the call ends, and a call with no deadline holds the loop open until it
ends, so a program never exits under a pending call. A call the kernel never
queued (`EPIPE`, not delivered) is sent once more; one a peer took and then
died with is sent again only when the IDL marks it `retry=safe`, resolving
the name again, so a restarted service is reached.

**A risk to settle first.** LazyOS found that Messenger handles and an app's
label belong to the thread that made them (`client-api-plan.md` 3.5: a
worker thread's `subscribe` failed with `EACCES`). Our reactor is a thread
the VM starts, so S10-02 begins with a spike: from a labelled app, the
reactor thread resolves, calls, serves and subscribes. If the label does not
reach it, the fix is LazyOS's (stamp the label per process) or ours (make
the reactor the thread `exec` started); that is decided before the rest of
S10-02 is built.

## 8. Packaging and permissions (planned, S10-05)

An app's manifest lists the services it calls or serves and the topics it
publishes or subscribes. `tools/lazyvm/package.py` (S10-05) finds them in
the jar: each generated client, server and topic class carries its
`SERVICE`, interface and pattern as constants, so scanning the constant
pools for references to generated classes finds them for Java and Siesta
alike, with no compiler support. Names built at run time are not found and
are listed by hand, as in LazyRAD.

The system interfaces ship as `lazy-api.jar`, generated from LazyOS's
`idl/` by its build and installed by the core package beside `lazyrt.jar`.
It is not part of `lazyrt.jar`, so it does not count against D14; its size
is reported in S10-05's PR. `siestac` and `lazyvm` put it on the class path
by default where it is installed. An app's own `.midl` is compiled with
`midlc --java` into the app's jar.

## 9. Java reads the same

```java
Echo echo = Echo.connect();
echo.echo("hi", 3).thenAccept(System.out::println);

final class EchoService implements EchoServer {
    public CompletionStage<Boolean> ping() {
        return CompletableFuture.completedFuture(true);
    }
    // ...
}
```

`tests/java/lazy/msg` (Java) and `tests/siesta/msg/MsgDemo` (Siesta) use
the same generated classes on the same runtime. Still to do (S10-03's
check): a Siesta client of a Java service and a Java client of a Siesta
service in one test, as the evidence that nothing here is Siesta-only or
Java-only.

## 10. Left out, and why

| Idea | Why not |
|---|---|
| `@Service`, `@Subscribe`, `@Deadline` (D8) | reflection on the wire path, and the IDL already says it |
| `import "foo.midl"` in `siestac` | `midlc --java` generates the same types once, for both languages ([`siesta-apps.md`](siesta-apps.md) section 2) |
| a scoped deadline block | `within(ms)` on a proxy |
| a pull form for topics | a second way; see section 6 |
| generated `ring` access | no LazyVM program needs it yet |
| blocking calls (`echo.echoNow(...)`) | a program that wants to block calls `join()`; one form per call |

## 11. The owner's decisions (2026-11-13)

1. The annotations go; Siesta is the target. D8 is reworded to this note.
2. No `Caller` parameter: `Caller.current()` (section 5), since passing it
   would lengthen every method for the few that authorize.
3. `oneway` calls return `CompletionStage<Void>`.
4. App services are named `app.<id>.<name>`, LazyOS's convention.
