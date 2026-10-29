# LazyVM plan

LazyVM runs Java 8 bytecode on Windows and on LazyOS. It is one interpreter
written in Rust, a small class library covering the essentials of Java SE 8,
and a `lazy.*` API for xui, Messenger and async I/O. Apps are written and built
in IntelliJ on Windows, run there on LazyVM, and run unchanged on LazyOS.

Stages S8-S14 are in [`plan-2.md`](plan-2.md); S11 runs right after S8. Read [`decisions.md`](decisions.md) first: it fixes the target level, the
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
| S3 | Class path, jars, launcher, IntelliJ | in progress |
| S4 | Language completeness: lambdas, annotations, reflection | in progress |
| S5 | Core library | in progress |
| S6 | Threads, concurrency, `CompletionStage`, the event loop | not started |
| S7 | Networking | not started |
| S8 | Verifier and hardening | not started |
| S11 | Streams, `java.time`, logging, JDWP | not started |
| S9 | `lazy.ui` on xui | not started (S9-00 prototype done) |
| S10 | Messenger and LazyOS integration | not started |
| S12 | Explore: AWT on xui | not started |
| S13 | Explore: Swing and media | not started |
| S14 | Proposed: javac 8 on LazyVM | not started |

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
- [x] S0-05 License check. `tools/licenses.py` fails on any crate built for a
      shipped target (`cargo tree --all-features -e normal,build` for Windows
      MSVC and Linux musl) whose license is not in the allowed list
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

- [x] S3-01 Class path: directories and jars (zip with stored and deflate
      entries, via the `miniz_oxide` crate), boot class path = `lazyrt.jar`,
      application class path from `-cp`, and `Class.getResourceAsStream` /
      `ClassLoader.getSystemResource`.
      Check: `tests/java/s3/Resources` (run from a jar).
- [x] S3-02 `-jar`: `META-INF/MANIFEST.MF` `Main-Class` and `Class-Path`.
      Check: `tools/test.py --jar` runs every S1-S3 test from a jar.
- [x] S3-03 Launcher options as IntelliJ passes them
      ([`decisions.md`](decisions.md) D3): `-D` into `System.getProperty`,
      `-Xmx`/`-Xss`, `-verbose:class`, `-version` (prints `lazyvm` plus the
      class library version and never claims "java"), and the IntelliJ extras
      ignored.
      Check: `tests/launcher/intellij_cmdline.txt` (a real captured IntelliJ command line) runs `Hello`.
      (Done as a launcher unit test with IntelliJ's documented options until
      the owner captures a real command line; `tools/launcher_test.py`
      covers `-jar`, manifests and jar errors against Java 8.)
