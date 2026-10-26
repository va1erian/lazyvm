# LazyVM plan

LazyVM runs Java 8 bytecode on Windows and on LazyOS. It is one interpreter
written in Rust, a small class library covering the essentials of Java SE 8,
and a `lazy.*` API for xui, Messenger and async I/O. Apps are written and built
in IntelliJ on Windows, run there on LazyVM, and run unchanged on LazyOS.

Stages S8-S13 are in [`plan-2.md`](plan-2.md). Read [`decisions.md`](decisions.md) first: it fixes the target level, the
license position, the toolchain and the scope this plan assumes. Every change
follows [`../AGENTS.md`](../AGENTS.md).

## How to read a work item

```
- [ ] S3-02 Title. What to build, in one or two sentences.
      Files: where it goes.
      Check: the commands and tests that must pass, on which platforms.
      Needs: work items that must be done first (omitted when it is the previous one).
```

- A work item is done when its **Check** passes, and not before. "Both" means
  Windows and LazyOS. LazyOS is checked from S3 onward through
  `tools/lazyos_test.py`.
- A test named `tests/java/lang/StringBasics` means
  `tests/java/lang/StringBasics.java` plus its golden `StringBasics.out`. The
  golden is blessed from the oracle JVM for `java.*` tests and hand-written for
  `lazy.*` tests ([`decisions.md`](decisions.md) D9).
- Each stage ends with a **Gate**: the demo that proves the stage, run on both
  platforms, and the stage's line in "Status" updated.
- Items inside a stage are ordered. Items in different stages may only run in
  parallel when their `Needs` allow it.

## Status

| Stage | Name | State |
|---|---|---|
| S0 | Workspace and harness | done |
| S1 | One class | done |
| S2 | Objects, exceptions, strings, GC | done |
| S3 | Class path, jars, launcher, IntelliJ | not started |
| S4 | Language completeness: lambdas, annotations, reflection | not started |
| S5 | Core library | not started |
| S6 | Threads, concurrency, `CompletionStage`, the event loop | not started |
| S7 | Networking | not started |
| S8 | Verifier and hardening | not started |
| S9 | `lazy.ui` on xui | not started |
| S10 | Messenger and LazyOS integration | not started |
| S11 | Streams, `java.time`, logging, JDWP | not started |
| S12 | Explore: AWT on xui | not started |
| S13 | Explore: Swing and media | not started |

## S0. Workspace and harness

Goal: an empty VM that every later item can test against, on both platforms.

- [x] S0-01 Workspace. Rename `master` to `main`. Turn the scaffold into the
      workspace in [`decisions.md`](decisions.md) D10, with empty crates that
      build. Add `rust-toolchain.toml` (stable, plus the
      `x86_64-unknown-linux-musl` target).
      Files: `Cargo.toml`, `crates/*/Cargo.toml`, `crates/*/src/lib.rs`.
      Check: `cargo build --workspace`; `cargo build -p launcher --target x86_64-unknown-linux-musl`.
- [x] S0-02 `tools/javac.py`: the one wrapper around javac
      (`-source 8 -target 8 -Xlint:-options -encoding UTF-8`, with an optional
      `-bootclasspath`). It finds javac through `LAZYVM_JAVAC` or `PATH` and
      fails with a readable message if it is missing or cannot emit major 52.
      Check: `python tools/javac.py --selftest` compiles a lambda class and asserts major version 52.
- [x] S0-03 Differential test runner. `tools/golden.py --bless <tests>` runs
      tests on the oracle JVM and writes the `.out` files. `tools/test.py
      [--filter]` runs them on `lazyvm` and prints one `PASS`/`FAIL name` line
      each plus a unified diff on failure.
      Files: `tools/golden.py`, `tools/test.py`, `tests/java/README.md`.
      Check: with a stub launcher that runs nothing, `tools/test.py` reports every test `FAIL`, and the exit code is 1.
