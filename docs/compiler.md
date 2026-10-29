# A compiler on LazyOS

Can a LazyOS machine turn `.java` sources into class files LazyVM runs? Today
it cannot: apps are compiled on Windows with JDK 25 (`-source 8 -target 8`,
[`decisions.md`](decisions.md) D3) and the jar is copied over. This note
weighs three ways to change that, records a probe run on 2026-10-26, and ends
with a recommendation. It is a proposal: A, B and the class library B needs
are proposed in `decisions.md` (D4 for A, D6 and `plan-2.md` S14 for B) and
nothing is decided until the owner confirms them there.

## 1. Constraints

- **Class-file level.** LazyVM loads major versions 45 to 52 only (D1). A
  compiler that runs *on LazyVM* must itself be Java 8 bytecode. javac 9 and
  later can still emit version 52, but only while running on a newer JVM.
- **Clean room** (shared rule 1). Running a compiled program and watching what
  it does is allowed; reading its source, or disassembling a JDK's classes, is
  not. A third-party compiler that fails on LazyVM is debugged from its
  observable behaviour and the Javadoc only: what it prints, its exit status,
  exception messages and stack traces. LazyVM's own `-Xtrace:interp`, `javap`
  and bytecode or verifier dumps of a JDK's classes (javac's `tools.jar`
  included) count as disassembly and are out. A `VerifyError` or
  `ClassFormatError` that LazyVM raises on a `tools.jar` class is a LazyVM
  bug: it is reproduced with a class we compiled, and the bytecode details
  in its message are not read.
- **Namespace** (shared rule 2). Whatever `java.*`/`javax.*` the compiler needs
  must be part of the Java SE 8 API, with exact signatures. Nothing JDK-internal
  (`sun.*`, `com.sun.*` outside the compiler itself) can be provided, because
  no spec describes it.
- **What LazyOS runs.** LazyOS runs unmodified `x86_64-unknown-linux-musl`
  programs, static or dynamically linked through musl's loader. Its
  self-hosting work (LazyOS `docs/self-hosting-plan.md`, SH0 and SH1) already
  runs rustc, cargo and rust-lld on the machine. Its Linux ABI delivers faults
  as `SIGSEGV` to a handler and supports JIT-style executable memory (W^X
  flips under `mprotect`, used by JavaScriptCore), threads and futexes
  (LazyOS `docs/linux-abi-plan.md`); SH0 found rustc's `posix_spawn` of the
  linker working too.

## 2. Options

### A. A stock JDK on LazyOS

Put a musl ("Alpine") build of a JDK in the LazyOS image behind a build switch,
the way `LAZYOS_RUSTC=1` stages rustc. `javac` then runs on HotSpot on LazyOS,
and can be any version, emitting `--release 8` for LazyVM.

- **For:** works independently of LazyVM's progress; the full language and
  annotation processing on day one; it exercises LazyOS's Linux ABI hard
  (signals on faults, a JIT, many threads); and it gives LazyOS an on-device
  oracle JVM.
- **Against:** a second JVM ships on LazyOS, a few hundred MiB of it. It must
  be presented as a developer tool for building, never as the way apps run.
  It is GPLv2 with the Classpath Exception: shipped beside the GPLv3 OS as a
  separate program (aggregation, no linking), with the source offer GPLv2
  requires. The staging strips the JDK's `src.zip`; the image carries only
  the written source offer, and the corresponding source is kept outside
  both repos and the image: agents read LazyOS, and shared rule 1 forbids
  them OpenJDK source.
- **Where:** entirely in the LazyOS repo, tracked in its own plan (not a
  `LOS-` item, which is LazyOS work LazyVM needs), with a staging script
  modelled on `tools/selfhost/`. Nothing in this repo changes beyond the
  proposed D4 note.
- **Unknowns:** whether HotSpot's startup probes (cgroups, `/proc`, `/sys`,
  CPU detection) and its safepoint and implicit-null-check signal handling
  work on LazyOS's ABI. A spike answers that: boot the image, run
  `javac Hello.java && java Hello` from the console.

### B. A compiler running on LazyVM

Run an existing Java 8-bytecode compiler with `lazyvm`. Candidates:

| Compiler | License | Runs on Java 8 | Language | Notes |
|---|---|---|---|---|
| javac 8 (`tools.jar` of a JDK 8) | GPLv2 + Classpath Exception | yes | all of Java 8 | the reference compiler; the probe below is for it |
| ECJ (Eclipse) | EPL-2.0 | only older releases | all of Java 8 | recent releases need Java 11, later Java 17, to run |
| Janino | BSD-3-Clause | yes | a subset: generic type arguments are parsed and ignored; lambda support unconfirmed | small; fits scripting, not general app builds |

- **For:** no second VM; the compiler becomes the largest real program LazyVM
  runs, a strong end-to-end test of the interpreter, the verifier and the
  class library; the same jar works on Windows and LazyOS.
