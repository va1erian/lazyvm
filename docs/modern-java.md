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
(`docs/languages.md`). That note lists newer Java as "possible, but a large
change of rules" and leaves it unprobed. This note fills that gap; section 6
compares the two.

## 1. Why the class-file cap has to move

javac refuses to emit Java 8 class files from newer source:
`javac -source 17 -target 8` fails with "target release 8 is too old for the
specified source release 17". So even `var`, which leaves no trace in the
bytecode, needs class files newer than D1's 45 to 52. The only way around
that is a javac plugin that lifts the check (Jabel, mentioned in #15), which
patches javac internals and still cannot give records.

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
and needs no `module-info.class`.

**Result.**

| Feature (Java version) | What javac emits | On LazyVM |
|---|---|---|
| `var` (10), text blocks (15), switch expressions with `->` and `yield` (14), `switch` on strings, pattern `instanceof` (16), private interface methods (9) | ordinary bytecode | runs, output identical to the oracle |
| Nest mates (11): inner classes reach each other's private members directly | `NestHost`/`NestMembers`; `invokevirtual` and `invokeinterface` on private methods | runs, identical, because access checks do not exist yet (they come with the S8 verifier). A private method called through a subclass that declares the same name still selects the private one, as on the oracle |
| String `+` (9) | `invokedynamic` to `StringConcatFactory` | not needed: `-XDstringConcat=inline` makes javac emit `StringBuilder` calls as in Java 8. Without the flag javac needs `StringConcatFactory` to compile at all |
| Records (16) | superclass `java.lang.Record`; `equals`, `hashCode` and `toString` are `invokedynamic` to `java.lang.runtime.ObjectMethods.bootstrap`, with the component getters as `MethodHandle` constants; javac also needs `java.lang.invoke.TypeDescriptor` to compile | with stub classes, compiles and runs up to the first `toString`, then `BootstrapMethodError: unsupported bootstrap method java/lang/runtime/ObjectMethods.bootstrap`. That is the only gap |
| Sealed classes (17) | `PermittedSubclasses` attribute | runs; only compile time enforces it for a valid program |
| Pattern `switch`, record patterns (21) | `invokedynamic` to `SwitchBootstraps.typeSwitch`; `MatchException` | not run; javac 25 rejects them at `-source 17` |

## 3. Cost by feature

- **Free once the cap moves:** every row above except records and pattern
  `switch`. No VM, library or verifier change.
- **Records:** a second native linker next to the `LambdaMetafactory` one in
  `crates/vm/src/lambda`. It reads the record class and its getter handles
  from the bootstrap arguments and links `equals`, `hashCode` and `toString`
  either as intrinsics or by generating code as the lambda linker does. Plus
  three small classes in `lazyrt`. `hashCode`'s combination is unspecified,
  so it is matched to the oracle through blessed goldens. A few hundred lines
  of Rust and well under a kilobyte of jar, so D14 is not at risk.
- **Nest mates in S8:** the verifier's access checks must follow the Java 11
  nest-mate rule instead of the Java 8 one.
- **Reflection** (`Class.isRecord`, `getRecordComponents`, `isSealed`):
  optional; reading the `Record` and `PermittedSubclasses` attributes is
  enough, and nothing in the probe needs it.
- **Pattern `switch` (21):** a third linker and `MatchException`. Medium
  effort; a separate step.
- **Out:** virtual threads, `java.lang.invoke` in general, runtime modules.
  Nothing above depends on them.

## 4. The rules that would have to change

The VM work is small; the rule changes are not.

1. **D1** (target level): major versions up to 61, and `invokedynamic`
   through `ObjectMethods.bootstrap` as well as `LambdaMetafactory`.
2. **D2 and shared rule 1** (references): records, nest mates and sealed
   classes are specified in the JVMS and JLS SE 16/17. They, and the Javadoc
   of the added classes, would join the allowed references. The oracle for
   tests at the new level is a JDK 17 or later; the Java 8 oracle stays for
   everything compiled at 8.
3. **Shared rule 2** (namespace): `java.lang.Record`,
   `java.lang.runtime.ObjectMethods` and `java.lang.invoke.TypeDescriptor`
   are not in the Java SE 8 API. The rule would allow a named list of later
   additions, with their exact Java SE 17 signatures, and nothing else.
4. **D3** (toolchain): `tools/javac.py` builds the compile-only module and
   gains the second flag set. Which level an app uses is its choice.
5. **IntelliJ** (not checked): the fake SDK declares `JAVA_VERSION="1.8.0"`,
   which likely caps the IDE's language level at 8, and a JDK 9+ home is
   normally read through a `lib/modules` image. Needs an owner check.

## 5. Proposal

1. **Language level 17, records and sealed classes included, pattern
   `switch` excluded.** Accept versions up to 61, add the `ObjectMethods`
   linker and the three classes, and make the rule changes in section 4.
   Differential tests at this level live in their own area (for example
   `tests/java/lang17/`), blessed on a JDK 17+ oracle.
2. **Later, each its own decision:** a native `StringConcatFactory` (only
   needed for jars we did not compile), the Java 21 switch bootstraps, and
   the nest-mate rule inside the S8 verifier (required once S8 lands, if 1
   is taken).
3. **If the rule changes are not wanted:** stay at Java 8. A javac plugin
   such as Jabel gives the syntax-only features at version 52 without
   records; it was not probed, and like Lombok it tracks javac internals.

## 6. Compared with #15

| | Newer Java (this note) | Kotlin (#15) |
|---|---|---|
| Class files | 61; needs D1 changed | 52; fits D1 today |
| VM work | `ObjectMethods` linker, later the S8 nest-mate rule | none |
| Library work | three classes outside Java SE 8 | `toUpperCase(Locale)`, then S6 for the stdlib and coroutines |
| Rule changes | references, namespace, oracle | none; one open question on reading Kotlin's source |
| Runtime shipped with apps | none | Kotlin stdlib, 1.85 MB unshrunk |
| Toolchain | javac already in use, a compile-only module | a pinned `kotlinc` and its own suite |
| Gain | records, `var`, switch expressions, patterns, text blocks | all of that and more (null safety, coroutines, extensions) |

The two do not exclude each other. Kotlin is the larger gain with no rule
change, but needs S6 and makes every app carry its stdlib. Java 17 is the
smaller gain with no runtime cost per app, but moves the spec baseline the
project was built on. If only one is taken, the choice is mostly about which
cost the owner prefers: a second language and its stdlib, or a later spec.

## 7. Open questions for the owner

- Move the spec baseline to Java SE 17 for the language (section 4), or stay
  at Java SE 8?
- If so, is a named list of post-8 `java.*` classes acceptable under rule 2?
- Take this, #15's Kotlin, both, or neither?