- [x] S0-04 Agent rules check. `tools/check_rules.py` fails if any `AGENTS.md`
      or `CLAUDE.md` in the tree lacks the shared rules block, or if its copy
      differs from the one in `AGENTS.md`. It also fails if a `.rs`, `.py` or
      `.java` file is over 500 lines, or an `unsafe` block lacks `// SAFETY:`.
      With `--lazyos <checkout>`, it also compares the copy in LazyOS's
      `docs/agents/lazyvm.md`.
      Check: `python tools/check_rules.py` passes; deleting a line of the block in a copy makes it fail.
- [x] S0-05 License check. `tools/licenses.py` walks `cargo metadata` and fails
      on any crate whose license is not in the allowed list
      ([`decisions.md`](decisions.md) D2) or that is missing from `THIRD_PARTY.md`.
      Check: `python tools/licenses.py` passes.
- [x] S0-06 CI. A GitHub workflow on Windows runs `cargo test`, `check_rules`,
      `licenses` and `tools/test.py`; a Linux job builds the musl target.
      Check: the workflow is green on a PR.

Gate: CI green with zero Java tests.

## S1. One class

Goal: `java -cp out Hello` prints `Hello` and runs integer loops, using only
static methods in one class. There are no objects except arrays and
`String` constants.

- [x] S1-01 Class file parser (JVMS 4.1-4.7): magic, versions 45-52, constant
      pool (all 14 Java 8 tags, with indices checked), fields, methods, `Code`,
      `LineNumberTable`, `SourceFile`, `StackMapTable` (kept raw), `BootstrapMethods`,
      `Signature`, `Exceptions`, and annotation attributes kept raw. Unknown
      attributes are skipped. Every malformed input returns a `ClassFormatError`
      value with the offset, and never panics.
      Files: `crates/classfile/src/*.rs`.
      Check: `cargo test -p classfile` with fixtures compiled by `javac.py`; a proptest feeding truncated and mutated fixtures never panics.
- [x] S1-02 Interpreter core: frames, the operand stack and locals as tagged
      slots, and every opcode for `int`, `long`, `float`, `double`, locals,
      stack, comparison, branches, `tableswitch`/`lookupswitch`,
      `invokestatic` within the class, `ireturn`… Arithmetic follows JVMS 2.8
      and 6.5 exactly: wrapping, `idiv` by zero, NaN comparisons and `f2i`
      saturation.
      Files: `crates/vm/src/interp/*.rs`.
      Check: `tests/java/s1/IntMath`, `LongMath`, `FloatMath`, `Switches`.
- [x] S1-03 Primitive arrays: `newarray`, `*aload`, `*astore`, `arraylength`,
      and bounds checks that print the oracle's message as an uncaught error
      (real exceptions arrive in S2).
      Check: `tests/java/s1/Arrays1`, `Sieve`.
- [x] S1-04 Bootstrap intrinsics: `System.out.println` for `int`, `long`,
      `double`, `char`, `boolean` and `String` constants, and `String`
      concatenation as javac 8 emits it (`StringBuilder` calls), all
      intrinsified until S2 provides the real classes. This scaffolding is
      deleted in S2-05. (Done differently: the first `lazyrt` slice landed
      with S1, so no intrinsics were ever written.)
      Check: `tests/java/s1/Hello`, `Concat`.
- [x] S1-05 Launcher v0: `lazyvm -cp <dir> <Main> [args]`, plus
      `-Xtrace:interp` printing one line per executed instruction.
      Files: `crates/launcher/src/main.rs`.
      Check: `tests/java/s1/Args` passes on Windows; the musl binary runs `Hello` under Linux or WSL.

Gate: `tools/test.py --filter s1` all `PASS`.

## S2. Objects, exceptions, strings, GC

Goal: ordinary object-oriented single-threaded programs run, with a real `java.lang` core.

- [x] S2-01 Heap and object model: object header (class pointer, hash and lock
      word), field layout per class, arrays of references, `Handle`/handle
      scopes, and a mark-compact collector with roots from frames, statics,
      handles and interned strings. `-Xmx` sizing applies, and running out of
      memory raises `OutOfMemoryError`.
      Files: `crates/vm/src/heap/*.rs`.
      Check: `cargo test -p vm heap::` (a stress test allocating 100x the heap); `tests/java/s2/GcChurn`.
