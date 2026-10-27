# java.util collections: behaviour beyond the Javadoc

This is the behavioural specification for LazyVM's `java.util` collections. It lists every place
where the Java 8 oracle (Temurin 1.8.0_504) does something more specific than the Java SE 8 Javadoc
and a test can see it: iteration orders, table sizes, call sequences, fail-fast timing, messages.
Implement the Javadoc, then these rules. How you build the classes is your choice; only what a
program can observe is fixed here.

Each rule reads *input → observable result*. "Pinned by" names the test in `tests/java/util` (and
its line label) that fails if the rule is implemented differently; all goldens are blessed on the
oracle. Section 12 lists what no program can see, where you may choose. Formulas such as
`m & (n - 1)` are Java `int` expressions; "in float" means Java `float` arithmetic. "Iteration
order" covers `iterator()`, `forEach`, `toString`, `toArray` and the views, unless a rule says
otherwise.

## 1. HashMap, HashSet, LinkedHashMap

**H1.** A key's *mixed hash* is `m = h ^ (h >>> 16)` with `h = key.hashCode()` (0 for `null`). A
table of `n` buckets (`n` a power of two) puts the key in bucket `m & (n - 1)`. Iteration visits
buckets in increasing index; within a bucket it follows that bucket's *sequence* (H5, H8). HashSet
behaves as a HashMap of its elements. LinkedHashMap and LinkedHashSet iterate in insertion (or
access) order; their tables never show. Pinned by: HashOrder (all lines), HashCapacity.

