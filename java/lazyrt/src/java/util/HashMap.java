package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A table of bins, each a sequence of nodes linked through `next` (spec 1.H1 to H5); a crowded
 * bin also holds a red-black tree over the same nodes (HashTree). Iteration walks the bins in
 * index order and each sequence front to back.
 */
public class HashMap<K, V> extends AbstractMap<K, V> implements Map<K, V>, Cloneable, java.io.Serializable {
    // Spec 1.H2: the default table size and load factor.
    private static final int DEFAULT_SIZE = 16;
    static final float DEFAULT_LOAD = 0.75f;
    /** The largest power of two an int holds; tables stop growing there. */
    private static final int LARGEST = 1 << 30;
    // Spec 1.H7: a list bin turns into a tree at these lengths, once the table has 64 bins.
    private static final int TREE_AT_PUT = 9;
    private static final int TREE_AT_COMPUTE = 8;
    private static final int MIN_TREE_TABLE = 64;

    static class Cell<K, V> extends MapNode<K, V> {
        final int hash;
        Cell<K, V> next;
        // Spec 8.C3: identity first, for the key and for the value.
        public final boolean equals(Object o) { return o == this || AbstractMap.entrySame(this, o); }

        Cell(int hash, K key, V value) {
            super(key, value);
            this.hash = hash;
        }
    }

    transient Cell<K, V>[] table;
    /** roots[i] is the tree root when bin i is a tree; the array exists once any bin was one. */
    transient Cell<K, V>[] roots;
    transient int size;
    transient int modCount;
    /** The size above which the table grows (0 until there is a table). */
    int threshold;
    /** The number of bins the first table will have; 0 for the default. */
    private transient int firstBins;
    final float loadFactor;
    private transient Set<Map.Entry<K, V>> entryView;

