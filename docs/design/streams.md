# java.util.stream, Spliterator and the hook-ups: behaviour beyond the Javadoc

Specification of S11-01 (`docs/plan-2.md`; `decisions.md` D6: sequential streams in full, `parallel()`
accepted and run sequentially). It lists what the Java 8 oracle (Temurin 1.8.0_504) does where the
Java SE 8 Javadoc is silent or vague and a program can see it. Implement the Javadoc, then these
rules. How you build the classes is your choice; only what a program can observe is fixed. The
oracle's internal types are not named here and need not exist.

Each rule reads *situation -> observable result*. "Pinned by" names the test in `tests/java/stream`
and the label its line starts with (`t("label", ...)` in the source, `label:` in the golden). Every
golden was blessed on the oracle with `tools/golden.py --bless`, and LazyVM matches all of them. Three tests
were changed after blessing because their output was not portable, and re-blessed: StatsKills prints doubles by
their exact bits (the oracle's `Double.toString` is not the shortest form; D9), CollectorMaps sorts a result
that depended on identity hash codes, and CollectionBinding / SourceFlags lost the lines listed as free in
section 11. Followups holds the cases of the second review. If a golden differs from your lazyrt, fix lazyrt,
never the golden. The `.out` files must not be hand-edited.

Notation: NPE, ISE, IAE, CME, UOE, NSEE, CCE are the java.lang / java.util exceptions
(NullPointer, IllegalState, IllegalArgument, ConcurrentModification, UnsupportedOperation,
NoSuchElement, ClassCast); "no message" means `getMessage()` is null. "Linked" means a stream was
already used as the source of an operation, or consumed.

## 1. Linking, reuse, and the order of checks

**R1.** Using a stream that is linked or consumed throws `ISE: stream has already been operated upon
or closed`. This covers every kind (Stream, IntStream, LongStream, DoubleStream), every intermediate
and terminal operation, `iterator()`, `spliterator()`, and the source of a pipeline after any
operation (even `peek`) was chained to it, even if nothing ran. The new stage is fine. A terminal
operation, `iterator()` or `spliterator()` consumes the stream. A stage whose argument check failed
(R3) stays unlinked. The one exception is `skip(0)`, which returns its own stream (R7). Pinned by: StreamReuse ("map then filter" to "spliterator then count", "source
after peek", "fresh pipeline still fine", "IntStream reuse", "IntStream boxed then map").

**R2.** These do not throw on a linked or consumed stream: `parallel()`, `sequential()`, `onClose(h)`
(h is registered, C1), `close()` (any number of times), `isParallel()` (false after a sequential
terminal operation). `unordered()` throws R1 on a linked ordered stream.
Pinned by: StreamReuse ("count then parallel" to "count then isParallel", "closed then close").

**R3.** On a linked stream, which error wins depends on the operation:
- argument checked first (NPE or IAE even though the stream is used): `map`, `filter`, `flatMap`,
  `onClose`, `limit(-1)` / `skip(-1)` (IAE), `reduce(op)`, `max` / `min`, `anyMatch` / `allMatch` /
  `noneMatch`, `collect(Collector)`, and the primitive twins of these;
- stream state first (ISE): `forEach(null)`, `sorted(null)`, `toArray(null)`, `concat(used, x)`;
- `Stream.concat(null, x)` and `concat(x, null)` throw NPE.

On an *unlinked* stream an argument error leaves the stream usable, except `forEach(null)`, which
throws NPE but marks the stream consumed. Pinned by: StreamReuse (the "used, ..." lines, the "fresh,
..." and "fresh after ..." lines).

**R4.** `limit(n)` and `skip(n)` with n < 0 throw `IAE` whose message is the decimal n (`-1`,
`-9223372036854775808`). `sorted(null)` on a fresh stream throws NPE. `Stream.of((Object[]) null)`,
`IntStream.of((int[]) null)` (and long/double), `Arrays.stream(null)` and
`StreamSupport.stream(null spliterator or null supplier, ...)` throw NPE; `Stream.of((Object) null)` is
a one-element stream. `iterate(seed, null)`, `generate(null)`, `IntStream.concat(null, x)` and the like
throw NPE. Pinned by: StreamReuse ("fresh, limit(Long.MIN_VALUE)", "fresh, sorted(null) ok", "Stream.of
null array", "Stream.of one null", "... iterate null", "... generate null").

**R5.** `Arrays.stream(a, from, to)` (all four array kinds), `Arrays.spliterator(a, from, to)` and
`Spliterators.spliterator(a, from, to, ch)` check the range as follows and throw
`ArrayIndexOutOfBoundsException`: if from > to, message `origin(<from>) > fence(<to>)`; else if from
is negative, message `Array index out of range: <from>`; else if to > a.length, message
`Array index out of range: <to>`. The empty range at the end is fine. Null array: NPE, checked before the range (`[1,0)` of a null array is
NPE). The checks run in this order whatever else is wrong, so `[-1,-2)` and `[-1,-5)` give the first
message (`origin(-1) > fence(-2)`), `[-1,7)` the second (`-1`), `[5,3)` the first, `[4,4)` of 3 elements
the third (`4`), `[MAX,MIN]` the first; an empty array allows only `[0,0)`.
Pinned by: StreamReuse ("Arrays.stream range*"), SpliteratorApi ("array range check*", "Spliterators
array range"),
RangeChecks (every line: 31 ranges for all four array kinds, `Arrays.spliterator` and `Spliterators.spliterator`).

**R6.** An iterator of a stream: `remove()` throws `UOE: remove`; `next()` when exhausted throws NSEE
(no message); `hasNext()` is idempotent; `next()` without `hasNext()` works. `forEachRemaining(null)`
throws NPE. Primitive iterators are `PrimitiveIterator.Of*`; their `next()` returns the box and
`forEachRemaining(Consumer)` boxes. Pinned by: ObjectStreams ("iterator*"), IntStreams, LongStreams,
DoubleStreams ("iterator*").

**R7.** `skip(0)` returns the stream it was called on (`==`), for all four kinds, whatever the stream is: fresh,
linked, consumed, closed or iterated. It does not link the stream and throws nothing, so it is exempt from
R1; the next operation on the result follows the normal rules (`s.skip(0)` then `s.count()` works, `s.skip(0)
.count()` then `s.count()` throws R1, `consumed.skip(0).count()` throws R1). `skip(1)` on a consumed stream
throws R1, `skip(-1)` throws IAE `-1` even on a consumed stream (R3). `limit(Long.MAX_VALUE)` and `unordered()`
do return new stages. The flags and the spliterator are those of the stream itself (a list's `skip(0)`
spliterator is the list's, ORDERED|SIZED|SUBSIZED; `skip(1)` drops SIZED), `count()` still runs the pipeline
(L2), and a handler added through the result is on the same pipeline. Pinned by: SkipZero (all).

## 2. Laziness and the order of side effects

**L1.** Nothing runs until a terminal operation (or the first `hasNext` / `tryAdvance` of `iterator()` /
`spliterator()`). Elements go one at a time through the whole pipeline: `peek p, filter f, map m, peek q`
prints `p1 f1 p2 f2 m2 q20 ...`. Pinned by: StreamLaziness ("no terminal", "interleave").

**L2.** `count()` always runs the pipeline, even when the size is known (Java 8; later versions skip):
`peek` / `map` functions fire for every element, also after `limit` / `skip` of a sized source
(`skip(3)` of 4 elements fires 4 peeks; `limit(2)` fires 2). Pinned by: StreamLaziness ("count *").

**L3.** Short-circuit: `findFirst` / `findAny` / `anyMatch` / `allMatch` / `noneMatch` stop after the
deciding element; `limit(n)` stops pulling after the n-th element reaches it (infinite sources work;
`generate(s).limit(3)` calls `s` exactly 3 times, `skip(2).limit(1)` exactly 3 times).
`iterate(seed, f).limit(1)` never calls f, `limit(3)` calls f twice; `iterate` calls `f` after the
previous element has been pushed through, so `anyMatch(>5)` over doubling prints `p1 f1 p2 f2 p4 f4 p8`.
`limit(0)` pulls nothing from a source or from stages before it, **unless a `sorted` comes before the limit**:
`sorted().limit(0)` still runs the whole upstream (`p3 p1 p2`) for `count`, `findFirst`, `iterator()` and
`spliterator()`, whereas `limit(0).sorted()` and `limit(0).map(..).sorted()` pull nothing.
The primitive `iterate` calls `f` **one element ahead**: it computes the successor before the current element
is passed on, so `IntStream.iterate(1, f).limit(3)` calls `f` 3 times (the object stream: 2), `findFirst` and
`limit(1)` call it once (the object stream: never), and `anyMatch(x -> x > 3)` calls it 4 times (the object
stream: 3). The trace is `f1 p1 f2 p2 f3 p3` for the primitive streams and `p1 f1 p2 f2 p3 f3` for
`Stream.iterate`. Long and Double behave like Int (the argument of `f` is printed: `f1 p1 f2 p2 f4 p4` for the doubling `IntStream`; Followups "int iterate trace", "long iterate trace", "double iterate trace", "object iterate trace"). Pinned by: StreamLaziness (the "limit*", "skip*",
"generate*", "iterate*", "int iterate", "long iterate", "double iterate" lines), PullMode ("sorted limit 0*",
"limit 0*", "IntStream sorted limit 0", "IntStream limit 0", "LongStream sorted limit 0", "DoubleStream
sorted limit 0").

**L4.** `sorted` is a barrier: all upstream effects precede all downstream ones, also with a downstream
`limit` / `findFirst` (`p3 p1 p2 q1`). `distinct` is not a barrier; it emits first occurrences as they
come (`p1 q1 p2 q2 p1 p3 q3 p2`) and `distinct` then `limit 2` stops early. `null` is a valid distinct
element. Through `iterator()`, `sorted` runs the whole upstream at the first `hasNext`.
Pinned by: StreamLaziness ("sorted *", "distinct*").

**L5.** `flatMap`: the inner stream's elements are pushed one by one; downstream cancellation stops the
inner stream too (`findFirst` prints only `o1 i1`; a logging source is asked for one element only); a
`null` inner stream is empty; each inner stream is closed right after its last pushed element, or at
cancellation, so the inner `onClose` handlers run during the terminal operation (`q1 close1 q2 close2`).
An exception thrown by an inner close propagates immediately and aborts the pipeline. Through
`iterator()` / `spliterator()` see L11.
Pinned by: StreamLaziness ("flatMap*"), StreamClose ("flatMap run", "flatMap inner close throws").

**L6.** `iterator()` and `spliterator()` of a pipeline without barriers pull one source element per
`hasNext` / `tryAdvance` (`p1 m1` on the first `hasNext`, nothing on the second). Nothing runs at
creation. Pinned by: StreamLaziness ("iterator created" to "forEachRemaining").

**L7.** `Stream.concat(a, b)` does not touch b before a is exhausted; `findFirst` / `limit` can end in a.
Deep concatenation (300 nested) works. Pinned by: StreamLaziness ("concat*"), ObjectStreams ("concat*").

**L8.** Reduction call order: `reduce(op)` is a left fold calling `op(acc, x)`; `max` / `min` are folds
calling `compare(acc, x)`, and for a tie both keep the accumulator (the first of equals);
`reduce(identity, op, combiner)` and `collect(s, a, c)` never call the combiner sequentially.
Pinned by: StreamLaziness ("reduce*", "max*", "min*", "collect 3"), SortedOrder ("min comparator calls",
"max comparator calls").

**L9.** An exception thrown by a user function propagates unchanged and the stream stays consumed
(second use: R1). Pinned by: StreamLaziness ("caught boom" to "again").

**L10.** `sorted()` makes exactly the comparisons `Arrays.sort(T[], Comparator)` makes, in the same
argument order (`compare(a[1], a[0])` first), for sized and unsized sources, small and large. The
simplest conforming implementation sorts a copy with `Arrays.sort`. A single element is never compared
(no CCE for one non-Comparable element). Under natural order, a String met by `Integer.compareTo` gives
`CCE: java.lang.String cannot be cast to java.lang.Integer`; a non-Comparable element gives
`CCE: <class name> cannot be cast to java.lang.Comparable`; a `null` element gives NPE. Sorting is stable.
Pinned by: SortedOrder (all), ObjectStreams ("sorted*").

**L11.** `iterator()` and `spliterator()` of a pipeline work in *steps*. A step happens at the first `hasNext` /
`next` / `tryAdvance` and again whenever the buffer is empty. It pulls **one source element** (for a `sorted`
barrier: the whole upstream, which then yields its elements one by one into the later stages) through **all
later stages**, and everything the last stage emits for it is buffered; the next calls only hand out the
buffer and run nothing. What a program sees:
- With a `flatMap` upstream, every element of the inner stream of one outer element passes through all later
  `map` / `filter` / `peek` stages at the step (`i1 in10 m10 in100 m100 in1000 m1000` at the first `hasNext`;
  a second `hasNext`, and the next two `next`s, run nothing; the fourth `next` runs `o2 i2 in20 m20 ...`).
  The outer `peek` of the next element runs only at the next step.
- A `limit` stops the push inside the step: `flatMap(3 items).limit(4).iterator()` runs `o1 i1 in10 in100 in1000`
  at the first call and `o2 i2 in20` at the second (`in200` is never pulled); `limit(2)` runs `o1 i1 in10 in100`
  and then nothing, also when the iterator reports the end. `skip(1)` hides the first element but the step
  still runs the whole first inner stream.
- A `sorted` barrier runs the whole upstream **and every stage after it, for all elements,** at the first
  `hasNext` / `tryAdvance`: `peek(a).sorted().peek(b).iterator()` prints `a3 a1 a2 b1 b2 b3` at the first
  `hasNext` and nothing for the later `next`s. A `flatMap` after a `sorted` runs for *all* outer elements at the
  first call (`o2 o1 i1 ... i2 ...`). `sorted().limit(0)` runs the upstream and then reports the end.
- Mixing is allowed and nothing is lost or repeated: after one `tryAdvance` / `next` a `forEachRemaining` delivers
  the rest of the buffer and then goes on **stepping**: each later step is still run whole before its elements are
  handed out (`flatMap(x -> Stream.of(x, x*10).onClose(c)).peek(p).spliterator()`, one `tryAdvance`, then
  `forEachRemaining`: `p1 p10 c1 t1 r10 p2 p20 c2 r2 r20`). Only when no step has run yet is the pipeline run in bulk
  (`p1 r1 p10 r10 c1 p2 r2 ...`). In the first case `flatMap` over 2 outer elements gives `10` then `100,1000,20,200,2000`,
  the second outer element running inside `forEachRemaining`. This holds through `map`,
  `filter`, `peek`, `limit`, `skip`, `distinct` and `concat` of two `flatMap`s, for `Stream` and the three
  primitive streams (`nextInt` then `forEachRemaining(IntConsumer)`; `tryAdvance(IntConsumer)` then
  `forEachRemaining`), for `flatMapToInt` and for a `null` or empty inner stream (skipped).
- `forEachRemaining` of an *iterator* is the plain `hasNext`/`next` loop, one step per element (a list that changes during it throws CME after the first element), and the `forEachRemaining` of the *spliterator* of a pipeline steps once a step has run and is bulk otherwise.
- When the line may stop early, an inner stream of `flatMap` is read through its own `spliterator()`, one step at a time: a nested `flatMap` therefore pulls all of the innermost elements of one step (`n10 n100`) even when the first one decided.
- Inner close handlers: the inner stream is closed at the end of the step that pushed it (or at its
  cancellation), so with `flatMap(onClose).limit(2).iterator()` `close1` appears at the first `next`, not later.
- `flatMap(onClose).limit(2).sorted()` as a terminal operation prints `close1` once (inner 1 supplied both
  elements) and returns `[1, 11]`.
- Nested `flatMap` with `findFirst` / `anyMatch` stops everything, closing inner streams inside out
  (`c1 C1`); through `iterator()` the first step runs one outer element, its two middle elements and all their
  inner elements (`o1 m1 n10 n100 m2 n20 n200`).
Pinned by: PullMode (everything before the "CME" lines; the first lines of each test show the log of the step).

**L12.** Short-circuit does not skip the modification check. A collection spliterator checks for a concurrent
modification right after it delivers the element that was being processed, even when the pipeline has already
decided to stop: `list.stream().limit(1).peek(x -> list.add(9)).count()` throws CME (also `peek(add).limit(1)`,
`findFirst`, `anyMatch`, `forEach`, `iterator()` + `next()`, a `HashSet` with `add` or `remove`, `LinkedList`,
`TreeSet`), whereas `ArrayDeque` and `CopyOnWriteArrayList` do not throw. Pinned by: PullMode ("list limit 1
peek add*", "set limit 1*", "list findFirst peek add", "list anyMatch add", "list limit 2 add", "linkedlist*",
"arraydeque*", "treeset*"), CollectionBinding ("stream add after peek of first", "stream add in last peek").

## 3. Close handlers

**C1.** `onClose(h)` returns a stream of the same pipeline. Handlers registered anywhere in a pipeline (on
the source or any later stage) all run, in registration order, on `close()` of any stage of it, at most
once ever: the second `close()` runs nothing. A terminal operation (also one that throws) never runs them.
A handler registered on a consumed stream is accepted and runs on `close()`.
Pinned by: StreamClose ("after count" to "late handler", "after throw", "try-with-resources").

**C2.** If handlers throw, all still run; the first thrown `Throwable` is rethrown and every later one is
added to it with `addSuppressed` in order (an Error thrown first wins over later exceptions; an exception
that already carries suppressed ones keeps them and gets the new ones appended). If a handler throws the
same object that is already being thrown, it is not suppressed into itself.
Pinned by: StreamClose ("first wins", "same twice", "pre-suppressed", "error first", "error second").

**C3.** Closing `Stream.concat(a, b)` runs a's handlers, then b's, then its own, with the same suppression
rule. `close()` on streams from `boxed`, `mapToObj`, `mapToInt`, `parallel`, `sequential`, `unordered`,
`sorted` / `limit` / `skip` / `distinct` / `peek`, collections, `StreamSupport`, `Stream.empty`, builders and
`generate` runs the handlers of the whole chain. A handler that closes another stream, or its own stream,
works (the own stream is not rerun). Pinned by: StreamClose ("concat*", "IntStream" to "self close").

**C4.** Inner streams of `flatMap` (also the primitive forms and `flatMapToInt/Long/Double`): each inner stream is
closed exactly once, even when downstream stops early (`limit` / `findFirst` / `anyMatch`), when the inner
pipeline throws (`inner.map(throws)`: the inner handlers still run and the original exception wins) and when
the downstream throws. The outer stream's own handlers are not run. Handlers on the inner stream run in
registration order, including those registered on later stages of the inner pipeline (`a b`). With nested
`flatMap` the innermost inner stream closes first: `c1 c2 C1 c2 c3 C2` for a full run; `limit(3)` stops after
`c1 c2 C1`; `findFirst` and `limit(1)` give `c1 C1`; a `limit` inside the nested pipeline also closes (`c1 c2 C1
c2 c3 C2`). A `null` inner stream and an empty inner stream are fine (the empty one is still closed: `c1 c2`).
An inner stream that was already used or linked, also one returned twice, throws R1 from the `flatMap`
**and is still closed** (its handlers run; `used` appears even though the stream was consumed before); a late
`onClose` on a consumed inner stream runs too. An inner stream returned by the function is not made
sequential before the pipeline runs, but is afterwards: see PA2. Pinned by: FlatMapClose ("flatMap run" to
"outer handler not run by flatMap", "inner pipeline throws" to "nested inner throws", "inner already consumed"
to "inner handler registered late").

**C5.** (When the body throws the very object that an inner stream's close handler throws, the result is
`IllegalArgumentException: Self-suppression not permitted`, as `Throwable.addSuppressed` does; Followups "flatMap same
exception body and close". The "not suppressed into itself" rule of C2 is for the handlers of one stream only.)
A body exception and a close exception: when the body of a pipeline throws and closing then throws,
the body exception stays the primary one and the close exceptions are added to it with `addSuppressed` in
the order they happened (`RuntimeException(body m1) +IllegalStateException(close c1)`). This holds for the inner
streams of `flatMap` (body exception thrown by the inner pipeline, by a stage after the `flatMap` such as `peek`
or `limit` + `map`, or in nested `flatMap`s: `body n +close c1 +close C1`), for all four stream kinds, for
`iterator()` and `spliterator()` of such a pipeline, and for try-with-resources over a stream (`close()` is
called after the body, so its exceptions are suppressed into the body's; an `Error` body keeps the `Error`
primary: `AssertionError(body err) +IllegalStateException(close c)`). When only the close throws, the first
close exception is the one thrown and later ones are suppressed (`close a +close b`). A `flatMap` inner close
that throws with a normal body propagates immediately (C2/L5), and an `AssertionError` from a close is thrown
as is. In try-with-resources over a stream the handler that throws the very exception object that the body is
throwing gives `IllegalArgumentException: Self-suppression not permitted` with that exception as cause (that
is `Throwable.addSuppressed`, not the stream). Handlers that throw still all run, and an exception that already
carries suppressed ones keeps them (`close a` thrown first, then `body pre`, `old`, `close b`). Pinned by:
FlatMapClose ("flatMap body and close" to "twr flatMap in twr", "close same exception object", "close
suppressed already present").

**C6.** Closing a stream that is the source of another stage: the later stage then throws `ISE: source already
consumed or closed` (not the R1 message) from every terminal operation and every intermediate operation
(`count`, `iterator`, `spliterator`, `map`, `sorted`, `collect`, and the primitive kinds, collections, ranges and
`concat` as sources), whereas the closed stream itself throws R1. Closing a *middle* stage does not poison the
stages after it (`close middle then count` gives 2) and closing the last stage does poison it. Closing runs the
handlers of the whole chain once (`a b`), `onClose` on a closed stream is accepted and runs on the next
`close()`, and `parallel()` on a closed stream works. Pinned by: FlatMapClose ("close source then *", "close
middle then count", "close last then count", "close then *", "*close source then sum", "*close source then
count", "close source of sorted then count").

## 4. Terminal operations and factories

**T1.** `toArray()` returns `Object[]`; `toArray(gen)` returns what the generator made (its array type:
`String[]`, `CharSequence[]`). The size passed to the generator is the exact element count for every source
and pipeline in the goldens (sized ones, filtered ones, `skip`, `limit`, `sorted`, `iterate().limit(n)`,
empty). Misbehaving generators, exact outcomes: an array one element too small or too large from a *sized*
pipeline gives `ISE: Begin size 6 is not equal to fixed size 5` (or `... 7`; the first number is the
stream's size, the second the array length); from an *unsized* pipeline (after `filter`) too small gives
`IndexOutOfBoundsException: does not fit`, too large returns the array with trailing nulls; a generator
returning null gives NPE; elements that do not fit the array type give `ArrayStoreException: java.lang.String`
(sized) or `ArrayStoreException` with no message (after `filter`; newer JDKs add text, the golden is Java 8);
an empty stream with a wrong-typed generator yields the empty array. Pinned by: ObjectStreams ("toArray*").

**T2.** `reduce(op)`, `findFirst`, `findAny`, `max`, `min` throw NPE when the result would be `null`
(`Optional.of`): `findFirst` on a stream whose first element is null, `reduce((a,b)->null)`, `max`
returning null. `reduce(null identity, op)` on an empty stream returns null.
Pinned by: ObjectStreams ("reduce null*", "findFirst null", "max null result", "reduce identity null").

**T3.** `Stream.of(arr)` with `String[]` streams the elements, with `int[]` is a one-element stream.
`Stream.builder()`: `add` returns the builder, `accept` works, `build()` twice, and `add` / `accept` after
`build`, throw `ISE` (no message); nulls are allowed; the built stream is single-use. The three primitive
builders behave alike. Pinned by: ObjectStreams ("of*", "builder*"), IntStreams, LongStreams,
DoubleStreams ("builder*").

**T4.** `IntStream.of(int[])` and `Arrays.stream(int[])` do not copy: a change to the array before the
terminal operation is seen. The same holds for `Arrays.asList(a).stream()`, `Stream.of(array)` and
`Arrays.stream(String[])`. Pinned by: IntStreams ("of array is not copied"), CollectionHooks ("late binding
Arrays.*", "late binding Stream.of array").

**T5.** `toArray` when the source's size is a lie (a SIZED spliterator from `Spliterators.spliterator(iterator,
size, ch)` whose iterator has a different length). The pipeline believes the size when it is still SIZED after
all stages (so after `map`, `peek`, `boxed`, `mapToObj`, `asDoubleStream`, primitive `map`, but not after `filter`,
`limit`, `skip`, `distinct`, `flatMap`):
- too few elements: `ISE: End size 2 is less than fixed size 5` (the first number is how many elements arrived);
  the generator of `toArray(gen)` is called with 5, peeks have fired (`p1 p2`), and for 0 elements it says
  `End size 0 ...`; `sorted()` after the lying source says `ISE: Begin size 2 is not equal to fixed size 5`;
- too many elements: `ISE: Accept exceeded fixed size of 2`, after the peeks of the first 3 elements fired
  (`p1 p2 p3`); a claimed size 0 gives `... of 0`; `sorted()` after it gives `ArrayIndexOutOfBoundsException: 2`;
- all four kinds behave alike (`IntStream`, `LongStream`, `DoubleStream`, boxed and mapped forms);
- an unsized pipeline (`filter`, `limit`, `skip`) just returns the elements; `collect(toList())`, `count()`,
  `forEach`, `reduce` and `iterator()` never see the lie;
- a generator that returns an array of the wrong length for a lying source: `Begin size 5 is not equal to fixed
  size 6` (size 5 claimed, array 6);
- a supplier-based source (`StreamSupport.stream(supplier, ch | SIZED, false)`) over a spliterator that is not
  sized: the claimed SIZED applies to the *stream's flags only until the spliterator is looked at*; `toArray`,
  `toArray(gen)` (gen called with the real count 3), `count`, `collect` and `iterator()` all work and
  `spliterator()` reports the real spliterator (`characteristics() == 16`, estimate `Long.MAX_VALUE`, exact -1).
  A supplied spliterator with fewer elements than it claims fails like above (`End size 2 is less than fixed
  size 5`).
Pinned by: ToArraySizes (all).

**T7.** What a sort stage is told about the size: the count known at its place in the pipeline. A stage that can change the count (`filter`, `flatMap`, `distinct`) announces unknown; `skip(n)` announces what is left after n and `limit(n)` at most n; `map`, `peek`, `boxed` and the like pass it on. So `LongStream.range(0, MAX).boxed().sorted().limit(3)`, `.sorted().filter(..)`, `.sorted().skip(3)` and `.skip(1).sorted()` all fail at once with `IAE: Stream size exceeds max array size`, while a `sorted()` after `filter` or `flatMap` is told no size (Followups "sorted flatMap sorted", "long range boxed *"). A `toArray` generator that returns null fails with NPE before any element is produced.

**T6.** A size that does not fit an array: the allocation is attempted and fails with `OutOfMemoryError` (the
oracle's message depends on the heap, so only the class is pinned, with `-Xmx64m`); there is no
`IllegalArgumentException` for these. This covers `toArray()` of `LongStream.range(0, Long.MAX_VALUE)`, of
`IntStream.range(0, MAX_INT)`, `rangeClosed(0, MAX_INT)`, and their `boxed`, `mapToObj`, `map`, `peek`,
`asDoubleStream`, `mapToLong`, `sorted` forms. `limit(3)` first makes `toArray` work (`3`), also after
`filter`. The `IllegalArgumentException: Stream size exceeds max array size` is thrown only by `sorted()` on a
sized stream that does not fit an array and is not already SORTED
(`LongStream.range(0, MAX).boxed().sorted()`, `mapToObj(..).sorted()`, `.map(..).sorted()`, boxed ints), see SP8.
Pinned by: HugeToArray (all), ToArraySizes ("long range limit*", "int range near limit count"),
SortedNoop ("long range boxed sorted", "long range mapToObj sorted", "int range boxed sorted huge",
"long range map sorted").

## 5. Primitive streams

**P1.** `range(a, b)` is empty when a >= b; `rangeClosed(a, b)` when a > b. The ends of the type are handled
without overflow: `rangeClosed(MAX-2, MAX)`, `rangeClosed(MIN, MIN)`, `range(MIN, MAX)` (4294967295 elements:
`spliterator().getExactSizeIfKnown()` is 4294967295, and 4294967296 for `rangeClosed(MIN, MAX)`);
`limit` / `skip` / `iterator` / `anyMatch` on them work without a wrap (`skip(4294967293L)` on the full
closed range gives the last three). For `LongStream.range(MIN, MAX)` and `rangeClosed(MIN, MAX)` the size
does not fit a long: `estimateSize()` is `Long.MAX_VALUE`, `getExactSizeIfKnown()` is -1, `limit(2)` works
(never iterate these fully). Pinned by: IntStreams ("range*", "rangeClosed*"), LongStreams (same).

**P2.** `IntStream.sum()` wraps in int; `LongStream.sum()` in long. `average()` of Int and Long streams sums
in a *long* (exact for ints; for longs it wraps: the average of two `Long.MAX_VALUE` is `-1.0`) and divides
by the count; empty gives `OptionalDouble.empty`. `count()` is a long. Pinned by: IntStreams ("sum*",
"average*"), LongStreams (same).

**P3.** `DoubleStream.sum()`, `average()`, `Collectors.summingDouble` / `averagingDouble` and
`DoubleSummaryStatistics.getSum` / `getAverage` use compensated (Kahan) summation; the result is *not* the
naive loop (`0.1` ten times is `1.0`, the naive loop gives `0.9999999999999999`; `1e16,1,1,1,1` gives
`1.0000000000000004E16`). Black-box fuzzed against the oracle (30000 random arrays including NaN and
infinities, 0 mismatches), the exact rule is: `sum = 0, c = 0, simple = 0`; for each value `v`:
`simple += v; t = v - c; s = sum + t; c = (s - sum) - t; sum = s`. Result `r = sum + c`; if `r` is NaN and
`simple` is infinite the result is `simple`, else `r`. `average = result / count` (no elements:
`OptionalDouble.empty`, or `0.0` for `averagingDouble` and the statistics; `summingDouble` and an empty
stream give `0.0`). The last step adds the compensation to the sum *with its sign as stored* (`sum + c`, where `c` already holds the
negated error): `[2^54-2, 3.5]` sums to `1.8014398509481982E16` and `[2^54-2, -3.5]` to `1.8014398509481978E16`;
`[2^54-2, 3.5, 3.5]` to `1.8014398509481988E16`; `[1e300, 1e300, -1e300]` to `1e300`; `combine` of the one-element
statistics of `2^54-2` and `3.5` gives `1.8014398509481982E16` in both orders. Every way to reach a sum gives the
same value: `sum`, `average`, `summaryStatistics`, `summingDouble`, `averagingDouble`, `summarizingDouble`
(`reduce(0, Double::sum)` is the naive loop and is not the same). Pinned by: StatsKills ("sum [*", "average [*",
"stats [*", "summingDouble [*", "averagingDouble [*", "summarizingDouble [*", "reduce [*", "combine *",
"accept one by one", "sum after sum"). So `[+Inf, 1]` sums to `Infinity`, `[+Inf, -Inf]` to `NaN`, `[MAX, MAX, -MAX]` to
`Infinity`, `[-0.0]` to `0.0`. Pinned by: DoubleStreams ("sum*", "average*", "stats*"), CollectorResults
("summingDouble*", "averagingDouble*"), SummaryStatistics ("double *").

**P4.** `DoubleSummaryStatistics.combine(o)` (Java 8 rule, fuzzed the same way, 0 mismatches in 20000
pairs): `count += o.count; simple += o.simple;` then the Kahan step of P3 on `o.sum`, then the Kahan step on
`o.c`, both on the receiver's `sum` and `c`; min and max combine with `Math.min` / `Math.max`. This
differs from newer JDKs: `ds(0.1,0.2,0.3)` combined with `ds(0.4,0.5,0.6,0.7)` has the sum
`2.8000000000000007`. `combine(null)` is NPE; `combine(self)` doubles it. `accept` keeps min and max with
`Math.min` / `Math.max`: NaN poisons both and `-0.0` is below `0.0`.
Pinned by: SummaryStatistics ("double combine*", "double NaN", "double signed zero*").

**P5.** `DoubleStream.min()` / `max()` use `Math.min` / `Math.max` semantics (NaN wins, `-0.0 < 0.0`);
`sorted()` orders `-Infinity ... -0.0, 0.0 ... Infinity, NaN`; `distinct()` keeps one NaN and both zeros;
`boxed().sorted()` has the same order; `mapToInt` / `mapToLong` narrow like the `(int)` / `(long)` casts
(NaN gives 0, saturating). Pinned by: DoubleStreams ("min*", "max*", "sorted*", "distinct*", "mapTo*").

**P6.** `iterate` / `generate` of the primitive streams, `concat`, `flatMap` (a null inner stream is empty),
`boxed` (values are `valueOf`, so small values are identical objects), `mapToObj`, `peek`,
`collect(supplier, accumulator, combiner)` (combiner unused) behave as in L1 to L9. `spliterator()` of an
`IntStream` is a `Spliterator.OfInt`; `tryAdvance(Consumer<Integer>)` and `forEachRemaining(Consumer<Integer>)`
box. Pinned by: IntStreams, LongStreams, DoubleStreams.

## 6. Statistics classes

**S1.** `toString` is `<simple class name of the runtime class>{count=%d, sum=%d, min=%d, average=%f, max=%d}`
for Int and Long and `...{count=%d, sum=%f, min=%f, average=%f, max=%f}` for Double (`String.format`
semantics, en-US; NaN prints `NaN`, infinities `Infinity` / `-Infinity`, `-0.0` prints `-0.000000`, large
values print all digits: `1100000000000000000000.000000`; `%f` rounds the shortest decimal digits of the
double, so `123456789.123456789 + 1e15` prints `1000000123456789.100000`). The class name is
`getClass().getSimpleName()`: a nested subclass `MyStats` prints `MyStats{...`, an anonymous one prints
`{count=...`. The long and double statistics print their subclass name too (`MyLong{count=2, sum=12, ...}`,
`MyDouble{...}`, a local class `Local{`, a `$` in the name stays: `Odd$Name{`), an anonymous subclass of either
prints `{count=...` and the plain class prints its own name. Pinned by: StatsKills ("Int subclass" to "Long
subclass class name"). Empty: Int `min=2147483647 max=-2147483648`, Long with the long limits, Double
`min=Infinity max=-Infinity`; `average=0.000000`; `sum=0` / `0.000000`.
Pinned by: SummaryStatistics ("int new", "int subclass toString", "int anonymous subclass toString",
"double toString*"), IntStreams / LongStreams / DoubleStreams ("summaryStatistics*").

**S2.** They implement `IntConsumer` / `LongConsumer` (Long also `IntConsumer`, with `accept(int)`) /
`DoubleConsumer` (`andThen` works), have public no-arg constructors, and are subclassable (an overridden
`accept` is what `forEach` calls); `combine(null)` is NPE. `getAverage()` is `getSum() / getCount()`, `0.0`
when empty. Int sum is a long; Long sum wraps. No `equals` / `hashCode` override. Pinned by:
SummaryStatistics ("int accept" to "int subclass accept", "long *").

## 7. Collectors

**K1.** Characteristics (`Collector.characteristics()` is an unmodifiable `Set`, UOE on `add` / `remove`;
`toString` shows `EnumSet` order CONCURRENT, UNORDERED, IDENTITY_FINISH):
`toList`, `toCollection`, `summarizing*`: IDENTITY_FINISH (`toSet` adds UNORDERED); `mapping(f, d)`: those of
`d`; `joining`, `counting`, `minBy` / `maxBy`, `summing*`, `averaging*`, `reducing*`: none;
`collectingAndThen(d, f)`: those of `d` minus IDENTITY_FINISH; `groupingBy` (all forms), `partitioningBy`:
IDENTITY_FINISH iff the downstream has it (nothing else, so `groupingBy(f, toSet())` has only
IDENTITY_FINISH); `toMap` (all forms): IDENTITY_FINISH; `groupingByConcurrent`: CONCURRENT, UNORDERED, plus
IDENTITY_FINISH iff the downstream has it; `toConcurrentMap` (all forms): CONCURRENT, UNORDERED,
IDENTITY_FINISH. `Collector.of(s, a, c, ch...)`: `ch` plus IDENTITY_FINISH (duplicates collapse);
`Collector.of(s, a, c, f, ch...)`: just `ch`. All `Collector.of` arguments are checked at once, a null
varargs array or null element included: NPE. `Collector.Characteristics.values()` is
`[CONCURRENT, UNORDERED, IDENTITY_FINISH]`. For a collector whose characteristics contain IDENTITY_FINISH,
`finisher()` is a working identity function. Pinned by: CollectorShapes (the first 60 output lines,
"of null *", "toList parts", "of 4 finisher is identity").

**K2.** Null arguments to the factories. Throw at the factory call: `mapping(f, null)`,
`collectingAndThen(null, f)` and `(d, null)`, `minBy(null)`, `maxBy(null)`, `groupingBy(f, null downstream)`,
`partitioningBy(p, null downstream)`. Do **not** throw at the call, nor on an empty stream, but throw NPE at
the first element: `mapping(null, d)`, `summing*(null)`, `averaging*(null)`, `summarizing*(null)`,
`reducing(identity, null, op)`, `reducing(identity, f, null)`, `reducing(identity, null op)`,
`groupingBy(null)`, `groupingByConcurrent(null)`, `partitioningBy(null)`, `toMap(null, v)`, `toMap(k, null)`,
`toMap(k, v, null)`, `toConcurrentMap(null, v)`. Throw NPE when the container is first made, also on an
empty stream: `toCollection(null)`, `groupingBy(f, null mapFactory, d)`, `toMap(k, v, m, null)`.
`reducing(null op)` with one element returns `Optional[a]` (the operator is never called). `joining(null)`,
`joining(d, null, s)`, `joining(d, p, null)` throw at use, also on an empty stream, with the messages
`NPE: The delimiter must not be null`, `The prefix must not be null`, `The suffix must not be null`.
`collect((Collector) null)` is NPE, also on an empty stream. Pinned by: CollectorShapes (the `built` /
`empty` / `one` lines).

**K3.** Result types: `toList` gives `java.util.ArrayList`, mutable, may hold nulls; `toSet` a `HashSet`;
`groupingBy` a `HashMap` of `ArrayList` (downstream results are applied to each value; with a `mapFactory`
the factory's map is returned and keeps its type even with a finishing downstream); `groupingByConcurrent`
a `ConcurrentHashMap`; `toMap` a `HashMap`; `toConcurrentMap` a `ConcurrentHashMap`; `counting` a `Long`;
`summingInt` an `Integer` (wraps), `summingLong` a `Long`, `summingDouble` / `averaging*` a `Double`;
`joining` a `String`; `minBy` / `maxBy` an `Optional`, empty for no elements *or* a null result;
`reducing(op)` an `Optional` (empty on a null result); `reducing(identity, op)` uses the identity object
itself as the accumulator (a mutable identity is shared by every use of the collector: a second use starts
from the first's result). `minBy` and `maxBy` both return the first of equals. Pinned by: CollectorResults
(lines "toList" to "summarizingInt type").

**K4.** `groupingBy`: a null key throws `NPE: element cannot be mapped to a null key` (also for
`groupingByConcurrent`), at the element that maps to null; nothing happens for an empty stream. With
`TreeMap::new` and the like, the order is the map's. `partitioningBy` returns a map with exactly the keys
`false`, `true` in that order (`toString` is `{false=[...], true=[...]}`), present also for an empty
stream (`{false=[], true=[]}`); `get(null)` and `get("x")` are null, `containsKey(null)` is false; `size()`
is 2; `put`, `remove`, `clear` and `Entry.setValue` throw `UOE` (no message); `equals` / `hashCode` follow the
`Map` contract; the value lists are real mutable `ArrayList`s and `get(true)` returns the same object every
time. Pinned by: CollectorResults ("groupingBy*", "partitioningBy*").

**K5.** `toMap`:
- `toMap(k, v)` with a duplicate key throws `ISE: Duplicate key <earlier value>`: the message shows the
  **value** stored first, formatted with `String.valueOf`, not the key (newer JDKs differ), as soon as the
  duplicate is met (later elements are not mapped);
- a null value throws NPE (also with a merge function, also for `ConcurrentHashMap`); a null key is accepted
  by `HashMap` targets (`{null=b, a=a}`) and rejected (NPE) by `TreeMap` and concurrent maps;
- with a merge function: called `(old, new)` for repeated keys only (left fold: `((ab)c)`); a null result
  removes the entry (`a,a,a` with an always-null function gives `{}`); its exceptions propagate;
- the 4-argument form uses the factory's map as is (also a pre-filled one), so an unmodifiable map throws UOE
  at the first element and is returned untouched for an empty stream.

Pinned by: CollectorMaps (all).

**K6.** Parts of a collector: `toList()`'s supplier makes an `ArrayList`, its combiner `addAll`s the second
into the first and returns the first; `joining(d)` combines two partial results with the delimiter.
Pinned by: CollectorShapes ("toList parts", "toSet parts", "joining parts", "of parts").

**K7.** Which methods of a `Collector` the library calls, and when. All of this is observable with a collector
that logs its getters and its containers (`supplier()` is the getter, `get` the call of the supplier).
- `collect(Collector)`, sequential: first the getters `supplier()`, `accumulator()`, `combiner()`,
  `characteristics()`, once each and in this order; then the container is created (also for an empty stream),
  the elements are accumulated; then `characteristics()` again, then `finisher()` and its application, unless the
  collector has IDENTITY_FINISH (then no `finisher()` call and the container is returned as is). The combiner
  is only fetched, never applied. The same collector can be used again; each use starts with a new container.
- `collect(supplier, accumulator, combiner)` (all kinds) calls the supplier once and the accumulator per
  element; the combiner is never called.
- `partitioningBy(p, d)`: the factory calls `d.accumulator()`, `d.combiner()`, `d.characteristics()` (and nothing
  else). `collect` then fetches `d.supplier()` and creates a container for `true`, then fetches `d.supplier()` again
  and creates the one for `false`, both **before** the first element (an empty stream and a throwing predicate
  see both); with a stateful supplier `true` gets the first container (`made1`), `false` the second. At the
  end the finisher is fetched and applied to the `true` container, then fetched and applied to the `false`
  container (a throwing finisher stops after the `true` one). With an IDENTITY_FINISH downstream there are no
  finisher calls. If the downstream supplier returns one shared container, both sides are that container.
- `groupingBy(f, mapFactory, d)`: the factory calls `d.supplier()`, `d.accumulator()`, `d.combiner()`,
  `d.characteristics()` and, unless IDENTITY_FINISH, `d.finisher()`. `collect` creates the map before the first
  element, and a container (`d.supplier().get`) for a key when its first element arrives (`key3 new1 acc 3 key2
  new2 ...`); after the last element the downstream finisher is applied to each value **in the map's iteration
  order** (TreeMap: key order; LinkedHashMap: insertion order). `groupingByConcurrent` fetches
  `d.characteristics()` twice. For an empty stream the factory's getters are called and nothing else.
- `mapping(f, d)` calls `d.accumulator()`, `d.supplier()`, `d.combiner()`, `d.finisher()`, `d.characteristics()` at the
  factory call (in this order) and none during `collect`; `collectingAndThen(d, g)` calls `d.characteristics()`,
  `d.supplier()`, `d.accumulator()`, `d.combiner()`, `d.finisher()` at the factory call, and applies `d`'s finisher
  then `g` at the end, also if `d` has IDENTITY_FINISH.
- `Collector.of(...)` and `Collectors.toList()` return the same supplier object on every `supplier()` call.
Pinned by: CollectorCalls (everything before "flatMap inner parallel").

## 8. Spliterators that programs can see

**SP1.** The characteristics (ORDERED 16, DISTINCT 1, SORTED 4, SIZED 64, NONNULL 256, IMMUTABLE 1024,
CONCURRENT 4096, SUBSIZED 16384) and `estimateSize()` / `getExactSizeIfKnown()` (-1 when not SIZED) are fixed
for each source. The SpliteratorSources golden lists them all; the shape is:
- `ArrayList`, `LinkedList`, `Vector`, `Stack`, `Arrays.asList`, `subList` of an ArrayList, `nCopies` and the
  `List` default: ORDERED|SIZED|SUBSIZED; `ArrayDeque` adds NONNULL; `PriorityQueue`: SIZED|SUBSIZED|NONNULL
  only; `HashSet` and `HashMap` keySet / entrySet: DISTINCT|SIZED (no SUBSIZED); `HashMap.values()`: SIZED;
  `LinkedHashSet` and `LinkedHashMap` views: ORDERED|(DISTINCT)|SIZED|SUBSIZED; `TreeSet` and `TreeMap`
  keySet / entrySet: ORDERED|DISTINCT|SORTED|SIZED, `getComparator()` null for natural order or the very
  comparator object given; `TreeMap.values()`: ORDERED|SIZED; `descendingSet()` / `descendingKeySet()`: no
  SORTED; `headSet` views: not sized (estimate `Long.MAX_VALUE`); `EnumSet`: DISTINCT|SIZED|SUBSIZED;
- `Collections.emptyList` / `emptySet`: SIZED|SUBSIZED; `singleton` / `singletonList`:
  ORDERED|DISTINCT|SIZED|NONNULL|IMMUTABLE|SUBSIZED; the `unmodifiable*` wrappers delegate (an unmodifiable
  view of a TreeSet is SORTED); the `Iterable` default: no characteristics, unknown size; the `Collection`
  default: SIZED|SUBSIZED (plus ORDERED for a List, DISTINCT for a Set; abstract classes in the goldens);
- `ConcurrentHashMap` views: DISTINCT (not `values`)|NONNULL|CONCURRENT, not SIZED, estimate = current size;
  `ConcurrentSkipListSet`: ORDERED|DISTINCT|SORTED|NONNULL|CONCURRENT, estimate `Integer.MAX_VALUE`;
  `ConcurrentLinkedQueue`: ORDERED|NONNULL|CONCURRENT, estimate `Long.MAX_VALUE`; `LinkedBlockingQueue` and
  `ArrayBlockingQueue`: the same flags, estimate = size; `CopyOnWriteArrayList`: ORDERED|SIZED|IMMUTABLE|
  SUBSIZED; `CopyOnWriteArraySet`: DISTINCT|SIZED|IMMUTABLE|SUBSIZED;
- arrays (`Arrays.spliterator`, `Arrays.stream`, `Stream.of(...)`, `IntStream.of`): ORDERED|SIZED|IMMUTABLE|
  SUBSIZED; a builder with one element likewise, with two or more no IMMUTABLE; `Stream.empty`,
  `IntStream.empty`: SIZED|SUBSIZED; `iterate`: ORDERED|IMMUTABLE (primitive: plus NONNULL); `generate`:
  IMMUTABLE; `range` / `rangeClosed` (Int and Long): ORDERED|DISTINCT|SORTED|SIZED|NONNULL|IMMUTABLE|SUBSIZED,
  `getComparator()` null;
- `chars()` of String / StringBuilder / CharBuffer: ORDERED|SIZED|SUBSIZED; `codePoints()`: ORDERED, unknown
  size; `BitSet.stream()`: ORDERED|DISTINCT|SORTED|SIZED|SUBSIZED; `Random.ints / longs / doubles` (any form):
  SIZED|NONNULL|IMMUTABLE|SUBSIZED, size = the requested count or `Long.MAX_VALUE`.

**SP2.** `stream.spliterator()` on a stream with no operation returns the source's spliterator (same
characteristics). After operations the flags follow the pipeline (goldens "map", "filter", "sorted",
"distinct", "limit", "skip", "unordered", "flatMap", "boxed", "mapToInt", ...). A pipeline reports only
ORDERED, DISTINCT, SORTED, SIZED and SUBSIZED (NONNULL, IMMUTABLE and CONCURRENT are dropped by every
operation, also `peek`; `skip(0)` is no operation, R7). Per operation: `map` / `boxed` / `mapToObj` /
`mapToInt` and the like keep SIZED and ORDERED but drop SORTED and DISTINCT; **`peek` keeps everything**
(ORDERED, DISTINCT, SORTED, SIZED, SUBSIZED), `sorted().peek(..)` is SORTED|ORDERED|SIZED|SUBSIZED and
`sorted().distinct().peek(..)` DISTINCT|SORTED|ORDERED; `filter` drops SIZED (the estimate stays); `sorted()`
adds SORTED, `sorted(cmp)` drops SORTED; the object `distinct` adds DISTINCT, keeps SORTED and drops SIZED;
**the primitive `distinct` adds nothing, drops SORTED, DISTINCT and SIZED** (`IntStream.range(0,5).distinct()`
is ORDERED only, estimate 5; `sorted().distinct()` too; `distinct().sorted()` is SORTED|ORDERED);
`limit` and `skip` drop SIZED (Java 8; later versions keep it); `unordered` drops ORDERED; `flatMap` drops
SIZED, SORTED and DISTINCT; `parallel()` / `sequential()` change nothing; the `getComparator()` of
`sorted(cmp)` throws ISE. **An empty range** (`IntStream.range(0, 0)`, `rangeClosed(1, 0)`, Long too) has
only SIZED|SUBSIZED, like `empty()`. **A source sorted by a comparator** (`TreeSet(cmp)`, `TreeMap(cmp)` views)
is SORTED at the source, but every operation on its pipeline, `peek` included, drops SORTED (a natural-order
TreeSet keeps it through `peek`); `sorted()` on it sorts again by natural order and reports SORTED with
`getComparator() == null`. The `getComparator()` of `sorted().peek(..)` and of `sorted().skip(..)` is null,
that of `sorted(cmp)` and of its `peek` is ISE, and `sorted(cmp).sorted()` is null again.
Pinned by: SortedNoop (the last block, "comparator-sorted sources": "TreeSet comparator *", "TreeSet natural
*", "list sorted*", "natural TreeSet sorted*", "claims SORTED*getComparator"), SkipZero
("*flags"), SourceFlags ("concat flags" lines), SortedNoop ("array int sorted flags", "int range sorted*").
The primitive `distinct` and empty-range lines: SortedNoop ("int range sorted distinct flags", "int range
sorted flags") and the first lines of this section's golden SpliteratorSources. **The implementer must compute these pipeline flags exactly; it is the largest observable table here.**
Pinned by: SpliteratorSources (the stream section and the "comparator*" lines).


**SP3.** `Spliterator.getComparator()` default throws `ISE` (no message); `getExactSizeIfKnown()` is -1 unless
SIZED; `hasCharacteristics(mask)` is true only if **all** bits are set (`0` gives true); the default
`forEachRemaining` loops `tryAdvance`; `tryAdvance(null)` and `forEachRemaining(null)` throw NPE, even when
empty. Pinned by: SpliteratorApi ("tryAdvance null*", "Spliterator default *"), SpliteratorSources
("hasCharacteristics").

**SP4.** `trySplit` of the standard sources: array and `ArrayList` spliterators split the remaining range,
the returned (prefix) part gets the **first** `n/2` elements (`1 2 | 3 4 5` of 5, `0 1 2 | 3 4 5 6` of 7)
and null for fewer than 2 elements; `IntStream.range` / `rangeClosed` / `LongStream.range` likewise
(`0..4 | 5..9`, `0..2 | 3..6` of 7); each part keeps the parent's characteristics. A spliterator over an
iterator of unknown or mismatched size (`spliteratorUnknownSize`, `spliterator(iterator, size, ch)`,
`AbstractSpliterator` with estimate `Long.MAX_VALUE`, and the `Collection` / `Iterable` defaults) splits by
copying a batch into an array: the first batch is `min(1024, remaining)`, then 2048, and so on (1024 then 1976
of 3000); the returned part is SIZED|SUBSIZED plus the parent's flags (see the goldens), the parent stays
unsized; the default `Collection` spliterator of 5 elements hands all 5 to the returned part and the parent is
then empty. Null when the source is empty. `Stream.of(1..4).filter(..).spliterator().trySplit()` is null.
Pinned by: SpliteratorApi ("trySplit*").

**SP5.** `Spliterators` factories: `emptySpliterator` (and Int / Long / Double): SIZED|SUBSIZED, a shared
instance, `tryAdvance(null)` NPE; `spliterator(array, ch)` and `(array, from, to, ch)`: `ch | SIZED |
SUBSIZED` only (no ORDERED / IMMUTABLE unless passed); `spliterator(Collection, ch)`: `ch | SIZED | SUBSIZED` (neither is added when `ch` has CONCURRENT, also for the iterator forms; Followups "spliterator * concurrent"),
late-binding and fail-fast (the size is read at the first traversal; a later modification makes the next
`tryAdvance` / `forEachRemaining` throw CME); `spliterator(Iterator, size, ch)`: `ch | SIZED | SUBSIZED`, the
size is only an estimate and not checked (traversal ends when the iterator does); `spliteratorUnknownSize(it,
ch)`: `ch` with SIZED and SUBSIZED removed, estimate `Long.MAX_VALUE`; `iterator(spliterator)` (all four kinds):
`hasNext` idempotent, `next` NSEE at the end, `remove` UOE `remove`. `Abstract*Spliterator(est, ch)`: reports
`ch`, adding SUBSIZED if `ch` has SIZED; `trySplit` as SP4. Null arguments (array, collection, iterator,
spliterator) throw NPE. Pinned by: SpliteratorApi ("Spliterators *", "iterator *", "collection *", "empty *",
"Abstract*").

**SP6.** `StreamSupport.stream(spliterator, parallel)`: the stream starts from the spliterator's current
position; `isParallel()` echoes the flag; reusing a used spliterator continues where it stopped (so it is
exhausted). `stream(supplier, characteristics, parallel)`: the supplier is called exactly once, at the
terminal operation or at the first **use** of what `spliterator()` / `iterator()` returned, never at creation,
at `map`, at `spliterator()` or at `iterator()` themselves: `stream.spliterator()` leaves the supplier uncalled
(count 0), and it runs at the first `tryAdvance`, `forEachRemaining`, `estimateSize()`, `getExactSizeIfKnown()`
or `characteristics()` of that spliterator (count 1), at the first `hasNext` / `next` of `iterator()`;
`spliterator()` of such a stream then reports the supplied spliterator's flags (the stream's claimed
characteristics do not apply to it: T5). A null supplier throws NPE at once
for `stream`, but `intStream` / `longStream` / `doubleStream` with a null supplier do not throw at creation.
A null spliterator is NPE (all four). Pinned by: SpliteratorApi ("StreamSupport*"), ToArraySizes ("supplier claims SIZED*"; the call log shows `sup`).

**SP7.** `PrimitiveIterator.OfX`: the default `next()` boxes `nextX()`, `forEachRemaining(Consumer)` boxes,
`remove()` throws UOE `remove`, `forEachRemaining(null)` NPE. The `Iterable.spliterator()` default
calls `iterator()` **at creation** (once, and not again at the first `tryAdvance`: `1 1`); the `Collection`
default is late-binding (SP11). Pinned by: SpliteratorApi
("PrimitiveIterator*", "Iterable default *").

**SP8.** `sorted()` and `distinct()` do nothing when the pipeline already has the property, and then the order of
side effects changes. The pipeline has SORTED if its source reports SORTED with a null comparator (natural order)
and nothing since has dropped it (so it survives `peek`, `filter`, `limit`, `skip` and `unordered`, and is lost after `map`, the primitive `distinct` and `sorted(cmp)`); it has DISTINCT if the source or an earlier object `distinct`
gave it.
- `sorted()` with SORTED: no sort, no barrier, no comparison: `TreeSet.stream().peek(a).sorted().peek(b)` prints
  `a1 b1 a2 b2 a3 b3` and `findFirst` stops after `a1 b1`; the elements are never compared (0 `compareTo`
  calls on a `TreeSet`). `LongStream.range(0, MAX).sorted().findFirst()` works (`0`), also `limit(3).sum()`,
  `skip(5).findFirst()`, iterator and spliterator; so do `IntStream.range(0, MAX_INT).sorted()` and `BitSet.stream()`.
  `sorted()` of a sorted stream twice sorts once (`sorted(c).sorted(c)`: 6 comparisons for 3 elements,
  `sorted().sorted()`: 4 `compareTo`s for the first sort only).
- Without SORTED, `sorted()` is a barrier (L4), and a sized source whose size does not fit an array fails
  (`IAE: Stream size exceeds max array size`: `boxed()`, `mapToObj`, `map` of a huge range).
- A spliterator that **claims** SORTED with a null comparator is believed: `Spliterators.spliterator(array,
  ORDERED | SORTED)` of `{3, 1, 2}` through `sorted()` gives `[3, 1, 2]`, also without ORDERED, in parallel, through
  `peek` (`a3 b3 a1 b1 a2 b2`), `filter`, `limit` and `skip` and `distinct` (`limit(2)` gives `[3, 1]`), whereas `map`
  then `sorted()` sorts (`[1, 2, 3]`). A claimed SORTED with a non-null `getComparator()` (the user spliterator
  returns `reverseOrder()`) does not count: `sorted()` sorts naturally. `sorted(cmp)` always sorts, even with
  the same comparator object as the source (`[3, 2, 1]`), and for int, long and double streams the claim is
  believed too. A claimed DISTINCT makes `distinct()` a no-op (`[1, 1, 2, 2]` stays) and `sorted()` on it still sorts.
- `distinct()` on a *sorted* pipeline compares neighbours only (with `equals`), it does not remember: with an
  element type whose `compareTo` is inconsistent with `equals`, `Stream.of(a1, a2, a1b, b3).sorted().distinct()`
  (a1 equals a1b, `compareTo` by group) is `[1:1, 1:2, 1:1, 2:3]`, while `distinct().sorted()` is `[1:1, 1:2, 2:3]`;
  `sorted().distinct()` of `[b3, a1b, a2, a1]` gives `[1:1, 1:2, 1:1, 2:3]` (neighbours differ), `.count()` gives 4.
  With a `TreeSet` source the `distinct()` is a no-op and the pipeline is `[1:1, 2:3]`. `distinct()` of a
  non-sorted stream uses hashing. The element order `sorted().distinct()` produces on unsorted input is the sort
  order. `distinct` after `sorted` is a short circuit (`sorted().distinct().peek(b).findFirst()` prints the
  whole upstream `a3 a1 a1 a2` and `b1`), and `limit(2)` after it ends after `b2`.
- The neighbour comparison treats a null so: the first null passes and makes the line forget the value before it (the next value always passes); later nulls are dropped and change nothing. On a stream that claims SORTED, `distinct()` gives `[null,1,null,1,1,null]` -> `[null, 1]`, `[1,null,1,null]` -> `[1, null, 1]`, `[1,1,null,null,1]` -> `[1, null, 1]`, `[1,2,null,2,1]` unchanged. Pinned by Followups ("sorted distinct null a" to "sorted distinct plain").
- Call counts: `new TreeSet<>(cmp).stream().sorted()` on a comparator `TreeSet` sorts by natural order and does
  not call the set's comparator; `sorted(sameCmp)` calls it 3 times for 4 elements (reverse-sorted input).
Pinned by: SortedNoop (all lines before "comparator-sorted sources").

**SP9.** Characteristics of the map and set views, per the SourceFlags golden (exact lines are the spec). The lines for the `TreeMap` descending and range views, `ConcurrentSkipListMap` and `ConcurrentSkipListSet` are no longer pinned (section 11); the bullets below that describe them say what the oracle does, and the defaults of the interfaces are acceptable:
- unmodifiable and synchronized wrappers of maps, entry sets, sorted sets delegate exactly (a `TreeMap`'s
  unmodifiable entry set is ORDERED|DISTINCT|SORTED|SIZED, `getComparator()` non-null (a key comparator
  object); an unmodifiable `descendingMap()` entry set is DISTINCT|SIZED|SUBSIZED; `HashMap`'s is DISTINCT|SIZED);
- `TreeMap` own views: whole map: `entrySet` ORDERED|DISTINCT|SORTED|SIZED with a comparator object, `keySet`
  and `navigableKeySet` the same with the map's comparator (null for natural), `values` ORDERED|SIZED; the
  **descending** views: `descendingMap().entrySet()` DISTINCT|SIZED|SUBSIZED, `.keySet()` and `descendingKeySet()`
  ORDERED|DISTINCT|SIZED, `.values()` SIZED|SUBSIZED; the **range** views (`headMap`, `tailMap`, `subMap`): entry
  sets and values are DISTINCT|SIZED|SUBSIZED / SIZED|SUBSIZED with the exact count (`headMap(3)`: 2), **key sets
  are not sized** (ORDERED|DISTINCT|SORTED, estimate `Long.MAX_VALUE`, comparator as the map's), a descending
  range key set is ORDERED|DISTINCT without SORTED;
- `ConcurrentSkipListMap`: whole `keySet` / `navigableKeySet` / `entrySet`: ORDERED|DISTINCT|SORTED|NONNULL|
  CONCURRENT with estimate `Integer.MAX_VALUE` (0 when empty), `values` ORDERED|NONNULL|CONCURRENT; `descendingMap`,
  `headMap`, `tailMap`, `subMap` and their descending forms: entry sets DISTINCT, key sets ORDERED|DISTINCT|
  SORTED, values no flags, all with estimate `Long.MAX_VALUE`; the comparator of `descendingMap().keySet()` /
  `descendingKeySet()` is non-null, of range key sets the map's; a stream drops CONCURRENT and NONNULL;
- `ConcurrentSkipListSet`: whole: ORDERED|DISTINCT|SORTED|NONNULL|CONCURRENT, natural (`getComparator() == null`) or
  the set's comparator; `descendingSet()`, `headSet`, `tailSet`, `subSet` and their combinations: ORDERED|DISTINCT|
  SORTED, estimate `Long.MAX_VALUE`, comparator non-null for descending, the set's for the others.
Pinned by: SourceFlags (everything before "long range MIN MAX").

**SP10.** Sources that bind at their first use: `chars()` / `codePoints()` / `BitSet.stream()` / the supplier form of
`StreamSupport` read the sequence or the bit set when the spliterator is first asked something, not when
`spliterator()` is called:
- `sb.chars().spliterator()` then `append("cd")`: the first call of `estimateSize()` / `getExactSizeIfKnown()` /
  `characteristics()` / `tryAdvance` binds, and it then reports the length at that moment (`4` if the append came
  first, `2` if `estimateSize()` came first); later changes are not reflected in `estimateSize()` after
  binding, but `forEachRemaining` after an `estimateSize()` still delivers the characters of the sequence as
  it is at traversal (`abcd`: the estimate says 2 and four elements arrive); after one `tryAdvance`, a later
  `append` is delivered (`2 bcd`).
- `codePoints().spliterator()` reports `ORDERED` (16) and delivers what the sequence holds at traversal;
  `chars()` reports ORDERED|SIZED|SUBSIZED (16464).
- `BitSet.stream().spliterator()`: size and bits are read at first use (`estimateSize` after a `set(5)` made
  after `spliterator()` and before the first look: 2); after `characteristics()` a later `set(5)` is delivered by
  `forEachRemaining` while the estimate stays 1; after `clear()` before the first look the size is 0 and
  `tryAdvance` is false; an empty set reports `0 16469`; the iterator binds at the first `hasNext`.
Pinned by: TextTrace ("chars spliterator binds*", "chars iterator binds at hasNext", "codePoints spliterator binds
at first use", "BitSet spliterator*", "BitSet iterator binds at hasNext", "BitSet stream created then set").

**SP11.** `Spliterators.spliterator(Collection, ch)` and the default `Collection` / `Iterable` spliterators
(CollectionBinding, per collection: ArrayList, LinkedList, Vector, HashSet, LinkedHashSet, TreeSet, ArrayDeque,
PriorityQueue, CopyOnWriteArrayList, ConcurrentLinkedQueue, LinkedBlockingQueue):
- on the first use (`estimateSize`, `getExactSizeIfKnown`, `tryAdvance`) the spliterator creates the
  collection's iterator **first** and reads `size()` **second** (`iterator size`); `spliterator()` and
  `stream()` call nothing; the log of an `AbstractCollection` is `| iterator size |`. The size read is the
  one reported ever after; `characteristics()` alone does not bind and a later `add` changes the estimate
  (`add after characteristics` gives 4) while `estimateSize()` / `getExactSizeIfKnown()` first fixes it (3).
- after binding, a later `add` makes `tryAdvance` throw CME on every fail-fast collection (ArrayList,
  LinkedList, Vector, HashSet, LinkedHashSet, TreeSet, PriorityQueue) and `forEachRemaining` too; `forEachRemaining`
  of `Spliterators.spliterator(c, 0)` throws CME after delivering **the elements that were there** (`1,2,3,CME`) for
  ArrayList, the default `ArrayList.spliterator()` delivers `1,2,3` then CME, `HashSet`'s delivers the added element
  too (`1,2,3,9,CME`), `Spliterators.spliterator(c, 0)` of a list throws at once on the first element when
  the body modifies (`1,CME`). `ArrayDeque`'s `tryAdvance` throws CME after a modification, its
  `forEachRemaining` does not (`1,2,3,done`), but a removal gives CME; `CopyOnWriteArrayList` and the concurrent
  queues never throw and deliver the snapshot.
- Reading the golden is mandatory: every line is a distinct outcome.
Pinned by: CollectionBinding (all).

**SP12.** Ranges and `concat`:
- `LongStream.range(a, b)` / `rangeClosed(a, b)` is SIZED only when the count fits in a long without overflow: `range(0, MAX)`
  and `rangeClosed(1, MAX)` are ORDERED|DISTINCT|SORTED|SIZED|NONNULL|IMMUTABLE|SUBSIZED with size `Long.MAX_VALUE`;
  `range(MIN, MAX)`, `rangeClosed(MIN, MAX)`, `range(-1, MAX)`, `rangeClosed(0, MAX)`, `range(MIN, 0)` and `range(MIN, 1)`
  are only ORDERED|NONNULL|IMMUTABLE, estimate `Long.MAX_VALUE`, exact -1 (no DISTINCT, no SORTED), and
  after `peek` / `boxed` / `limit` just ORDERED. `rangeClosed(MIN, MIN)` and `(MAX, MAX)` are full-flag with size 1.
  An advance on an unsized one keeps the estimate `Long.MAX_VALUE`; on a sized one it decrements it. Their `limit(3)
  .sum()` works. IntStream ranges up to the full type range are sized (4294967295, 4294967296) and
  `rangeClosed(MAX, MAX)` after one advance has size 0.
- `concat(a, b)` spliterators: flags are the intersection of the two sides' flags among ORDERED, SIZED, SUBSIZED,
  IMMUTABLE and NONNULL (two arrays: ORDERED|SIZED|IMMUTABLE|SUBSIZED; two lists without IMMUTABLE; two HashSets
  only SIZED; two int ranges add NONNULL), SORTED and DISTINCT never survive, `unordered()` on one side drops ORDERED; the estimate is the sum of both sides and is reported exactly
  (`getExactSizeIfKnown`) only if both are SIZED and the sum does not overflow: the sum may reach `Long.MAX_VALUE`
  (`MAX/2 + (MAX - MAX/2)` is SIZED and exact `MAX`; `MAX + 0` and `0 + MAX` too; `MAX + 1` and `MAX + MAX` are not
  SIZED, estimate `Long.MAX_VALUE`). **After advances** the estimate follows the remainder: `5 4 3 2 1 0` for
  `of(1,2,3)` + `of(4,5)`, and the pair is exact (`n/n`) the whole time when both sides are sized; when the
  first side is unsized (`filter`) the estimates are the plain sums and the result becomes SIZED only when the
  second side is the only one left (`... est=2 exact=-1 | SIZED est=1 exact=1`). `trySplit` of a concat gives the
  first side as prefix (`123|45`, estimates 3 and 2). The flags are worked out once when the concatenation is made, so a
  pair whose total overflowed does not become SIZED again after an advance (Followups "concat unsized by overflow after
  advance"); only the switch to the second side changes them.
Pinned by: SourceFlags (from "long range MIN MAX" to the end).

## 9. Hook-ups in the rest of the library

**H1.** `Collection.stream()` / `parallelStream()` bind late: elements added before the terminal operation
are seen; once the first element has been taken, a structural change makes the next step throw CME.
`parallelStream().isParallel()` is true. All concrete collections stream in their iteration order. What a
stream sees when its own `forEach` adds one element at the first visit, or removes one, is fixed per
collection and printed by the "grow *" lines (visits, `done` or `CME`, final size): copy the outcome, do not
guess. In short: ArrayList, LinkedList, Vector and PriorityQueue visit the old elements and end with CME;
HashSet and TreeSet visit the added element too and end with CME; LinkedHashSet stops with CME after the first
visit; ArrayDeque, CopyOnWriteArrayList and ConcurrentLinkedQueue never throw. `anyMatch`, `limit`,
`findFirst` and `map` over a list modified inside the pipeline throw CME; `count()` after `peek(add)` throws
CME; `sorted().peek(add).findFirst()` does not (the source was read completely first). A stream of a
`subList` binds late to the parent, and a structural change through the sublist after `stream()` gives CME.
Pinned by: CollectionHooks (everything).

**H2.** `Arrays.stream` and `Arrays.spliterator`: R5, T4, SP1. Pinned by: CollectionHooks ("Arrays.stream*"),
StreamReuse, SpliteratorApi.

**H3.** `CharSequence.chars()` / `codePoints()` (default methods; String, StringBuilder and StringBuffer inherit them. The oracle's `CharBuffer` declares its own `chars()`, which takes position and limit when the stream is first used and ignores later changes of either; its `codePoints()` is the default) bind late: nothing is called on the sequence at creation; the
content at the terminal operation is used (`append` before the terminal operation is seen, `setLength(1)`
shrinks it; `CharBuffer.position` changes are seen by `codePoints()`; `chars()` of a `CharBuffer` is the one exception, see above). The calls a
user-written `CharSequence` sees are fixed (L = `length()`, cN = `charAt(N)`): `chars().sum()` on "ab":
`L L c0 L c1 L`; `chars().count()` on "abc": `L L c0 L c1 L c2 L`; `chars().limit(2).sum()` on "abcd":
`L L L c0 L L c1`; `codePoints().sum()` on "ab": `L c0 c1`, on a string with a surrogate pair of 4 chars
`L c0 c1 c2 c3`; an empty sequence with `chars()`: `L L`; the iterator's first `nextInt` on "abc": `L L L c0`.
`codePoints` joins a valid pair into one code point and passes unpaired surrogates through (`[120, 55357,
121, 56832, 122]`); `chars` yields UTF-16 units. The golden "CharSequence modified while streaming" (done 1)
pins the oracle's behaviour when the sequence shrinks during traversal. `chars()` on null is NPE. TextTrace pins the traces further: `codePoints().iterator()` on "abc" calls `L L c0`
on the first `hasNext`, nothing on the second, nothing on the next `nextInt`, `L L c1` on the second `nextInt`'s
*following* `hasNext` and so on (each `hasNext` re-reads `length()` twice and the next character; an exhausted
iterator reads `L` once); a pair reads `c1 c2` together; a sequence that shrinks during traversal: the
iterator ends at the new length (`97 `), a pair cut after its high surrogate is delivered as that surrogate
(`61 d83d`), a sequence cut inside a pair throws `StringIndexOutOfBoundsException` from `charAt` in `forEach`
(not in the iterator), growth is seen (`97 98 99 100`); `codePoints().forEach` reads `L c0 c1 ...` with a single
`length()`, `chars().forEach` reads `L L c0 (L c1 L c2 ...)`; see the golden for every case. Pinned by:
TextHooks (first section), TextTrace (everything before "chars spliterator binds at first use").

**H4.** `BitSet.stream()`: ascending set-bit indices, late-binding (bits set before the terminal operation
show, `clear()` empties it); a bit cleared during the traversal above the current one is skipped (golden
"modified during clear"); SIZED|SUBSIZED with `cardinality()` as size. Pinned by: TextHooks ("BitSet stream*").

**H5.** `Random.ints / longs / doubles` (all forms; `ThreadLocalRandom` too): `streamSize < 0` throws
`IAE: size must be non-negative`; `origin >= bound` (and any NaN bound) throws `IAE: bound must be greater than
origin`; both are checked at the call, size first. Elements: unbounded `ints()` is exactly the sequence of
`nextInt()` calls (`new Random(42).ints(3)` equals three `nextInt()`s; the generator advances lazily as
elements are drawn; streams share the generator's state with later direct calls); `longs()` equals
`nextLong()`, `doubles()` equals `nextDouble()`. Bounded values follow the "as if" pseudo-code in the Javadoc
of `Random.ints(long,int,int)`, `longs(long,long,long)` and `doubles(long,double,double)`, which calls the
public methods `nextInt(int)`, `nextInt()`, `nextLong()`, `nextDouble()` **virtually**: a subclass overriding
`nextInt()`, `nextInt(int)`, `nextLong()`, `nextDouble()` or `next(int)` changes the stream (goldens "ints
subclass*", "longs subclass*", "doubles subclass*"). `doubles(n, 0.0, +Infinity)` is accepted. **Both checks failing at once:** the size message wins for every form
(`ints(-1, 5, 3)`, `longs(-1, 5, 3)`, `doubles(-1, 5, 3)`, `doubles(-1, NaN, 3)`, `ThreadLocalRandom` and a
subclass too); a good size with a bad range gives the bound message, and a negative size alone (even
`Long.MIN_VALUE`) the size message. **A bounded stream over the full int range** calls the overridable
`nextInt()` and never `nextInt(int)` when `bound - origin` overflows or is 0 or less: `ints(3, MIN, MAX)` (also
the unsized `ints(MIN, MAX)`) with a subclass that replays `{MAX, 5, MIN, -1}` gives `[5, MIN, -1]` after 4
calls of `nextInt()` (`MAX` is rejected: the bound is exclusive); `ints(4, -2e9, 2e9)` also draws by `nextInt()`
and rejects out-of-range values; `ints(3, -1, MAX)` and `ints(3, 0, MAX)` differ: `0..MAX` is a positive width
and calls `nextInt(2147483647)`, `-1..MAX` overflows and calls `nextInt()`; ranges within the int width call
`nextInt(width)` (`nextInt(16)` for a power of two, `nextInt(1)` for one value); the unbounded forms call
`nextInt()` / `nextLong()` / `nextDouble()`. `longs` and `doubles` with bounds call only `nextLong()` /
`nextDouble()`; a bounded `doubles` clamps a result that reaches the bound to the largest double below it.
Pinned by: StatsKills ("ints size*", "longs size*", "doubles size*", "TLR *", "subclass ints size and bounds",
"ints full range*", "ints overflowing range", "ints half range", "ints range *", "ints small range", "ints
power of two", "ints one value", "ints unbounded*", "longs *", "doubles bounded*", "doubles unbounded", "doubles
infinite bound"). Pinned by:
TextHooks ("ints*", "longs*", "doubles*", "ThreadLocalRandom*"). Seeded values come from the existing `Random`.

**H6.** `BufferedReader.lines()`: lazy (nothing is read before the terminal operation; creating the stream on a
closed reader works) and splits lines as `readLine` does (`\n`, `\r`, `\r\n`; no final empty line; an empty
input has no lines, `"\n"` has one empty line). It reads through the reader, so interleaving with `readLine()`
works (`findFirst` then `readLine` gives the second line), two streams from one reader share its position,
closing the stream does not close the reader, `mark` / `reset` stay usable. An `IOException` while reading is
wrapped in `UncheckedIOException` whose `getMessage()` is `<cause.toString()>` and whose cause is the original
(`java.io.IOException: Stream closed` for a closed reader, also when closed midway). Not SIZED, ORDERED.
Pinned by: FileStreams ("br lines*").

**H7.** `Files.lines(path[, cs])`: opens the file at the call (a missing file gives `NoSuchFileException` at the
call; null path or charset gives NPE); decodes strictly: a malformed sequence throws `UncheckedIOException`
wrapping `MalformedInputException: Input length = 1` **at the first read** that decodes it, and reads happen
in chunks, so a bad byte anywhere in a short file fails even `findFirst()` and `limit(1)`. Default charset UTF-8.
Closing the stream closes the file; **exhausting it does not** (the file stays open until `close()`, a
failing pipeline does not close it either), `onClose` handlers run on `close()`. The `flatMap` closes the file
of an inner `Files.lines` stream (after `count`, `findFirst`, `limit`, `anyMatch`, an exception, nested, through
`iterator()` / `spliterator()` after the first step). The earlier golden "lines close then delete" cannot show
any of this (a Windows file can be deleted while it is open); see H9. Not SIZED. `Files.list(dir)`: entries in directory order (tests sort), shallow,
`NoSuchFileException` / `NotDirectoryException` at the call, entries are `dir.resolve(name)`.
`Files.walk(start[, depth][, options])`: pre-order, the start first, depth 0 is the start only; a negative depth
throws `IAE: 'maxDepth' is negative`; a file as start streams just it; a missing start throws
`NoSuchFileException` at the call; a null options element is NPE. `Files.find(start, depth, matcher[,
options])`: like walk, filtered by the matcher with `BasicFileAttributes`; the same negative-depth IAE; a null
matcher is NPE only when a path is tested; exceptions from the matcher propagate. `list` / `walk` / `find`
streams must be closed to release the directories; closing twice is fine; handlers run. Pinned by:
FileStreams ("lines*", "list*", "walk*", "find*"), FileLazy, FileCloseRename.

**H8.** `Files.list`, `walk`, `find` and `lines` flags, laziness and iterators after `close()` (FileLazy):
- flags: `list` DISTINCT, `walk` and `find` none, `lines` ORDERED|NONNULL (any charset); none is sized (exact -1);
  `map` / `filter` of them drops what is dropped;
- the directory is read **when the stream runs**, not at the call: a file deleted (or created) after
  `Files.list(dir)` and before the terminal operation is missing (or present) in the result; the same holds
  for `walk` (including a directory created later) and `find`, and for an iterator (the entry deleted after
  `iterator()` is missing); the content of a `lines` file is read at the terminal operation (a rewritten file
  gives the new lines);
- after `close()`, an iterator of `list` reports `hasNext()` false (`next()` of an iterator that had
  already looked ahead still works), the spliterator's `tryAdvance` is false and `forEachRemaining` delivers
  nothing; an iterator or spliterator of `walk` or `find` throws `IllegalStateException` (no message) from
  `hasNext` / `tryAdvance`, also after a successful `hasNext`; one of `lines` throws `UncheckedIOException` (message
  `java.io.IOException: Stream closed`, cause that exception) from `hasNext` / `tryAdvance` / `forEachRemaining`, but a
  line already read by `hasNext` is still returned by the next `next()`; closing inside `forEach` or inside an
  iterator loop makes the next read throw the same;
- `list` opens the directory when the stream first reads and reads one entry per step, so a large directory is not buffered; `lines` is closed only by `close()`, never by reaching the end. A mapped iterator of these streams is not guaranteed to survive the closing of the *source* stage (F5 in section 11).
- `close()` twice is fine and runs the handler once; the stream is consumed (R1) afterwards; a source closed
  makes the later stage throw C6.
Pinned by: FileLazy (all).

**H9.** Whether a `Files.lines` file is open can be seen on Windows by renaming its directory: the rename fails
(`AccessDeniedException`) while the file is open and succeeds once it is closed. `FileCloseRename` pins:
open after `Files.lines`, after `findFirst`, after exhausting, after `count`, after an exception in the
pipeline, after `iterator()` / `spliterator()` steps; closed after `close()` (once or twice), after try-with-
resources, after `flatMap` of file streams in every form (`count`, `findFirst`, `limit`, `anyMatch`, nested,
`sorted().limit(1)`, a throwing `peek` or inner `map`, `iterator().next()`, `spliterator().tryAdvance`), after
`flatMap` of an existing `Files.lines` stream (even when it was already used; the `ISE` comes first); open after
`BufferedReader.lines()` is closed (the reader owns the file), closed once the reader is. **This check is
platform dependent:** it needs an OS that refuses to rename a directory with an open file. The test first
opens a file by hand and tries the rename; where the rename succeeds it cannot observe anything and each line
prints the Windows-correct value instead, so the golden is the same on Windows and LazyOS and the check is only
real where renaming is refused. Directory handles of `list` / `walk` / `find` do not block a rename on Windows
and so are not pinned at all. Pinned by: FileCloseRename (all).

**H10.** Symbolic links with `FileVisitOption.FOLLOW_LINKS` (**not pinned by a test**: creating a symbolic link
needs a privilege the oracle machine does not grant, and lazyrt has no `Files.createSymbolicLink`). From the
Javadoc: `walk` and `find` do not follow links by default; with `FOLLOW_LINKS` a link to a directory is
traversed as the directory, and a link that leads back into the walk is reported through a
`FileSystemLoopException` wrapped as `UncheckedIOException`. lazyrt has no symbolic links, so this is untested on LazyVM. Implement no-follow by default and do not
traverse with `FOLLOW_LINKS` until the file system can make links; the implementer may treat `FOLLOW_LINKS` as
accepted and not traverse links.

## 10. parallel()

**PA1.** `parallel()` sets the flag and every stage of the pipeline reports it (`isParallel()` is true for stages
created before and after, false after `sequential()`); the work runs sequentially on the calling thread in
encounter order. `parallelStream()` and `StreamSupport.stream(sp, true)` likewise. No test depends on a real
parallel execution order, nor on `DoubleStream.sum()` of a truly parallel run (the oracle's parallel sums can
differ in the last bit; do not copy that). Pinned by: ObjectStreams ("parallel*"), the "parallel" lines of the
per-kind tests.

**PA2.** The inner stream returned to `flatMap` (all forms, `flatMapToInt/Long/Double`) is made **sequential before
it is pushed**: after the terminal operation its `isParallel()` is false even if it was parallel before and the
outer stream is parallel; before the terminal operation it is untouched (`before=true`), and the stage returned
by `flatMap` is parallel only if the outer stream is (`m.parallel=false` for a sequential outer). A sequential
inner stream stays sequential, and the outer stream stays parallel. The same happens when the pipeline is read
through `iterator()` (after the first step). Pinned by: CollectorCalls (the "flatMap inner*" and "flatMap
outer stays parallel" lines).

## 11. What the implementer may choose

- Class names, helper packages (package-private or `lazy.internal.*`), the classes behind the interfaces
  (`toString` of a stream or collector is not pinned; no test prints it).
- Data structures and thread use (none is needed), as long as every rule above holds; the `HashMap` iteration
  order is that of lazyrt's `HashMap` (tests print `HashMap` results only with small `Integer` / `Character`
  keys, or sort).
- Memory behaviour, buffer growth and internal size hints, as long as R1 to R6, L1 to L10 and T1 hold.
- Messages of exceptions that no golden prints.
- `trySplit` of the spliterators of pipelines and of `Random` streams may return `null` (always): parallelism is
  out (D6) and splitting these is rare. No second-round test splits them. The standard sources of SP4 still split
  as specified.
- How much the `trySplit` of a collection's spliterator hands over (the batch sizes of the iterator-based
  default, the prefix of an array-backed collection): only SP4 is pinned, and the second-round goldens avoid
  the numbers (the `ConcurrentSkipListSet`, `TreeMap` view and `Long.MIN`..`MAX` range split lines were left
  out on purpose).
- The spliterator flags of `IdentityHashMap`, `WeakHashMap`, `LinkedBlockingDeque`, `PriorityBlockingQueue`,
  `ConcurrentLinkedDeque` and `LinkedTransferQueue` beyond the defaults of the interfaces (no golden lists them;
  use the `Collection` / `Set` / `Queue` default or a plausible set, and nothing is pinned). They are rare
  in programs.
- `FOLLOW_LINKS` traversal (H10).
- What a walk or an iterator sees after a structural change between steps, beyond this: it never loses an element silently (a change ends in `ConcurrentModificationException`), and where the oracle delivers a stale value (`1 null CME` after `LinkedList.clear`) lazyrt matches only the cases in Followups. The Javadoc calls fail-fast behaviour best effort.
- F5: closing the first stage of a pipeline while an iterator or spliterator of a later stage is running keeps that run going on the source it already holds. For a mapped iterator of `Files.list`, `walk` or `lines` the close handler also closes the file or directory, and what the iterator then does is not pinned.
- Concat: `getComparator` after the first side is used up, CONCURRENT in the flags of a concat, `trySplit` of a spliterator whose estimate is 1, a directory deleted after `Files.list` was called, a child deleted during a walk, when an `ArrayBlockingQueue` spliterator reads its estimate, how many times a spliterator method is called by a stream, and the flags of a supplier-sourced stream before its spliterator is looked at.
- How much `trySplit` hands over for `HashSet`, `TreeSet`, `ConcurrentLinkedQueue` and `LinkedBlockingQueue` (parallel is out, D6).
- The spliterator flags of the `TreeMap` descending and range views, `ConcurrentSkipListMap` and `ConcurrentSkipListSet`: the defaults of the interfaces are fine.

## 12. What lazyrt has, and what is missing

(Written before the implementation: everything listed as missing now exists, S11-01.)

Present: `java.util.function` (all 43 interfaces with their default and static methods), `Optional`,
`OptionalInt` / `Long` / `Double` (complete for streams), `Comparator` and `Map.Entry` comparators,
`Map.merge` / `computeIfAbsent`, `StringJoiner` (its null messages must be those of K2), `Objects`, the
concrete collections, the `java.util.concurrent` collections and `ThreadLocalRandom`, `UncheckedIOException`,
`MalformedInputException`, `Files` (no stream methods), `BufferedReader` (no `lines`), `CharBuffer`,
`Arrays.sort`, `BitSet`, `Random`.

Missing (to write): everything in `java.util.stream`; `Spliterator` (with `OfPrimitive`, `OfInt`, `OfLong`,
`OfDouble`), `Spliterators` (with the four `Abstract*Spliterator` classes), `PrimitiveIterator`, the three
`*SummaryStatistics`; `Iterable.spliterator`; `Collection.stream` / `parallelStream` / `spliterator` and the
specific spliterators of SP1 (a collection without one gets the `Collection` / `List` / `Set` defaults;
specific ones are needed for ArrayList, LinkedList, Vector, ArrayDeque, PriorityQueue, HashSet, HashMap views,
LinkedHashSet / LinkedHashMap views, TreeSet / TreeMap views, `Collections.emptyList` / `emptySet` /
`singleton*` / `nCopies`, `Arrays.asList`, CopyOnWriteArrayList / Set, ConcurrentHashMap views,
ConcurrentSkipListSet, ConcurrentLinkedQueue, LinkedBlockingQueue, ArrayBlockingQueue, EnumSet; the wrappers of
`Collections` delegate); `Arrays.stream` / `spliterator`; `CharSequence.chars` / `codePoints`;
`Random.ints` / `longs` / `doubles` (`ThreadLocalRandom` inherits them); `BitSet.stream`;
`BufferedReader.lines`; `Files.lines` / `list` / `walk` / `find`. `CharBuffer.chars()` is the `CharSequence` default, which hands a `CharBuffer` to `lazy.internal.TextStreams`; that takes the snapshot of position and limit the oracle's own `CharBuffer.chars()` takes. `CharBuffer` itself declares nothing new, because Java SE 8 lists no such method and the namespace rule forbids adding one. `ConcurrentModificationException` messages are none (the goldens print
only the class). Deferred items confirmed covered: io.md section 15 (`Files.list`, `walk`, `find`, `lines`,
`BufferedReader.lines`, `CharBuffer.chars`: H6, H7, H3), util.md section 5 (`Random` streams: H5),
util-data.md (`BitSet.stream`: H4), collections.md section 13 (streams, spliterators, `PrimitiveIterator`: all
of section 8 and the API table).

## 13. API to provide

Members are those each type declares itself; generics, return types and exceptions come from the Java SE 8
Javadoc. Do not add public members beyond these.

| Type | Members |
|---|---|
| `java.util.stream.BaseStream<T,S>` | interface extends AutoCloseable: close(); isParallel(); iterator(); onClose(Runnable); parallel(); sequential(); spliterator(); unordered() |
| `Stream<T>` | interface extends BaseStream: static builder(); static concat(Stream,Stream); static empty(); static generate(Supplier); static iterate(T,UnaryOperator); static of(T); static of(T...); allMatch; anyMatch; collect(Collector); collect(Supplier,BiConsumer,BiConsumer); count(); distinct(); filter; findAny(); findFirst(); flatMap; flatMapToDouble; flatMapToInt; flatMapToLong; forEach; forEachOrdered; limit(long); map; mapToDouble; mapToInt; mapToLong; max(Comparator); min(Comparator); noneMatch; peek; reduce(BinaryOperator); reduce(T,BinaryOperator); reduce(U,BiFunction,BinaryOperator); skip(long); sorted(); sorted(Comparator); toArray(); toArray(IntFunction) |
| `Stream.Builder<T>` | interface extends Consumer: accept(T); default add(T); build() |
| `IntStream` | interface extends BaseStream: static builder(); concat; empty(); generate(IntSupplier); iterate(int,IntUnaryOperator); of(int); of(int...); range; rangeClosed; allMatch; anyMatch; asDoubleStream(); asLongStream(); average(); boxed(); collect(Supplier,ObjIntConsumer,BiConsumer); count(); distinct(); filter; findAny(); findFirst(); flatMap; forEach; forEachOrdered; iterator() returning PrimitiveIterator.OfInt; limit; map; mapToDouble; mapToLong; mapToObj; max(); min(); noneMatch; parallel(); peek; reduce(IntBinaryOperator); reduce(int,IntBinaryOperator); sequential(); skip; sorted(); spliterator() returning Spliterator.OfInt; sum(); summaryStatistics(); toArray() |
| `IntStream.Builder` | interface extends IntConsumer: accept(int); default add(int); build() |
| `LongStream` | as IntStream with long: static builder, concat, empty, generate(LongSupplier), iterate(long,LongUnaryOperator), of(long), of(long...), range(long,long), rangeClosed; asDoubleStream(); average(); boxed(); collect(Supplier,ObjLongConsumer,BiConsumer); mapToDouble; mapToInt; mapToObj; reduce(LongBinaryOperator); reduce(long,LongBinaryOperator); summaryStatistics() returning LongSummaryStatistics; iterator() returning PrimitiveIterator.OfLong; spliterator() returning Spliterator.OfLong; the rest as IntStream (no asLongStream) |
| `LongStream.Builder` | interface extends LongConsumer: accept(long); default add(long); build() |
| `DoubleStream` | as IntStream with double: static builder, concat, empty, generate(DoubleSupplier), iterate(double,DoubleUnaryOperator), of(double), of(double...) (no range); average(); boxed(); collect(Supplier,ObjDoubleConsumer,BiConsumer); mapToInt; mapToLong; mapToObj; reduce(DoubleBinaryOperator); reduce(double,DoubleBinaryOperator); summaryStatistics() returning DoubleSummaryStatistics; iterator() returning PrimitiveIterator.OfDouble; spliterator() returning Spliterator.OfDouble; sum(); (no asXStream) |
| `DoubleStream.Builder` | interface extends DoubleConsumer: accept(double); default add(double); build() |
| `Collector<T,A,R>` | interface: accumulator(); characteristics(); combiner(); finisher(); supplier(); static of(Supplier,BiConsumer,BinaryOperator,Collector.Characteristics...); static of(Supplier,BiConsumer,BinaryOperator,Function,Collector.Characteristics...) |
| `Collector.Characteristics` | enum: CONCURRENT, UNORDERED, IDENTITY_FINISH (values(), valueOf) |
| `Collectors` | final class, static: averagingDouble; averagingInt; averagingLong; collectingAndThen; counting(); groupingBy(Function); groupingBy(Function,Collector); groupingBy(Function,Supplier,Collector); groupingByConcurrent (same 3 forms); joining(); joining(CharSequence); joining(CharSequence,CharSequence,CharSequence); mapping; maxBy; minBy; partitioningBy(Predicate); partitioningBy(Predicate,Collector); reducing(BinaryOperator); reducing(T,BinaryOperator); reducing(U,Function,BinaryOperator); summarizingDouble; summarizingInt; summarizingLong; summingDouble; summingInt; summingLong; toCollection; toConcurrentMap (3 forms); toList(); toMap (3 forms); toSet() |
| `StreamSupport` | final class, static: stream(Spliterator,boolean); stream(Supplier,int,boolean); intStream(Spliterator.OfInt,boolean); intStream(Supplier,int,boolean); longStream (2 forms); doubleStream (2 forms) |
| `java.util.Spliterator<T>` | interface: constants ORDERED, DISTINCT, SORTED, SIZED, NONNULL, IMMUTABLE, CONCURRENT, SUBSIZED; characteristics(); estimateSize(); default forEachRemaining(Consumer); default getComparator(); default getExactSizeIfKnown(); default hasCharacteristics(int); tryAdvance(Consumer); trySplit() |
| `Spliterator.OfPrimitive<T,T_CONS,T_SPLITR>` | interface extends Spliterator: default forEachRemaining(T_CONS); tryAdvance(T_CONS); trySplit() |
| `Spliterator.OfInt` / `OfLong` / `OfDouble` | interfaces extending OfPrimitive: default forEachRemaining(IntConsumer) and the default boxed forEachRemaining(Consumer); tryAdvance(IntConsumer) and the default boxed tryAdvance(Consumer); trySplit() (covariant); Long and Double likewise |
| `Spliterators` | final class, static: emptySpliterator(); emptyIntSpliterator(); emptyLongSpliterator(); emptyDoubleSpliterator(); `<T> Spliterator<T>` spliterator(Object[],int) and spliterator(Object[],int,int,int) (generic in the return only: `Spliterator<String> s = Spliterators.spliterator(strings, 0)` compiles; the tests rely on it); spliterator(int[],int); spliterator(int[],int,int,int); long[] and double[] likewise; spliterator(Collection,int); spliterator(Iterator,long,int); spliterator(PrimitiveIterator.OfInt/OfLong/OfDouble,long,int); spliteratorUnknownSize(Iterator,int) and the 3 primitive-iterator forms; iterator(Spliterator); iterator(Spliterator.OfInt/OfLong/OfDouble) |
| `Spliterators.AbstractSpliterator<T>` | abstract class implements Spliterator: protected (long est,int ch); characteristics(); estimateSize(); trySplit() |
| `Spliterators.AbstractIntSpliterator` / `AbstractLongSpliterator` / `AbstractDoubleSpliterator` | abstract classes implementing Spliterator.OfInt / OfLong / OfDouble: protected (long,int); characteristics(); estimateSize(); trySplit() |
| `PrimitiveIterator<T,T_CONS>` | interface extends Iterator: default forEachRemaining(T_CONS) |
| `PrimitiveIterator.OfInt` / `OfLong` / `OfDouble` | interfaces: default forEachRemaining(IntConsumer); default forEachRemaining(Consumer); default next(); nextInt(); Long and Double likewise |
| `IntSummaryStatistics` | class implements IntConsumer: new(); accept(int); combine(IntSummaryStatistics); getAverage(); getCount(); getMax(); getMin(); getSum(); toString() |
| `LongSummaryStatistics` | class implements LongConsumer, IntConsumer: new(); accept(int); accept(long); combine; getAverage(); getCount(); getMax(); getMin(); getSum(); toString() |
| `DoubleSummaryStatistics` | class implements DoubleConsumer: new(); accept(double); combine; getAverage(); getCount(); getMax(); getMin(); getSum(); toString() |
| `java.lang.Iterable` | default spliterator() |
| `java.util.Collection` | default stream(); default parallelStream(); default spliterator() |
| `java.util.List`, `Set`, `SortedSet` | default spliterator() each (ORDERED; DISTINCT; ORDERED|DISTINCT|SORTED with the set's comparator) |
| `java.util.Arrays` | static stream(T[]); stream(T[],int,int); stream(int[]); stream(int[],int,int); stream(long[]) and range; stream(double[]) and range; spliterator(T[]); spliterator(T[],int,int); spliterator(int[]) and range; long[] and double[] likewise |
| `java.lang.CharSequence` | default chars(); default codePoints() |
| `java.util.Random` | ints(); ints(long); ints(int,int); ints(long,int,int); longs() in the same 4 forms; doubles() in the same 4 forms |
| `java.util.BitSet` | stream() |
| `java.io.BufferedReader` | lines() |
| `java.nio.file.Files` | static lines(Path); lines(Path,Charset); list(Path); walk(Path,FileVisitOption...); walk(Path,int,FileVisitOption...); find(Path,int,BiPredicate,FileVisitOption...) |
| `java.nio.CharBuffer` | inherits `chars()` / `codePoints()` from CharSequence; nothing to add (the snapshot of `chars()` is done in `lazy.internal.TextStreams`, see H3) |

The concrete collections of SP1 (and EnumSet) override `spliterator()` with the characteristics given there;
that override is the same public method every `Collection` already has.
