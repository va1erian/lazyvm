# LazyVM decisions

The answers to the open points raised before planning. [`plan.md`](plan.md)
builds on them; [`../AGENTS.md`](../AGENTS.md) turns the ones that bind every
change into rules. A decision marked **(default)** was proposed and not yet
confirmed by the owner. It holds until someone changes it here. A row or
decision marked **(proposed)** is not in force until the owner confirms it.

## D1. Target level

- The VM loads class files with major versions 45 to 52 (Java 1.1 to 8) and
  rejects anything newer with a clear error.
- `invokedynamic` is supported only through `LambdaMetafactory.metafactory`
  and `altMetafactory`, which javac 8 uses for lambdas and method references.
  The VM implements both natively by generating a hidden class (S4). javac 8
  has no other use for `invokedynamic`; string concatenation through
  `invokedynamic` starts in Java 9.
- Out of scope: general `MethodHandle`/`MethodType` behaviour,
  `SecurityManager`, the `jsr`/`ret` opcodes (no javac since 6 emits them,
  and they are rejected), and the old type-inference verifier. The
  `StackMapTable` type-checking verifier lands in S8, before any jar we did not
  build ourselves is run.

## D2. Standards and licenses

- **References:** the JVMS SE 8, the JLS SE 8 and the Java SE 8 API Javadoc.
  They are the only sources of behaviour, together with what HotSpot observably
  does when it runs our tests.
- **Clean room:** no OpenJDK source, no decompiled JDK classes and no other
  class library's source (GNU Classpath, Apache Harmony, Android libcore) is
  read or copied. Spec and Javadoc prose is not pasted into the repo either:
  doc comments are written fresh. The full rule, with what agents may read, is
  the shared rules block in every agent file.
- **Our legal position, stated plainly:** the Java SE 8 specification license
  (JVMS appendix A, `spec-license.html`) grants implementation rights only to a
  *full* implementation that passes the TCK and does not subset the `java`,
  `javax` or `com.sun` namespaces. LazyVM deliberately implements a subset, so
  that grant **does not cover it**. We rely instead on an independent,
  clean-room implementation, no Oracle code, binaries or trademarks, and no
  claim of compatibility. This is a hobby project and this note is not legal
  advice. The owner accepts this risk. Anyone planning commercial distribution
  should get legal advice first.
- **Namespace:** we add no `public` or `protected` type, field or method to
  `java.*` or `javax.*` beyond what the Java SE 8 API declares. Helpers are
  package-private or live in `lazy.internal.*`. Our own API lives in `lazy.*`.
- **Naming:** the product is "LazyVM, a VM for Java 8 bytecode". It never says
  "Java" in a product, package or binary name, and never claims to be
  "Java compatible". The `java.exe` in the fake JDK home (D3) is an IDE shim,
  not a brand.