    public HashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal initial capacity: " + initialCapacity);
        }
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Illegal load factor: " + loadFactor);
        }
        this.loadFactor = loadFactor;
        this.firstBins = powerOfTwoAtLeast(initialCapacity);
    }

    public HashMap(int initialCapacity) { this(initialCapacity, DEFAULT_LOAD); }
    public HashMap() { this.loadFactor = DEFAULT_LOAD; }

    public HashMap(Map<? extends K, ? extends V> m) {
        this.loadFactor = DEFAULT_LOAD;
        absorb(m, false);
    }

    // Spec 1.H1: the mixed hash.
    static int mixHash(Object key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    /** The smallest power of two >= c (1 for c <= 1). */
    static int powerOfTwoAtLeast(int c) {
        int n = 1;
        while (n < c && n < LARGEST) {
            n <<= 1;
        }
        return n;
    }

    // ---- hooks for LinkedHashMap ----

    Cell<K, V> makeCell(int hash, K key, V value) { return new Cell<K, V>(hash, key, value); }
    void used(Cell<K, V> e) { }
    void added(boolean evict) { }
    void dropped(Cell<K, V> e) { }

    /** A fresh cell holding what `old` holds, to stand in its place (spec 1.H15). */
    Cell<K, V> replica(Cell<K, V> old) { return new Cell<K, V>(old.hash, old.key, old.value); }

    /** The first node in iteration order, or null. */
    Cell<K, V> firstNode() { return table == null ? null : scanFrom(0); }

    /** The node after e in iteration order, or null. */
    Cell<K, V> nextNode(Cell<K, V> e) { return e.next != null ? e.next : scanFrom((e.hash & (table.length - 1)) + 1); }

    private Cell<K, V> scanFrom(int i) {
        Cell<K, V>[] t = table;
        for (; i < t.length; i++) {
            if (t[i] != null) {
                return t[i];
            }
        }
        return null;
    }

    // ---- lookup and change ----

    final Cell<K, V> find(Object key) {
        if (table == null) {
            return null;
        }
        int h = mixHash(key);
        return findIn(h & (table.length - 1), h, key);
    }

    // Spec 8.C3: the probe key's equals is called, after a cheap identity test.
    private Cell<K, V> findIn(int i, int h, Object key) {
        for (Cell<K, V> e = table[i]; e != null; e = e.next) {
            if (e.hash == h && Objects.equals(key, e.key)) {
                return e;
            }
        }
        return null;
    }

    private boolean isTree(int i) { return roots != null && roots[i] != null; }

    /** put-style insertion (spec 1.H3a, H5): replaces, or appends and then may grow. */
    final V store(K key, V value, boolean onlyIfAbsent, boolean evict) {
        if (table == null) {
            widen();
        }
        int h = mixHash(key);
        Cell<K, V> e = findIn(h & (table.length - 1), h, key);
        if (e != null) {
            V old = e.value;
            if (!onlyIfAbsent || old == null) {
                e.value = value;
            }
            used(e);
            return old;
        }
        addNode(h, key, value, false);
        if (++size > threshold) {
            widen();
        }
        added(evict);
        return null;
    }

    /** Spec 1.H5, H7, H8: links a new node into its bin; `front` for the compute family. */
    private void addNode(int h, K key, V value, boolean front) {
        int i = h & (table.length - 1);
        Cell<K, V> n = makeCell(h, key, value);
        modCount++;
        if (isTree(i)) {
            HashTree.insert(table, roots, i, n);
            return;
        }
        int length = 1;
        if (front || table[i] == null) {
            n.next = table[i];
            table[i] = n;
            for (Cell<K, V> e = n.next; e != null; e = e.next) {
                length++;
            }
        } else {
            Cell<K, V> last = table[i];
            for (; last.next != null; last = last.next) {
                length++;
            }
            last.next = n;
            length++;
        }
        if (length >= (front ? TREE_AT_COMPUTE : TREE_AT_PUT)) {
            crowded(i);
        }
    }

    // Spec 1.H7, H3c: a crowded bin becomes a tree, except that a small table grows instead.
    private void crowded(int i) {
        if (table.length < MIN_TREE_TABLE) {
            widen();
        } else {
            roots = HashTree.plant(this, table, roots, i);
        }
    }

    /** compute-style insertion (spec 1.H3b, H5): the caller grew the table first. */
    private void addFront(int h, K key, V value) {
        addNode(h, key, value, true);
        size++;
        added(true);
    }

    // Spec 1.H3b: the compute family grows first, when the size already exceeds the threshold.
    private void growBeforeCompute() {
        if (size > threshold || table == null) {
            widen();
        }
    }

    /** Unlinks e; map-style removals may reshape a tree bin (spec 1.H9, H10, H11). */
    final void unlink(Cell<K, V> e, boolean mapStyle) {
        int i = e.hash & (table.length - 1);
        if (isTree(i)) {
            HashTree.remove(this, table, roots, i, e, mapStyle);
        } else {
            HashTree.unlinkFromSequence(table, i, e);
        }
        modCount++;
        size--;
        dropped(e);
    }

    /** Removes the node for key (if `matchValue`, only when its value equals value), map-style. */
    final Cell<K, V> removeMapping(Object key, Object value, boolean matchValue) {
        Cell<K, V> e = find(key);
        if (e == null || (matchValue && !Objects.equals(value, e.value))) {
            return null;
        }
        unlink(e, true);
        return e;
    }

    // Spec 1.H2: double the table (or make the first one), splitting each bin by one hash bit.
    final void widen() {
        Cell<K, V>[] old = table;
        int oldN = old == null ? 0 : old.length;
        int n;
        if (oldN > 0) {
            if (oldN >= LARGEST) {
                threshold = Integer.MAX_VALUE;
                return;
            }
            n = oldN << 1;
            threshold = oldN >= DEFAULT_SIZE ? threshold << 1 : 0;
        } else {
            n = firstBins > 0 ? firstBins : DEFAULT_SIZE;
            threshold = 0;
        }
        if (threshold == 0) {
            threshold = (int) (n * loadFactor);
        }
        table = newTable(n);
        Cell<K, V>[] oldRoots = roots;
        roots = oldRoots == null ? null : newTable(n);
        for (int j = 0; j < oldN; j++) {
            if (old[j] == null) {
                continue;
            }
            Cell<K, V> bin = old[j];
            old[j] = null;
            if (oldRoots != null && oldRoots[j] != null) {
                HashTree.divide(this, bin, oldRoots[j], j, oldN, table, roots);
            } else {
                Cell<K, V>[] halves = HashTree.halves(bin, oldN);
                table[j] = halves[0];
                table[j + oldN] = halves[1];
            }
        }
    }

    @SuppressWarnings("unchecked")
    static <K, V> Cell<K, V>[] newTable(int n) { return (Cell<K, V>[]) new Cell<?, ?>[n]; }

    // Spec 1.H4: a copy sizes the table for s / lf + 1 entries first, then puts each entry in order.
    final void absorb(Map<? extends K, ? extends V> m, boolean evict) {
        int s = m.size();
        if (s == 0) {
            return;
        }
        if (table == null) {
            int t = (int) (s / loadFactor + 1.0f);
            if (t > firstBins) {
                firstBins = powerOfTwoAtLeast(t);
            }
        } else if (s > threshold) {
            widen();
        }
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            store(e.getKey(), e.getValue(), false, evict);
        }
    }

    /**
     * Spec 9.F3: walks the bins of the table the call began with, seeing nodes added to bins not
     * yet visited, then reports a change. Growth empties those bins (resize), ending the walk early.
     */
    void traverse(Consumer<Cell<K, V>> visit) {
        int expected = modCount;
        Cell<K, V>[] t = table;
        for (int i = 0; t != null && i < t.length; i++) {
            for (Cell<K, V> e = t[i]; e != null; e = e.next) {
                visit.accept(e);
            }
        }
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
    }

    // ---- Map ----

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public V get(Object key) {
        Cell<K, V> e = find(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Object key) { return find(key) != null; }
    public V put(K key, V value) { return store(key, value, false, true); }
    public void putAll(Map<? extends K, ? extends V> m) { absorb(m, true); }

    public V remove(Object key) {
        Cell<K, V> e = removeMapping(key, null, false);
        return e == null ? null : e.value;
    }

    // Spec 9.F1: clearing counts as a change even when empty; the table keeps its size.
    public void clear() {
        modCount++;
        if (table != null && size > 0) {
            size = 0;
            Arrays.fill(table, null);
            roots = null;
        }
    }

    public boolean containsValue(Object value) {
        for (Cell<K, V> e = firstNode(); e != null; e = nextNode(e)) {
            if (Objects.equals(value, e.value)) {
                return true;
            }
        }
        return false;
    }

    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new HashViews.Keys<K, V>(this);
        }
        return keyView;
    }

    public Collection<V> values() {
        if (valueView == null) {
            valueView = new HashViews.Values<K, V>(this);
        }
        return valueView;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new HashViews.Entries<K, V>(this);
        }
        return entryView;
    }

    public V getOrDefault(Object key, V defaultValue) {
        Cell<K, V> e = find(key);
        return e == null ? defaultValue : e.value;
    }

    public V putIfAbsent(K key, V value) { return store(key, value, true, true); }
    public boolean remove(Object key, Object value) { return removeMapping(key, value, true) != null; }

    public boolean replace(K key, V oldValue, V newValue) {
        Cell<K, V> e = find(key);
        if (e == null || !Objects.equals(oldValue, e.value)) {
            return false;
        }
        e.value = newValue;
        used(e);
        return true;
    }

    public V replace(K key, V value) {
        Cell<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        V old = e.value;
        e.value = value;
        used(e);
        return old;
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        growBeforeCompute();
        Cell<K, V> e = find(key);
        if (e != null && e.value != null) {
            used(e);
            return e.value;
        }
        V v = mappingFunction.apply(key);
        if (v == null) {
            return null;
        }
        if (e != null) {
            e.value = v;
            used(e);
        } else {
            addFront(mixHash(key), key, v);
        }
        return v;
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Cell<K, V> e = find(key);
        if (e == null || e.value == null) {
            return null;
        }
        V v = remappingFunction.apply(key, e.value);
        settle(e, v);
        return v;
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        growBeforeCompute();
        Cell<K, V> e = find(key);
        V v = remappingFunction.apply(key, e == null ? null : e.value);
        if (e != null) {
            settle(e, v);
        } else if (v != null) {
            addFront(mixHash(key), key, v);
        }
        return v;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(remappingFunction);
        growBeforeCompute();
        Cell<K, V> e = find(key);
        if (e == null) {
            addFront(mixHash(key), key, value);
            return value;
        }
        V v = e.value == null ? value : remappingFunction.apply(e.value, value);
        settle(e, v);
        return v;
    }

    /** The end of a remapping: a value replaces e's, null removes e map-style. */
    private void settle(Cell<K, V> e, V v) {
        if (v != null) {
            e.value = v;
            used(e);
        } else {
            unlink(e, true);
        }
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        traverse(e -> action.accept(e.key, e.value));
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        traverse(e -> e.value = function.apply(e.key, e.value));
    }

    // Spec 1.H4: a clone re-inserts every entry into a table sized like a copy.
    @SuppressWarnings("unchecked")
    public Object clone() {
        HashMap<K, V> copy;
        try {
            copy = (HashMap<K, V>) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
        copy.blank();
        copy.absorb(this, false);
        return copy;
    }

    /** Empties a fresh clone without touching the original's nodes. */
    void blank() {
        table = null;
        roots = null;
        size = 0;
        modCount = 0;
        threshold = 0;
        firstBins = 0;
        entryView = null;
    }
}
