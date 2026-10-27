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

    static class Node<K, V> extends MapNode<K, V> {
        final int hash;
        Node<K, V> next;

        Node(int hash, K key, V value) {
            super(key, value);
            this.hash = hash;
        }
    }

    transient Node<K, V>[] table;
    /** roots[i] is the tree root when bin i is a tree; the array exists once any bin was one. */
    transient Node<K, V>[] roots;
    transient int size;
    transient int modCount;
    /** The size above which the table grows; before the first table, the size it will have. */
    int threshold;
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
        this.threshold = tableSizeFor(initialCapacity);
    }

    public HashMap(int initialCapacity) { this(initialCapacity, DEFAULT_LOAD); }
    public HashMap() { this.loadFactor = DEFAULT_LOAD; }

    public HashMap(Map<? extends K, ? extends V> m) {
        this.loadFactor = DEFAULT_LOAD;
        copyIn(m, false);
    }

    // Spec 1.H1: the mixed hash.
    static int spread(Object key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    /** The smallest power of two >= c (1 for c <= 1). */
    static int tableSizeFor(int c) {
        int n = 1;
        while (n < c && n < LARGEST) {
            n <<= 1;
        }
        return n;
    }

    // ---- hooks for LinkedHashMap ----

    Node<K, V> newNode(int hash, K key, V value) { return new Node<K, V>(hash, key, value); }

    void accessed(Node<K, V> e) {
    }

    void inserted(boolean evict) {
    }

    void removed(Node<K, V> e) {
    }

    /** The first node in iteration order, or null. */
    Node<K, V> firstNode() { return table == null ? null : scanFrom(0); }

    /** The node after e in iteration order, or null. */
    Node<K, V> nextNode(Node<K, V> e) { return e.next != null ? e.next : scanFrom((e.hash & (table.length - 1)) + 1); }

    private Node<K, V> scanFrom(int i) {
        Node<K, V>[] t = table;
        for (; i < t.length; i++) {
            if (t[i] != null) {
                return t[i];
            }
        }
        return null;
    }

    // ---- lookup and change ----

    final Node<K, V> find(Object key) {
        if (table == null) {
            return null;
        }
        int h = spread(key);
        return findIn(h & (table.length - 1), h, key);
    }

    // Spec 8.C3: the probe key's equals is called, after a cheap identity test.
    private Node<K, V> findIn(int i, int h, Object key) {
        for (Node<K, V> e = table[i]; e != null; e = e.next) {
            if (e.hash == h && (e.key == key || (key != null && key.equals(e.key)))) {
                return e;
            }
        }
        return null;
    }

    private boolean isTree(int i) { return roots != null && roots[i] != null; }

    /** put-style insertion (spec 1.H3a, H5): replaces, or appends and then may grow. */
    final V putValue(K key, V value, boolean onlyIfAbsent, boolean evict) {
        if (table == null) {
            resize();
        }
        int h = spread(key);
        Node<K, V> e = findIn(h & (table.length - 1), h, key);
        if (e != null) {
            V old = e.value;
            if (!onlyIfAbsent || old == null) {
                e.value = value;
            }
            accessed(e);
            return old;
        }
        addNode(h, key, value, false);
        if (++size > threshold) {
            resize();
        }
        inserted(evict);
        return null;
    }

    /** Spec 1.H5, H7, H8: links a new node into its bin; `front` for the compute family. */
    private void addNode(int h, K key, V value, boolean front) {
        int i = h & (table.length - 1);
        Node<K, V> n = newNode(h, key, value);
        modCount++;
        if (isTree(i)) {
            HashTree.insert(table, roots, i, n);
            return;
        }
        int length = 1;
        if (front || table[i] == null) {
            n.next = table[i];
            table[i] = n;
            for (Node<K, V> e = n.next; e != null; e = e.next) {
                length++;
            }
        } else {
            Node<K, V> last = table[i];
            for (; last.next != null; last = last.next) {
                length++;
            }
            last.next = n;
            length++;
        }
        if (length >= (front ? TREE_AT_COMPUTE : TREE_AT_PUT)) {
            treeifyOrGrow(i);
        }
    }

    // Spec 1.H3c: a small table grows instead of making a tree.
    private void treeifyOrGrow(int i) {
        if (table.length < MIN_TREE_TABLE) {
            resize();
            return;
        }
        if (roots == null) {
            roots = newTable(table.length);
        }
        HashTree.treeify(table, roots, i);
    }

    /** compute-style insertion (spec 1.H3b, H5): the caller grew the table first. */
    private void addFront(int h, K key, V value) {
        addNode(h, key, value, true);
        size++;
        inserted(true);
    }

    // Spec 1.H3b: the compute family grows first, when the size already exceeds the threshold.
    private void growBeforeCompute() {
        if (size > threshold || table == null) {
            resize();
        }
    }

    /** Unlinks e; map-style removals may reshape a tree bin (spec 1.H9, H10, H11). */
    final void unlink(Node<K, V> e, boolean mapStyle) {
        int i = e.hash & (table.length - 1);
        if (isTree(i)) {
            HashTree.remove(table, roots, i, e, mapStyle);
        } else {
            HashTree.unlinkFromSequence(table, i, e);
        }
        modCount++;
        size--;
        removed(e);
    }

    /** Removes the node for key (if `matchValue`, only when its value equals value), map-style. */
    final Node<K, V> removeKey(Object key, Object value, boolean matchValue) {
        Node<K, V> e = find(key);
        if (e == null || (matchValue && !(e.value == value || (value != null && value.equals(e.value))))) {
            return null;
        }
        unlink(e, true);
        return e;
    }

    // Spec 1.H2: double the table (or make the first one), splitting each bin by one hash bit.
    final void resize() {
        Node<K, V>[] old = table;
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
            n = threshold > 0 ? threshold : DEFAULT_SIZE;
            threshold = 0;
        }
        if (threshold == 0) {
            threshold = (int) (n * loadFactor);
        }
        table = newTable(n);
        Node<K, V>[] oldRoots = roots;
        roots = oldRoots == null ? null : newTable(n);
        for (int j = 0; j < oldN; j++) {
            if (old[j] == null) {
                continue;
            }
            if (oldRoots != null && oldRoots[j] != null) {
                HashTree.split(old[j], oldRoots[j], j, oldN, table, roots);
            } else {
                Node<K, V>[] halves = HashTree.splitSequence(old[j], oldN);
                table[j] = halves[0];
                table[j + oldN] = halves[1];
            }
        }
    }

    @SuppressWarnings("unchecked")
    static <K, V> Node<K, V>[] newTable(int n) { return (Node<K, V>[]) new Node<?, ?>[n]; }

    // Spec 1.H4: a copy sizes the table for s / lf + 1 entries first, then puts each entry in order.
    final void copyIn(Map<? extends K, ? extends V> m, boolean evict) {
        int s = m.size();
        if (s == 0) {
            return;
        }
        if (table == null) {
            int t = (int) (s / loadFactor + 1.0f);
            if (t > threshold) {
                threshold = tableSizeFor(t);
            }
        } else if (s > threshold) {
            resize();
        }
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            putValue(e.getKey(), e.getValue(), false, evict);
        }
    }

    /** Spec 9.F3: visits every node, including ones added meanwhile, then reports a change. */
    final void traverse(Consumer<Node<K, V>> visit) {
        int expected = modCount;
        for (Node<K, V> e = firstNode(); e != null; e = nextNode(e)) {
            visit.accept(e);
        }
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
    }

    // ---- Map ----

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public V get(Object key) {
        Node<K, V> e = find(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Object key) { return find(key) != null; }
    public V put(K key, V value) { return putValue(key, value, false, true); }
    public void putAll(Map<? extends K, ? extends V> m) { copyIn(m, true); }

    public V remove(Object key) {
        Node<K, V> e = removeKey(key, null, false);
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
        for (Node<K, V> e = firstNode(); e != null; e = nextNode(e)) {
            if (Support.eq(value, e.value)) {
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
        Node<K, V> e = find(key);
        return e == null ? defaultValue : e.value;
    }

    public V putIfAbsent(K key, V value) { return putValue(key, value, true, true); }
    public boolean remove(Object key, Object value) { return removeKey(key, value, true) != null; }

    public boolean replace(K key, V oldValue, V newValue) {
        Node<K, V> e = find(key);
        if (e == null || !(e.value == oldValue || (oldValue != null && oldValue.equals(e.value)))) {
            return false;
        }
        e.value = newValue;
        accessed(e);
        return true;
    }

    public V replace(K key, V value) {
        Node<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        V old = e.value;
        e.value = value;
        accessed(e);
        return old;
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        growBeforeCompute();
        Node<K, V> e = find(key);
        if (e != null && e.value != null) {
            accessed(e);
            return e.value;
        }
        V v = mappingFunction.apply(key);
        if (v == null) {
            return null;
        }
        if (e != null) {
            e.value = v;
            accessed(e);
        } else {
            addFront(spread(key), key, v);
        }
        return v;
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Node<K, V> e = find(key);
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
        Node<K, V> e = find(key);
        V v = remappingFunction.apply(key, e == null ? null : e.value);
        if (e != null) {
            settle(e, v);
        } else if (v != null) {
            addFront(spread(key), key, v);
        }
        return v;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(remappingFunction);
        growBeforeCompute();
        Node<K, V> e = find(key);
        if (e == null) {
            addFront(spread(key), key, value);
            return value;
        }
        V v = e.value == null ? value : remappingFunction.apply(e.value, value);
        settle(e, v);
        return v;
    }

    /** The end of a remapping: a value replaces e's, null removes e map-style. */
    private void settle(Node<K, V> e, V v) {
        if (v != null) {
            e.value = v;
            accessed(e);
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
        copy.reset();
        copy.copyIn(this, false);
        return copy;
    }

    /** Empties a fresh clone without touching the original's nodes. */
    void reset() {
        table = null;
        roots = null;
        size = 0;
        modCount = 0;
        threshold = 0;
        entryView = null;
    }
}