**H2.** `new HashMap()` has no table until the first insertion, which makes 16 buckets with resize
threshold 12. `new HashMap(c, lf)` and `new HashMap(c)` (lf 0.75) make, at the first insertion, a
table of the smallest power of two ≥ `c` (1 for `c` = 0), threshold `(int)(n * lf)` in float.
Growing doubles `n`. The new threshold is twice the old one when the old `n` was ≥ 16 and the
doubled value is not 0, otherwise `(int)(newN * lf)` in float; apart from that it is never
recomputed from `lf`. The table never shrinks. Pinned by: HashCapacity ("new HashMap(c)", "cap … lf
…", "doubled threshold 0"), HashOrder ("cap", "lf").

**H3.** The table grows (a) after `put`, `putIfAbsent`, `putAll` (per entry) or `HashSet.add` adds a
new key and the size is then greater than the threshold; (b) at the *start* of `computeIfAbsent`,
`compute` and `merge` when the size is already greater than the threshold, whether or not the call
then adds anything (they never grow after adding); (c) when a bin would become a tree (H7) but `n` <
64, instead of converting and before (a). Pinned by: HashCapacity (compute and put lines),
HashTreeRules, HashOrder ("lf 10.0").

**H4.** Copies. `new HashMap(m)`, `putAll(m)` on a map with no table yet, and `clone()` first set
the table size for `s = m.size()` entries: `t = (int)(s / lf + 1.0f)` in float, and if `t` exceeds
the table size already set (the rounded constructor argument, or 0 for the no-argument constructor
and for a clone), the table will be the smallest power of two ≥ `t`. `putAll(m)` on a map that has a
table grows it once up front when `s` > threshold. Then every entry of `m` is put in `m`'s iteration
order. So a clone re-inserts into a table sized like a copy, and its order can differ from the
original's. `new HashSet(c)` asks for `max((int)(c.size() / .75f) + 1, 16)` buckets (H2), then adds
the elements one at a time in `c`'s order. `HashSet.addAll` adds one at a time (no presizing).
`HashSet.clone()` is a map clone. Pinned by: HashCapacity (copy, clone, putAll, HashSet lines),
HashOrder (copy, putAll, "hashset of 13").

**H5.** A bin that is not a tree keeps a sequence: a new key added by `put`, `putIfAbsent`, `putAll`
or `HashSet.add` goes to the end; a new key added by `computeIfAbsent`, `compute` or `merge` goes to
the front. Replacing a value never moves an entry. Removal deletes the entry from the sequence. On
growth, each bin's entries go to bucket `b` or `b + oldN` by bit `oldN` of the mixed hash, keeping
their relative order. Pinned by: HashOrder ("compute family goes first", "descending", "random200"),
HashTreeRules.

### Tree bins

**H6.** *Tree order* of a key `a` against a stored key `b` in the same bin, in priority order:
1. mixed hashes compared as signed ints;
2. if both keys are of the same class `C`, `C` qualifies (below), and `a.compareTo(b)` ≠ 0: its sign;
3. `a.getClass().getName().compareTo(b.getClass().getName())` if ≠ 0;
4. otherwise `System.identityHashCode(a) <= System.identityHashCode(b)` counts as "before".

A key that orders before a node goes to its left. Lookups never change the bin (which `compareTo`
calls they make is left free, section 12).

`C` qualifies when `C` is `String`, or `C` itself directly declares the interface `Comparable<C>`
with exactly `C` as type argument. A raw `Comparable`, `Comparable<Object>`, `Comparable` of a
supertype, `Comparable<C<T>>` for a generic `C`, `Comparable` inherited from a superclass or through
another interface: none qualify, and `compareTo` is never called on such keys. Pinned by:
HashTreeRules ("class names", "compareTo calls", "String", "identity" lines), HashTreeBins ("odd
keys").

**H7.** A list bin becomes a tree bin when `put`-style insertion (H5) brings it to 9 entries, or
`compute`-style insertion brings it to 8, and `n` ≥ 64 (else H3's growth). The tree is a red-black
tree (Cormen et al., *Introduction to Algorithms*, insertion and deletion with rebalancing) keyed by
tree order, built by inserting the bin's entries in sequence order. The sequence keeps its order
except that the tree's root moves to the front. Pinned by: HashTreeBins, HashTreeRules ("converts"
lines, "32 buckets").

**H8.** A new key in a tree bin is inserted into the tree. In the sequence it is placed directly
after the entry that becomes its parent in the tree (its parent before rebalancing); then the root
(perhaps new) moves to the front. Replacing a value changes nothing. Pinned by: HashOrder ("tree bin
…"), HashTreeRules.

**H9.** *Map-style removal* of key `k` from a tree bin (operations in H11): `k` leaves the sequence.
Then, judged on the tree as it was before the removal: if its root has no left child, or no right
child, or its left child has no left child, the bin becomes a list bin with the current sequence and
nothing else moves. Otherwise `k` is deleted from the tree (a node with two children is replaced by
its in-order successor), the tree is rebalanced, and the root moves to the front. Pinned by:
HashTreeRules ("shrink …"), HashTreeBins ("removals map").

**H10.** *Iterator-style removal* (H11): `k` leaves the sequence and is deleted from the tree as in
H9, but the root does not move to the front and the bin stays a tree however small it gets. The next
H8 insertion, or H9 removal that keeps the tree, moves the then root to the front. Pinned by:
HashTreeRules ("iterator removal …"), HashTreeBins ("removals iterator").

**H11.** Map-style: `remove(k)`, `remove(k, v)`, `keySet().remove`, `entrySet().remove`, `compute`,
`computeIfPresent` or `merge` returning `null`, and `removeAll(c)` on `keySet()` or `entrySet()`
when the set is larger than `c` (it then removes each element of `c`). Iterator-style:
`Iterator.remove` on any view, `removeIf` on any view, `values().remove`, `retainAll`, and
`removeAll(c)` when the set is not larger than `c` (it then iterates the set). Pinned by:
HashTreeRules ("removal by …").

**H12.** On growth, a tree bin's entries split by H5's bit rule, keeping sequence order. A half of
at most 6 entries becomes a list bin. A half of 7 or more becomes a new tree built from its sequence
as in H7, if the other half is non-empty; if all entries stay together, the bin moves unchanged
(same tree, same sequence, even if the root is not first). Pinned by: HashTreeRules ("split",
"unsplit"), HashTreeBins ("split").

**H15.** When a bin becomes a tree (H7), or a tree bin becomes a list (H9 shrink, an H12 half of at
most 6), its entries are replaced by new entry objects in the same order. An entry obtained earlier
(from iteration) no longer writes through, and a `forEach` standing on one carries on along the bin
as it was when it changed, so keys then added to the bin go unseen. A split half that becomes a new
tree keeps its entries. Pinned by: HashTreeRules ("entries replaced").

### LinkedHashMap

**H13.** In access order, these move an existing entry to the end, and count as a structural change
(F1) unless it was already last: `get`, `getOrDefault`, and on an existing key `put`, `putIfAbsent`,
`replace` (both forms), `compute`, `computeIfAbsent`, `computeIfPresent`, `merge`, `putAll`. These
do not: `containsKey`, `containsValue`, `replaceAll`, `Entry.setValue`, a miss, iteration. Pinned
by: LinkedHashMaps (access-order lines), FailFast ("access-order").

**H14.** `removeEldestEntry(eldest)` is called once after every insertion of a new key, by `put`,
`putAll`, `putIfAbsent`, `computeIfAbsent`, `compute` and `merge`, when the new entry is already in
the map, with the then first entry; if it returns true that entry is removed. Replacing a value does
not call it. Pinned by: LinkedHashMaps ("evicted").

## 2. Hashtable

**T1.** Bucket = `(h & 0x7FFFFFFF) % n`. A new key goes to the front of its bucket's chain.
Iterators, `keys()`, `elements()`, the views and `toString` visit buckets from `n - 1` down to 0,
each chain front to back. `forEach` and `replaceAll` visit buckets from 0 up to `n - 1`, each chain
front to back. Pinned by: HashtableOrder, Vectors, MapDefaults.

**T2.** `new Hashtable()` has 11 buckets; `new Hashtable(c, lf)` has `max(c, 1)`; the threshold is
`(int)(n * lf)` in float. Before adding a new key (`put`, `putIfAbsent`, `compute`,
`computeIfAbsent`, `merge`, `putAll` per entry), if the size is ≥ the threshold, the table is
rebuilt with `2n + 1` buckets and threshold `(int)(newN * lf)`: old buckets are taken from `n - 1`
down to 0, each chain front to back, and every entry is pushed onto the front of its new chain; then
the key is added. Pinned by: HashtableOrder.

**T3.** `new Hashtable(m)` has `max(2 * m.size(), 11)` buckets, then puts `m`'s entries in `m`'s
order; `clone()` copies the chains as they are. An iterator or enumeration obtained while the table
is empty yields nothing and never throws, even after later puts. Pinned by: HashtableOrder, FailFast
("clear() of an empty collection").

**T4.** `hashCode()` sums the entries' `key.hashCode() ^ value.hashCode()`; a nested call on a table
whose hash is already being computed returns 0 (no recursion), so self- and mutually referencing
tables hash finitely. Empty: 0. Pinned by: HashtableOrder, SelfReference.

**T5.** An enumeration or iterator of a table that was not empty throws
`NoSuchElementException("Hashtable Enumerator")` past its end and
`IllegalStateException("Hashtable Enumerator")` for `remove()` before `next()`; an enumeration
cast to `Iterator` refuses `remove()` with `UnsupportedOperationException`. `remove(k)`,
`remove(k, v)`, `keySet().remove` and `entrySet().remove` clear the removed entry's value, so an
entry held from iteration then shows `null`; iterator removal, the compute family and `clear()` do
not. Such an entry's `hashCode()` is then its key's hash (a null value counts 0) and its
`toString()` throws `NullPointerException`. `entrySet().add(x)` casts `x` to `Map.Entry` first, so a
non-entry gives `ClassCastException` and an entry `UnsupportedOperationException`. The empty
table's `keys()` and `elements()` are Enumerations only, and its view iterators are Iterators only
(X7). Pinned by: HashtableOrder ("enumerator", "removed entry").

## 3. Vector, Stack and ArrayList sizes

**V1.** `capacity()`: `new Vector()` and `new Stack()` 10; `new Vector(c)` `c`; `new Vector(coll)`
`coll.size()`; `clone()` its size; `trimToSize()` its size. When an operation needs room for `r`
elements and `r` > capacity, the new capacity is `capacity + increment` (increment > 0) or
`2 * capacity` (increment ≤ 0), raised to `r` if still smaller. `r` is `size + 1` for single adds,
`size + c.size()` for `addAll`, the argument of `ensureCapacity` (only if > 0) and of `setSize`
(only if > size). Pinned by: Vectors (capacity lines).

**V2.** ArrayList capacity shows through F1 and through a `sort` whose comparator adds elements: the
sort works on the storage it began with, so while the additions fit, the list ends sorted (and the
sort then throws); once they do not fit, the list is copied and the sort's work is lost.
`new ArrayList()` has no storage; its first growth makes `max(10, r)` slots. `new ArrayList(c)` has
`c` slots and `new ArrayList(coll)` `coll.size()`. When `r` elements are needed and do not fit, the
new capacity is `max(capacity + (capacity >> 1), r)`. `ensureCapacity(r)` grows only when `r`
exceeds the capacity, and on a list without storage only when `r` > 10. `trimToSize()` and `clone()`
leave exactly `size` slots; removals and `clear()` never shrink. Pinned by: Lists ("capacity
through a sort that adds"), FailFast.

## 4. ArrayDeque

**D1.** Elements occupy consecutive slots of a circular array, first to last. The array has 8 slots
when created with `n` < 8 (and for collections smaller than 8), otherwise the smallest power of two
greater than `n` (or the collection's size); `new ArrayDeque()` has 16. `new ArrayDeque(c)` adds `c`
with `addLast`, so the first element sits in slot 0. `addFirst` uses the slot before the first,
`addLast` the slot after the last; when an add fills the array it doubles, and the elements are
renumbered so the first is in slot 0. The array never shrinks; `clone` copies it as is. Pinned by:
Deques ("slots …").

**D2.** A forward iterator remembers the deque's end position when created and walks slots from the
first. `hasNext()` compares with the remembered end. `next()`: at the remembered end,
`NoSuchElementException`; otherwise, if the deque's end has moved or the slot is empty,
`ConcurrentModificationException`; otherwise it returns the slot's element. A descending iterator is
the mirror: it remembers the front, walks backward from the end and fails when the front has moved.
It steps back before it checks, so after a failed `next()` the following calls go on with the slots
below (in a deque of 1..5 after `pollLast()`: CME, 4, 3, 2, 1, then `NoSuchElementException`), and
its `forEachRemaining` likewise fails once and leaves the walk moved on. So changes at the side the
iterator does not watch go unseen, and shifted elements may be seen twice. `forEachRemaining` on a
forward iterator visits up to the remembered end and does not notice additions. `remove()` throws
`ConcurrentModificationException` when the slot of the last returned element no longer lies between
the deque's current ends (as after a `pollFirst()` or a middle removal that moved the front past
it); otherwise it removes whatever that slot now holds. Pinned by: Deques ("iterator …" lines,
"descending next after pollLast").

**D3.** Removing the element at index `i` of `n` (by `remove(Object)`, `removeFirstOccurrence`,
`removeLastOccurrence` or an iterator) shifts the `i` elements before it one slot toward the end if
`i < n - i`, else shifts the elements after it one slot toward the front. Iterator removal adjusts
the iterator, which then continues correctly. `clear()` empties every slot and moves both ends to
slot 0, except that on an empty deque it does nothing, so the ends stay where they were (an open
iterator can tell). Pinned by: Deques ("remove index …", "remove at the middle (tie)", "clear"),
ReentrantWalks ("clear of an empty deque", "deque remove after the walked slot left").

## 5. PriorityQueue

**P1.** Iteration, `toString` and `toArray` follow a binary heap array: children of `i` at `2i+1`
and `2i+2`. All comparisons call `compare(x, y)` (or `x.compareTo(y)`) with the arguments in the
order below. Pinned by: PriorityQueues ("compare calls", "building calls", "add …", "comparator
adding to its own queue").
- `offer(x)`: put `x` at the end; while it has a parent `p` and `compare(x, p) < 0`, move `p` down.
  The size counts `x` before the first comparison, so a comparator that adds to the queue meets an
  empty slot (a `null` argument).
- Moving down from slot `k` with element `x`: while `k` has a child, let `c` be the left child, or
  the right child if it exists and `compare(left, right) > 0`; stop if `compare(x, c) <= 0`, else
  move `c` up and continue at `c`'s slot.
- `poll()`: take slot 0, move the last element down from slot 0.
- Removing slot `i` (`remove(o)`, which finds `o` with `o.equals`, and iterators): take the last
  element `x`; if `i` was last, done; else move `x` down from `i`, and if it stayed at `i` move it
  up.

**P2.** Iterator removal: when the element moved into the removed slot went up past the cursor, the
iterator does not revisit that slot; it returns such elements after the array is exhausted, in the
order they were displaced. `removeIf`, `removeAll` and `retainAll` remove through the iterator.
Pinned by: PriorityQueues ("iterator remove …", "removeIf").

**P3.** `new PriorityQueue(c)`: from a `SortedSet` or a `PriorityQueue` the elements are copied in
their order, with that comparator and no comparisons; otherwise they are copied in `c`'s order and
the heap is built by moving each slot down, from `(n / 2) - 1` to 0. `addAll` offers each element.
Pinned by: PriorityQueues ("building calls", "ctor …").

## 6. TreeMap and TreeSet

**M1.** TreeMap is a red-black tree with Cormen et al.'s insertion and deletion. Each lookup (`get`,
`containsKey`, `put`, `remove`) walks from the root calling `compare(key, nodeKey)` and goes left on
a negative result. The first `put` into an empty map calls `compare(key, key)`. Pinned by:
TreeMapCalls ("step …", "first put").

**M2.** Deleting a node with two children moves the successor's key and value into that node and
unlinks the successor's node. So a `Map.Entry` held from iteration for the deleted key then shows
the successor's key and value and writes through to it, and one held for the successor is detached.
Pinned by: TreeMapCalls ("held entries …").

**M3.** `new TreeMap(sortedMap)`, `putAll(sortedMap)` into an empty map with an equal comparator,
`new TreeSet(sortedSet)` and `addAll(sortedSet)` into an empty TreeSet with an equal comparator
build the tree without comparisons: the middle element (index `(lo + hi) >>> 1`) is the root,
recursively, and only the nodes of an incomplete bottom level are red. `clone()` builds the same
way. Other copies insert one at a time. Pinned by: TreeMapCalls (SortedMap, putAll, TreeSet and
"clone" lines).

**M4.** Range views (`subMap`, `headMap`, `tailMap`, `descendingMap`, their key sets and the
TreeSet views on them) check a key against a bound with `compare(key, bound)`, and their
navigation methods walk the tree as M1 does. A walk over a view first finds its start (the lowest
node in range, or the highest when descending, then checks it against the other bound), then its
fence: the first node past the far bound, found by ceiling (exclusive bound) or higher (inclusive),
or by floor or lower when descending. `size()` counts by walking and counts again only after the
map changed. Making a view compares its bounds: the low key with the high one when both exist
(`fromKey > toKey` if it is greater), else the one bound with itself. A map or view keeps the one
`descendingMap()`, `navigableKeySet()` (also its `keySet()`) and `entrySet()` it hands out, so
`descendingKeySet()` twice makes one view and no comparisons the second time; the descending view of
a descending view is a new view. TreeSet's `descendingSet()` and `headSet(…)`, and the key sets'
`descendingSet()`, are new each call. Clearing a view removes its entries one by one. Pinned by:
TreeMapCalls ("subMap walk", "descending view", "view bounds"), TreeMaps ("views kept", "clear
views").

## 7. Sorting objects

**S1.** `Arrays.sort(Object[] …)`, `Arrays.sort(T[], …, Comparator)`, `List.sort` and
`Collections.sort` use the stable merge sort described by Tim Peters ("listsort"), with these exact
rules; `compare(x, y)` below means `comparator.compare(x, y)` or `x.compareTo(y)`. Pinned by:
SortCalls (its "runs [98, …]" line separates this collapse rule from one that also checks a fourth
run), Sorting ("comparison …", "inconsistent comparators", "list.sort violates").
- Fewer than 2 elements: no calls. Fewer than 32: find one run at the start, then insert the rest.
- *Run*: from `lo`, `compare(a[lo+1], a[lo])`; if < 0 the run extends while
  `compare(a[i], a[i-1]) < 0` and is then reversed; otherwise it extends while ≥ 0.
- *Insert* `a[i]` into sorted `a[lo..i)`: `left = lo, right = i`; while `left < right`:
  `mid = (left + right) >>> 1`; `compare(a[i], a[mid]) < 0` → `right = mid`, else `left = mid + 1`.
- Minimum run for `n` ≥ 32: `r = 0`; while `n >= 32`: `r |= n & 1; n >>= 1`; result `n + r`. A
  shorter natural run is extended to the minimum (or the rest) by insertion. Each run is pushed on a
  stack, then the stack is *collapsed*, then the next run starts. At the end, merge until one run.
- Collapse, with `L[k]` the run lengths and `j` the second-newest run: if `j ≥ 1` and
  `L[j-1] <= L[j] + L[j+1]`, merge runs `j-1, j` when `L[j-1] < L[j+1]`, else `j, j+1`; else if
  `L[j] <= L[j+1]` merge `j, j+1`; else stop. Repeat. Only these three runs are looked at.
- Final merging: while two or more runs: `j` second newest; merge `j-1, j` if `j ≥ 1` and
  `L[j-1] < L[j+1]`, else `j, j+1`. Pinned by: SortCalls ("final merging of 30 runs").
- *Find-after(key, run, hint)* is the number of leading run elements `e` with
  `compare(key, e) >= 0`; *find-before* counts those with `compare(key, e) > 0`. Both first test
  `run[hint]`. If the key belongs after it, they probe `hint + 1, hint + 3, hint + 7, …` (offsets
  `2·ofs + 1`) while the key still belongs after, else `hint - 1, hint - 3, …` while it does not,
  stopping at the run's ends. Then with `lo` one past the nearest index known to be "after" (or 0)
  and `hi` the nearest index known not to be (or the run length): while `lo < hi`:
  `m = lo + ((hi - lo) >>> 1)`; the key belongs after `run[m]` → `lo = m + 1`, else `hi = m`. The
  result is `hi`.
- Merging runs A then B (A first in the array): drop the first find-after(B[0], A, 0) elements of A;
  if no A is left, done. Keep only the first find-before(A[last], B, |B| - 1) elements of B; if
  none, done. Merge forward if |A| ≤ |B|, else backward. `g`, the gallop threshold, starts at 7 for
  each sort call and carries over between merges (at least 1).
- Forward merge (A set aside): take B[0]. Then, unless B is empty or A has one element left, take
  one element per step: `compare(b, a) < 0` takes `b`, else `a`; stop when B is empty or A has one
  left. After `g` consecutive takes from one side, gallop: take the find-after(b, A, 0) leading
  elements of A (stop if at most one is left), take `b` (stop if B is empty), take the
  find-before(a, B, 0) leading elements of B (stop if B is empty), take `a` (stop if one is left),
  `g -= 1`; repeat while either count was ≥ 7; on leaving, `g = max(g, 0) + 2` and go back to one
  per step. At the end: if A has one left, copy B's rest, then it; if A is empty, throw
  `IllegalArgumentException("Comparison method violates its general contract!")`; else copy A's
  rest.
- Backward merge (B set aside) mirrors it from the right end with the runs' roles exchanged: take
  A's last; per step `compare(b, a) < 0` takes `a` (from A's end), else `b`; A is the side that can
  run empty, B the side checked for one left and for the exception. Galloping takes
  `|A| - find-after(b, A, |A| - 1)` elements from A's end (stop if A is empty), then `b` (stop if
  one is left in B), then `|B| - find-before(a, B, |B| - 1)` from B's end (stop if at most one is
  left), then `a` (stop if A is empty).

**S2.** Which array is sorted: ArrayList, Vector and `Arrays.asList` sort their own storage
(ArrayList and Vector then check and bump the modification count, F1); other lists are copied with
`toArray()`, sorted, and written back with `listIterator()`, `next()` and `set()` for each element.
Pinned by: CollectionsCallLog ("sort"), SortCalls, FailFast.

## 8. Collections algorithms and AbstractList

**C1.** AbstractList's iterator calls `get(i)` once per `next()`; its list iterator calls
`get(i - 1)` per `previous()` and `set(i)` per `set`. `listIterator()` calls `listIterator(0)`;
`iterator()` does not. `equals` walks `this.listIterator()` and the other list's `listIterator()` in
step; `hashCode`, `contains`, `toString`, `toArray` use `iterator()`; `indexOf` uses
`listIterator()`; `lastIndexOf` uses `listIterator(size())` backwards. A sublist's `get(i)` and
`set(i)` call the parent's with `i + from`; its iterators come from the parent's
`listIterator(from + i)` (except an ArrayList's, F2). A list iterator whose `add` throws leaves its
cursor where it was. Pinned by: CollectionsCallLog, FailFast ("failed listIterator add").
ArrayList itself, by contrast, works on its storage and its size field: on a subclass that overrides
`get`, `size`, `isEmpty` or `set`, its iterators, `forEachRemaining`, `forEach`, `toString`,
`hashCode`, `equals`, `indexOf`, `contains`, `toArray`, `subList` (and the window's `get`), `sort`,
`clone` and the copy constructor call none of them, and `set(i, e)` and `remove(i)` do not call
`get`. The calls that do reach a subclass are those the iterators make to change the list
(`remove(i)`, `set(i, e)`, `add(i, e)`) and those of the Collections algorithms as C2 lists them
(`reverse`: `size`, then `get` and two `set`s per swap; `binarySearch`: `size`, then `get`).
Pinned by: ArrayListSubclass.

**C2.** The calls each algorithm makes on a list `l` of size `n` (indexed form unless noted; "RA"
means `l instanceof RandomAccess`). Pinned by: CollectionsCallLog, CollectionsCalls.

| Algorithm | Indexed form when | Indexed form | Otherwise |
|---|---|---|---|
| `swap(l, i, j)` | always | `get(i)`, `set(j)`, `set(i)` | |
| `reverse` | RA or `n` < 18 | swap(i, n-1-i) for i < n/2 | `listIterator()`, `listIterator(n)`; n/2 times: forward next, backward previous, forward set, backward set |
| `shuffle(l, r)` | RA or `n` < 5 | for i = n down to 2: swap(i-1, r.nextInt(i)) | `toArray()`, same swaps on the array, then `listIterator()` next+set per element |
| `fill` | RA or `n` < 25 | `set(i)` ascending | `listIterator()`, next+set per element |
| `copy(dst, src)` | `src` < 10 elements, or both RA | `dst.set(i, src.get(i))` | `dst.listIterator()`, `src.listIterator()`; per element dst next, src next, dst set |
| `rotate(l, d)` | RA or `n` < 100 | `d %= n` (made ≥ 0; 0 → nothing); cycles from 0: `get(start)`, then `set` at start+d, +2d, … (mod n) until back at start; next cycle at start+1 until n moved | `m = -d % n` made ≥ 0 (0 → nothing); `reverse(subList(0, m))`, `reverse(subList(m, n))`, `reverse(l)` |
| `replaceAll(l, o, x)` | RA or `n` < 11 | `get(i)` ascending, `set(i)` on each match | `listIterator()`, next per element, set on match |
| `binarySearch` | RA or `n` < 5000 | `lo = 0, hi = n-1`, `mid = (lo+hi) >>> 1`, `get(mid).compareTo(key)` or `c.compare(get(mid), key)`; result `-(lo + 1)` on a miss | one `listIterator()` moved to each `mid` with next (if behind) or previous (if ahead), reading every element passed |
| `indexOfSubList(s, t)` | `s` < 35 elements, or `s` and `t` both RA | for each start from 0: `t.get(i)` then `s.get(start + i)` until a mismatch | `s.listIterator()`; per start: `t.listIterator()`, alternate t next, s next; on a mismatch after `i` matches, s steps back `i` |
| `lastIndexOfSubList(s, t)` | `s` < 35, or `s` RA (`t` does not matter) | starts from `n - |t|` down | `s.listIterator(n - |t|)`; per start: `t.listIterator()`, alternate t next, s next; on a mismatch after `i` matches and start > 0, s steps back `i + 2` |
| `min`, `max`, `frequency` | always | `iterator()`; `max`: `next.compareTo(best) > 0` replaces; `min`: `< 0` | |
| `disjoint(a, b)` | always | when neither is a Set and either is empty: `true`, touching neither; else iterates `b` and asks `a.contains` if `a` is a Set, or if neither is a Set and `a` is larger; else iterates `a` and asks `b.contains` | |

**C3.** Equality tests call `equals` on the argument or target object: `o.equals(e)` for `indexOf`,
`lastIndexOf`, `contains`, `remove(Object)`, `frequency`, `replaceAll` and hashed lookups;
`t.equals(s)` with the target's element for the sublist searches; `e1.equals(e2)` with the receiver
list's element in `List.equals`. HashMap and HashSet keys, `HashMap.containsValue`, `remove(k, v)` and
`replace(k, old, new)` test identity first (`a == b`, then `equals`); ArrayList's `contains` and
`indexOf` and `TreeMap.containsValue` call `equals` alone, so an element unequal to itself is not
found. Per map type:
- HashMap and LinkedHashMap entries: `equals` is true for the entry itself and otherwise compares key
  and value identity first, so `entrySet().contains` finds an entry whose key is unequal to itself
  when given the same objects; the unmodifiable and checked entry sets hand `contains` on and agree.
- TreeMap entries: key and value are compared with the stored object's `equals` alone, without a
  shortcut for the entry itself, so such an entry is not even equal to itself. `values().remove(o)`
  asks each stored value's `equals(o)`, where other maps' value views ask `o.equals(stored)`.
- Hashtable: `contains`, `containsValue` and `values().contains` ask the stored value's
  `equals(probe)`; `entrySet().contains` and `remove` compare the given entry once with each entry
  of its key's chain (the stored key's and value's `equals`), without a separate key lookup.
- IdentityHashMap `equals(m)` for a map that is not an IdentityHashMap compares sizes, then looks up
  each of `m`'s entries here by identity, calling no `equals`.

Pinned by: CollectionsCallLog ("receiver …"), Lists ("HashMap values compared by identity first"),
EntryEquality.

## 9. Fail-fast iterators and modification counts

**F1.** Besides adding and removing elements, these count as structural changes (an open iterator's
next `next()` throws `ConcurrentModificationException`):
- ArrayList and Vector: `sort`, `replaceAll`, `addAll` of an empty collection (both forms),
  `subList(i, i).clear()`, `trimToSize()`, `clear()` even when empty, and `ensureCapacity(r)` for
  any `r` > 0, except that a list made by `new ArrayList()` that has not yet allocated storage
  (nothing added, no earlier counted `ensureCapacity`) counts only `r` > 10. Vector also:
  `remove(Object)` / `removeElement` even when nothing is removed, every `setSize`, and `add(i, e)`,
  `insertElementAt`, `remove(i)` and `removeElementAt` even when the index is bad and they throw.
  ArrayList's `remove(i)` with a negative `i` counts, then throws `ArrayIndexOutOfBoundsException`.
- LinkedList, ArrayList, Vector, PriorityQueue, HashMap, HashSet, LinkedHashMap, IdentityHashMap:
  `clear()` even when empty.
- Not counted: `set`, `Collections.reverse`/`swap`, LinkedList's `sort` and `replaceAll`,
  `removeIf`/`removeAll`/`retainAll` that remove nothing, ArrayList's `remove` of a missing element;
  in every map, `put`/`replace`/`compute`/`merge` on an existing key, `putAll` of existing keys,
  `replaceAll`, `Entry.setValue`, and compute-family calls that add nothing (except H13).

Pinned by: FailFast (… "failed index calls that count"), Lists ("… modCount", "… CME", "sub stale
…"), Iterators.

**F2.** `next()` checks for modification before checking for the end in ArrayList, LinkedList,
Vector, PriorityQueue, HashMap/HashSet/LinkedHashMap, Hashtable and IdentityHashMap; TreeMap and
TreeSet check the end first; ArrayDeque follows D2. `hasNext()` never throws: ArrayList and Vector
answer `cursor != size()`, LinkedList `cursor < size()`, PriorityQueue `cursor < size()` or
displaced elements remain (P2); the hashed and tree maps report whether a next node was found.
Sublists of an ArrayList, and their own sublists at any depth, compare with the ArrayList itself:
they take its count when made, and any call on one after the ArrayList changed elsewhere (`size()`
and `isEmpty()` included) throws ConcurrentModificationException. Their iterators run against the
sublist's own size, so after a change `hasNext()` may be true and `next()` throws
ConcurrentModificationException. Such a window reads and writes the ArrayList's storage at its
offset itself, so `get`, `set` and its iterators work on a window made from a stale window as long as
the ArrayList has not changed since it was made; `add`, `remove` and `addAll` go through the parent,
which checks for itself. Its iterator's `forEachRemaining` reads the storage as it was when the call
began and stops early only for a change made through the window; a change made to the ArrayList
directly lets it visit the rest, then throws ConcurrentModificationException.
Sublists of other AbstractLists (LinkedList, Vector) compare with their direct parent, whose own
check reaches further up only when it is called; `subList()` on a stale one asks its `size()` first
and so throws ConcurrentModificationException before any range check. Their iterators answer `hasNext()` by `nextIndex() < size` and fail with
NoSuchElementException at their end.
`forEachRemaining` at the end of the walk: ArrayList and Vector return quietly even after a change;
LinkedList checks and throws ConcurrentModificationException. LinkedList gives each element to the
action before stepping past it, so an action that throws leaves `next()` returning that element
again, and `remove`, `set` and `nextIndex` called from the action see the iterator as it was before
that element. If the action itself runs the iterator to its end (say by a nested
`forEachRemaining`), the outer walk then throws `NullPointerException`, leaving the index at the end
and nothing to remove. ArrayList, Vector and ArrayList windows step past the whole walk only at its end:
during the walk the iterator still stands where `forEachRemaining` began. Their walk reads the
storage taken when it began (so after the action grew the list it reads the old storage, and after
a shift it may read a moved element or a cleared slot), up to the size taken then, and it compares
the iterator's own expected count afresh at each step, so the iterator's own `add` or `remove`
called from the action lets the walk go on. After a completed `forEachRemaining`,
`set` and `remove` act on the last element visited, except in an ArrayList sublist, whose
`remove()` then throws ConcurrentModificationException while `set` writes the list element just past
the sublist. LinkedList's list iterator checks for a change before it checks for a missing `next()`
in `remove()`, and the other way round in `set()`. Pinned by: FailFast ("exhausted …", "sublist
iterators", "stale nested sublists", "forEachRemaining", "LinkedList iterator checks",
"ArrayList window after a direct change"), Iterators.

**F3.** How far a call gets when its action changes the collection, then throws
`ConcurrentModificationException`: ArrayList and Vector `forEach`, `replaceAll`, `removeIf` and
`Iterator.forEachRemaining` stop right after the action that changed it; `sort` completes, then
throws. HashMap and LinkedHashMap `forEach` (on the map, `keySet()` and `values()`) and `replaceAll`
finish the whole traversal, seeing entries added to buckets not yet visited (HashMap) or appended
(LinkedHashMap), then throw. A HashMap traversal walks the table the call began with: if an action
makes the table grow, those buckets are emptied as their entries move, so the walk ends with the
rest of the current entry's chain as it now stands, then throws. TreeMap, Hashtable and IdentityHashMap check after every action.
Collections that inherit `Iterable.forEach` (LinkedList, ArrayDeque, PriorityQueue, HashSet,
TreeSet) fail on the next `next()`. EnumMap and EnumSet iterators never throw. Pinned by: FailFast,
LinkedHashMaps, IdentityMaps ("forEach CME"), FailFast ("… grows the table").

**F4.** EnumMap iterators see the current contents. An EnumSet iterator captures the members of the
first 64 constants when created and of each later group of 64 when it reaches it; later changes
inside a captured group are not seen (except its own `remove`). Pinned by: EnumMaps ("… iterator",
"modify during iteration"), FailFast.

## 10. Other rules

**X1.** `Random(long)` on a subclass calls the (overridable) `setSeed(seed)` exactly once; on
`Random` itself the seed is set directly. `Random()` does the same with a seed of its own choice,
which is not observable (any well-spread value). Pinned by: RandomSeeding.

**X2.** EnumSet factories return an instance of class `java.util.RegularEnumSet` when the enum has
at most 64 constants (0 included) and `java.util.JumboEnumSet` otherwise. Pinned by: EnumMaps ("set
classes", "wide").

**X3.** `Arrays.asList` returns an instance of `java.util.Arrays$ArrayList`; its `toArray()` returns
a copy with the same runtime type as the backing array. Pinned by: ArraysUtil, Lists.

**X4.** `Collections.checkedMap(...).merge(k, v, f)` does not type-check `k` or `v` when `k` is
absent: they are stored unchecked. When `k` is present, `f`'s result is checked. `computeIfAbsent`,
`compute` and `putIfAbsent` check what they store. Its `entrySet().add(x)` casts `x` to `Map.Entry`
(`ClassCastException` for a non-entry) and then refuses with `UnsupportedOperationException`, as does
`entrySet().addAll`. Pinned by: CheckedViews ("merge …", "entrySet add").

**X5.** `toString` prints `(this Collection)` or `(this Map)` for an element, key or value that is
the very object being printed; any other path back (a view, a wrapper, another collection) recurses
until `StackOverflowError`. `hashCode` recurses (StackOverflowError) for every List, Set and Map
that contains itself, except Hashtable (T4) and IdentityHashMap (identity hashes).
`Arrays.deepToString` prints `[...]`. Pinned by: SelfReference, Lists, Vectors, ArraysUtil.

**X6.** `Collections.singletonMap` and `emptyMap` refuse `putIfAbsent`, `remove(k, v)`, both
`replace` forms, `computeIfAbsent`, `computeIfPresent`, `compute` and `merge` with
`UnsupportedOperationException` whatever the arguments; `replaceAll(f)` walks the entries (so
`emptyMap().replaceAll(null)` throws `NullPointerException`). `singletonList(x).sort(c)` does
nothing. Pinned by: MapDefaults ("singletonMap …", "emptyMap …"), CollectionsUtil ("singletonList
sort").

**X7.** `Collections.emptyIterator()`, `emptyListIterator()` and `emptyEnumeration()` are three
distinct objects: an Iterator only, a ListIterator, and an Enumeration only. `emptyList().iterator()`
and `emptySet().iterator()` are Iterators only, `emptyList().listIterator()` a ListIterator. An
empty Hashtable's view iterators are Iterators only, and its `keys()` and `elements()` Enumerations
only. `iterator()` is a ListIterator for LinkedList and for every sublist, and a plain Iterator for
ArrayList, Vector, Stack, `Arrays.asList`, `nCopies`, `singletonList`, AbstractList's default and the
unmodifiable and checked wrappers; the synchronized wrappers hand out the wrapped list's own. A checked
collection's iterator steps through `next()` in `forEachRemaining` instead of forwarding it, while
the list iterators of the checked and unmodifiable lists hand it on. A range of `nCopies` is again
an `nCopies` list. `emptyList().iterator()` and `emptySet().iterator()` are `emptyIterator()` itself
and `emptyList().listIterator()` is `emptyListIterator()`. All three views of `emptyMap()` are
`emptySet()` itself; `singletonMap`'s three views are one-element sets (`values()` included), each
made once. Pinned by: CollectionsUtil ("empty iterator kinds", "list
iterator kinds", "checked iterator forEachRemaining").

## 11. Exception types and messages

The common misuse cases throw these; "–" is a null message. Pinned by the tests named.

| Case | Exception: message | Test |
|---|---|---|
| ArrayList `get(-1)`; `get`/`set`/`add(i)`/`remove(i)`/`addAll(i)` out of range; LinkedList any bad index or `listIterator(i)` | `ArrayIndexOutOfBoundsException: -1`; `IndexOutOfBoundsException: Index: i, Size: n` | Lists, LinkedLists |
| ArrayList/Vector `listIterator(i)` bad; AbstractList's own | `IndexOutOfBoundsException: Index: i`; `Index: i, Size: n` | Iterators, Lists |
| `subList` bad bounds | `IndexOutOfBoundsException: fromIndex = -1` / `toIndex = 99`; `IllegalArgumentException: fromIndex(3) > toIndex(2)` | Lists |
| Vector `get`/`set`/`remove(i)`/`addAll(i)` past the end, `removeElementAt(-1)`; `get(-1)`, `elementAt(-1)`, `setSize(-1)` | `ArrayIndexOutOfBoundsException: Array index out of range: i`; `ArrayIndexOutOfBoundsException: -1` | Vectors |
| Vector `elementAt`/`setElementAt`/`removeElementAt` past the end; `insertElementAt`/`add(i)` past it; `lastIndexOf(o, i)` past it | `ArrayIndexOutOfBoundsException: 99 >= 9`; `… 99 > 9`; `IndexOutOfBoundsException: 99 >= 9` | Vectors |
| Vector/Stack empty `firstElement`, `pop`, `peek`; enumeration past the end | `NoSuchElementException` –; `EmptyStackException` –; `NoSuchElementException: Vector Enumeration` | Vectors |
| negative capacity | ArrayList/Vector `Illegal Capacity: -1`; HashMap/HashSet/LinkedHashMap `Illegal initial capacity: -1`; Hashtable `Illegal Capacity: -1`; IdentityHashMap `expectedMaxSize is negative: -1`; PriorityQueue < 1: `IllegalArgumentException` –; ArrayDeque: no exception (8 slots) | Lists, Vectors, Sets, LinkedHashMaps, IdentityMaps, PriorityQueues, Deques |
| bad load factor | `IllegalArgumentException: Illegal load factor: 0.0` (or `NaN`); Hashtable `Illegal Load: 0.0` | LinkedHashMaps, Sets, Vectors |
| `null` key or value in Hashtable, `null` in ArrayDeque/PriorityQueue/TreeMap (natural order) | `NullPointerException` – | Vectors, Deques, PriorityQueues, TreeMaps |
| empty ArrayDeque/LinkedList `getFirst`, `remove()`, `pop`, `element`; TreeMap `firstKey`; iterator `remove()` twice or before `next()`, ListIterator `set` after `add`/`remove` | `NoSuchElementException` –; `IllegalStateException` – | Deques, LinkedLists, TreeMaps, Iterators |
| TreeMap/TreeSet view ranges | `IllegalArgumentException: key out of range`, `fromKey out of range`, `toKey out of range`, `fromKey > toKey` | TreeMaps, Sets |
| not Comparable / mixed types | `ClassCastException: java.lang.Object cannot be cast to java.lang.Comparable`, `… Integer cannot be cast to java.lang.String` | Sorting, Sets, TreeMaps |
| EnumMap/EnumSet wrong type, not an enum, empty copy | `ClassCastException: class A != class B`, `class java.lang.String not an enum`; `IllegalArgumentException: Specified map is empty`, `Collection is empty`, `FRI > TUE`; removed entry `IllegalStateException: Entry was removed`; `new EnumMap(String.class)` `NullPointerException` – | EnumMaps |
| checked views | `ClassCastException: Attempt to insert class X element into collection with element type class Y` (`key into map with key type`, `value into map with value type`); null arguments `NullPointerException: c` / `type` | CheckedViews |
| Collections misuse | `Source does not fit in dest`; `List length = -2`; `Index: 2, Size: 2`; `Index: 0` (emptyList); `Map is non-empty` | CollectionsUtil |
| Arrays ranges | `IllegalArgumentException: fromIndex(5) > toIndex(2)`; `ArrayIndexOutOfBoundsException: Array index out of range: -1`; `copyOfRange`: `IllegalArgumentException: 3 > 1`, `ArrayIndexOutOfBoundsException` –; `copyOf(a, -1)` `NegativeArraySizeException` –; `asList(…).get(3)` `ArrayIndexOutOfBoundsException: 3` | Sorting, ArraysUtil, Lists |
| store of a wrong type | `ArrayStoreException: java.lang.Integer` (fill, LinkedList.toArray, and AbstractCollection's `toArray(T[])`, so HashSet, TreeSet and the views); ArrayList/PriorityQueue `toArray(T[])`: `ArrayStoreException` – | ArraysUtil, LinkedLists, Lists, PriorityQueues |
| Random bound; sort contract | `IllegalArgumentException: bound must be positive`; `… Comparison method violates its general contract!` | RandomSeeding, Sorting, SortCalls |

## 12. Not observable: the implementation may choose

LinkedHashMap/LinkedHashSet table sizes; the maximum table and array sizes;
the primitive sort algorithms (only the sorted result shows); the merge sort's temporary storage and
run-stack size; IdentityHashMap's order (IdentityMaps sorts its output); the seed of `new Random()`
and the hidden Random of `Collections.shuffle(l)`; and identity hash codes themselves (only H6's
use of them is fixed).

Observable but left free: which `compareTo` calls a HashMap makes to find an existing key in a
tree bin. The oracle's lookups call it on keys of the bin (a key that logs its calls sees them),
and so do its compute-family insertions before they insert; this specification pins only the
calls of H6 to H8 insertion, and LazyVM finds keys by hash and `equals` alone.

Also left free: what a HashMap compute-family call (`computeIfAbsent`, `computeIfPresent`, `compute`,
`merge`) stores, and what the map then holds, when its function changes the map structurally (for
example clears it or adds keys). The oracle can bring back entries that were removed, or store a key
that its lookups then miss; LazyVM keeps the map consistent (every key a walk shows is found), and
which entries it then holds is its own.

Re-entrant changes. Fail-fast checking is best effort (the Javadoc says so). When a callback (an
action, a comparator, a function) changes the very collection being walked, LazyVM matches the
oracle's order and timing of ConcurrentModificationException wherever this specification describes
and a test pins it. Beyond that, the exact point at which such a walk notices the change, and so
whether one more element is visited or the exception comes one call later, is left free, on three
conditions: the change never yields wrong data (a wrong element removed or returned, an element
lost or seen twice beyond what the walk's description allows), never a crash or a hang, and never
leaves the collection in a broken state without a ConcurrentModificationException.

## 13. API to provide

Every public class below and its public and protected members, from the Java SE 8 API (streams,
spliterators and `PrimitiveIterator` belong to S11; WeakHashMap is separate). Members are the ones
each class declares itself; parameter types are erased; take generics, return types and exceptions
from the Javadoc. `new(…)` is a constructor.

| Class | Kind | Members |
|---|---|---|
| `Collection` | interface extends Iterable | add(Object); addAll(Collection); clear(); contains(Object); containsAll(Collection); default removeIf(Predicate); equals(Object); hashCode(); isEmpty(); iterator(); remove(Object); removeAll(Collection); retainAll(Collection); size(); toArray(); toArray(Object[]) |
| `List` | interface extends Collection | add(Object); add(int, Object); addAll(Collection); addAll(int, Collection); clear(); contains(Object); containsAll(Collection); default replaceAll(UnaryOperator); default sort(Comparator); equals(Object); get(int); hashCode(); indexOf(Object); isEmpty(); iterator(); lastIndexOf(Object); listIterator(); listIterator(int); remove(Object); remove(int); removeAll(Collection); retainAll(Collection); set(int, Object); size(); subList(int, int); toArray(); toArray(Object[]) |
| `Set` | interface extends Collection | add(Object); addAll(Collection); clear(); contains(Object); containsAll(Collection); equals(Object); hashCode(); isEmpty(); iterator(); remove(Object); removeAll(Collection); retainAll(Collection); size(); toArray(); toArray(Object[]) |
| `SortedSet` | interface extends Set | comparator(); first(); headSet(Object); last(); subSet(Object, Object); tailSet(Object) |
| `NavigableSet` | interface extends SortedSet | ceiling(Object); descendingIterator(); descendingSet(); floor(Object); headSet(Object); headSet(Object, boolean); higher(Object); iterator(); lower(Object); pollFirst(); pollLast(); subSet(Object, Object); subSet(Object, boolean, Object, boolean); tailSet(Object); tailSet(Object, boolean) |
| `Queue` | interface extends Collection | add(Object); element(); offer(Object); peek(); poll(); remove() |
| `Deque` | interface extends Queue | add(Object); addFirst(Object); addLast(Object); contains(Object); descendingIterator(); element(); getFirst(); getLast(); iterator(); offer(Object); offerFirst(Object); offerLast(Object); peek(); peekFirst(); peekLast(); poll(); pollFirst(); pollLast(); pop(); push(Object); remove(); remove(Object); removeFirst(); removeFirstOccurrence(Object); removeLast(); removeLastOccurrence(Object); size() |
| `Map` | interface | clear(); containsKey(Object); containsValue(Object); default compute(Object, BiFunction); default computeIfAbsent(Object, Function); default computeIfPresent(Object, BiFunction); default forEach(BiConsumer); default getOrDefault(Object, Object); default merge(Object, Object, BiFunction); default putIfAbsent(Object, Object); default remove(Object, Object); default replace(Object, Object); default replace(Object, Object, Object); default replaceAll(BiFunction); entrySet(); equals(Object); get(Object); hashCode(); isEmpty(); keySet(); put(Object, Object); putAll(Map); remove(Object); size(); values() |
| `Map.Entry` | interface | equals(Object); getKey(); getValue(); hashCode(); setValue(Object); static comparingByKey(); static comparingByKey(Comparator); static comparingByValue(); static comparingByValue(Comparator) |
| `SortedMap` | interface extends Map | comparator(); entrySet(); firstKey(); headMap(Object); keySet(); lastKey(); subMap(Object, Object); tailMap(Object); values() |
| `NavigableMap` | interface extends SortedMap | ceilingEntry(Object); ceilingKey(Object); descendingKeySet(); descendingMap(); firstEntry(); floorEntry(Object); floorKey(Object); headMap(Object); headMap(Object, boolean); higherEntry(Object); higherKey(Object); lastEntry(); lowerEntry(Object); lowerKey(Object); navigableKeySet(); pollFirstEntry(); pollLastEntry(); subMap(Object, Object); subMap(Object, boolean, Object, boolean); tailMap(Object); tailMap(Object, boolean) |
| `Iterator` | interface | default forEachRemaining(Consumer); default remove(); hasNext(); next() |
| `ListIterator` | interface extends Iterator | add(Object); hasNext(); hasPrevious(); next(); nextIndex(); previous(); previousIndex(); remove(); set(Object) |
| `Enumeration` | interface | hasMoreElements(); nextElement(). Also `RandomAccess`: a marker interface with no members. |
| `Comparator` | interface | compare(Object, Object); default reversed(); default thenComparing(Comparator); default thenComparing(Function); default thenComparing(Function, Comparator); default thenComparingDouble(ToDoubleFunction); default thenComparingInt(ToIntFunction); default thenComparingLong(ToLongFunction); equals(Object); static comparing(Function); static comparing(Function, Comparator); static comparingDouble(ToDoubleFunction); static comparingInt(ToIntFunction); static comparingLong(ToLongFunction); static naturalOrder(); static nullsFirst(Comparator); static nullsLast(Comparator); static reverseOrder() |
| `AbstractCollection` | abstract class implements Collection | protected new(); add(Object); addAll(Collection); clear(); contains(Object); containsAll(Collection); isEmpty(); iterator(); remove(Object); removeAll(Collection); retainAll(Collection); size(); toArray(); toArray(Object[]); toString() |
| `AbstractList` | abstract class extends AbstractCollection implements List | protected new(); protected field modCount; add(Object); add(int, Object); addAll(int, Collection); clear(); equals(Object); get(int); hashCode(); indexOf(Object); iterator(); lastIndexOf(Object); listIterator(); listIterator(int); protected removeRange(int, int); remove(int); set(int, Object); subList(int, int) |
| `AbstractSequentialList` | abstract class extends AbstractList | protected new(); add(int, Object); addAll(int, Collection); get(int); iterator(); listIterator(int); remove(int); set(int, Object) |
| `AbstractSet` | abstract class extends AbstractCollection implements Set | protected new(); equals(Object); hashCode(); removeAll(Collection) |
| `AbstractQueue` | abstract class extends AbstractCollection implements Queue | protected new(); add(Object); addAll(Collection); clear(); element(); remove() |
| `AbstractMap` | abstract class implements Map | protected new(); clear(); containsKey(Object); containsValue(Object); entrySet(); equals(Object); get(Object); hashCode(); isEmpty(); keySet(); protected clone(); put(Object, Object); putAll(Map); remove(Object); size(); toString(); values() |
| `AbstractMap.SimpleEntry` | class implements Map.Entry, Serializable | new(Object, Object); new(Map.Entry); equals(Object); getKey(); getValue(); hashCode(); setValue(Object); toString() |
| `AbstractMap.SimpleImmutableEntry` | class implements Map.Entry, Serializable | new(Object, Object); new(Map.Entry); equals(Object); getKey(); getValue(); hashCode(); setValue(Object); toString() |
| `Dictionary` | abstract class | new(); elements(); get(Object); isEmpty(); keys(); put(Object, Object); remove(Object); size() |
| `ArrayList` | class extends AbstractList implements List, RandomAccess, Cloneable, Serializable | new(Collection); new(); new(int); add(Object); add(int, Object); addAll(Collection); addAll(int, Collection); clear(); clone(); contains(Object); ensureCapacity(int); forEach(Consumer); get(int); indexOf(Object); isEmpty(); iterator(); lastIndexOf(Object); listIterator(); listIterator(int); protected removeRange(int, int); remove(Object); remove(int); removeAll(Collection); removeIf(Predicate); replaceAll(UnaryOperator); retainAll(Collection); set(int, Object); size(); sort(Comparator); subList(int, int); toArray(); toArray(Object[]); trimToSize() |
| `LinkedList` | class extends AbstractSequentialList implements List, Deque, Cloneable, Serializable | new(); new(Collection); add(Object); add(int, Object); addAll(Collection); addAll(int, Collection); addFirst(Object); addLast(Object); clear(); clone(); contains(Object); descendingIterator(); element(); get(int); getFirst(); getLast(); indexOf(Object); lastIndexOf(Object); listIterator(int); offer(Object); offerFirst(Object); offerLast(Object); peek(); peekFirst(); peekLast(); poll(); pollFirst(); pollLast(); pop(); push(Object); remove(); remove(Object); remove(int); removeFirst(); removeFirstOccurrence(Object); removeLast(); removeLastOccurrence(Object); set(int, Object); size(); toArray(); toArray(Object[]) |
| `Vector` | class extends AbstractList implements List, RandomAccess, Cloneable, Serializable | new(Collection); new(); new(int); new(int, int); protected field elementData; protected field elementCount; protected field capacityIncrement; add(Object); add(int, Object); addAll(Collection); addAll(int, Collection); addElement(Object); capacity(); clear(); clone(); contains(Object); containsAll(Collection); copyInto(Object[]); elementAt(int); elements(); ensureCapacity(int); equals(Object); firstElement(); forEach(Consumer); get(int); hashCode(); indexOf(Object); indexOf(Object, int); insertElementAt(Object, int); isEmpty(); iterator(); lastElement(); lastIndexOf(Object); lastIndexOf(Object, int); listIterator(); listIterator(int); protected removeRange(int, int); remove(Object); remove(int); removeAll(Collection); removeAllElements(); removeElement(Object); removeElementAt(int); removeIf(Predicate); replaceAll(UnaryOperator); retainAll(Collection); set(int, Object); setElementAt(Object, int); setSize(int); size(); sort(Comparator); subList(int, int); toArray(); toArray(Object[]); toString(); trimToSize() |
| `Stack` | class extends Vector | new(); empty(); peek(); pop(); push(Object); search(Object) |
| `ArrayDeque` | class extends AbstractCollection implements Deque, Cloneable, Serializable | new(int); new(); new(Collection); add(Object); addFirst(Object); addLast(Object); clear(); clone(); contains(Object); descendingIterator(); element(); getFirst(); getLast(); isEmpty(); iterator(); offer(Object); offerFirst(Object); offerLast(Object); peek(); peekFirst(); peekLast(); poll(); pollFirst(); pollLast(); pop(); push(Object); remove(); remove(Object); removeFirst(); removeFirstOccurrence(Object); removeLast(); removeLastOccurrence(Object); size(); toArray(); toArray(Object[]) |
| `PriorityQueue` | class extends AbstractQueue implements Serializable | new(int, Comparator); new(Collection); new(PriorityQueue); new(SortedSet); new(); new(int); new(Comparator); add(Object); clear(); comparator(); contains(Object); iterator(); offer(Object); peek(); poll(); remove(Object); size(); toArray(); toArray(Object[]) |
| `HashSet` | class extends AbstractSet implements Set, Cloneable, Serializable | new(int); new(int, float); new(Collection); new(); add(Object); clear(); clone(); contains(Object); isEmpty(); iterator(); remove(Object); size() |
| `LinkedHashSet` | class extends HashSet implements Set, Cloneable, Serializable | new(Collection); new(); new(int); new(int, float) |
| `TreeSet` | class extends AbstractSet implements NavigableSet, Cloneable, Serializable | new(SortedSet); new(Collection); new(Comparator); new(); add(Object); addAll(Collection); ceiling(Object); clear(); clone(); comparator(); contains(Object); descendingIterator(); descendingSet(); first(); floor(Object); headSet(Object); headSet(Object, boolean); higher(Object); isEmpty(); iterator(); last(); lower(Object); pollFirst(); pollLast(); remove(Object); size(); subSet(Object, Object); subSet(Object, boolean, Object, boolean); tailSet(Object); tailSet(Object, boolean) |
| `HashMap` | class extends AbstractMap implements Map, Cloneable, Serializable | new(int); new(); new(Map); new(int, float); clear(); clone(); compute(Object, BiFunction); computeIfAbsent(Object, Function); computeIfPresent(Object, BiFunction); containsKey(Object); containsValue(Object); entrySet(); forEach(BiConsumer); get(Object); getOrDefault(Object, Object); isEmpty(); keySet(); merge(Object, Object, BiFunction); put(Object, Object); putAll(Map); putIfAbsent(Object, Object); remove(Object); remove(Object, Object); replace(Object, Object); replace(Object, Object, Object); replaceAll(BiFunction); size(); values() |
| `LinkedHashMap` | class extends HashMap implements Map | new(int, float, boolean); new(Map); new(int); new(int, float); new(); clear(); containsValue(Object); entrySet(); forEach(BiConsumer); get(Object); getOrDefault(Object, Object); keySet(); protected removeEldestEntry(Map.Entry); replaceAll(BiFunction); values() |
| `TreeMap` | class extends AbstractMap implements NavigableMap, Cloneable, Serializable | new(SortedMap); new(Map); new(Comparator); new(); ceilingEntry(Object); ceilingKey(Object); clear(); clone(); comparator(); containsKey(Object); containsValue(Object); descendingKeySet(); descendingMap(); entrySet(); firstEntry(); firstKey(); floorEntry(Object); floorKey(Object); forEach(BiConsumer); get(Object); headMap(Object); headMap(Object, boolean); higherEntry(Object); higherKey(Object); keySet(); lastEntry(); lastKey(); lowerEntry(Object); lowerKey(Object); navigableKeySet(); pollFirstEntry(); pollLastEntry(); put(Object, Object); putAll(Map); remove(Object); replace(Object, Object); replace(Object, Object, Object); replaceAll(BiFunction); size(); subMap(Object, Object); subMap(Object, boolean, Object, boolean); tailMap(Object); tailMap(Object, boolean); values() |
| `Hashtable` | class extends Dictionary implements Map, Cloneable, Serializable | new(Map); new(); new(int); new(int, float); clear(); clone(); compute(Object, BiFunction); computeIfAbsent(Object, Function); computeIfPresent(Object, BiFunction); contains(Object); containsKey(Object); containsValue(Object); elements(); entrySet(); equals(Object); forEach(BiConsumer); get(Object); getOrDefault(Object, Object); hashCode(); isEmpty(); keySet(); keys(); merge(Object, Object, BiFunction); protected rehash(); put(Object, Object); putAll(Map); putIfAbsent(Object, Object); remove(Object); remove(Object, Object); replace(Object, Object); replace(Object, Object, Object); replaceAll(BiFunction); size(); toString(); values() |
| `IdentityHashMap` | class extends AbstractMap implements Map, Serializable, Cloneable | new(); new(Map); new(int); clear(); clone(); containsKey(Object); containsValue(Object); entrySet(); equals(Object); forEach(BiConsumer); get(Object); hashCode(); isEmpty(); keySet(); put(Object, Object); putAll(Map); remove(Object); replaceAll(BiFunction); size(); values() |
| `EnumMap` | class extends AbstractMap implements Serializable, Cloneable | new(Class); new(Map); new(EnumMap); clear(); clone(); containsKey(Object); containsValue(Object); entrySet(); equals(Object); get(Object); hashCode(); keySet(); put(Enum, Object); putAll(Map); remove(Object); size(); values() |
| `EnumSet` | abstract class extends AbstractSet implements Cloneable, Serializable | clone(); static allOf(Class); static complementOf(EnumSet); static copyOf(Collection); static copyOf(EnumSet); static noneOf(Class); static of(Enum); static of(Enum, Enum); static of(Enum, Enum, Enum); static of(Enum, Enum, Enum, Enum); static of(Enum, Enum, Enum, Enum, Enum); static of(Enum, Enum...); static range(Enum, Enum) |
| `Arrays` | class | all static. P is each of byte, char, short, int, long, float, double; B is P or boolean. asList(Object...); binarySearch(Object[], Object), (Object[], Object, Comparator), (Object[], int, int, Object), (Object[], int, int, Object, Comparator), (P[], P), (P[], int, int, P); copyOf(Object[], int), (Object[], int, Class), (B[], int); copyOfRange(Object[], int, int), (Object[], int, int, Class), (B[], int, int); deepEquals(Object[], Object[]); deepHashCode(Object[]); deepToString(Object[]); equals(Object[], Object[]), (B[], B[]); fill(Object[], Object), (Object[], int, int, Object), (B[], B), (B[], int, int, B); hashCode(Object[]), (B[]); parallelPrefix(Object[], BinaryOperator), (Object[], int, int, BinaryOperator), (double[], DoubleBinaryOperator), (double[], int, int, DoubleBinaryOperator), (int[], IntBinaryOperator), (int[], int, int, IntBinaryOperator), (long[], LongBinaryOperator), (long[], int, int, LongBinaryOperator); parallelSetAll and setAll(Object[], IntFunction), (double[], IntToDoubleFunction), (int[], IntUnaryOperator), (long[], IntToLongFunction); parallelSort(Comparable[]), (Comparable[], int, int), (Object[], Comparator), (Object[], int, int, Comparator), (P[]), (P[], int, int); sort(Object[]), (Object[], Comparator), (Object[], int, int), (Object[], int, int, Comparator), (P[]), (P[], int, int); toString(Object[]), (B[]) |
| `Collections` | class | static field EMPTY_SET; static field EMPTY_LIST; static field EMPTY_MAP; static addAll(Collection, Object...); static asLifoQueue(Deque); static binarySearch(List, Object); static binarySearch(List, Object, Comparator); static checkedCollection(Collection, Class); static checkedList(List, Class); static checkedMap(Map, Class, Class); static checkedNavigableMap(NavigableMap, Class, Class); static checkedNavigableSet(NavigableSet, Class); static checkedQueue(Queue, Class); static checkedSet(Set, Class); static checkedSortedMap(SortedMap, Class, Class); static checkedSortedSet(SortedSet, Class); static copy(List, List); static disjoint(Collection, Collection); static emptyEnumeration(); static emptyIterator(); static emptyList(); static emptyListIterator(); static emptyMap(); static emptyNavigableMap(); static emptyNavigableSet(); static emptySet(); static emptySortedMap(); static emptySortedSet(); static enumeration(Collection); static fill(List, Object); static frequency(Collection, Object); static indexOfSubList(List, List); static lastIndexOfSubList(List, List); static list(Enumeration); static max(Collection); static max(Collection, Comparator); static min(Collection); static min(Collection, Comparator); static nCopies(int, Object); static newSetFromMap(Map); static replaceAll(List, Object, Object); static reverse(List); static reverseOrder(); static reverseOrder(Comparator); static rotate(List, int); static shuffle(List); static shuffle(List, Random); static singleton(Object); static singletonList(Object); static singletonMap(Object, Object); static sort(List); static sort(List, Comparator); static swap(List, int, int); static synchronizedCollection(Collection); static synchronizedList(List); static synchronizedMap(Map); static synchronizedNavigableMap(NavigableMap); static synchronizedNavigableSet(NavigableSet); static synchronizedSet(Set); static synchronizedSortedMap(SortedMap); static synchronizedSortedSet(SortedSet); static unmodifiableCollection(Collection); static unmodifiableList(List); static unmodifiableMap(Map); static unmodifiableNavigableMap(NavigableMap); static unmodifiableNavigableSet(NavigableSet); static unmodifiableSet(Set); static unmodifiableSortedMap(SortedMap); static unmodifiableSortedSet(SortedSet) |
| `Random` | class implements Serializable | new(); new(long); nextBoolean(); nextBytes(byte[]); nextDouble(); nextFloat(); nextGaussian(); nextInt(); nextInt(int); nextLong(); protected next(int); setSeed(long) |
| exceptions | `ConcurrentModificationException`, `NoSuchElementException`, `EmptyStackException` extend RuntimeException | CME: new(), new(String), new(Throwable), new(String, Throwable); NSEE: new(), new(String); ESE: new() |