- [ ] S3-04 Fake JDK home: `tools/sdk.py` builds `dist/lazyvm-sdk/`
      ([`decisions.md`](decisions.md) D3).
      Check: `docs/intellij.md` walks through registering the SDK and running the template; an owner-run manual check is recorded in the PR with a screenshot (agents cannot drive IntelliJ).
      (Agent part done: `tools/sdk.py`, the launcher's IDE options and
      `-version`, `docs/intellij.md`. Waiting on the owner's IDE check.)
- [x] S3-05 App template: `templates/app/` with an IntelliJ project, a
      Gradle file and a `Main.java`.
      Check: `gradle -p templates/app jar` produces a jar that `lazyvm -jar` runs.
- [x] S3-06 LazyOS run: `lazyvm-lazyos` platform v0 (console, files, clock)
      and `tools/lazyos_test.py`, which builds the musl binary, stages
      `lazyrt.jar` and the tests, and runs the suite in a LazyOS image under
      QEMU. Needs LOS-01.
      Check: `python tools/lazyos_test.py --filter s1,s2,s3` all `PASS`.
      (Done: 32/32 inside LazyOS under QEMU on 2026-10-27, through LazyOS's
      LOS-01 hook. No separate `lazyvm-lazyos` crate was needed: the std
      platform runs unchanged through the Linux ABI.)

Gate: the template app runs from IntelliJ's Run button on Windows and through
`lazyos_test.py` on LazyOS.

## S4. Language completeness: lambdas, annotations, reflection

Goal: everything javac 8 can emit runs, and annotations and reflection are
good enough for `lazy.msg` (S10).

- [x] S4-01 `invokedynamic` with `LambdaMetafactory` (`metafactory` and
      `altMetafactory`, including the serializable and marker flags, which are
      ignored with a note). The bootstrap is recognised by its owner and name;
      the VM generates a hidden class implementing the functional interface,
      with bridges for `altMetafactory`, and one call site object per `indy`
      instruction. Other bootstrap methods throw `BootstrapMethodError`.
      Check: `tests/java/s4/Lambdas`, `MethodRefs` (static, bound, unbound, constructor), `Capture`.
- [x] S4-02 Language support classes: `Enum` (`values`, `valueOf`, `ordinal`,
      `EnumMap`, `EnumSet`), string `switch`, `Iterable` for-each, varargs,
      try-with-resources (`addSuppressed`), and `assert` (`-ea`).
      Check: `tests/java/s4/Enums`, `StringSwitch`, `TryWithResources`, `Asserts`.
- [x] S4-03 `java.util.function` (all interfaces) and the default methods on
      `Comparator`, `Iterable` and `Map` that use them.
      Check: `tests/java/util/FunctionalBasics`, `Comparators`.
- [x] S4-04 Reflection core ([`decisions.md`](decisions.md) D6): `Class`
      metadata, `getDeclared*`/`get*` for methods, fields and constructors
      (public filtering per the Javadoc), `Method.invoke` with boxing and
      `InvocationTargetException`, `Field` access, `Modifier`, and
      `Class.forName` with initialization. No access-checking beyond
      `setAccessible` bookkeeping.
      Check: `tests/java/s4/ReflectBasics`, `ReflectInvoke`.
- [x] S4-05 Runtime annotations: parse `RuntimeVisible*Annotations` and
      `AnnotationDefault`, generate annotation implementation classes (equals,
      hashCode and `toString` as in the Javadoc), and implement
      `getAnnotation`, `getAnnotations`, `isAnnotationPresent` and
      `getParameterAnnotations`. Includes `@Retention`, `@Target`, `@Inherited`,
      `@Repeatable` lookup and `@FunctionalInterface`.
      Check: `tests/java/s4/Annotations` (nested, arrays, enums, class values, defaults), `InheritedAnnotations`.
- [x] S4-06 Generic signatures: `getGenericSuperclass`,
      `getGenericInterfaces`, `Method.getGenericReturnType`, and
      `ParameterizedType` / `TypeVariable` / `WildcardType`, all parsed from
      `Signature` attributes. They are needed by `lazy.msg` codecs.
      Check: `tests/java/s4/GenericSignatures`.

Gate: `tests/java/s4/Showcase` (a small event bus built with lambdas and an
`@Subscribe` annotation scanned by reflection) passes on both platforms.

## S5. Core library

Goal: the everyday `java.util` and `java.io` surface from
[`decisions.md`](decisions.md) D6, with oracle-matched output.

- [x] S5-01 Collections I: the collection interfaces and abstract classes,
      `ArrayList`, `LinkedList`, `Vector`/`Stack`, `ArrayDeque`, `Collections`
      (algorithms, factories, unmodifiable, synchronized and checked views) and
      `Arrays` (objects sort with the merge sort of
      [`design/collections.md`](design/collections.md) 7.S1, so compare calls,
      stability and exceptions match the oracle; primitives sort by key), with
      the iterator and `ConcurrentModificationException` behaviour of that
      specification. Written clean-room from the Javadoc and that spec.
      Check: `tests/java/util/Lists`, `Sorting`, `Iterators` (and the rest of
      `tests/java/util`) on both.
- [x] S5-02 Collections II: `HashMap`/`LinkedHashMap`/`HashSet`/`LinkedHashSet`
      (the Java 8 hash spreading, table growth and tree bins, so iteration
      order matches the oracle), `TreeMap`/`TreeSet` (red-black, with range
      views), `Hashtable`, `PriorityQueue`, `IdentityHashMap`, `EnumMap`/
      `EnumSet`, the `Map` default methods and `Random`, to the same
      specification.
      Check: `tests/java/util/HashOrder`, `TreeMaps`, `MapDefaults` (and the
      rest of `tests/java/util`) on both.
- [x] S5-02a `WeakHashMap` (needs S5-07): the `java.util` map whose keys are
      held through `WeakReference`, with entries expunged through a
      `ReferenceQueue`, behaviour specified in `design/collections.md` like
      the other maps ([`design/weakhashmap.md`](design/weakhashmap.md)).
      Check: `tests/java/util/WeakMaps`, `WeakMapOrder`, `WeakMapViews` (both).
- [x] S5-03 Text: `Formatter`/`String.format`/`printf` (all conversions in the
      Javadoc except `%t` dates, which come with S11), `Double.toString`
      (the shortest form its Javadoc asks for, D9), `Integer.parseInt` and friends,
      `StringJoiner`, `String.split` (literal patterns exist since S5-01;
      regular expressions come with this item), and `java.util.regex` (`Pattern` and
      `Matcher` backtracking engine, Java syntax subset documented in
      `docs/regex.md`).
      Check: `tests/java/util/FormatAll`, `DoubleToString` (100k random values against the oracle), `Regex`.
- [x] S5-04 Utilities: `Optional*`, `Objects`, `Random` (the exact LCG, done with S5-02),
      `Scanner`, `Properties`, `UUID`, `Base64`, `BitSet`, `StringTokenizer`, to
      [`design/util.md`](design/util.md) and the files it indexes.
      Check: `tests/java/util/Utilities`, `RandomSeq`, `Base64s`, `Base64Streams`,
      `Uuids`, `BitSets`, `UtilBuffers`, `Scanners`, `ScannerNumbers`, `ScannerEdges`,
      `ScannerLong`, `ScannerSources`, `Dates`, `PropertiesIO`, `Tokenizers`, and `tests/java/lazy/util/ScannerPositions`,
      `ScannerMargin`
      (both).
      Done: all of the above, plus `Base64`'s `ByteBuffer` overloads, `BitSet.valueOf` of
      buffers and a minimal `java.util.Date` for `Properties.store`; every Check test passes on
      both (`StrictMath` stays on `libm`, decisions.md D13). `Scanner`'s `BigInteger`/`BigDecimal`
      readers wait for `java.math`.
- [x] S5-05 `java.io`: byte and char streams, `Buffered*`, `InputStreamReader`
      and `OutputStreamWriter` over the charsets, `File`, `FileInputStream`,
      `FileOutputStream`, `RandomAccessFile`, `Data*Stream`, `PrintWriter`,
      and `System.in` (a `BufferedInputStream` over the console). File paths
      go through `lazy.internal.FileSystem` (`design/files.md`; on LazyOS,
      through the Linux ABI) and one path syntax per platform, chosen at run
      time (`lazy.internal.PathSyntax`). Behaviour: `design/io.md`. Not here:
      `FileDescriptor` and the constructors taking one, `deleteOnExit`,
      `File.toURL` (S7) and the free/total/usable space queries.
      Check: `tests/java/io/Streams`, `Files1`, `Charsets` (both).
- [x] S5-06 `java.nio`: heap `ByteBuffer`/`CharBuffer` (and the other typed
      buffers and views, generated by `tools/buffers.py`), `charset` (the six
      standard charsets, coded once in `java.nio.charset.Codec` for String,
      the readers, the writers and the coders), and `java.nio.file` (`Path`,
      `Paths`, `Files` read, write, copy, move, delete, attributes and
      `walkFileTree`, plus `DirectoryStream` and glob/regex `PathMatcher`
      over `java.util.regex`, `docs/regex.md`).
      `Files.list`, `walk`, `find`, `lines`, `BufferedReader.lines` and
      `CharBuffer.chars` return streams and come with S11, as do the
      `java.time` conversions of `FileTime`; channels, watch services,
      providers and non-basic attribute views are out of scope (`design/io.md`
      section 15). Behaviour: `design/io.md` and `design/nio-file.md`.
      Check: `tests/java/nio/Buffers`, `FilesWalk` (both).
- [x] S5-08 Unicode tables: `tools/unicode.py` generates
      `crates/natives/data/unicode.bin` by observing the Java 8 oracle (no
      download): general categories, directionality, a few flag bits, simple
      and special case mappings, digit and numeric values. The `Character`
      natives (and `String` case conversion, via `StringCase`) use them instead
      of Rust's `char` properties, and the tool verifies the file against the
      oracle for every code point.
      Check: `tests/java/lang/CharacterTables` (a per-property checksum over
      every code point, diffed with the oracle) and `CaseMapping`.
- [x] S5-07 References: `WeakReference`, `SoftReference` (cleared on memory
      pressure), `ReferenceQueue`, and finalization not supported (documented:
      `finalize` is never called).
      Check: `tests/java/lang/WeakRefs`.

- [x] S5-09 `java.util.regex` engine: an explicit backtrack stack (no recursion per
      iteration, so a non-flat loop runs as deep as the heap allows instead of
      about 500 to 2000 iterations), and the loop memo extended across lookarounds
      and possessive repeats (so `(?:a|a*)*b(?=c)` stops being exponential).
      Behaviour stays as pinned by the `Regex*` goldens; see "Known gaps" in
      `docs/regex.md` for the measured limits.
      Check: the `Regex*` goldens unchanged, plus `tests/java/util/RegexDepth`
      (100000 iterations of `(?:(a)b)*`, `(a)\1*`, `(?:(?=a)a)*`) and a timed
      row for `(?:a|a*)*b(?=c)` (`RegexMemoAround`: on 20 `a`, because the oracle
      is exponential on it too and does not finish 30 `a` to bless the golden;
      30 `a` takes 0.25 s here), a timed `RegexRuns` row for `[ ]*,[ ]*` failing
      to `find` in 5000 spaces (42 s at S5-04, 0.02 s now, the oracle 25 ms), and
      `RegexNamesLocale` (property names under a Turkish default).

S5-10 to S5-12 were added at the S5 checkpoint (2026-10-29) for the gaps the
Minicraft Plus port found. They run after S6-01 and S6-02.

- [ ] S5-10 `java.math`: `BigInteger`, `BigDecimal`, `MathContext` and
      `RoundingMode`, the whole Java SE 8 API of each. That covers arithmetic,
      bit operations, `modPow`/`modInverse`/`gcd`, `isProbablePrime`, radix
      parsing and printing, `toByteArray`, scale and rounding, and the three
      `BigDecimal` string forms. `valueOf(double)` and `new BigDecimal(double)`
      must be exact. Also: `Scanner`'s `BigInteger`/`BigDecimal` readers, and
      `Formatter` on both types. Long values must not be quadratic: multiplying
      and printing a 100000-digit number takes under 2 s.
      Check: `tests/java/math/BigIntegers`, `BigDecimals`, `MathFuzz` (seeded
      random operations), `tests/java/util/ScannerBig`.
- [ ] S5-11 `java.util.zip` over the `miniz_oxide` the VM already uses for jars:
      - `ZipFile`, `ZipEntry`, `ZipInputStream`, `ZipOutputStream`, `ZipException`;
      - `Inflater`/`Deflater` and their streams, `GZIPInputStream`/`GZIPOutputStream`;
      - `CRC32`, `Adler32`, `CheckedInputStream`.
      A malformed archive is an exception, never a panic. Tests print contents
      and checksums, never compressed bytes, since those may differ from zlib's.
      Check: `tests/java/zip/ZipReadWrite` (written with `ZipOutputStream`,
      read back with `ZipFile` and `ZipInputStream`, and a checked-in fixture
      zip), `GzipRoundTrip`, `Checksums`, `ZipMalformed`.
- [ ] S5-12 Resources as URLs, before the rest of S7-02. It covers:
      - `java.net.URL` for `file:` and `jar:`: parts, `toString`, `equals` and
        `hashCode`, `openStream`, and a minimal `URLConnection` for those protocols;
      - `MalformedURLException`, `URI.toURL`;
      - `Class.getResource` and `ClassLoader.getResource`/`getResources`, which
        return `file:` or `jar:` URLs.
      Unknown protocols throw `MalformedURLException` until S7-02 adds `http`.
      Check: `tests/java/net/UrlFile`, `ResourceUrls` (also with `--jar`).

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
      `DatagramSocket`, `URI` (RFC 2396 as the Javadoc says), `URL` (`http`;
      `file:` and `jar:` come with S5-12),
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


S8-S14 and the LazyOS-side items continue in [`plan-2.md`](plan-2.md).
