# Siesta: `async` and `await`

Status: proposal, part of S16 in [`plan-2.md`](plan-2.md). The language is
[`siesta.md`](siesta.md); its use as an embedded script language, where the same
two keywords suspend a script task, is [`siesta-script.md`](siesta-script.md).

## 1. Goal

LazyOS APIs are asynchronous: `lazy.net`, `lazy.async`, and from S10
`lazy.msg`, all return `CompletionStage` ([`async.md`](async.md)). Chained
with `thenCompose`, a five-step flow is five nested lambdas, and a loop or a
`try` around it has to be rebuilt by hand. Siesta lets the same flow read
top to bottom, as if it blocked, while it never blocks a thread:

```siesta
import lazy.net.*
import java.io.IOException
import java.nio.charset.StandardCharsets

async String fetch: String host path: String path {
    var c := await Tcp connect: host port: 80
    [
        await c write: ("GET ${path} HTTP/1.0
Host: ${host}

" getBytes: StandardCharsets UTF_8)
        ^ String new: (await c readAll) charset: StandardCharsets UTF_8
    ] ensure: [c close]
}

async void serve: Connection c {
    var bytes := await c read
    [bytes size > 0] whileTrue: [
        await c write: bytes
        bytes := await c read
    ]
    await c close
}

[println: (await fetch: "example.com" path: "/") length]
    on: IOException do: [:e | println: "fetch failed: ${e getMessage}"]
```

The `on: IOException do:` handler sees the `IOException` itself, not a `CompletionException`, and
`fetch` is an ordinary Java method to Java callers: it returns
`CompletionStage<String>`.

## 2. The rules

- **`async` marks a method or a block whose body may suspend.** An
  `async T m: ...` is declared with the type of its result; to the JVM and
  to Java code it returns `CompletionStage<T>` (`CompletionStage<Void>` for
  `void`, boxed for a primitive). `async [:a | ...]` is a block whose body
  may suspend.
- **`await e`** (prefix, over a whole keyword message: `await c write:
  data`) needs `e` to be a `CompletionStage<T>` (a subtype such as
  `CompletableFuture` included) and has type `T`. Inside a larger expression it is
  parenthesised: `(await c readAll) size`. It may appear an `async` method or block, or at a file's top
  level. Anywhere else it is a compile error that names the enclosing method
  and suggests `async`.
- **A failed stage throws.** `await` rethrows the stage's exception, with
  one `CompletionException` wrapper removed, so `on: IOException do:`
  works as it would around a blocking call. A cancelled stage throws
  `CancellationException`.
- **Calling without `await` starts the work and goes on.** The call returns
  the stage, which can be awaited later, so two requests run at once:
  `var a := fetch: x path: "/"`, `var b := fetch: y path: "/"`, then
  `println: (await a) + (await b)`.
- **Not in `ensure:` or `on:do:` handler blocks, constructors or field
  initialisers.** The same restriction C# started with; it keeps the
  lowering free of copied `ensure:` code that suspends. The protected block
  of `on:do:` or `ensure:` may await.
- **Where the code after `await` runs.** An `async` body remembers its
  *home*: the executor whose thread started it, if it has one
  (`lazy.async.Loop.home()`, section 4). Each resumption runs on the home,
  so a `lazy.ui` handler stays on the UI thread across an `await` of a
  network stage, and a handler on the loop stays on the loop. With no home
  (a plain thread such as `main` before the loop starts), it resumes on the
  thread that completed the stage, `CompletableFuture`'s own rule.
- **Cancellation reaches the awaited operation.** Cancelling the stage an
  `async` call returned cancels the stage it is awaiting at that moment, so
  `(fetch: h path: p) toCompletableFuture cancel: true` aborts the connection as
  `lazy.net` defines ([`async.md`](async.md), "Cancellation"), and the body
  does not resume.
- **Fire and forget.** An `async` block given where the functional
  interface returns `void` (`button onClick: async [...]`) is started
  and its stage dropped. If it fails, the exception is rethrown on its home
  executor, so it is handled exactly as a throwing task there is (a loop
  task is reported and the loop goes on; a `lazy.ui` callback ends
  `Ui run:`); with no home, it goes to the thread's uncaught exception
  handler. Where the interface returns a `CompletionStage`, the block's
  stage is returned.
- **Top-level `await`.** A file whose top level awaits gets a `main` that
  starts the body and waits for it, rethrowing its failure unwrapped, so a
  script's exit status and stack trace are those of a plain program.

