# Other source languages

Could LazyVM apps be written in something more expressive than Java 8? This
note looks at Kotlin, Groovy, Scala, Clojure, Lombok-flavoured Java and
newer Java, records a probe run on 2026-10-28, and ends with a
recommendation. It is a proposal: nothing here is decided until the owner
confirms it in [`decisions.md`](decisions.md).

"Source language" here means the language **apps** are written in. The
class library (`lazyrt`) and `lazy.*` stay in Java; section 5 says why.

## 1. What a language has to fit

- **Class-file level** (D1): major versions 45 to 52, and `invokedynamic`
  only through `LambdaMetafactory.metafactory`/`altMetafactory`. A language
  whose compiler emits other bootstrap methods, or newer class files, does
  not run.
- **Class library** (D6): only the Java SE 8 API subset `lazyrt` provides.
  A language's runtime library calls into it like any app does, so its
  needs count as the app's needs.
- **Size** (D14): the VM plus `lazyrt.jar` stays under 4 MB. A language
  runtime shipped inside every app jar is outside that budget but against
  its spirit; one shipped with LazyOS would be a new line in it.
- **Clean room** (shared rule 1): a language's compiler and runtime are not a
  JDK or a Java class library, so running them is fine. Whether agents may
  read their source to debug a failure is not settled by the rule's wording
  (section 6).
- **Speed**: LazyVM is an interpreter. A runtime that does a lot of work at
  startup, or generates classes at run time, is slow on it.

## 2. Probe

**Method.** Programs were compiled on Windows and run on the Java 8 oracle
(Temurin 1.8.0_504) and on LazyVM (release build of `main` at `f4b8c4d`),
and their outputs diffed. Only compiled output we produced was inspected
with `javap`. The probe sources live outside the repo.

- **Kotlin 2.4.20** (compiler and stdlib from the Gradle cache), compiled with
  `-jvm-target 1.8 -jdk-home dist/lazyvm-sdk -no-reflect`, so the compiler
  resolves the JDK API against `lazyrt` only. Two programs, each check in
  its own `try` so one failure does not hide the rest:
  - language: 36 checks (data, sealed, enum and value classes, `when`,
    null safety, `lateinit`, `lazy`, extensions, default and named
    arguments, `reified`, `tailrec`, SAM conversion, labelled loops,
    ranges, unsigned types, destructuring, string templates);
  - stdlib: 41 checks (collection operations, sequences and the
    `sequence {}` coroutine builder, `buildList`/`buildMap`, `Regex`,
    string functions, `kotlin.random`, `kotlin.math`, `Result`,
    `::class`, file helpers).
  A third program used `kotlinx-coroutines-core` 1.9.0 (`runBlocking`,
  `async`, `Channel`, `Flow`, `delay`, `withTimeoutOrNull`).
- **Groovy 4.0.29**, compiled with `groovy.target.bytecode=1.8`, once dynamic
  and once `@CompileStatic`.
- **Lombok 1.18.46** through javac 25 with `-source 8 -target 8
  -bootclasspath dist/lazyrt.jar` (`@Data`, `@Value`, `@With`, `@Builder`,
  `@NonNull`, `val`).
- Scala and Clojure were not run (not cached here; nothing was downloaded).

**Result.**

| Probe | Class files | Result on LazyVM |
|---|---|---|
| Kotlin language | 52; only `LambdaMetafactory.metafactory` | 35 of 36 checks match the oracle |
| Kotlin stdlib | 52; only `LambdaMetafactory.metafactory` | 36 of 41 match |
| Kotlin coroutines | 52 | fails at start: `NoClassDefFoundError: java/lang/ThreadLocal` |
| Groovy, dynamic or `@CompileStatic` | 52; every call site through Groovy's own bootstrap method | fails at start: `NoClassDefFoundError: java/util/EventListener` |
| Lombok | 52; nothing at run time | identical to the oracle |

Every Kotlin failure is a missing `lazyrt` API, not a VM bug:

