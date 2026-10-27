package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A red-black tree of MapNodes (RedBlack) ordered by the comparator, or natural order. Lookups
 * walk from the root calling compare(key, nodeKey) (spec 6.M1); TreeRange gives the range views.
 */
public class TreeMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V>, Cloneable, java.io.Serializable {
    private final Comparator<? super K> comparator;
    private transient MapNode<K, V> root;
    private transient int size;
    transient int modCount;
    private transient Set<Map.Entry<K, V>> entryView;
    private transient NavigableMap<K, V> mirror;

    public TreeMap() { comparator = null; }
    public TreeMap(Comparator<? super K> comparator) { this.comparator = comparator; }

    public TreeMap(Map<? extends K, ? extends V> m) {
        comparator = null;
        putAll(m);
    }

    // Spec 6.M3: a sorted map's entries are built into a tree without comparisons.
    public TreeMap(SortedMap<K, ? extends V> m) {
        comparator = m.comparator();
        build(m.size(), m.entrySet().iterator(), true, null);
    }

    @SuppressWarnings("unchecked")
    final int compare(Object a, Object b) {
        return comparator == null ? Support.NATURAL.compare(a, b) : ((Comparator<Object>) comparator).compare(a, b);
    }

    // Spec 6.M1: one compare(key, nodeKey) per node from the root, left on a negative result.
    final MapNode<K, V> locate(Object key) {
        if (comparator == null) {
            checkNatural(key);
        }
        for (MapNode<K, V> p = root; p != null;) {
            int c = compare(key, p.key);
            if (c == 0) {
                return p;
            }
            p = c < 0 ? p.left : p.right;
        }
        return null;
    }

    /** Natural order needs a non-null Comparable key, even when there is nothing to compare with. */
    private static void checkNatural(Object key) { Comparable<?> k = (Comparable<?>) Objects.requireNonNull(key); }

    /**
     * The nearest node above (`above`) or below key, or the node equal to it when `inclusive`:
     * ceiling, higher, floor and lower.
     */
    final MapNode<K, V> near(Object key, boolean above, boolean inclusive) {
        MapNode<K, V> best = null;
        for (MapNode<K, V> p = root; p != null;) {
            int c = compare(key, p.key);
            if (c == 0 && inclusive) {
                return p;
            }
            if (above ? c < 0 : c > 0) {
                best = p;
                p = above ? p.left : p.right;
            } else {
                p = above ? p.right : p.left;
            }
        }
        return best;
    }

    final MapNode<K, V> first() { return RedBlack.first(root); }
    final MapNode<K, V> last() { return RedBlack.last(root); }

    // Spec 6.M2: a node with two children takes its successor's key and value; the successor goes.
    final void discard(MapNode<K, V> p) {
        modCount++;
        size--;
        if (p.left != null && p.right != null) {
            MapNode<K, V> s = RedBlack.following(p);
            p.key = s.key;
            p.value = s.value;
            p = s;
        }
        root = RedBlack.remove(root, p);
    }

    /**
     * Spec 6.M3: builds a tree of n entries (or keys with `value`) taken in order from `it`: the
     * middle element of each range is the subtree root; an incomplete bottom level is red.
     */
    final void build(int n, Iterator<?> it, boolean entries, V value) {
        modCount++;
        size = n;
        root = buildRange(0, 0, n - 1, 31 - Integer.numberOfLeadingZeros(n + 1), it, entries, value);
    }

    @SuppressWarnings("unchecked")
    private MapNode<K, V> buildRange(int depth, int lo, int hi, int redDepth, Iterator<?> it, boolean entries, V value) {
        if (hi < lo) {
            return null;
        }
        int mid = (lo + hi) >>> 1;
        MapNode<K, V> left = buildRange(depth + 1, lo, mid - 1, redDepth, it, entries, value);
        Object next = it.next();
        MapNode<K, V> node = entries
                ? new MapNode<K, V>(((Map.Entry<K, V>) next).getKey(), ((Map.Entry<K, V>) next).getValue())
                : new MapNode<K, V>((K) next, value);
        node.red = depth == redDepth;
        node.left = left;
        if (left != null) {
            left.up = node;
        }
        node.right = buildRange(depth + 1, mid + 1, hi, redDepth, it, entries, value);
        if (node.right != null) {
            node.right.up = node;
        }
        return node;
    }

    /** Whether `other` sorts like this empty map, so it can be built in without comparisons. */
    final boolean canBuildFrom(Object other) {
        return size == 0 && other instanceof SortedMap
                && Objects.equals(comparator, ((SortedMap<?, ?>) other).comparator());
    }

    static <K, V> Map.Entry<K, V> snapshot(MapNode<K, V> e) {
        return e == null ? null : new AbstractMap.SimpleImmutableEntry<K, V>(e);
    }

    static <K> K keyOf(MapNode<K, ?> e) { return e == null ? null : e.key; }

    static <K> K keyOrFail(MapNode<K, ?> e) {
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e.key;
    }

    // ---- Map ----

    public int size() { return size; }
    public boolean containsKey(Object key) { return locate(key) != null; }

    public boolean containsValue(Object value) {
        for (MapNode<K, V> e = first(); e != null; e = RedBlack.following(e)) {
            if (Support.eq(value, e.value)) {
                return true;
            }
        }
        return false;
    }

    public V get(Object key) {
        MapNode<K, V> p = locate(key);
        return p == null ? null : p.value;
    }