- [x] S2-02 Class loading and linking for one class path (JVMS 5): loading,
      superclass and interface resolution, preparation, `<clinit>` with the
      JVMS 5.5 initialization rules (single-threaded for now), and field and
      method resolution, including the default-method selection of JVMS 5.4.3.3.
      Check: `tests/java/s2/Inheritance`, `Interfaces`, `DefaultMethods`, `StaticInit`.
- [x] S2-03 Invocation and objects: `new`, `getfield`/`putfield`,
      `invokevirtual`, `invokespecial`, `invokeinterface` (vtables and itables),
      `checkcast`, `instanceof`, `anewarray`, `multianewarray`, `athrow`,
      `monitorenter`/`monitorexit` (no-op until S6), and the null and bounds
      checks.
      Check: `tests/java/s2/Dispatch`, `Casts`, `MultiArrays`.
- [x] S2-04 Exceptions: exception tables, unwinding, `finally`, the VM's own
      exceptions (`NullPointerException`, `ArithmeticException`,
      `ArrayIndexOutOfBounds…`, `ClassCast…`, `NegativeArraySize…`),
      `Throwable.printStackTrace` with file:line from `LineNumberTable`, and
      uncaught exceptions printed in HotSpot's format with exit code 1.
      Check: `tests/java/s2/Exceptions`, `Finally`, `StackTraces` (goldens compare the trace lines).
- [x] S2-05 `lazyrt` bootstrap: `tools/lazyrt.py` builds `java/lazyrt` against
      itself into `dist/lazyrt.jar`. The first classes are `Object`, `String`
      (UTF-16 `char[]`, `hashCode`, `equals`, `intern`), `StringBuilder`,
      `System` (`out`, `err`, `arraycopy`, `currentTimeMillis`, `nanoTime`,
      `exit`, `getProperty`), `PrintStream` over a native console,
      `Math`, `Integer`/`Long`/`Character`/`Boolean`… with value caches, and
      the throwable hierarchy. The S1 intrinsics are deleted.
      Files: `java/lazyrt/src/java/lang/*.java`, `crates/natives/src/java_lang/*.rs`.
      Check: all S1 and S2 tests still pass with no intrinsics; `tests/java/lang/StringBasics`, `Boxing`, `MathFns`.
- [x] S2-06 `Object` natives: `hashCode` (identity, stable across GC),
      `getClass`, `clone` (arrays and `Cloneable`), `toString`, plus string
      constant interning.
      Check: `tests/java/lang/Identity`, `CloneArrays`.

Gate: a 300-line text-adventure test (`tests/java/s2/Adventure`) passes on Windows.

## S3. Class path, jars, launcher, IntelliJ

Goal: an app built by IntelliJ runs from the Run button on Windows, and the
same jar runs on LazyOS.

- [ ] S3-01 Class path: directories and jars (zip with stored and deflate
      entries, via the `miniz_oxide` crate), boot class path = `lazyrt.jar`,
      application class path from `-cp`, and `Class.getResourceAsStream` /
      `ClassLoader.getSystemResource`.
      Check: `tests/java/s3/Resources` (run from a jar).
- [ ] S3-02 `-jar`: `META-INF/MANIFEST.MF` `Main-Class` and `Class-Path`.
      Check: `tools/test.py --jar` runs every S1-S3 test from a jar.
- [ ] S3-03 Launcher options as IntelliJ passes them
      ([`decisions.md`](decisions.md) D3): `-D` into `System.getProperty`,
      `-Xmx`/`-Xss`, `-verbose:class`, `-version` (prints `lazyvm` plus the
      class library version and never claims "java"), and the IntelliJ extras
      ignored.
      Check: `tests/launcher/intellij_cmdline.txt` (a real captured IntelliJ command line) runs `Hello`.
- [ ] S3-04 Fake JDK home: `tools/sdk.py` builds `dist/lazyvm-sdk/`
      ([`decisions.md`](decisions.md) D3).
      Check: `docs/intellij.md` walks through registering the SDK and running the template; an owner-run manual check is recorded in the PR with a screenshot (agents cannot drive IntelliJ).
- [ ] S3-05 App template: `templates/app/` with an IntelliJ project, a
      Gradle file and a `Main.java`.
      Check: `gradle -p templates/app jar` produces a jar that `lazyvm -jar` runs.