| Missing | Reached from | Planned in |
|---|---|---|
| `String.toUpperCase(Locale)` (and, by the same path, `toLowerCase(Locale)`) | `uppercase()`, `lowercase()`, `replaceFirstChar { it.titlecase() }` | nowhere; a small S5 gap (`Locale` exists) |
| `AtomicReference` | `asSequence()` on iterators, `useLines`, `readLines` | S6-03 |
| `ThreadLocal`, `LockSupport`, `Atomic{Integer,Long,Reference}FieldUpdater`, `AtomicReferenceArray`, `Executor`, `CancellationException`, `RejectedExecutionException` | `kotlinx-coroutines` start-up | S6-01, S6-03, S6-04 |

Two smaller differences, neither a Kotlin problem:

- `listOf(1)::class.qualifiedName` names `lazyrt`'s private implementation
  class (`java.util.FixedCollections.OneList`, not the oracle's
  `java.util.Collections.SingletonList`). Any program printing class names
  of library internals sees this, in Java too.
- `"%5.2f".format(...)` uses the default locale, which on this machine is
  French on the oracle (`3,14`) and not on LazyVM (`3.14`). Goldens must
  pin the oracle's locale, as for Java tests.

**Lesson about compile-time checking.** Compiling against `lazyrt` with
`-jdk-home` catches only the app's own calls. Stdlib functions marked
`inline` are copied into the app with their JDK calls already resolved
(the `toUpperCase(Locale)` gap compiled cleanly), and the stdlib's own calls
are never checked. Only running differential tests finds these gaps.

**Size.** `kotlin-stdlib-2.4.20.jar` is 1,853,314 bytes (995 classes),
about twice `lazyrt.jar` (871,887 bytes). The probes loaded 260 stdlib
classes, 726,088 bytes as a jar of just those classes. A method-level
shrinker (ProGuard, R8) should do much better, but none was run, so that is
unmeasured. `kotlinx-coroutines-core` adds 1,463,587 bytes.

**Speed** (Windows, release build, wall clock, three runs): the Kotlin
language probe 77 to 95 ms, the stdlib probe 95 to 98 ms, a Java hello 49 to
68 ms. Loading Kotlin's stdlib classes costs tens of milliseconds, not
seconds.

**Footprint on the oracle** (`-verbose:class`, classes beyond a hello
loaded from a jar): the Kotlin language probe 331 JDK classes, 140 of them
`java.lang.invoke` (HotSpot's own lambda plumbing, which LazyVM replaces
with its native metafactory); Groovy `@CompileStatic` 703 JDK classes, 276
of them `java.lang.invoke`, plus 511 Groovy classes.

## 3. The candidates

### Kotlin: fits

- Compiles to version 52 with `-jvm-target 1.8`, and Kotlin 2.4.20 still
  accepts that target without a warning. Lambdas, SAM conversions and
  function references all go through `LambdaMetafactory.metafactory`, which
  LazyVM already implements natively (D1, S4). String concatenation and
  `when` on strings compile to plain bytecode at that target.
- Its stdlib uses a small part of the JDK, mostly what S5 and S6 already
  plan. Suspending functions are compiled into ordinary state machines, so
  `sequence {}` and `iterator {}` already work. `kotlinx-coroutines` needs
  only the S6 work.
- Apache-2.0 compiler and stdlib, so apps may ship the stdlib under any
  license.
- IntelliJ is Kotlin's home, and the `lazyvm-sdk` fake JDK (D3) works as its
  JDK: the probes were compiled against it.