    public Comparator<? super K> comparator() { return comparator; }
    public K firstKey() { return keyOrFail(first()); }
    public K lastKey() { return keyOrFail(last()); }

    @SuppressWarnings("unchecked")
    public void putAll(Map<? extends K, ? extends V> map) {
        if (map.size() != 0 && canBuildFrom(map)) {
            build(map.size(), map.entrySet().iterator(), true, null);
            return;
        }
        super.putAll(map);
    }

    // Spec 6.M1: the first put into an empty map compares the key with itself.
    public V put(K key, V value) {
        if (root == null) {
            compare(key, key);
            root = new MapNode<K, V>(key, value);
            size = 1;
            modCount++;
            return null;
        }
        if (comparator == null) {
            checkNatural(key);
        }
        MapNode<K, V> p = root;
        while (true) {
            int c = compare(key, p.key);
            if (c == 0) {
                return p.setValue(value);
            }
            MapNode<K, V> next = c < 0 ? p.left : p.right;
            if (next == null) {
                MapNode<K, V> n = new MapNode<K, V>(key, value);
                RedBlack.attach(p, n, c < 0);
                root = RedBlack.fixInsert(root, n);
                size++;
                modCount++;
                return null;
            }
            p = next;
        }
    }

    public V remove(Object key) {
        MapNode<K, V> p = locate(key);
        if (p == null) {
            return null;
        }
        V old = p.value;
        discard(p);
        return old;
    }

    public void clear() {
        modCount++;
        size = 0;
        root = null;
    }

    // Spec 6.M3: a clone is built like a copy of a sorted map, without comparisons.
    @SuppressWarnings("unchecked")
    public Object clone() {
        TreeMap<K, V> copy;
        try {
            copy = (TreeMap<K, V>) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
        copy.root = null;
        copy.size = 0;
        copy.modCount = 0;
        copy.entryView = null;
        copy.mirror = null;
        copy.build(size, entrySet().iterator(), true, null);
        return copy;
    }

    // ---- NavigableMap ----

    public Map.Entry<K, V> firstEntry() { return snapshot(first()); }
    public Map.Entry<K, V> lastEntry() { return snapshot(last()); }
    public Map.Entry<K, V> pollFirstEntry() { return poll(first()); }
    public Map.Entry<K, V> pollLastEntry() { return poll(last()); }

    private Map.Entry<K, V> poll(MapNode<K, V> e) {
        Map.Entry<K, V> out = snapshot(e);
        if (e != null) {
            discard(e);
        }
        return out;
    }

    public Map.Entry<K, V> lowerEntry(K key) { return snapshot(near(key, false, false)); }
    public K lowerKey(K key) { return keyOf(near(key, false, false)); }
    public Map.Entry<K, V> floorEntry(K key) { return snapshot(near(key, false, true)); }
    public K floorKey(K key) { return keyOf(near(key, false, true)); }
    public Map.Entry<K, V> ceilingEntry(K key) { return snapshot(near(key, true, true)); }
    public K ceilingKey(K key) { return keyOf(near(key, true, true)); }
    public Map.Entry<K, V> higherEntry(K key) { return snapshot(near(key, true, false)); }
    public K higherKey(K key) { return keyOf(near(key, true, false)); }
    public Set<K> keySet() { return navigableKeySet(); }

    public NavigableSet<K> navigableKeySet() {
        if (keyView == null) {
            keyView = new TreeViews.Keys<K>(this);
        }
        return (NavigableSet<K>) keyView;
    }

    public NavigableSet<K> descendingKeySet() { return descendingMap().navigableKeySet(); }
    public Collection<V> values() { return super.values(); }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new TreeViews.Entries<K, V>(this, this, () -> new TreeViews.Walk<K, V>(this, first(), null, true),
                    this::locate);
        }
        return entryView;
    }

    public NavigableMap<K, V> descendingMap() {
        if (mirror == null) {
            mirror = new TreeRange<K, V>(this, null, null, true);
        }
        return mirror;
    }

    public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        return new TreeRange<K, V>(this, edge(fromKey, fromInclusive), edge(toKey, toInclusive), false);
    }

    public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        return new TreeRange<K, V>(this, null, edge(toKey, inclusive), false);
    }

    public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        return new TreeRange<K, V>(this, edge(fromKey, inclusive), null, false);
    }

    private static <K> TreeRange.Edge<K> edge(K key, boolean shut) {
        return new TreeRange.Edge<K>(key, shut);
    }

    public SortedMap<K, V> subMap(K fromKey, K toKey) { return subMap(fromKey, true, toKey, false); }
    public SortedMap<K, V> headMap(K toKey) { return headMap(toKey, false); }
    public SortedMap<K, V> tailMap(K fromKey) { return tailMap(fromKey, true); }

    public boolean replace(K key, V oldValue, V newValue) {
        MapNode<K, V> p = locate(key);
        if (p == null || !Objects.equals(oldValue, p.value)) {
            return false;
        }
        p.value = newValue;
        return true;
    }

    public V replace(K key, V value) {
        MapNode<K, V> p = locate(key);
        return p == null ? null : p.setValue(value);
    }

    // Spec 9.F3: TreeMap checks for a change after every action.
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = modCount;
        for (MapNode<K, V> e = first(); e != null; e = RedBlack.following(e)) {
            action.accept(e.key, e.value);
            if (modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = modCount;
        for (MapNode<K, V> e = first(); e != null; e = RedBlack.following(e)) {
            e.value = function.apply(e.key, e.value);
            if (modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }
}