- **Licenses (default):**
  - VM crates and the `lazy.*` Java API: `MIT OR Apache-2.0`, matching xui (MIT).
  - Class library (`lazyrt`): MIT, so apps can use any license.
  - `lazyvm-lazyos` (it links LazyOS's `libs/messenger` and `lazyos-sys`):
    `GPL-3.0-or-later`, like LazyOS. The LazyOS binary is GPLv3 as a whole;
    the Windows binary is permissive.
  - Third-party crates: permissive only (MIT, Apache-2.0, BSD, Zlib, ISC), and
    each is recorded in `THIRD_PARTY.md`. `tools/licenses.py` checks the tree
    (work item S0-05).

## D3. Toolchain and the IntelliJ workflow

- **javac:** JDK 25 (installed: Zulu 25.0.2) with `-source 8 -target 8`.
  It emits major version 52 and warns that 8 is obsolete
  (`-Xlint:-options` silences that). If a future javac drops 8, pin JDK 21 or
  Temurin 8. `tools/javac.py` is the one place that knows the flags.
- **Class library build:** `lazyrt` is compiled against itself
  (`-bootclasspath java/lazyrt/build/classes`, never `--release 8`, which would
  compile against the real JDK's API) into `dist/lazyrt.jar`.
- **Fake JDK home:** `tools/sdk.py` lays out `dist/lazyvm-sdk/` with
  `bin/java.exe` (the LazyVM launcher), `jre/lib/rt.jar` (= `lazyrt.jar`),
  `lib/lazy-api.jar`, `release` (`JAVA_VERSION="1.8.0"`) and `src.zip` (our
  sources, for IDE navigation). It is registered once in IntelliJ as an SDK,
  so completion shows only APIs that exist and the Run button starts LazyVM.
  The launcher accepts `-cp`/`-classpath`, `-jar`, `-D`, `-X…` sizing and the
  main class, and ignores IntelliJ extras (`-javaagent:…idea_rt.jar`,
  `-Dfile.encoding`, `-Didea.*`) with one `-verbose` note.
- **App build:** IntelliJ jar artifacts, or the template's small Gradle file
  (`templates/app/`), which sets the same javac flags and the bootclasspath.
- **Debugging:** stack traces with line numbers from S2, a `-Xtrace:` switch
  from S1, and JDWP for IntelliJ breakpoints in S11.

## D4. Platforms

- One VM on both platforms, behind a `Platform` trait (files, clocks, threads,
  sockets, the event reactor, the console, windows).
  - `lazyvm-win`: Windows; windows use xui's `winit-backend`.
  - `lazyvm-lazyos`: static `x86_64-unknown-linux-musl` `std` binary run through
    LazyOS's Linux ABI, exactly like `xui-app`; windows use xuid's
    `os.lazy.display.v1` client mode; Messenger through `lazyos-sys`.
- HotSpot is not a runtime target. It is the **test oracle** for `java.*`
  behaviour (D9).
- **(proposed)** A stock JDK may ship in a LazyOS image as a developer tool
  for compiling, never as the way apps run; tracked in the LazyOS repo. See
  [`compiler.md`](compiler.md) option A.
- "xui rehauled for Windows" means whatever the xui `winit-backend` lacks for
  real apps (DPI, menus, file dialogs, clipboard). That work is tracked as xui
  issues in `va1erian/xui`, found in S9 rather than guessed now.

## D5. Execution engine, memory, threads

- **Interpreter only:** a `match` over opcodes, with constant-pool entries
  resolved lazily and cached in place. No JIT. Quickening is a possible S11+
  item behind a benchmark.
- **Heap (revised in S0):** a slot table. A reference is a `Ref`, a checked
  index into the table, never a raw pointer, so the VM core needs no `unsafe`.
  The collector is precise, non-moving mark-sweep: roots are every thread's
  frames, class statics, interned strings and the native root stack. Freed
  slots carry a generation, so a stale `Ref` is detected rather than aliased.
  Natives that hold a `Ref` across an allocation push it on the root stack
  (`vm.roots`), and the review checks this.
- **Threads (revised in S0):** real OS threads from S6, each `java.lang.Thread`
  mapped to one, sharing one VM lock that a thread holds while it runs
  bytecode. A thread releases the lock at blocking calls (I/O, `sleep`,
  `wait`, monitor contention) and every few thousand backward branches. That
  gives real concurrency for I/O and a simple memory model, but no parallel
  bytecode, which is not a goal. GC runs under the lock, so it stops the world
  without safepoint handshakes. Until S6 the VM runs one thread, and
  `Thread.start` throws `UnsupportedOperationException`.

## D6. Class library scope

| Area | Stage | Contents |
|---|---|---|
| `java.lang` core | S2-S5 | `Object`, `String`, `StringBuilder`, boxing with caches, `Math`/`StrictMath` (through Rust `libm`, within a few ulps of fdlibm: D13), `Character` (tables generated from UnicodeData, Unicode 6.2 as in Java 8), `System`, `Runtime`, `Throwable` with suppressed exceptions and causes, `Enum`, `Iterable`, `AutoCloseable` |
| Reflection and annotations | S4 | `Class` (name, super, interfaces, `isInstance`, `forName`, `newInstance`, `getEnumConstants`), `java.lang.annotation`, `getAnnotation(s)` on classes, methods, fields and parameters, `Method.invoke`, `Field.get/set`, `Constructor.newInstance` |
| `java.util` | S5 | Collections (`ArrayList`, `LinkedList`, `ArrayDeque`, `HashMap`, `LinkedHashMap`, `TreeMap`, `HashSet`, `TreeSet`, `PriorityQueue`, `Collections`, `Arrays`), `Optional*`, `Objects`, `Random`, `Scanner`, `StringJoiner`, `Formatter`/`String.format`, `Properties`, `UUID`, `Base64`, `BitSet`, `Iterator` and friends |
| `java.util.function` | S4 | All 43 interfaces |
| `java.io` | S5 | Byte and char streams, buffering, `File`, `PrintStream`/`PrintWriter`, `RandomAccessFile`, `Data*Stream` |
| `java.nio` | S5 | `charset` (UTF-8, UTF-16 variants, ISO-8859-1, US-ASCII), `ByteBuffer` (heap only), and a `file` subset (`Path`, `Paths`, `Files` read, write, list, walk, attributes) |
| `java.math` | S5 | `BigInteger`, `BigDecimal`, `MathContext`, `RoundingMode` |
| `java.util.zip` | S5 | `ZipFile`, `ZipEntry`, `ZipInputStream`/`ZipOutputStream`, `Inflater`/`Deflater` and their streams, GZIP streams, `CRC32`, `Adler32` |
| `java.util.concurrent` | S6 | Atomics, locks, `ExecutorService`/`ThreadPoolExecutor`, `ScheduledExecutorService`, `ConcurrentHashMap`, `CountDownLatch`, `Semaphore`, blocking queues, `CompletableFuture`/`CompletionStage`, `ForkJoinPool` (simple) |
| `java.net` | S7 | `InetAddress` (DNS through the platform), `Socket`, `ServerSocket`, `DatagramSocket`, `URI`, `URL`, `URLEncoder`/`URLDecoder`, `HttpURLConnection` (`http`, and `https` through rustls) |
| `java.nio.channels` | S7 | `SocketChannel`, `ServerSocketChannel`, `Selector` (non-blocking), `AsynchronousSocketChannel` bridged onto the reactor |
| `java.util.stream` | S11 | Sequential streams in full; `parallel()` accepted and run sequentially |
| `java.time` | S11 | `Instant`, `Duration`, `LocalDate`, `LocalTime`, `LocalDateTime`, `ZoneOffset`, `DateTimeFormatter` (ISO and pattern); no zone database at first |
| `java.util.logging` | S11 | `Logger`, `Level`, `ConsoleHandler` |
| `java.awt` on xui | S12 (explore) | Drawing, images, events, containers and layouts with no native peers; see plan-2 S12 |
| Swing and media | S13 (explore) | A `javax.swing` subset with one look and feel, `javax.imageio` (PNG, JPEG, BMP) and `javax.sound.sampled`; see plan-2 S13 |
| Compiler support | S14 (proposed) | Only if S14 is confirmed, and as far as javac 8 uses them: `javax.lang.model`, `javax.tools`, `javax.annotation.processing`; `java.util.zip` and `java.util.jar` readers; `ResourceBundle` and `ListResourceBundle` with locale fallback, `ServiceLoader`, `MessageFormat` and the `java.text` it needs, `LinkedHashSet`, `URLClassLoader`, `Package`. Confirming it narrows "other `javax.*`" in the "Out" row. See [`compiler.md`](compiler.md) section 5 |
| Out | never | JavaFX, applets, RMI, CORBA, JNDI, JMX, `javax.sound.midi`, other `javax.*`, `SecurityManager`, serialization, `Calendar`/`TimeZone` beyond UTC, JNI, `System.loadLibrary` |

## D7. Native interface

There is no public JNI. The VM has an internal registry from
`class.method(descriptor)` to Rust functions (`natives/`). Every `native`
method in `lazyrt` and `lazy.*` is implemented there. Apps are pure Java, and
an unknown `native` method throws `UnsatisfiedLinkError` naming the method.

## D8. Custom API (`lazy.*`)

- `lazy.async`: the event loop. `Loop` owns a reactor thread that waits on
  the platform (LazyOS: one `wait` naming `WAIT_CALLS`, endpoints and a
  `WAIT_FD` epoll descriptor for sockets; Windows: `mio`). Completions are
  handed to an `Executor` the caller chooses (default: the loop's executor;
  in UI code: `Ui.executor()`), and every async API returns
  `CompletionStage<T>`. Cancelling the `CompletableFuture` cancels the
  underlying operation (`msg_cancel`, socket close).
- `lazy.net`: async sockets, TLS and HTTP returning `CompletionStage`. These
  sit beside `java.net`, sharing the reactor; `java.net` blocking calls are
  built on the same platform layer.
- `lazy.ui`: Java bindings for xui. xui owns the UI thread and its event loop;
  Java describes the UI as a tree that a `render` method rebuilds from state,
  matching `xui-core`'s update model (read its README at the pinned rev before
  designing S9). Callbacks run on the UI thread, and I/O is never done on it.
- `lazy.msg`: Messenger. The source of truth is the `.midl` file: LazyOS's
  `midlc` gains a Java backend that generates the interface types and codecs,
  with no reflection on the wire path. Annotations keep the hand-written side
  short:
  `@Service("os.lazy.example.v1")` on an implementation,
  `@Subscribe(topic = "system/health/*", qos = Qos.LATEST)` on a handler,
  `@Deadline(millis = 500)` on a call site's interface method. Calls return
  `CompletionStage<Reply>`; topics deliver to a handler method or a
  `lazy.async.Subscription`.
  - On Windows, a loopback in-process broker implements the same API for tests
    and demos (default). Real LazyOS services are only reachable on LazyOS.
- `lazy.os`: well-known paths (`libs/fhs`), notifications, the clipboard,
  opening files by type (`mimed`), and the trace API (`Trace.pass("NAME")`
  prints the evidence lines LazyOS harnesses read).

## D9. Testing and evidence

- **Differential tests:** a test is a Java class under `tests/java/<area>/`
  whose stdout is the result. `tools/golden.py --bless` runs it on the oracle
  JVM and writes `<Name>.out` next to it. `tools/test.py` runs every test on
  LazyVM and diffs against the `.out`. Goldens are checked in, so CI needs no
  oracle.
- **The oracle is Java 8** (Temurin 8, `LAZYVM_ORACLE_JAVA`; installed on the
  owner's machine at `%USERPROFILE%\.jdks\temurin-1.8.0_504`). LazyVM matches
  Java 8's observable behaviour, exception messages included.
- **One deliberate exception:** `Double.toString` and `Float.toString`
  follow their Javadoc ("as many, but only as many, more digits as are
  needed to uniquely distinguish"). Java 8 sometimes prints an extra digit
  (`1.23456792E8` for `123456789f`), a bug fixed in JDK 19, and LazyVM does
  not copy it. The same digits drive string conversion and `Formatter`'s
  `%e`, `%f` and `%g` (whose Javadoc defines them through `Double.toString`),
  so those differ from Java 8 for the same values: `%f` of
  `2.82879384806159E17` is `282879384806159000.000000`, not Java 8's
  `282879384806159008.000000`. A test whose output depends on this declares `// ORACLE: modern`
  and is blessed on JDK 19 or newer (`LAZYVM_ORACLE_JAVA_MODERN`, else `java`
  on `PATH`).
- `lazy.*` tests have hand-written `.out` files and run on LazyVM only.
- Rust unit tests cover the class-file parser, the verifier and the heap. A
  fuzz target (`cargo fuzz`) covers the parser and the verifier from S8.
- **On LazyOS:** from S3, `tools/lazyos_test.py` builds the musl binary, stages
  it with the test classes into a LazyOS image (the `LAZYOS_LAZYVM` switch) and
  runs the same suite in QEMU, judging the serial output.

## D10. Repo layout

```
lazyvm/
  Cargo.toml            workspace
  crates/classfile      parse class files and jars, no unsafe
  crates/vm             interpreter, heap, GC, threads, class loading
  crates/natives        native methods of lazyrt and lazy.*
  crates/platform       the Platform trait and StdPlatform (files, console,
                        clock, sockets through Rust std, on both systems)
  crates/gfx            lazy.gfx pixel windows: headless, and the Windows
                        host on winit + softbuffer (S9-00, docs/gfx.md)
  crates/lazyvm-win     Windows-only parts: xui winit window host (S9)
  crates/lazyvm-lazyos  LazyOS-only parts: Messenger, xuid (GPL-3.0-or-later, S9)
  crates/launcher       the `lazyvm` / `java` command line
  java/lazyrt           the java.* class library (Java source)
  java/lazy-api         the lazy.* API (Java source)
  tests/java            differential and lazy.* tests with .out goldens
  templates/app         IntelliJ / Gradle starter app
  tools                 javac.py, sdk.py, golden.py, test.py, licenses.py, ...
  docs                  plan.md, plan-2.md, decisions.md, design notes per stage
```

The default branch is `main` (the scaffold is on `master` and gets renamed in
S0).

## D11. Work items

The work items live in [`plan.md`](plan.md) and [`plan-2.md`](plan-2.md), each with an ID (`S3-02`), its
files and its checks. They are copied into GitHub issues on the LazyVM GitHub repo
only when a stage starts, with the ID in the title. LazyOS-side items (`LOS-…`)
become issues on the LazyOS repo.

## D12. Default charset

- **UTF-8 on every platform (default).** `file.encoding` is `UTF-8` and
  `Charset.defaultCharset()` is UTF-8 on Windows and on LazyOS alike. Java 8
  on Windows uses the ANSI code page (Cp1252 on the owner's machine), so
  `FileReader`, `FileWriter`, `PrintStream(File)`, `String.getBytes()`,
  `new String(byte[])` and the other calls without a charset give other bytes
  there for non-ASCII text.
- **Why:** one encoding for every platform, files written on Windows read
  the same on LazyOS, every char can be written, and LazyVM ships no legacy
  code page tables. Java 18 made the same choice (JEP 400).
- **Tests** name their charsets wherever output depends on it, so goldens
  blessed on Windows hold on LazyOS too. `design/io.md` (N3) and `design/nio-file.md` (section 3)
  lists the difference.

## D13. `StrictMath` is not bit-identical to fdlibm

`StrictMath` uses the `libm` crate (`crates/natives/src/math.rs`, MIT, a
port of musl's libm) and accepts its results. The `StrictMath` Javadoc asks
for fdlibm's exact results, but `libm` follows newer algorithms for some
functions, so their results can be a few ulps from Java 8's. Porting fdlibm
or adding another crate was considered and rejected (owner's decision,
S5-04). Tests must not print exact bits of the affected functions; round
them well above the error below.

Measured during S5-04: `libm` 0.2.16 against the oracle (Temurin 1.8.0_504),
10^6 inputs per function (edge values, random bit patterns, a typical range
and log-uniform magnitudes); 0.2.7 gives the same counts.

| Function | Inputs that differ | Max difference |
|---|---|---|
| `atan2` | 9.6 % (mostly results near ±π/2) | 1 ulp |
| `cbrt` | 8.3 % | 1 ulp |
| `log10` | 2.9 % | 2 ulps |
| `hypot` | 1.6 % | 1 ulp |
| `tanh` | 0.95 % | 3 ulps |
| `cos` | 0.62 % | 1 ulp |
| `sin` | 0.61 % | 1 ulp |
| `log` | 0.16 % | 1 ulp |
| `cosh` | 0.10 % | 1 ulp |
| `sinh` | 0.09 % | 1 ulp |
| `log1p` | 0.02 % | 1 ulp |

The other 12 are bit-identical on every input measured: `tan`, `asin`,
`acos`, `atan`, `exp`, `sqrt`, `ceil`, `floor`, `rint`, `expm1`, `pow`
(with Java's `NaN` special cases) and `IEEEremainder`. `Math` delegates to
the same natives. `Random.nextGaussian` uses `StrictMath.log`, so its values
can differ from Java 8's in the last digits while its draws, rejections and
pairing stay exact (`design/util.md` R1).

## D14. Size budget

The VM stays small: on every platform, the release binary with its debug info
stripped and gzipped (-9), plus `lazyrt.jar` as built (already deflated), is
under 4,000,000 bytes. `tools/size.py` measures it and fails above the budget,
and CI runs it for the Windows binary and for the static musl binary LazyOS
runs. The budget is the owner's choice (October 2026, with the sum near 1.4 MB
at the end of S5).

- **Over budget** is a failing gate like any other: shrink something (dead
  code, tables, a dependency) rather than raise the number. Raising it is the
  owner's decision and changes this entry.
- **Above 80 %** the tool warns. A plan item expected to add a lot (TLS in
  S7-05, fonts and imaging for AWT and Swing) says in its PR what it costs.
- **Data the VM needs** (Unicode tables, and later time zones or fonts)
  counts, whether it is embedded in the binary or shipped in the jar.