- **Costs:** the stdlib in every app (section 2's size figures), and a
  compiler that could drop the 1.8 target in some later release, as Scala
  did. Pinning one Kotlin version in a `tools/kotlinc.py` covers the second
  point.

### Groovy: does not fit

Every call site, `@CompileStatic` included, goes through Groovy's own
`invokedynamic` bootstrap, which needs general `MethodHandle` support (out in
D1). Its runtime builds a metaclass registry at start-up that pulls in
`java.beans` and twice Kotlin's JDK footprint, and the jar is 7.7 MB.
Supporting it would mean implementing `java.lang.invoke` in full.

### Scala: does not fit

Not run here. Scala 3.8 and later need JDK 17 to compile and run, and only
the Scala 3.3 LTS line still emits Java 8 bytecode
([Scala news](https://scala-lang.org/news/next-scala-lts-jdk.html),
[3.8 release notes](https://scala-lang.org/news/3.8/)). Choosing Scala would
pin the project to an LTS that new Scala work has left behind, and Scala's
standard library is several MB. Scala's JDK 8 lazy values relied on
`sun.misc.Unsafe` according to that announcement, which LazyVM cannot provide
(shared rule 2: no spec describes it).

### Clojure: does not fit

Not run here. Clojure compiles its code at run time and defines the classes
through its own class loader, so it needs `ClassLoader.defineClass`,
reflection-driven calls and its whole core library loaded at start-up. On an
interpreter that start-up is the problem, before any app code runs.

### Java with Lombok: fits today, small gain

Lombok runs inside javac and leaves plain Java 8 bytecode with no runtime
jar: the probe matched the oracle exactly. It removes boilerplate (data
classes, builders, `val`) but adds no new language features, and it works by
patching javac internals, so each new JDK release needs a matching Lombok
release.

### Newer Java: possible, but a large change of rules

Two routes, neither probed:

- **Accept newer class files.** Records, string concatenation and pattern
  `switch` compile to `invokedynamic` with three more bootstrap methods
  (`StringConcatFactory`, `ObjectMethods`, `SwitchBootstraps`), which LazyVM
  could implement natively the way it does `LambdaMetafactory`, plus nest
  mates and the `PermittedSubclasses` attribute. But records need
  `java.lang.Record`, which is not in the Java SE 8 API, so shared rules 1
  and 2 would have to move to a later Java SE spec, and the oracle with them.
- **Compile newer syntax down to version 52** with a javac plugin that lifts
  the source-level check (Jabel is one; Apache-2.0). It gives `var`, switch
  expressions, text blocks and `instanceof` patterns, but not records, and
  like Lombok it depends on javac internals.

## 4. Recommendation

1. **Kotlin as the supported second language**, after S6 (it needs S6-01,
   S6-03 and S6-04 for the stdlib to be complete and for coroutines), with
   its own differential suite: `tests/kotlin/<area>/*.kt`, compiled by a
   pinned Kotlin through a `tools/kotlinc.py`, blessed on the oracle JVM
   with the stdlib on its class path, and run by `tools/test.py` on Windows
   and LazyOS. The two probe programs are a ready start for it.
2. **Fix the `toUpperCase(Locale)`/`toLowerCase(Locale)` gap now**, as S5
   work: it is in the Java SE 8 API, and plain Java apps hit it too.
3. **Lombok allowed but not promoted**: it already works, and nothing in
   LazyVM has to change for it.
4. **Not Groovy, Scala or Clojure.** Each needs general `java.lang.invoke`,
   a newer JDK, or run-time class generation.
5. **Newer Java** only as a separate decision that moves the spec baseline;
   it is not a language add-on.

## 5. Why `lazyrt` and `lazy.*` stay in Java

- `lazyrt` must not depend on another library, and Kotlin code needs its
  stdlib for lambdas, ranges and null checks. Kotlin can be compiled without
  it, but then little of what makes it expressive is left.
- Every `public` and `protected` member of `java.*` must match the Java SE 8
  signature exactly (shared rule 2). Kotlin generates members of its own
  (property accessors, `Companion` fields, `$default` bridges), which would
  have to be suppressed one annotation at a time.
- The size budget counts `lazyrt.jar`.

`lazy.*` can serve Kotlin well while staying Java: single-method interfaces
for callbacks (Kotlin's SAM conversion), `CompletionStage` results
(`kotlinx-coroutines` turns them into suspending calls with `await()`), and
nullness annotations Kotlin understands, such as JSpecify's (Apache-2.0,
compile-time only), so Kotlin sees `String?` rather than an unchecked
platform type.

## 6. Open questions for the owner

- Confirm Kotlin as a supported language and the suite in section 4.
- May agents read the Kotlin stdlib's or `kotlinx-coroutines`' source to
  debug a failure? They are not JDKs or Java class libraries, so shared rule 1
  does not forbid it in so many words, but the rule's intent of keeping JDK
  internals out could be read either way. Debugging from observable
  behaviour only, as `compiler.md` does for javac, is the cautious default.
- Should LazyOS ship one shared, shrunk Kotlin stdlib, which would then
  count toward D14, or should every app carry its own?
