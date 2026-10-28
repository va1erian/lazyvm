# java.util utilities (S5-04): behaviour beyond the Javadoc

This is the behavioural specification for the S5-04 utilities and the index of its parts. It
follows [`collections.md`](collections.md): implement the Java SE 8 Javadoc, then these rules. Each
rule reads *input → observable result* and ends with the test in `tests/java/util` (and the label
its output line starts with) that fails if the rule is implemented differently. Every golden is
blessed on the oracle (Temurin 1.8.0_504). How the classes are built is the implementer's choice;
only what a program can observe is fixed. Everything here comes from the Javadoc and from
experiments on the oracle, never from any JDK's source.

## 1. Where each class is specified

| Classes | Spec | Tests | Waits for |
|---|---|---|---|
| `Optional`, `OptionalInt`, `OptionalLong`, `OptionalDouble`, `Objects` | this file, sections 2 and 3 | Utilities | nothing |
| `Random` | this file, section 4; also [`collections.md`](collections.md) X1 | RandomSeq, RandomSeeding | nothing (the fix is to `new Random()` and to `StrictMath.log`) |
| `Base64`, `UUID`, `BitSet` | [`util-data.md`](util-data.md) | Base64s, Base64Streams, Uuids, BitSets, UtilBuffers | an MD5 (UUID); `java.nio` for UtilBuffers (S5-06, branch `s5-io`) |
| `Scanner`, `Properties`, `StringTokenizer` | [`util-text.md`](util-text.md) | Scanners, ScannerNumbers, PropertiesIO, Tokenizers | `java.util.regex` (branch `s5-regex`) for Scanner; `java.io` (branch `s5-io`) for both; `java.util.Date` for `Properties.store` |
| `WeakHashMap` (S5-02a) | [`weakhashmap.md`](weakhashmap.md) | WeakMaps, WeakMapOrder | nothing (S5-07 references are on main) |

The implementation of `Scanner` and `Properties` waits for the `s5-regex` and `s5-io` merges. Their
tests and goldens are already here; they were blessed through a wrapper that compiles the tests
with the oracle's own `javac` (lazyrt lacks the classes) and records the same transcript as
`tools/golden.py`. Re-bless with `tools/golden.py` once the classes exist; the goldens must not
change. Until a class exists, its tests do not compile against lazyrt, so run the suite with
`--filter` while the work is in progress.

## 2. Optional, OptionalInt, OptionalLong, OptionalDouble

"The four classes" means all of `Optional` and its three primitive counterparts; "present" and
"empty" are the two states.

**O1.** Each of the four classes has one shared empty object: every `empty()` call returns it, and
so does `Optional.ofNullable(null)`. `Optional`'s operations that end with no value return that
shared object: `filter` whose predicate says false, `map` whose function returns null, and
`filter`, `map` and `flatMap` on an empty `Optional`. `of(…)` makes a new object on every call,
even for equal values (no cache of small values). Pinned by: Utilities ("empty same", "of fresh",
"empty results").

