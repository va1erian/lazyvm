# java.util.WeakHashMap: behaviour beyond the Javadoc

This is the behavioural specification of LazyVM's `java.util.WeakHashMap` (plan item S5-02a). It
follows [`collections.md`](collections.md): it lists what the Java 8 oracle (Temurin 1.8.0_504)
does that a program can see and the Java SE 8 Javadoc leaves open. Implement the Javadoc, then
these rules. Everything here was found by experiment on the oracle; nothing comes from any JDK's
source.

Each rule reads *input → observable result*. "Pinned by" names the test in `tests/java/util` and
the label of its output line; every golden is blessed on the oracle. Formulas are Java `int`
expressions. "Iteration order" means the order of the three views' iterators, and so of
`toString`, `hashCode`'s calls, `equals`, `toArray` and the views' `forEach`; `forEach` and
`replaceAll` on the map itself differ (W8).

The tests wait for the collector with a bounded loop (`System.gc()`, a short sleep, then a map
call) and print only the outcome, never how long it took. LazyVM's `System.gc()` is a full
collection that clears weak references and puts them on their queues before it returns
(`docs/design/natives.md`, `java/lang/ref`); the oracle enqueues a little later, which the loops
absorb. Two things the VMs may do differently stay out of the tests: LazyVM treats every local
variable slot of a frame as a root, even one whose variable is out of scope, where HotSpot ignores
dead slots (JLS 12.6.1 allows both), so a key that must go is never left in a local; and identity
hash codes differ, so a printed map hash uses values with their own `hashCode`.

## 1. Order without the collector

These rules describe the order of a map whose keys all stay strongly reachable. They are the same
machinery as `HashMap`'s in spirit but differ in every detail: do not reuse `HashMap`.

**W1.** *Bucket function.* A key with hash code `h` (from `key.hashCode()`, called once when the key
is stored; see W15 for the null key) goes to bucket `g(h) & (n - 1)` of a table of `n` buckets,
where

    p    = h ^ (h >>> 12) ^ (h >>> 20)
    g(h) = p ^ (p >>> 4) ^ (p >>> 7)

