# `lazy.msg`: Messenger for LazyVM programs (proposal)

Status: proposal for S10 in [`plan-2.md`](plan-2.md). It replaces the
annotation sketch in [`decisions.md`](decisions.md) D8 (`@Service`,
`@Subscribe`, `@Deadline`, handlers found by reflection) with generated
types and plain objects, and needs the owner's confirmation before S10-01.
Messenger itself is LazyOS's (`docs/messenger.md`, `docs/midl.md` there);
this note only says how a LazyVM program sees it. Examples are in Java;
[`siesta-apps.md`](siesta-apps.md) shows the same in Siesta.

## 1. What hurts today

Read from LazyOS's own Rust clients and services at its current revision:

- **No typed client.** A call is connect, build an `XArgs` struct, encode,
  `call(INTERFACE_ID, METHOD_X, ERROR_FIELD, body, TICKS)`, decode, and map
  three kinds of error by hand (`xui-app/src/platform/notify.rs:122-148`).
- **No typed service.** A service is a receive loop and a
  `match method { … }` with decode, logic and encode in every arm, plus a
  manual "unknown method" arm (`xui-app/src/shell/notify/service.rs:184-229`).
- **Enums are `u32` constants** and every struct has a hand-written
  converter to the program's own type (`platform/print.rs:50-95`).
- **Deadlines are tick constants** at each call site (`notify.rs:23,195`).
- **Callbacks** need a channel pair, a moved end, a receive buffer and a
  polling loop (`notify.rs:209-270`); topics need a hand-run pull loop
  (`platform/topic_feed.rs`).

LazyRAD's Rhai binding (`docs/rhai/msg.md` there) already shows the target:
typed calls that return values, catchable errors, handlers that are just
functions, a global timeout, and cleanup when the form closes. `lazy.msg`
gives Java and Siesta the same, with static types.

## 2. Shape

Two parts, as S10 already splits them:

1. **Generated code** (S10-01, `midlc`'s Java backend in the LazyOS repo):
   for each `.midl` interface, a Java interface, its structs and enums, a
   codec, and topic accessors, packaged in `lazy-api-generated.jar`.
2. **The runtime** (S10-02, S10-03, `java/lazyrt/src/lazy/msg/`): connecting,
   serving, topics, deadlines and errors, on the `lazy.async` loop.

No reflection and no annotations: the generated codec knows every method,
so nothing is looked up at run time, and the wire path stays free of
`java.lang.reflect` (D8's own requirement).

## 3. Generated types

`os.lazy.echo.v1` becomes package `os.lazy.echo.v1` with:

```java
public interface Echo extends lazy.msg.Interface {
    String NAME = "os.lazy.echo.v1";
    CompletionStage<String> echo(String text, long count);   // one reply field: its value
    CompletionStage<Boolean> ping();
    void notify(Event event);                                // oneway: no reply, no stage
}
public final class Event { public String topic; public long at; /* fluent setters, equals, toString */ }
public enum Level { INFO, WARN, ERROR }
```

- **Methods** return `CompletionStage<T>`: the reply's value when it has one
  field, `Void` when it has none, and a generated reply class otherwise.
  `oneway` methods return `void`.
- **Arguments.** A method with up to four fields takes them positionally.
  One with more takes a generated arguments class with fluent setters, so
  optional fields are simply not set:
  `notify.notify(new NotifyArgs().title("Saved").body(path))`.
- **Types.** `Bool` is `boolean`; signed integers are `int` or `long` by
  width; unsigned ones are the next wider signed type (`U32` is `long`),
  `U64` is `long` read as unsigned; `String` is `String`; bytes are
  `byte[]`; lists are `List<T>`; optional fields are boxed and nullable;
  structs are final classes with public fields; enums are Java enums;
  `Channel<I>` is `I` (section 6); `Buffer` is `lazy.msg.Buffer`. The full
  table is fixed in S10-01 from LazyOS's `docs/midl.md`.
- **Topics** become static accessors on the interface's companion class:
  `ClipboardTopics.changed(String session)` returns a `Topic<OfferMeta>`
  with the declared QoS and retention.
- **The codec** (`EchoCodec`, package-private in spirit, public for the
  runtime) encodes and decodes each method's arguments and reply over the
  `libmessenger` TLV wire, and dispatches an incoming call to an
  implementation by method id.

## 4. Calling

```java
Echo echo = Messenger.connect(Echo.class);                  // resolves lazily
echo.echo("hi", 3).thenAccept(System.out::println);
Echo quick = Messenger.connect(Echo.class).withTimeout(200); // a proxy with its own deadline
```

- `connect` returns a proxy at once; the name is resolved on the first call
  and again after the peer dies, so a service that restarts is reached
  without the program noticing beyond one failed call.
- **Deadline**: 5 s by default (Rhai's default), per proxy with
  `withTimeout(ms)`, converted to Messenger's ticks by the runtime.
- **Cancellation**: cancelling a returned stage cancels the call
  (`msg_cancel`), as `lazy.net` does for sockets ([`async.md`](async.md)).
- **Threads**: calls are begun, drained and cancelled on the `lazy.async`
  loop (S10-02's single `wait`); stages complete there, and a Siesta
  `await` moves the code back to its home thread, the UI thread in a
  window ([`siesta-async.md`](siesta-async.md)).

## 5. Errors

Every failure completes the stage with `lazy.msg.MessengerException extends
IOException`: `errno()` (`ETIMEDOUT`, `EPERM`, `EPIPE` for a dead peer,
`ENOSYS` for an unknown method, ...), and when the peer sent a structured
error, its `code()`, `hint()` and message. The message reads as LazyRAD's
does: `os.lazy.confd.v1.Get: no such key (ENOENT)`. Encode and decode
failures are the same exception with `EINVAL`, so a caller has one thing to
catch.

## 6. Serving and callbacks

```java
class EchoService implements Echo {
    public CompletionStage<String> echo(String text, long count) {
        return CompletableFuture.completedFuture(repeat(text, count));
    }
    public CompletionStage<Boolean> ping() { return CompletableFuture.completedFuture(true); }
    public void notify(Event e) { System.out.println(e.topic); }
}
Registration r = Messenger.serve("app.demo.echo", new EchoService());
```

- `serve` registers the name (apps may only use `app.<id>.<name>`), and the
  runtime answers calls from the loop through the codec: arguments decoded,
  the method called, the stage's value encoded as the reply.
- A stage that fails with `MessengerException` sends its code and hint; any
  other exception sends `EIO` with its message. A method the object does not
  implement cannot exist, since the class implements the whole interface.
- `Registration.close()` withdraws the name; so does the end of the program.
- **Callbacks**: a `Channel<I>` argument is an object implementing `I`. The
  runtime makes the channel pair, moves one end and serves the object on the
  other, so `notify.listen(events -> refresh(events))` works when `I` has one
  method, and an object does otherwise.

## 7. Topics

```java
Subscription s = ClipboardTopics.changed(session).subscribe(meta -> refresh(meta));
ClipboardTopics.changed(session).publish(new OfferMeta().mime("text/plain"));
```

- `subscribe(handler)` uses the topic's declared QoS; `subscribe(Qos,
  handler)` overrides it. The runtime runs the `NextEvent` pull on the loop
  and calls the handler on the subscriber's home executor (the UI thread
  for a subscription made from it), one event at a time.
- A retained value is delivered first, as Messenger defines.
- `Subscription.close()` ends it. Subscriptions and registrations made on
  the UI thread close when `Ui.run` returns, as LazyRAD's do when a form
  closes.

## 8. Permissions

An app's manifest lists the services and topics it uses. The packager
(S10-05) reads them from the jar: each generated interface carries its
`NAME` and each topic accessor its pattern as constants, so scanning the
class files' constant pools for references to generated classes finds them,
for Java and Siesta alike, with no compiler support. Names built at run time
are not found and must be listed by hand, as in LazyRAD.

## 9. Windows

The loopback broker of D8 implements the same API in-process, so
`lazy.msg` tests and demos run on Windows; real LazyOS services are only
reachable on LazyOS.

## 10. Plan changes it implies

- S10-01: generate the types of section 3 (interfaces, argument and reply
  classes, enums, topic accessors, codecs), no annotations.
- S10-02: `Messenger.connect`, proxies with `withTimeout`, cancellation and
  `MessengerException` as above.
- S10-03: `Messenger.serve`, callbacks through `Channel<I>` objects, and
  `Topic.subscribe`/`publish`, replacing "`@Subscribe` handlers discovered by
  reflection".
- D8's `lazy.msg` paragraph changes accordingly once the owner agrees.
