# The memory model and atomics (S6-03)

Code: `lazy.internal.Atomics` in `lazyrt`, `crates/natives/src/atomics.rs`, and
`java.util.concurrent.atomic`. Read [`threads.md`](threads.md) first: this page
builds on its single VM lock (decisions.md D5).

## Why `volatile` costs nothing here

JLS 17.4 defines what a read may see through *happens-before*: actions in one
thread are ordered by program order, and a volatile write is ordered before
later reads of that field (17.4.4, 17.4.5). LazyVM gives a stronger guarantee
to every field and array element, volatile or not.

- **One thread runs bytecode at a time.** Handing the VM to another thread goes
  through the `Gil` mutex, so a thread's memory effects are visible to whichever
  thread holds the VM next. All actions of a run form one total order, the
  order they happened in, and every read sees the last write before it in that
  order. That is what sequential consistency means (17.4.3), so the volatile
  rules, the `final` field rules (17.5) and the rule that `long` and `double`
  writes are atomic (17.7) all hold for every variable.
- **No value is cached across a switch.** `getfield`, `getstatic` and the array
  loads read the heap or the class's statics each time they run. `cp_cache`
  holds what a constant pool entry resolved to (a class, a method, a field's
  owner and slot, a string), never a field's value, and there is no quickening
  that embeds one. Locals and operand stacks belong to one frame of one thread.
  A native that holds a value across `Vm::blocking` holds it for its own use,
  and no native here calls `blocking`.
- **No reordering.** The interpreter runs bytecodes one at a time in order, and
  has no JIT to move them. The Rust compiler cannot reorder across the lock
  either, because the `Vm` is moved through a mutex.

So the interpreter adds no fence and treats `ACC_VOLATILE` like any other flag.
A compiler (`docs/compiler.md`) or parallel bytecode would need to revisit this
page. `tests/java/thread/VolatileHandoff` passes a turn between two threads
through volatile fields of each kind, in loops with no calls, so only a
back-branch safepoint can switch threads.

## Why an intrinsic is atomic

A thread gives up the VM at a safepoint (a back-branch or call in bytecode) or
in `Vm::blocking`. A native runs between safepoints, so if it reads and writes
without `blocking` or a call into Java, nothing can run in between. Each
operation in `atomics.rs` does its checks first, including class initialization
of a static field's class (which may run Java), then reads and writes in one
step.

Everything else in `java.util.concurrent.atomic` is built from those steps and
plain field access. Loops that call a user function (`updateAndGet`,
`accumulate`) read, call and compare-and-set, and retry when another thread got
in between, because the call is a safepoint.

## Naming a variable

`Atomics` takes an int, long or reference variable named in one of two ways:

- `(Object target, Field f, ...)`: an instance field, or a static field, for
  which `target` is ignored.
- `(int[]/long[]/Object[] array, int i, ...)`: an array element.

No offset ever reaches Java. A `Field` holds its class and slot in ordinary
private fields which reflection can change, so the native checks on every call:

| Check | Failure |
|---|---|
| The `Field` is not null, its class is set | `NullPointerException` |
| The object is a `java.lang.reflect.Field` | `IllegalArgumentException` |
| The slot is a field of that class | `IllegalArgumentException` |
| The descriptor is exactly `I`, `J`, or a reference | `IllegalArgumentException` |
| The field is not `final` | `IllegalArgumentException` |
| An instance field's target is not null | `NullPointerException` |
| An instance field's target is an instance of the class | `IllegalArgumentException` |
| A reference stored in a field fits its declared type | `ClassCastException` |
| An element's array is an array of the operation's kind | `IllegalArgumentException` |
| The array is not null, the index is in range | `NullPointerException`, `ArrayIndexOutOfBoundsException` |
| A reference stored in an element passes `aastore`'s check | `ArrayStoreException` |