- [ ] S3-06 LazyOS run: `lazyvm-lazyos` platform v0 (console, files, clock)
      and `tools/lazyos_test.py`, which builds the musl binary, stages
      `lazyrt.jar` and the tests, and runs the suite in a LazyOS image under
      QEMU. Needs LOS-01.
      Check: `python tools/lazyos_test.py --filter s1,s2,s3` all `PASS`.

Gate: the template app runs from IntelliJ's Run button on Windows and through
`lazyos_test.py` on LazyOS.

## S4. Language completeness: lambdas, annotations, reflection

Goal: everything javac 8 can emit runs, and annotations and reflection are
good enough for `lazy.msg` (S10).

- [ ] S4-01 `invokedynamic` with `LambdaMetafactory` (`metafactory` and
      `altMetafactory`, including the serializable and marker flags, which are
      ignored with a note). The bootstrap is recognised by its owner and name;
      the VM generates a hidden class implementing the functional interface,
      with bridges for `altMetafactory`, and one call site object per `indy`
      instruction. Other bootstrap methods throw `BootstrapMethodError`.
      Check: `tests/java/s4/Lambdas`, `MethodRefs` (static, bound, unbound, constructor), `Capture`.
- [ ] S4-02 Language support classes: `Enum` (`values`, `valueOf`, `ordinal`,
      `EnumMap`, `EnumSet`), string `switch`, `Iterable` for-each, varargs,
      try-with-resources (`addSuppressed`), and `assert` (`-ea`).
      Check: `tests/java/s4/Enums`, `StringSwitch`, `TryWithResources`, `Asserts`.
- [ ] S4-03 `java.util.function` (all interfaces) and the default methods on
      `Comparator`, `Iterable` and `Map` that use them.
      Check: `tests/java/util/FunctionalBasics`, `Comparators`.
- [ ] S4-04 Reflection core ([`decisions.md`](decisions.md) D6): `Class`
      metadata, `getDeclared*`/`get*` for methods, fields and constructors
      (public filtering per the Javadoc), `Method.invoke` with boxing and
      `InvocationTargetException`, `Field` access, `Modifier`, and
      `Class.forName` with initialization. No access-checking beyond
      `setAccessible` bookkeeping.
      Check: `tests/java/s4/ReflectBasics`, `ReflectInvoke`.
- [ ] S4-05 Runtime annotations: parse `RuntimeVisible*Annotations` and
      `AnnotationDefault`, generate annotation implementation classes (equals,
      hashCode and `toString` as in the Javadoc), and implement
      `getAnnotation`, `getAnnotations`, `isAnnotationPresent` and
      `getParameterAnnotations`. Includes `@Retention`, `@Target`, `@Inherited`,
      `@Repeatable` lookup and `@FunctionalInterface`.
      Check: `tests/java/s4/Annotations` (nested, arrays, enums, class values, defaults), `InheritedAnnotations`.
- [ ] S4-06 Generic signatures: `getGenericSuperclass`,
      `getGenericInterfaces`, `Method.getGenericReturnType`, and
      `ParameterizedType` / `TypeVariable` / `WildcardType`, all parsed from
      `Signature` attributes. They are needed by `lazy.msg` codecs.
      Check: `tests/java/s4/GenericSignatures`.

Gate: `tests/java/s4/Showcase` (a small event bus built with lambdas and an
`@Subscribe` annotation scanned by reflection) passes on both platforms.

## S5. Core library

Goal: the everyday `java.util` and `java.io` surface from
[`decisions.md`](decisions.md) D6, with oracle-matched output.

- [ ] S5-01 Collections I: `ArrayList`, `LinkedList`, `ArrayDeque`,
      `Collections`, `Arrays` (sort: dual-pivot quicksort for primitives,
      TimSort for objects, so stability and exceptions match the Javadoc),
      and the iterator and `ConcurrentModificationException` behaviour.
      Check: `tests/java/util/Lists`, `Sorting`, `Iterators`.