- **Against:** it cannot start before S8 (D1: the verifier comes before any
  jar we did not build) and needs S4 and S5 (`URLClassLoader` builds on
  S5-12's `file:` and `jar:` URLs) as well, plus three `javax` packages that D6 rules out today
  (section 5). It is placed after S11 so the S11-05 performance pass comes
  first: an interpreted javac's speed is the main risk. The compiler's
  internals are opaque to us under the clean room, so failures are found by differential
  output only. And LazyVM is an interpreter, so compiles are slower than on
  HotSpot.
- **Shipping:** this repo would not carry `tools.jar`. The test harness takes
  it from the oracle JDK 8 (`LAZYVM_ORACLE_JAVA`). Whether LazyOS ships it in
  an image is a separate license decision (the same GPLv2 source offer as A).

### C. Our own compiler

Write a compiler from the JLS SE 8, in Rust or in Java. Clean by construction
and fully ours, but the JLS's expression typing and generic method inference
(chapters 15 and 18), lambdas, inner classes and definite assignment make it a
project measured in months at least, and it would be a second way to do what
javac already does. Worth reconsidering only if B is blocked.

## 3. Probe: what javac 8 needs from the class library

**Question.** Does javac 8 reach into JDK-internal classes that LazyVM can
never provide? If so, B is blocked for javac.

**Method** (observable behaviour only, shared rule 1). Temurin 1.8.0_504 ran
`com.sun.tools.javac.Main` from its `tools.jar` with `-verbose:class`,
compiling `-Xlint:all -g` a test file using generics with bounded wildcards,
inner, local and anonymous classes, enums with bodies, a runtime annotation
with array members, default and static interface methods, lambdas,
method and constructor references, string and enum switch,
try-with-resources, multi-catch, streams, `Optional`, labelled break, numeric
literals with underscores and binary digits, and `assert`. The program
compiled and ran correctly. The classes a plain hello program loads on the
same JVM were then subtracted.

**Result.**

| Loaded from | Classes |
|---|---|
| `tools.jar` (javac itself) | 884 |
| `rt.jar` and locale data, also loaded by the hello baseline | 414 |
| `rt.jar` and locale data, beyond the baseline | 327 |
| total | 1,625 |

None of the `sun.*` classes in the 327 appears to come from a direct call by
javac. The likely
cause of each, inferred from the load order only (not checked; running javac
on LazyVM in S14-01 is the real test):

- `sun.misc.URLClassPath`, `JarIndex`, `FileURLMapper`,
  `sun.security.util.SignatureFileVerifier`: the application class loader
  opening `tools.jar`. LazyVM has its own jar loader (S3-01).
- `sun.reflect.NativeMethodAccessorImpl`: `Method.invoke`.
- `sun.nio.cs.*`, `sun.util.locale.provider.*`, `sun.util.resources.*`,
  `sun.text.resources.fr`: charsets and locale data (the machine's locale is
  French).
- `sun.misc.FloatingDecimal`: loaded right after javac's numeric-literal
  tokens, the pattern of a public `Double`/`Float` parse call.

The SE 8 API it used beyond the baseline, by area:

- `javax.lang.model.*` (element, type, util), `javax.tools`,
  `javax.annotation.processing`;
- `java.util.regex` (60 classes loaded);
- `java.util.zip` and `java.util.jar` (`ZipFile`, `Inflater`, `CRC32`,
  `JarFile`), `RandomAccessFile`, `Data*Stream`;
- `ServiceLoader`, `ResourceBundle` and `ListResourceBundle` with locale
  lookup, `MessageFormat`, `Formatter`, `DecimalFormatSymbols`;
- `EnumSet`, `EnumMap`, `ArrayDeque`, `LinkedList`, `LinkedHashSet`;
- `URLClassLoader` (the annotation-processor path), `java.lang.Package`, and
  reflection.

**Timing** (same machine, HotSpot): a hello file took 0.34 s with
`-Xint` (interpreter only), the test file plus hello about 0.8 s. LazyVM's
`match`-loop interpreter will be some multiple of that; it is not measured.

**Limits of the probe.**

- Subtracting the baseline also hides any internal class the JVM loads at
  startup (`sun.misc.Unsafe`, `sun.misc.SharedSecrets` and the like). A direct
  use of one by javac would not show here. Tools that analyse the JDK's own
  class files (`jdeps` on `tools.jar` or `rt.jar`) are out under shared
  rule 1. The test is running the jar on LazyVM: an unknown class is then a
  `NoClassDefFoundError` naming it.
- One source file, no annotation processors, no `-sourcepath`, no
  `-bootclasspath`. S14-01 compiles all of `tests/java` with
  `-bootclasspath dist/lazyrt.jar`, which is how javac would run on LazyVM.

## 4. Recommendation

1. **A now, in the LazyOS repo**, as a developer tool next to rustc, after the
   owner agrees that HotSpot may ship in a LazyOS image. It needs nothing from
   LazyVM.
2. **B with javac 8 as LazyVM's long-term target**, a new stage after S11
   (`plan-2.md` S14), judged by byte-identical class files against javac 8 on
   the oracle JVM on Windows, and on LazyOS by running what it compiled
   against the tests' goldens. It needs the D6 proposal in section 5.
3. **Not C**, unless S14 finds javac 8 blocked by an internal class.

## 5. Class library changes B needs

The "Compiler support" row of [`decisions.md`](decisions.md) D6 proposes
these, as far as javac 8 uses them. It is not confirmed.

- `javax.lang.model` (all subpackages), `javax.tools` and
  `javax.annotation.processing`. All three are in the Java SE 8 API, so
  shared rule 2 allows them; D6's "other javax.*" in the "Out" row is what
  excludes them today.
- `java.util.zip` and `java.util.jar` as public API: javac probably opens the
  `-bootclasspath` jar through them (the probe cannot separate this from the
  class loader opening `tools.jar`; S14-01 shows it). S3-01 reads jars inside
  the VM, which javac cannot use.
- `ResourceBundle` and `ListResourceBundle` with locale fallback,
  `ServiceLoader`, `MessageFormat` and the `java.text` it needs
  (`DecimalFormatSymbols`), `LinkedHashSet`, `URLClassLoader` and
  `java.lang.Package`.

`java.util.regex` is needed too, but S5-03 already plans it.
