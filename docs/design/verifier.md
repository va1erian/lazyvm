# The verifier (S8)

LazyVM checks class files in three places, so a class it did not build is an
error or a working program, never a confused VM.

| When | What | Failure | Code |
|---|---|---|---|
| Parsing | The format of the file: lengths, pool indices, attribute layout | `ClassFormatError` | `crates/classfile` |
| Defining | Flags, names and descriptors (JVMS 4.8), the `-Xmaxcode` limit, superclass access | `ClassFormatError`, `IllegalAccessError` | `verify/structural/format.rs`, `class/load.rs` |
| Linking | Static code constraints (JVMS 4.9.1), then the type checker (JVMS 4.10.1) | `VerifyError`; a malformed `StackMapTable` is `ClassFormatError` | `verify/structural/code.rs`, `verify/check.rs`, `ops*.rs`, `stackmap.rs` |
| Resolving | Access control (JVMS 5.4.4) | `IllegalAccessError` | `class/access.rs` |

## When linking runs

`Vm::initialize` links first, so a class is verified before any of its code can
run. `Vm::link_class` verifies the superclass chain and all superinterfaces
before the class itself: interface methods are reachable from a class whose
initialization never touches the interface (JVMS 5.5 step 7 skips those without
default methods), and verifying them all closes that gap.

No Java runs while linking, so it needs no state of its own in `InitState`.
A failure leaves `Class::verified` false, so each use fails again the same way,
which is what the oracle does. Loading a class the checker asks about can fail;
that error propagates as itself (`tests/verify/bad/CatchUnloadable`).

## What each `-Xverify` does

- `-Xverify:all`, the default: every class with a file.
- `-Xverify:remote`: not the boot class path, as other JVMs default to.
- `-Xverify:none`, `-noverify`: nothing but parsing and defining, which always
  run. The interpreter and natives keep their own checks, and a confused class
  gets `VerifyError` or an exception when it reaches one of them
  (`tests/java/lazy/atomics/Intrinsics`).
- `-Xverify:check`: verifies every class on both class paths, prints one line per
  failure, and exits. It needs no main class.

## Choices

- **Before version 50 there is no type checking.** Those classes have no stack
  maps, and the inference verifier is out of scope (D1). They get the structural
  checks, including no `jsr`/`ret`, and the interpreter's own checks. So the
  interpreter's checks stay: S11-05 may not remove one on the ground that "the
  verifier proved it" while such classes, or `-Xverify:none`, can reach it.
- **Version 50 does not fail over.** The JVMS lets a version 50 class that fails
  type checking fall back to inference, and the oracle does. Here it stays
  rejected (`bad/Version50NeedsFrames`).
- **Generated lambda classes are verified too.** `Class::host` makes one part of
  the class that linked it: resolution gives it the host's access, and its
  `invokespecial` of the host's private `lambda$` methods counts as the host's.
- **Hostile input has a budget.** Types are interned to one small `Copy` value,
  a frame stores only the locals it declares, and a handler's frame is compared
  again only when the locals changed. A class then gets 2^30 slot comparisons and
  copies, and a method's declared frames 2^24 slots; past either it is a
  `VerifyError`. `verify/tests.rs` builds the worst cases.

## Where the oracle and the JVMS differ

The JVMS's Prolog is the reference, and `tools/verify_test.py --oracle` runs every
`tests/verify` class on Java 8 as well. Where they disagree the oracle wins (D9),
except as marked. Each row has a test.

| Rule | JVMS 4.10.1 | Observed, and what LazyVM does | Test |
|---|---|---|---|
| Arrays and interfaces | an array fits `Cloneable`, `Serializable` | an array fits any interface | `good/ArrayAsAnyInterface` |
| `this` after `super()` | becomes the class of the `<init>` called | becomes the current class | every constructor |
| `putfield` before `super()` | not mentioned | allowed on a field the class declares | `good/PutfieldBeforeSuper` |
| Handler across `<init>` | checked with the incoming state | also with the rewritten locals, as 4.10.1.9's note says | `bad/HandlerAround*` |
| The protected check | looks at the class the reference names | looks from that class up, for the declaring class | `bad/ProtectedThroughMiddleClass` |
| `Object.clone` on an array | fails the protected check | allowed | `good/ObjectCloneOnArray` |
| `invokespecial` of an interface method | nothing more than assignability | the interface must be a direct one | `bad/SpecialIndirectInterface` |
| `<clinit>` that is not static (51+) | 4.6 asks for it to be static | loads, and never runs | `good/ClinitNotStaticIsIgnored` |
| `<init>` in an interface | 4.6 forbids it | loads | `good/InterfaceInitLoads` |
| Bad names in the pool, such as `a//b` | format error | **LazyVM follows the JVMS**: the oracle fails later, at resolution | `bad/MemberRefBadClassName` |

Timing differs in one way: the oracle reports a malformed `StackMapTable` when the
class loads and LazyVM when it links, which only a `Class.forName(name, false, ...)`
that never initializes the class can see.

## Tests

- `cargo test -p vm verify::`: hand-made bad classes for the structural checks,
  the assignment rules, and the hostile cases.
- `python tools/verify_test.py`: all of `lazyrt.jar` and the compiled tests verify;
  each `tests/verify/bad/*.jasm` is rejected with its class and reason, each
  `good` one prints `ok`; the limits never abort. `--oracle` adds the Java 8 run.
  `tools/jasm.py` is the assembler for them.
- `fuzz/`: `cargo +nightly fuzz run parser --fuzz-dir fuzz fuzz/corpus/parser` and
  the same for `verifier`, with a checked-in corpus. CI runs an hour of each weekly
  (`.github/workflows/fuzz.yml`). On Windows the ASAN runtime must be on `PATH`, from
  Visual Studio's `VC\Tools\MSVC\<version>\bin\Hostx64\x64`.

## Limits (S8-04)

Each limit is an error Java can catch, never an abort.

| Limit | Option | Default | Error |
|---|---|---|---|
| Stack depth | `-Xss` | 16384 frames per MiB | `StackOverflowError` |
| Heap | `-Xmx` | 256 MiB | `OutOfMemoryError` |
| Memory the OS refuses | | | `OutOfMemoryError`, for arrays of 1 MiB and up |
| Classes loaded | `-Xmaxclasses:<n>` | 2^20 | `OutOfMemoryError` |
| Bytes of code in a method | `-Xmaxcode:<bytes>` | 65535, the format's own limit | `ClassFormatError` |

Creating the error for the class limit needs classes of its own, so while an
exception is being made the limit has 256 classes of headroom, as the stack limit
has frames.