How this was found: a table of 2^k buckets (load factor 4, so it never grows) filled with keys of
hash 0 … 2^k−1 holds exactly one key per bucket, and the order of the walk gives each bucket's
index. One extra key of hash `1 << b` then lands at the front of a bucket shared with one filler,
which names its bucket. For k = 16 and k = 20 and every bit b this gives the low k bits of `g` for
each single bit; random hashes showed `g` is linear over GF(2) (the bucket of `h` is the XOR of the
buckets of its bits); and of all two-stage XOR-shift functions only this one (with its two stages
in either order, which commute) fits all 32 bits. Bits of `g` above bit 19 were not observed
directly. Pinned by: WeakMapOrder ("W1 fill", "W1 bit mates", "W1 random mates", "W1 ints", "W1
big", "W1 words", "W1 longs").

**W2.** *Walk.* Every iterator visits the buckets from index `n − 1` down to 0, and each bucket's
keys in its chain order (W3). Pinned by: WeakMapOrder (all lines), WeakMaps ("W10 before").

**W3.** *Chains.* A new key goes to the front of its bucket's chain. Replacing a value keeps the
entry (and its original key object, W11) where it is. Removal unlinks the entry; adding the key
again puts it at the front. Pinned by: WeakMapOrder ("W3 one bucket", "W3 replace, remove,
re-add").

**W4.** *Table size and growth.* `new WeakHashMap()` has 16 buckets and load factor 0.75.
`new WeakHashMap(c)` and `new WeakHashMap(c, lf)` have the smallest power of two ≥ `c` buckets (one
bucket for `c` = 0 or 1). The threshold is `(int)(n * lf)` (`n` is a power of two, so float and
double agree; an infinite `lf` gives `Integer.MAX_VALUE`, so the table never grows). The table
exists from construction. When `put` (or `putAll`, per entry) adds a new key and the size is then
≥ the threshold, the table doubles and the threshold becomes `(int)(2n * lf)`. Replacing a value
never grows it; nothing ever shrinks it. Pinned by: WeakMapOrder ("W4 cap …", "W4 lf …", "W4 cap c
lf 100").

**W5.** *Moving entries on growth.* Old buckets are emptied one at a time; each old chain is
walked from front to back and every entry is put at the *front* of its new bucket (`b` or
`b + n`). So within a new bucket the entries' relative order is the reverse of their old order.
Pinned by: WeakMapOrder ("W5 before", "W5 after growth", "W5 after second growth", "W4 …" lines
that cross a growth).

**W6.** *Copies and putAll.* `new WeakHashMap(m)` is `new WeakHashMap(max((int)(s / 0.75f) + 1,
16), 0.75f)` with `s = m.size()`, followed by `putAll(m)`. `putAll(m)` does nothing when `s` is 0.
Otherwise, when `s` > the threshold, it first computes `t = (int)(s / lf + 1)` (float arithmetic),
doubles a candidate size from `n` until it is ≥ `t`, and if that is larger than `n` it grows the
table to it at once, moving entries by W5, but only under W7's condition. Then it puts every entry
of `m` in `m`'s iteration order, each `put` following W3 and W4. A `putAll` whose keys are all
present changes no order. Pinned by: WeakMapOrder ("W6 s=…", "W6 putAll of present keys", "W6
putAll of nothing"), WeakMapOrder fuzz.

**W7.** *Presizing a sparse map is skipped.* The up-front growth of W6 happens only when the size
before `putAll` is ≥ `threshold / 2` (integer division, the threshold before growing). Otherwise
the table keeps its size and the entries are put one by one, growing by W4 as needed. (Growth from
`put` always meets the condition.) Pinned by: WeakMapOrder ("W7 cap … lf …", "W6 … pre8", "W6 …
pre16", "W6 … putAll(1)"); removing the condition, or removing the presizing, changes about one
fuzz run in seven.

**W8.** *`forEach` and `replaceAll`.* `WeakHashMap.forEach(action)` and `replaceAll(function)` walk
the buckets from index 0 *up* to `n − 1`, each chain front to back: not the iterators' order. The
views' `forEach` and `Iterator.forEachRemaining` use the iterator order of W2. Pinned by:
WeakMapOrder ("W8 forEach … replaceAll … keySet.forEach … values.forEach … forEachRemaining"),
WeakMaps ("W19 visited").

Pinned by all of W1–W7 at once: WeakMapOrder ("W1-W7 fuzz …", "W1-W7 fuzz checksum"), 800 random
sequences of `put`, `remove`, `putAll` and copying on keys that collide, with random capacities and
load factors.

## 2. Entries and the collector

**W10.** *When entries go.* Once a key is only weakly reachable and the collector has cleared it,
the map behaves as if the entry had been removed: `size`, `isEmpty`, `get`, `containsKey`,
`containsValue` (even for a value the caller still holds), `remove`, `toString`, `hashCode`, the
views' sizes, contents and walks all leave it out. The map forgets the value too. Survivors keep
their order (W2, W3); later puts and growth follow section 1 from the surviving chains. Pinned by:
WeakMaps ("W10 …" lines).

**W11.** *Which key object is held.* Putting a key equal to a present one replaces only the value;
the map keeps the first key object. So the entry goes when that first object becomes unreachable,
even if the caller still holds the equal one used later. Pinned by: WeakMaps ("W11 an equal key
keeps the first key object").

**W12.** *What keeps an entry.* Values are held strongly. An entry stays while its key is strongly
reachable from anywhere, including from its own value or from another entry's value: a value that
refers to its key keeps the entry forever. The `null` key is never cleared. Keys that the VM keeps
reachable itself stay: boxed values from the boxing caches (`Integer` −128 … 127 and the like) and
string literals; a `new String(...)` copy or a `Integer` outside the cache (100000) goes. Pinned by:
WeakMaps ("W12 settled", "W12 a value that is another key keeps it").

**W13.** *Iterators hold keys.* The key a `hasNext()` call has found (and will return next) and
the key the last `next()` returned are held strongly by the iterator until it moves on, so they
stay in the map even when nothing else refers to them, and `next()` and `remove()` still work on
them. Keys cleared before the walk reaches them are skipped silently. Dropping cleared entries is
not a modification: it never causes a `ConcurrentModificationException`. Once `hasNext()` has
reported the end of the walk (also inside a `next()` that throws `NoSuchElementException`), the
iterator lets go of the key it returned last, so that key can go too (W26). Pinned by: WeakMaps ("W13
…" lines).

**W14.** *A held entry after its key goes.* An entry object obtained from the entry iterator stays
usable after its key is cleared and the map has dropped it: `getKey()` and `getValue()` return
`null`, `toString()` is `null=null`, `hashCode()` is 0, and `setValue(v)` returns the old (null)
value, stores `v` in that detached entry and does not change the map. Pinned by: WeakMaps ("W14
held entry", "W14 setValue on it").

## 3. Keys, entries and views

**W15.** *Lookups.* `get`, `containsKey`, `put` and `remove` call the argument's `hashCode()` once
(never for `null`) and walk the chain of its bucket. For each stored entry whose stored hash code
equals the argument's, the key matches if it is the same object, else if `argument.equals(stored)`
is true (the argument's `equals`, stored key as argument); entries with another hash are passed
without calls. `containsValue` and `toString` call no `hashCode` or `equals` of keys. `hashCode()`
of the map sums the entries' hash codes in iteration order, calling each key's `hashCode()` again.
`equals` follows `AbstractMap` (collections.md). The `null` key is accepted everywhere; its bucket
is not fixed (section 7). Pinned by: WeakMaps ("W15 …" lines), WeakMaps ("W12 null key …").

**W16.** *Entries.* An entry equals any `Map.Entry` whose key is the same object or for which
`thisKey.equals(otherKey)`, and whose value is equal (both null, or `equals`); `null` and
non-entries are unequal. `hashCode()` is `hash(key) ^ hash(value)` (0 for null), calling the key's
`hashCode()`; `toString()` is `key=value`. `setValue` returns the old value and writes through.
The null key's entry reports `null` as its key. `entrySet().contains(e)` and `remove(e)` find the
key and compare the value, with the calls of W24; anything that is not an entry gives `false`. A map may hold
itself as a value. Pinned by: WeakMaps ("W16 …" lines).

**W17.** *Views.* `keySet()`, `values()` and `entrySet()` return the same object on every call and
reflect the map. Their `remove`, `removeAll`, `retainAll`, `removeIf`, `clear` and iterator
`remove` change the map; `values().remove(v)` removes the first match in iteration order. `add` on
any view throws `UnsupportedOperationException`. An iterator's `remove()` before `next()`, or twice,
throws `IllegalStateException`; `next()` past the end throws `NoSuchElementException`. Pinned by:
WeakMaps ("W17 …" lines).

## 4. Fail-fast behaviour

**W18.** An iterator remembers the map's modification count when created. Adding a new key,
removing a present key (by any route other than this iterator's own `remove`), `putAll` that adds
keys, and `clear()` (even of an empty map) change the count; replacing a value, removing an absent
key, `putAll` that adds nothing, and dropping cleared entries (W13) do not. Only `next()` checks:
it throws `ConcurrentModificationException` when the count changed. `hasNext()` never throws and
answers from the current table (false after `clear`, true when a key was added ahead). `remove()`
first throws `IllegalStateException` when there is nothing to remove. Pinned by: WeakMaps ("W18 …"
lines).

**W19.** *Callbacks.* `forEach` and `replaceAll` (W8) throw `NullPointerException` for a null
argument even on an empty map. After every call of the action or function they check the count and
throw `ConcurrentModificationException` at once if it changed, also after the last entry; a
`replaceAll` stores each new value before that check. Replacing values from inside `forEach` is
allowed. The views' `forEach` behave like their iterators (W18): a change throws at the following
`next()`, so a change made while visiting the last element goes unnoticed. Pinned by: WeakMaps
("W19 …" lines).

**W20.** *Map defaults.* `getOrDefault`, `putIfAbsent`, `remove(k, v)`, both `replace`,
`computeIfAbsent`, `computeIfPresent`, `compute` and `merge` behave as the `Map` interface's default
methods: built on `get`, `containsKey`, `put` and `remove`, with no fail-fast check, so a function
that changes the map causes no exception. Null values are allowed. Pinned by: WeakMaps ("W20 …"
lines).

## 5. Exceptions and messages

**W21.**

| Input | Result |
|---|---|
| negative capacity (checked first) | `IllegalArgumentException: Illegal Initial Capacity: -1` |
| load factor ≤ 0 or NaN | `IllegalArgumentException: Illegal Load factor: 0.0` (`-1.0`, `NaN`) |
| infinite load factor | accepted |
| `new WeakHashMap(null)`, `putAll(null)` | `NullPointerException` with no message |

Pinned by: WeakMaps ("W21 …" lines).

## 6. Walks under change, and the calls the views make

These rules were found after the first implementation, by probing the oracle with keys and
values that log their `hashCode` and `equals` calls (`P` is the probe, `S` the stored key or
value, `X.eq(Y)` means `X.equals(Y)`), and by differential fuzzing: random operation sequences,
with and without the collector, compared with the oracle.

**W22.** *A walk survives the collector.* When the collector clears some keys while an iterator
is part way through a walk, the iterator still visits every entry it has not reached yet whose
key is still strongly reachable, in W2 order; only the cleared ones are missing. This holds
inside one chain too: ten keys of one hash code put from 9 down to 0 (so `k0` heads the chain),
strong references kept to the even ones only, `entrySet().iterator().next()` gives `k0=v0`, and
once the collector has brought `size()` to 5 the rest of the walk is `k2=v2 k4=v4 k6=v6 k8=v8`.
Pinned by: WeakMaps ("W22 one chain", "W22 many buckets").

**W23.** *What `hasNext()` sees after a change.* The iterator moves along each chain by taking a
link's successor at the moment it visits that link, and reads a bucket's head only when it gets
to that bucket. So, after a change that `next()` would report (W18):
- an iterator made while the map was empty reports `false` for good, whatever is added later;
- a key added at the head of a bucket the walk has not reached yet is seen (`true`); one added to
  a bucket already passed, or to the front of the chain the iterator is in, is not;
- removing the key that follows the last returned one in its chain still leaves `hasNext()`
  `true`, and so does `clear()` when the last returned key had a successor in its chain;
  without one, `clear()` leaves nothing to find;
- once `hasNext()` has answered `false`, it stays `false`, even if keys are added;
- after growth it goes on from the link and the bucket index it had reached, now reading the
  new table.

Pinned by: WeakMapViews ("W23 …" lines), the fuzzer.

**W24.** *Calls made by the key and entry views.*

| Call | Calls on the keys, in order |
|---|---|
| `keySet().contains(P)` | as `containsKey` (W15): `P.h P.eq(S)`; the same object: `S.h` |
| `keySet().remove(P)`, `P` present | `containsKey` then `remove`: `P.h P.eq(S) P.h P.eq(S)`; same object `S.h S.h` |
| `keySet().remove(P)`, absent | one lookup only: `P.h` and `P.eq` of stored keys with the same hash |
| `entrySet().contains(e)` | the lookup of W15 by `e`'s key, then the stored entry's `equals(e)`: `P.h P.eq(S) S.eq(P) SV.eq(PV)` |
| `entrySet().contains(e)`, same key object | `S.h`, then the value: `SV.eq(PV)`, or nothing when it is the same value too |
| `entrySet().remove(e)` | `P.h`, then for each live entry of that hash code in its chain, the stored entry's `equals(e)`: `P.h S.eq(P) SV.eq(PV)`; never `P.eq` |
| `entrySet().remove(e)`, same objects | `S.h` only |
| stored entry `equals(o)` | key identity or `S.eq(otherKey)`, then value identity or `SV.eq(otherValue)` |
| stored entry `hashCode()` | `S.h SV.h`; `toString` and `setValue` call nothing |

`removeAll`, `retainAll` and `containsAll` on the views behave as `AbstractSet` and
`AbstractCollection` do (collections.md 1.H11): they call the views' own `contains` and `remove`
above, or walk the view and ask the argument collection, removing through the iterator (W26). The
views' `hashCode` sums in iteration order. Pinned by: WeakMapViews ("W24 …" lines).

**W25.** *Value comparisons.* `containsValue(v)` and `values().contains(v)` call `v.equals(stored)`
on each value in iteration order (W2) until one is true, with no identity shortcut (a stored value
that is the argument itself is still asked through `v.equals`); for `v == null` they call nothing
and look for a null value. `values().remove(v)` makes the same calls, stops at the first match and
removes that entry through the iterator (W26), which then calls that entry's key's `hashCode()`.
Keys are never compared. Pinned by: WeakMapViews ("W25 …" lines).

**W26.** *Iterator `remove()`.* It throws `IllegalStateException` when there is nothing to remove
(before any `next()`, or twice in a row), checked before the modification count; otherwise it
throws `ConcurrentModificationException` if the map changed since the iterator was made or last
removed (W18). It removes as `map.remove(key)` would with the key the iterator holds: it calls that
key's `hashCode()` and, for stored keys of the same hash code ahead of it in the chain, `key.equals`
on them. Once the walk has reported its end (W13), the iterator holds no key any more, and
`remove()` then removes the mapping of the `null` key if the map has one, and nothing otherwise,
without an exception. Pinned by: WeakMapViews ("W26 …" lines), WeakMaps ("W26 …" lines).

## 7. Not observable: the implementation may choose

- *The bucket of the null key.* The oracle stores `null` as a hidden placeholder object whose
  identity hash code picks the bucket, so its position among other keys changes with the VM's
  identity hashes. Tests never print the position of `null` next to other keys. LazyVM may use
  any fixed hash for it (0 is simplest), as long as W15 holds.
- *When cleared entries are dropped.* Any time before the next map operation reports on them.
  The natural design is entries that are `WeakReference`s registered with a `ReferenceQueue` the
  map polls at the start of each public method (and while growing); dropping entries during a
  growth is invisible because only the collector can cause it.
- *Moving entries back.* Whether a skipped presize (W7) is never attempted or is attempted and
  undone cannot be told apart, nor in which order buckets are walked while undoing it.
- *Tables above 2^30 buckets* and capacities near `Integer.MAX_VALUE`: no test heap holds them.
  Clamp to 2^30 buckets, never fail with an overflow.
- *Iteration while the collector clears keys* beyond W13 and W22: which of the not-yet-reached keys are
  still seen depends on when the collection happens.
- Whether the map keeps the hash code or `g(h)` per entry; how `clear` empties the queue.

## 8. API to provide

From the Java SE 8 API; members WeakHashMap declares itself; parameter types erased.
`WeakHashMap` is neither `Cloneable` nor `Serializable`.

| Class | Kind | Members |
|---|---|---|
| `WeakHashMap` | class extends AbstractMap implements Map | new(int, float); new(int); new(); new(Map); clear(); containsKey(Object); containsValue(Object); entrySet(); forEach(BiConsumer); get(Object); isEmpty(); keySet(); put(Object, Object); putAll(Map); remove(Object); replaceAll(BiFunction); size(); values() |

It needs `java.lang.ref.WeakReference` and `ReferenceQueue` (S5-07, on main) and the collection
classes of collections.md; nothing else.