- [ ] S5-02 Collections II: `HashMap`/`LinkedHashMap`/`HashSet` (the Java 8
      hash spreading and table growth, so iteration order matches the oracle),
      `TreeMap`/`TreeSet` (red-black), `PriorityQueue`, `IdentityHashMap`,
      `WeakHashMap` (with S5-07), `Map` default methods.
      Check: `tests/java/util/HashOrder`, `TreeMaps`, `MapDefaults`.
- [ ] S5-03 Text: `Formatter`/`String.format`/`printf` (all conversions in the
      Javadoc except `%t` dates, which come with S11), `Double.toString`
      (shortest repr as Java 8 prints it), `Integer.parseInt` and friends,
      `StringJoiner`, `String.split`, and `java.util.regex` (`Pattern` and
      `Matcher` backtracking engine, Java syntax subset documented in
      `docs/regex.md`).
      Check: `tests/java/util/FormatAll`, `DoubleToString` (100k random values against the oracle), `Regex`.
- [ ] S5-04 Utilities: `Optional*`, `Objects`, `Random` (the exact LCG),
      `Scanner`, `Properties`, `UUID`, `Base64`, `BitSet`, `StringTokenizer`.
      Check: `tests/java/util/Utilities`, `RandomSeq`.
- [ ] S5-05 `java.io`: byte and char streams, `Buffered*`, `InputStreamReader`
      and `OutputStreamWriter` over the charsets, `File`, `FileInputStream`,
      `FileOutputStream`, `RandomAccessFile`, `Data*Stream`, `PrintWriter`,
      and `System.in`. File paths go through the platform (on LazyOS, through
      the Linux ABI, respecting `libs/fhs` paths).
      Check: `tests/java/io/Streams`, `Files1`, `Charsets` (both).
- [ ] S5-06 `java.nio`: heap `ByteBuffer`/`CharBuffer`, `charset`, and
      `java.nio.file` (`Path`, `Paths`, `Files` read, write, copy, move,
      delete, list, walk and basic attributes, plus `DirectoryStream`).
      Check: `tests/java/nio/Buffers`, `FilesWalk` (both).
- [ ] S5-07 References: `WeakReference`, `SoftReference` (cleared on memory
      pressure), `ReferenceQueue`, and finalization not supported (documented:
      `finalize` is never called).
      Check: `tests/java/lang/WeakRefs`.

Gate: `tests/java/s5/WordCount` (reads a 5 MB file, counts with `HashMap`,
prints the top 20 with `printf`) matches the oracle on both platforms.

## S6. Threads, concurrency, `CompletionStage`, the event loop

Goal: real threads, `java.util.concurrent`, and `lazy.async` delivering
platform completions into `CompletableFuture`.

- [ ] S6-01 Threads: `Thread` on OS threads, safepoints, a stop-the-world GC
      handshake, `Thread.sleep`/`join`/`interrupt`/`yield`, daemon threads, the
      uncaught exception handler, `ThreadLocal`, and VM exit when the last
      non-daemon thread ends.
      Check: `tests/java/thread/StartJoin`, `Interrupts`, `GcUnderThreads` (8 threads allocating while GC runs, 1000 iterations).
- [ ] S6-02 Monitors: `synchronized`, `wait`/`notify`/`notifyAll`, and
      `IllegalMonitorStateException`; class initialization made thread-safe
      (JVMS 5.5 locking procedure).
      Check: `tests/java/thread/ProducerConsumer`, `ClinitRace`.
- [ ] S6-03 The Java memory model at the VM level: `volatile` fields and
      `Unsafe`-free atomics through VM intrinsics (`lazy.internal.Atomics`)
      used by `java.util.concurrent.atomic`.
      Check: `tests/java/thread/AtomicCounters` (deterministic totals).
- [ ] S6-04 `java.util.concurrent`: locks (`ReentrantLock`, `Condition`,
      `ReentrantReadWriteLock`, `LockSupport`), executors, scheduled
      executors, `ConcurrentHashMap`, `CopyOnWriteArrayList`, blocking queues,
      `CountDownLatch`, `CyclicBarrier`, `Semaphore`, and a simple `ForkJoinPool`.
      Check: `tests/java/concurrent/*` (each prints only deterministic results).
