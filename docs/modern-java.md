# Newer Java language features

Could LazyVM apps use `var`, records, switch expressions and the rest of the
Java 9 to 17 language, without the heavy runtime work of later JDKs (virtual
threads, runtime modules, general `java.lang.invoke`)? This note records a
probe run on 2026-10-28, sorts the features by what they cost the VM, and
ends with a proposal. Nothing here is decided until the owner confirms it in
[`decisions.md`](decisions.md).

The source-language question as a whole (Kotlin, Groovy, Scala, Lombok) is
weighed in a separate proposal,
[va1erian/lazyvm#15](https://github.com/va1erian/lazyvm/pull/15)
(`docs/languages.md`). That note names two routes to newer Java, accepting
newer class files or compiling newer syntax down to version 52, and probes
neither. This note probes the first; section 6 compares the two proposals.

## 1. Why the class-file cap has to move

javac refuses to emit Java 8 class files from newer source:
`javac -source 17 -target 8` fails with "specified target release 8 is too
old for the specified source release 17". So even `var`, which leaves no
trace in the bytecode, needs class files newer than D1's 45 to 52. The other
way round is a tool that works around the check: a javac plugin such as
Jabel (#15's second route, which patches javac internals and gives no
records) or a bytecode downgrader. Neither was probed.

## 2. Probe

**Method.** Programs were compiled with javac 25.0.2 at `-source 17
-target 17` and run on LazyVM (release build of `main` at `f4b8c4d`, with
`MAX_MAJOR` in `crates/classfile/src/lib.rs` raised from 52 to 61 for the
probe only, then reverted) and on HotSpot 25 as the oracle. Only class files
we compiled were inspected with `javap`. The probe sources live outside the
repo.

**Compiling against `lazyrt`.** `-bootclasspath` is rejected for targets
above 8, and `--release 17` would compile against the real JDK's API, which
breaks D3's "only APIs that exist". What worked: a compile-only `java.base`
module built from the `lazyrt` sources (a generated `module-info.java` that
exports every package), then

```text
javac --system none --module-path <that module> -source 17 -target 17 \
      -XDstringConcat=inline ...
```

The module is a build input only; the shipped `lazyrt.jar` stays version 52
and needs no `module-info.class`. `-XDstringConcat=inline` is a hidden,
unsupported javac option (section 3).

**Result.**

| Feature (Java version) | What javac emits | On LazyVM |
|---|---|---|
| `var` (10), text blocks (15), switch expressions with `->` and `yield` (14), pattern `instanceof` (16), private interface methods (9) | ordinary bytecode | runs, output identical to the oracle |
| Nest mates (11): inner classes reach each other's private members directly | `NestHost`/`NestMembers`; `invokevirtual` and `invokeinterface` on private methods | runs, identical, because access checks do not exist yet (they come with the S8 verifier). A private method called through a subclass that declares the same name still selects the private one, as on the oracle |
| String `+` (9) | `invokedynamic` to `StringConcatFactory` | avoided with `-XDstringConcat=inline`, which makes javac emit `StringBuilder` calls as in Java 8. Without the flag javac needs `StringConcatFactory` to compile at all |
| Records (16) | superclass `java.lang.Record`; `equals`, `hashCode` and `toString` are `invokedynamic` to `java.lang.runtime.ObjectMethods.bootstrap`, whose static arguments are the record class, the component names joined with `;`, and one `REF_getField` handle per component field; javac also needs `java.lang.invoke.TypeDescriptor` to compile | with stub classes, compiles and runs up to the first `toString`, then `BootstrapMethodError: unsupported bootstrap method java/lang/runtime/ObjectMethods.bootstrap`. That is the only gap |
| Sealed classes (17) | `PermittedSubclasses` attribute | runs; nothing checks it at load time (section 3) |
| Pattern `switch`, record patterns (21) | `invokedynamic` to `SwitchBootstraps.typeSwitch`; `MatchException` | not run; javac 25 rejects them at `-source 17` |

## 3. Cost by feature

- **Free once the cap moves:** the syntax-only row, and nest mates until S8.
  No VM, library or verifier change for programs javac compiled together.
- **Load-time checks:** at the new versions a class may only extend or
  implement a sealed type that permits it, and nest membership is validated
  before private access is granted (`IncompatibleClassChangeError`
  otherwise). Without them LazyVM accepts separately compiled, inconsistent
  class files that the oracle rejects. Small VM work, done with the version
  change or recorded as a known difference.
- **Nest mates in S8:** the verifier's access checks follow the Java 11
  nest-mate rule instead of the Java 8 one.
- **Records:** a second native linker next to the `LambdaMetafactory` one in
  `crates/vm/src/lambda`. It reads the record class and the field handles
  from the bootstrap arguments and links `equals`, `hashCode` and
  `toString`, as intrinsics or by generating code as the lambda linker does.
  The lambda linker only handles invoke-kind method handles, so
  `REF_getField` constants are new. Plus three types in `lazyrt`
  (`TypeDescriptor` brings its nested `OfField` and `OfMethod`, so five
  class files, a kilobyte or two). `hashCode`'s combination is unspecified,
  so it is matched to the oracle through blessed goldens. A few hundred
  lines of Rust; D14 is not at risk.
- **String concatenation:** the flag is enough for what `tools/javac.py`
  builds, but it tracks javac internals as Jabel and Lombok do, and any app
  built by IntelliJ or Gradle without it emits `StringConcatFactory` calls
  that fail on LazyVM. A native `makeConcatWithConstants` linker (a recipe
  string and argument types, no general `MethodHandle` support) removes both
  problems; it is the likely second step.
- **Reflection** (`Class.isRecord`, `getRecordComponents`, `isSealed`):
  optional; reading the `Record` and `PermittedSubclasses` attributes is
  enough, and nothing in the probe needs it.
- **Pattern `switch` (21):** a further linker and `MatchException`. Medium
  effort; a separate step.
- **Out:** virtual threads, `java.lang.invoke` in general, runtime modules.
  Nothing above depends on them.

## 4. The rules that would have to change

The VM work is small; the rule changes are not.

1. **D1** (target level): major versions up to 61, and `invokedynamic`
   through `ObjectMethods.bootstrap` (and later `StringConcatFactory`) as
   well as `LambdaMetafactory`.
2. **D2 and shared rule 1** (references): records, nest mates and sealed
   classes are specified in the JVMS and JLS SE 16/17. They, and the Javadoc
   of the added classes, would join the allowed references. D2's naming line
   ("a VM for Java 8 bytecode") changes with them.
3. **Shared rule 2** (namespace): `java.lang.Record`,
   `java.lang.runtime.ObjectMethods` and `java.lang.invoke.TypeDescriptor`
   (with `OfField` and `OfMethod`) are not in the Java SE 8 API. The rule
   would allow a named list of later additions with their exact Java SE 17
   signatures, and nothing else. Real SE 17 `Class` and `MethodType`
   implement the nested interfaces; whether `lazyrt`'s must too is part of
   that decision.
4. **D6** (class library): the table gains those types.
5. **D9** (testing): the oracle is Java 8. Tests at the new level reuse the
   existing `// ORACLE: modern` marker of `tools/golden.py`, which already
   blesses on JDK 19 or newer, rather than adding a second oracle.
6. **D3** (toolchain): `tools/javac.py` builds the compile-only module and
   gains the second flag set. Which level an app uses is its choice.
7. **IntelliJ** (not checked): the fake SDK declares `JAVA_VERSION="1.8.0"`,
   which likely caps the IDE's language level at 8, and a JDK 9+ home is
   normally read through a `lib/modules` image. IntelliJ builds would also
   need the string-concatenation flag until the native linker exists. Needs
   an owner check.

## 5. Proposal

1. **Language level 17, records and sealed classes included, pattern
   `switch` excluded.** Accept versions up to 61 with the load-time checks,
   add the `ObjectMethods` linker and the three types, and make the rule
   changes in section 4. Tests at this level carry `// ORACLE: modern`.
2. **Next, each its own work item:** a native `StringConcatFactory` linker
   (so IDE and Gradle builds work without the hidden flag), and the
   nest-mate rule inside the S8 verifier (required once S8 lands, if 1 is
   taken).
3. **Later, its own decision:** the Java 21 switch bootstraps.
4. **If the rule changes are not wanted:** stay at Java 8. A javac plugin
   or a bytecode downgrader gives the syntax-only features at version 52;
   neither was probed, and both track tool internals.

## 6. Compared with #15

| | Newer Java (this note) | Kotlin (#15) |
|---|---|---|
| Class files | 61; needs D1 changed | 52; fits D1 today |
| VM work | `ObjectMethods` linker, load-time checks, later the S8 nest-mate rule and a `StringConcatFactory` linker | none |
| Library work | three types outside Java SE 8 | `toUpperCase(Locale)`, then at least S6 for the stdlib and coroutines |
| Rule changes | references, namespace, naming, D6, D9 | none; open questions on reading Kotlin's source and on whether a shared stdlib counts toward D14 |
| Runtime shipped with apps | none | Kotlin stdlib: 1.85 MB, 726 KB for the classes #15's probes used, unshrunk |
| Toolchain | javac already in use, a compile-only module, tests under the existing modern oracle | a pinned `kotlinc` and its own suite |
| Gain | `var`, records, sealed classes, switch expressions, `instanceof` patterns, text blocks | all of that and more (null safety, coroutines, extensions) |

The two do not exclude each other. Kotlin is the larger gain with no rule
change, but needs at least S6 and makes every app carry its stdlib. Java 17
is the smaller gain with no runtime cost per app, but moves the spec
baseline the project was built on. If only one is taken, the choice is
mostly about which cost the owner prefers: a second language and its
stdlib, or a later spec.

## 7. Open questions for the owner

- Move the spec baseline to Java SE 17 for the language (section 4), or stay
  at Java SE 8?
- If so, is a named list of post-8 `java.*` types acceptable under rule 2?
- Take this, #15's Kotlin, both, or neither?