**O2.** `toString()`: `Optional[` + `String.valueOf(value)` + `]` and `Optional.empty`;
`OptionalInt[5]`, `OptionalLong[-1]`, `OptionalDouble[1.0E10]` with the value as `Integer`/`Long`/
`Double.toString` prints it; `OptionalInt.empty`, `OptionalLong.empty`, `OptionalDouble.empty`. A
value whose `toString()` returns null prints as `Optional[null]`. Pinned by: Utilities
("toString", "toString int", "toString long", "toString double", "toString null text", "fuzz
sample").

**O3.** `hashCode()`: 0 when empty; when present, the value's `hashCode()` for `Optional`, the
value for `OptionalInt`, `Long.hashCode(v)` (the two halves XORed) for `OptionalLong`, and
`Double.hashCode(v)` (from `doubleToLongBits`, so every NaN hashes alike) for `OptionalDouble`.
So `OptionalInt.of(0)` and `OptionalInt.empty()` share hash 0. Pinned by: Utilities ("hash", "hash
int", "hash long", "hash double", "fuzz").

**O4.** `equals(o)` is true only when `o` is of the same one of the four classes and both are empty,
or both are present with equal values. `Optional` treats the same value object (or itself) as equal
without a call, and otherwise, when its own value is present, asks that value's `equals` once with
the other value, which is `null` when the other `Optional` is empty; an empty receiver calls nothing. `OptionalInt` and
`OptionalLong` compare with `==`. `OptionalDouble` compares as `Double.compare(a, b) == 0`: any NaN
equals any NaN, and `0.0` differs from `-0.0`. A present value never equals an empty one even when
the value is 0, an `Optional` never equals its bare value, and the classes never equal each other.
Pinned by: Utilities ("equals", "equals int", "equals double", "equals calls", "equals same value",
"equals empty", "fuzz").

**O5.** Null arguments throw `NullPointerException` with no message, as follows. `Optional.of(null)`
always. `filter`, `map` and `flatMap` with a null argument always, present or empty, before
anything else happens. `flatMap` whose function returns null, when present. `ifPresent(null)` only
when present. `orElseGet(null)` and `orElseThrow(null)` only when empty. `orElseThrow` whose
supplier returns null, when empty (the `throw` of a null). The primitive classes behave the same
for `ifPresent`, `orElseGet` and `orElseThrow`. `orElse(null)` is allowed and returns null when
empty. Pinned by: Utilities ("of null", "ifPresent null", "filter null", "map null", "flatMap
null", "orElseGet null", "orElseThrow null", "orElse null", "prim nulls", "prim nulls long", "prim
nulls double").

**O6.** `get()`, `getAsInt()`, `getAsLong()` and `getAsDouble()` on an empty object throw
`NoSuchElementException: No value present`. Pinned by: Utilities ("get empty").

**O7.** `map` on a present `Optional` returns a new `Optional` of the function's result (`ofNullable`
of it), never the receiver, even when the result is the same value. `flatMap` returns the very
`Optional` object the function returned. `filter` whose predicate says true returns the receiver
itself. Pinned by: Utilities ("identity", "map flatMap").

**O8.** Functions and suppliers run only when needed, and at most once per call: `orElseGet` and
`orElseThrow` call their supplier only when empty; `ifPresent`, `filter`, `map` and `flatMap`
call their function only when present. `orElseThrow` throws the exact object its supplier
returned. Pinned by: Utilities ("orElseGet", "call log", "orElseThrow same", "orElseThrow",
"orElse", "isPresent", "prim log", "prim get").

## 3. Objects

**J1.** `equals(a, b)`: true when `a == b` without calling anything, false when `a` is null,
otherwise `a.equals(b)`, called once. `deepEquals(a, b)`: true when `a == b`; false when exactly one
is null; when both are arrays it compares them as `Arrays.deepEquals` compares two one-element
`Object[]` holding them: arrays of different component types are unequal (`int[]` vs `long[]`,
`int[]` vs `Object[]`), reference arrays of different classes with equal elements are equal
(`Object[0]` and `String[0]`), `double[]` elements compare by bits (NaN equals NaN, `0.0` differs
from `-0.0`), and nested arrays compare deeply. Two non-arrays compare with `a.equals(b)`. Pinned
by: Utilities ("equals", "deepEquals", "equals calls").

**J2.** `hashCode(o)` is 0 for null, else `o.hashCode()`. `hash(values…)` is
`Arrays.hashCode(values)`: `hash()` is 1, `hash((Object[]) null)` is 0, `hash((Object) null)` is
31, and an array argument contributes its identity hash, not a deep one. Pinned by: Utilities
("hashCode", "hash", "fuzz").

**J3.** `toString(o)` is `String.valueOf(o)` (`"null"` for null). `toString(o, nullDefault)` returns
`nullDefault`, even a null one, when `o` is null. Pinned by: Utilities ("toString").

**J4.** `compare(a, b, c)` returns 0 without calling `c` when `a == b`, even when `c` is null and
when both are null. Otherwise it returns `c.compare(a, b)`, passing nulls through, and a null `c`
throws `NullPointerException`. Pinned by: Utilities ("compare").

**J5.** `requireNonNull` returns its argument itself when it is not null. For null it throws
`NullPointerException`: with no message for `requireNonNull(o)`; with the given message for
`requireNonNull(o, message)` (no message when that is null); with the supplier's result for
`requireNonNull(o, supplier)`, calling the supplier only then, and with no message when the supplier
is null or returns null. A null supplier is never touched when the object is not null. Pinned by:
Utilities ("requireNonNull", "requireNonNull pass").

**J6.** `isNull(o)` is `o == null`; `nonNull(o)` is `o != null`. Pinned by: Utilities ("isNull").

## 4. Random

`Random` is on main (S5-02) and stays. Its seeded sequences already match; R1 and R2 restate them
so the tests have rules to cite. R3 is a known bug to fix.

**R1.** The generator and the formulas the Javadoc gives, exactly: the 48-bit linear congruential
generator with multiplier `0x5DEECE66D` and addend 11, the seed scrambled by XOR with the multiplier,
`next(bits)` as the top `bits` of the new seed; `nextInt(bound)` with its power-of-two shortcut and
its rejection loop (a draw is rejected when `bits - val + (bound - 1)` overflows); `nextLong` as
`((long) next(32) << 32) + next(32)` (a signed sum); `nextFloat`, `nextDouble`, `nextBoolean` as given;
`nextBytes` taking one `nextInt()` per 4 bytes, low byte first, and dropping the unused bytes of the
last one; `nextGaussian` by the polar method with `StrictMath.log` and `StrictMath.sqrt`, returning the
second value of each pair on the next call, which `setSeed` forgets. LazyVM's `StrictMath.log` is
the `libm` crate's, which is within 1 ulp of fdlibm's but not always equal (`decisions.md` D13), so a
gaussian can differ from Java 8's in its last digits. Everything else about `nextGaussian` is exact:
which draws it makes, the rejected pairs, the signs, and the pairing and caching. RandomSeq
therefore prints gaussians to 10 significant digits and their sums to 1e-6, which no ulp-level
difference reaches (checked: the nearest rounding boundary is about 3e-7 away, the error bound about
1e-11), and prints no exact `log` bits. Pinned by: RandomSeq ("sums", "seed -77", "setSeed
restarts", "bounded", "dice", "bytes N", "after 3 bytes", "scripted", "scripted top", "rejected",
"scripted bytes", "gaussians", "gaussian sum", "scripted gaussian", "nextGaussian" lines),
RandomSeeding.

**R2.** Each method reaches the overridable `next(bits)` like this: `nextInt()` once with 32;
`nextLong()` twice with 32; `nextBoolean()` once with 1; `nextFloat()` once with 24; `nextDouble()`
with 26 then 27; `nextInt(bound)` once with 31 per draw, rejected draws included, for powers of two
and other bounds alike, and not at all when the bound is not positive (`IllegalArgumentException:
bound must be positive`); `nextBytes(b)` through `nextInt()`, so once with 32 per started group of
4 bytes, nothing for an empty array, and `NullPointerException` for null; `nextGaussian()` through
`nextDouble()` pairs, nothing when it returns the held second value. Pinned by: RandomSeq
("nextInt", "nextLong", "nextBoolean", "nextFloat", "nextDouble", "nextInt(16)", "nextInt(1)",
"nextBytes(0)", "nextBytes(5)", "nextBytes(8)", "nextGaussian", "nextGaussian cached",
"nextGaussian again", "nextInt(2^30+1) draws", "nextInt(0)", "nextBytes(null)").

**R3.** `new Random()` must give a different seed to every instance, and different seeds in
different runs of the same program: a seed may not be a function of a process-relative clock.
LazyVM's `System.nanoTime()` counts from process start, so today's seed (`nanoTime` XORed with a
multiple of the multiplier per instance) repeats closely from run to run. Mix in something that
changes between runs (the wall clock at full resolution, or an operating-system random source)
and keep a per-instance difference. RandomSeq makes eight instances, recovers each one's 48-bit seed
from three `nextInt()` values, and checks that it is neither a `nanoTime()` nor a
`currentTimeMillis()` reading taken around the constructor, alone or XORed with `k * 0x5DEECE66D`
for `k` < 4096; it prints only the verdict. One run cannot see two runs, so the cross-run half is
checked by review. `UUID.randomUUID()` needs the same kind of source (util-data.md U8). Pinned by:
RandomSeq ("two instances differ", "seeds are not clock readings"), RandomSeeding (last line).

## 5. Not observable: the implementation may choose

- How the empty Optionals are made and stored, as long as O1's identities hold; whether
  `OptionalDouble` keeps the value's bits or the `double`.
- Which `NullPointerException` messages beyond "none" the classes use: the oracle's are all null,
  and the tests print the message only when there is one, so a message would fail them.
- `Objects`' private constructor: no test reaches it. Today's lazyrt throws an `AssertionError`
  whose text looks recalled from a JDK; reword it in your own words when touching the file.
- `new Random()`'s seed source, within R3; the stream methods `ints`, `longs` and `doubles` of
  `Random` belong to S11 with the other streams.

## 6. What lazyrt has today

- `Objects`: complete and matches every J rule; the private constructor's text is reworded.
- `Random`: matches R1, R2 and R3 (seeded from `lazy.internal.VM.entropy()`, natives.md); its
  gaussians may differ from Java 8's in the last digits, as R1 and `decisions.md` D13 accept.
- Built on branch `s5-util`: `Optional*`, `Base64` (not the `ByteBuffer` overloads), `UUID` (MD5 in
  `lazy.internal.Md5`), `BitSet` (not the buffer `valueOf`s), `StringTokenizer`,
  `InputMismatchException`, `WeakHashMap`.
- Still absent, waiting for the merges: `Scanner` (S5-03 regex and S5-05), `Properties` (S5-05),
  the `java.nio` overloads (S5-06), `java.util.Date`; and `java.math`, `java.security`.

## 7. API to provide

Members are those each class declares; parameter types are erased; take generics, return types and
exceptions from the Javadoc. Base64, UUID and BitSet are listed in util-data.md, Scanner, Properties
and StringTokenizer in util-text.md, WeakHashMap in weakhashmap.md, Random in collections.md.

| Class | Kind | Members |
|---|---|---|
| `Optional` | final class | static empty(); equals(Object); filter(Predicate); flatMap(Function); get(); hashCode(); ifPresent(Consumer); isPresent(); map(Function); static of(Object); static ofNullable(Object); orElse(Object); orElseGet(Supplier); orElseThrow(Supplier); toString() |
| `OptionalInt` | final class | static empty(); equals(Object); getAsInt(); hashCode(); ifPresent(IntConsumer); isPresent(); static of(int); orElse(int); orElseGet(IntSupplier); orElseThrow(Supplier); toString() |
| `OptionalLong` | final class | static empty(); equals(Object); getAsLong(); hashCode(); ifPresent(LongConsumer); isPresent(); static of(long); orElse(long); orElseGet(LongSupplier); orElseThrow(Supplier); toString() |
| `OptionalDouble` | final class | static empty(); equals(Object); getAsDouble(); hashCode(); ifPresent(DoubleConsumer); isPresent(); static of(double); orElse(double); orElseGet(DoubleSupplier); orElseThrow(Supplier); toString() |
| `Objects` | final class | static compare(Object, Object, Comparator); static deepEquals(Object, Object); static equals(Object, Object); static hash(Object...); static hashCode(Object); static isNull(Object); static nonNull(Object); static requireNonNull(Object); static requireNonNull(Object, String); static requireNonNull(Object, Supplier); static toString(Object); static toString(Object, String) |