```siesta
// A window that stays responsive while it fetches.
var status := Ui label: "idle"
var go := Ui button: "Go"
go onClick: async [
    status text: "fetching"
    var page := await fetch: "example.com" path: "/"   // the UI thread is free meanwhile
    status text: "${page length} characters"           // back on the UI thread
]
Ui run: ((Ui window: "Fetch" width: 320 height: 120) content: (Ui column add: status with: go))
```

## 3. Lowering on the JVM

The checker first linearises every expression that contains `await` into
temporaries (A-normal form), so the operand stack is empty at every await,
and both back ends (the JVM one here, a script host's in
[`siesta-script.md`](siesta-script.md)) only see `x := await e` and `await e` as
statements.

Each `async` method or block then becomes two pieces:

| Piece | What it is |
|---|---|
| frame class `Owner$m$async` (synthetic, final) | extends `CompletableFuture<Object>` and implements `BiConsumer<Object, Throwable>` and `Runnable`. Fields: `int state`, the owner (`this`) if any, one field per parameter and local, `Object value`, `Throwable error`, `CompletionStage awaited`, `Executor home`, `boolean running`, `boolean ready` |
| `m$resume(frame)` (static synthetic, in the owner class) | the body as a state machine, so it reaches the owner's private members without accessors |

- **The call** creates the frame, stores the parameters, sets `home` from
  `Loop.home()` and calls `m$resume`; it returns the frame, which is the
  result stage. One object per call is both the future and the callback.
- **At an await** the code stores every local in scope into the frame,
  sets `state` to the await's number and `awaited` to the stage, and calls
  `stage.whenComplete(frame)`. If the stage completed already,
  `whenComplete` calls back at once, while `running` is set: the callback
  then only records the outcome and sets `ready`, and `m$resume` loops back
  to its dispatch instead of returning. A loop over completed stages
  therefore runs flat, never growing the Java stack.
- **The callback** (`accept`) stores `value` and `error`, then runs
  `m$resume` directly when there is no home or the current thread's home is
  the frame's (`Loop.home() == home`), and otherwise `home.execute(frame)`,
  whose `run()` calls `m$resume`.
- **Resumption.** `m$resume` starts with a `tableswitch` on `state`. Each
  case reloads the locals that were in scope at that await, throws `error`
  (unwrapped) if there is one, `checkcast`s `value` to the awaited type
  into the target local, and jumps to the instruction after the await. A
  jump into the middle of a protected range is legal bytecode; the exception
  table covers the resumed code, so a failed await is caught by the
  surrounding `on:do:` handler with no extra work.
- **Completion.** `return v` completes the frame with `v`; the whole body
  sits in a catch-all handler that completes it exceptionally.
- **`cancel(boolean)`** is overridden: it calls `super.cancel` and, if that
  succeeded, cancels `awaited` when it is a `Future`. It never calls
  another public completion method of its own (the S6-05 rule that
  `CompletableFuture`'s public methods do not call each other virtually).
- **Stack maps.** Every resume label's frame is the scope at its await plus
  the target temporary, which the generator already knows (siesta.md
  section 7).

Cost per await on an interpreter: one `whenComplete` node and, off the home
thread, one executor task. Nothing is allocated for an await whose stage is
already complete except that node.

## 4. What it needs from `lazy.*`

One method, `static Executor lazy.async.Loop.home()`: `Loop.executor()` on
the loop's thread, `Ui.executor()` on the UI thread while `Ui.run` runs,
`null` elsewhere. `lazy.ui` registers its executor through
`lazy.internal`, so `lazy.async` does not depend on `lazy.ui`. It is our
API (shared rule 2), lives with `Loop` (`java/lazyrt/src/lazy/async/`) and
gets its own `lazy.*` test.

Siesta programs that `await` therefore need `lazy.async` at run time. That is
the one exception to "no runtime library" (siesta.md section 2), and it is
part of `lazyrt`, not a Siesta library.

## 5. Testing

`await` programs depend on `lazy.async`, so, like every `lazy.*` test (D9),
they run on LazyVM against hand-written goldens: `tests/siesta/async/`.
Programs that use only `CompletableFuture` still run on the oracle too.
The suite covers: a loop over already-completed stages a million times (no
`StackOverflowError`); an await inside `on:do:` in a loop with a failure on
the third turn; cancellation of an `async` call while it waits on
`Loop.delay`, with the timer removed (`lazy.async` pending count back to
zero); a UI handler that awaits a `lazy.net` read and checks it resumed on
the UI thread; a fire-and-forget failure on the loop and on the UI thread;
and top-level `await` with a failing stage (exit status and stack trace).