- [ ] S6-05 `CompletableFuture` and `CompletionStage`: every method in the
      Javadoc, the async variants on `ForkJoinPool.commonPool()` or a given
      executor, `exceptionally`, `handle`, `allOf`/`anyOf`, and cancellation.
      Check: `tests/java/concurrent/CompletableFutures`.
- [ ] S6-06 `lazy.async.Loop`: the reactor thread described in
      [`decisions.md`](decisions.md) D8, `Loop.submit`, timers
      (`Loop.delay(Duration)` returning `CompletionStage<Void>`), and the rule
      that completions run on the chosen `Executor`, never on the reactor
      thread. On LazyOS it is one `wait` over `WAIT_CALLS`, endpoints and an
      epoll `WAIT_FD` (read LazyOS `docs/architecture/call-completion.md` and
      `endpoint-fd.md` first); on Windows it is `mio`. Cancellation reaches
      the platform operation.
      Files: `java/lazy-api/src/lazy/async/*.java`, `crates/platform/src/reactor.rs`, `crates/lazyvm-*/src/reactor.rs`.
      Check: `tests/lazy/async/Timers`, `Cancel` (both); no polling: a test asserts the reactor thread's CPU time stays under 1% over an idle 2 s.

Gate: `tests/lazy/async/Pipeline` (1000 chained timer stages fanned in with
`allOf`) passes on both platforms.

## S7. Networking

Goal: blocking `java.net`, non-blocking `java.nio.channels`, and
`CompletionStage`-based `lazy.net`, including HTTPS.

- [ ] S7-01 Platform sockets: TCP and UDP, IPv4 and IPv6, DNS through the
      platform resolver (on LazyOS, through `netd` via the Linux ABI), socket
      options and timeouts.
      Check: `cargo test -p lazyvm-win sockets`; LazyOS `tools/lazyos_test.py --net` with the image's networking switch.
- [ ] S7-02 `java.net`: `InetAddress`, `Socket`, `ServerSocket`,
      `DatagramSocket`, `URI` (RFC 2396 as the Javadoc says), `URL`,
      `URLEncoder`/`URLDecoder`, and the exceptions (`ConnectException`,
      `SocketTimeoutException`, `UnknownHostException`).
      Check: `tests/java/net/EchoServer` (a server and client in one test on localhost), `UriParse`, `UrlParts` (both).
- [ ] S7-03 `java.nio.channels`: `SocketChannel`, `ServerSocketChannel`,
      `DatagramChannel` and `Selector` over the reactor;
      `AsynchronousSocketChannel` with `Future` and `CompletionHandler`.
      Check: `tests/java/net/SelectorEcho`, `AsyncChannelEcho`.
- [ ] S7-04 `lazy.net`: `TcpClient.connect(host, port)` →
      `CompletionStage<Connection>`; `Connection.read`, `write` and `close`
      returning stages; `TcpServer.listen` delivering connections to a handler;
      backpressure by not reading.
      Check: `tests/lazy/net/AsyncEcho`, `ManyClients` (500 concurrent connections, both).
- [ ] S7-05 TLS through rustls, with nettls's MIT pure-Rust `crypto` provider
      on both platforms (no `ring`/`aws-lc`). Roots come from the Mozilla bundle
      at the LazyOS well-known path, and on Windows from a bundled copy. There
      is no "trust all" switch. Exposed as `lazy.net.Tls.wrap(connection, host)`
      and as `https` in `HttpURLConnection`.
      Check: `tests/lazy/net/TlsLocal` against a local test server with a test CA (both).
- [ ] S7-06 HTTP: `HttpURLConnection` (HTTP/1.1, chunked, redirects,
      keep-alive) and `lazy.net.Http.get/post` → `CompletionStage<Response>`
      with streaming bodies.
      Check: `tests/lazy/net/HttpLocal` against `tools/test_http_server.py`; `tests/java/net/HttpUrlConn`.

Gate: `samples/fetch` downloads a page over HTTPS with `lazy.net.Http` and
prints its status and size on both platforms (manual live run, recorded in the PR).


S8-S13 and the LazyOS-side items continue in [`plan-2.md`](plan-2.md).