The field type check is what stops a forged `Field` from putting a value of the
wrong kind in a slot. It uses the descriptor read during the same validation,
because initializing the class can run Java that changes the `Field`.
`tests/java/lazy/atomics/Intrinsics` covers each row, forged `Field`s included.
javac code cannot reach the row on the array's kind, or pass something other
than a `Field`; until the verifier (S8) bytecode can, so the test loads
`CraftedAtomics.class`, which `tools/fixtures.py` builds from
`tools/crafted/CraftedAtomics.java` with the casts removed.

int and long fields need no read intrinsic (`getAndAdd` of zero is one), so the
only plain read is `getRef`.

The `Field` lookups cost about 20% over a plain method call on `AtomicInteger`
(2M calls: 201 ns for a Java method, 245 ns for `getAndAdd`), so a separate
faster handle is not worth having.

## The classes

Observable rules below are pinned by goldens in `tests/java/thread/Atomic*`.

- **`AtomicBoolean`** holds an int, since the intrinsics work on ints.
- **`LongAdder`, `DoubleAdder`, `LongAccumulator`, `DoubleAccumulator`** hold one
  value. Striping exists to spread contention between CPUs, and here no two
  threads ever run at once. The double classes keep raw bits in an `AtomicLong`,
  so `compareAndSet` is a bit comparison and a NaN does not loop forever.
- **`AtomicStampedReference`** compare-and-sets an immutable pair of reference
  and stamp, and **`AtomicMarkableReference`** is one with a stamp of 0 or 1.
- **`AtomicReferenceArray`** keeps an `Object[]`, so the source array's element
  type does not limit later stores (`AtomicArrays`).

### Field updaters

`newUpdater` reads its caller with `Atomics.callerClass`, which looks through
`Method.invoke`. A method reference's caller is the lambda's own class, on the
oracle too, so `AtomicIntegerFieldUpdater::newUpdater` cannot reach a private
field of the class that wrote it (`AtomicUpdaterAccess`). Observed rules:

- Anything failing while finding the field or checking access is wrapped in a
  `RuntimeException`, with the original as the cause: a null class or name
  (`NullPointerException`), a missing field (`NoSuchFieldException`, inherited
  fields do not count), no access (`IllegalAccessException`).
- Access follows JLS 6.6 with the class as the VM sees it: a `private` nested
  class counts as package-private and a `protected` one as public. A class's own
  nested classes get no private access to it, nor it to them: nesting is a
  compiler feature here. A refused protected field's message never says
  `protected`: its modifiers read `volatile` on a class the VM sees as public and
  `public volatile` on any other.
- Then, in order: the type (`IllegalArgumentException` "Must be integer type" or
  "Must be long type"; for references, `ClassCastException` unless the field's
  type is exactly `vclass`, then "Must be reference type" if that is primitive);
  "Must be volatile type"; and a static field, `IllegalArgumentException` with
  no message.
- A receiver that is not an instance of `tclass`, or null, is a
  `ClassCastException` with no message.
- **Protected fields:** when the caller is a subclass of `tclass` in another
  package and the field is `protected`, receivers must be instances of the
  caller instead (JLS 6.6.2). Any other receiver is a `RuntimeException` wrapping
  an `IllegalAccessException` naming the three classes, and null is a
  `NullPointerException`.
- A reference updater throws `ClassCastException` with no message for a non-null
  new value that is not a `vclass`, whatever the expected value.
- `getAndSet`, `getAndAdd` and the increments are concrete in the abstract class,
  each a loop over `get` and `compareAndSet` that calls no other concrete method,
  so a user's subclass sees exactly those calls and its overrides stay independent
  (`AtomicUpdaters`). `newUpdater`'s updater inherits them: a loop costs little,
  and one way to do it keeps these classes small.

`AtomicApi` compares the public and protected members of all sixteen classes
with the oracle's by reflection. Serialization is not implemented: the classes
are `Serializable` as declared, with no `serialVersionUID` and no serial form,
until the class library has object streams.
