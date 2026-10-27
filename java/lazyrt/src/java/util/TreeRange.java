package java.util;

/**
 * A live view of the keys of a TreeMap between two optional ends, in key order or reversed:
 * subMap, headMap, tailMap and descendingMap (spec 6.M4). Spec 11 gives the range messages.
 */
final class TreeRange<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V>, java.io.Serializable {
    /** One end of a range: a key, and whether that key itself belongs to the range. */
    static final class Edge<K> {
        final K key;
        final boolean shut;

        Edge(K key, boolean shut) {
            this.key = key;
            this.shut = shut;
        }
    }

    private final TreeMap<K, V> tree;
    /** The ends in the map's own order; null where the range is open. */
    private final Edge<K> low;
    private final Edge<K> high;
    private final boolean reversed;
    private transient int count = -1;
    private transient int countedAt;
    private transient TreeRange<K, V> mirror;
    private transient Set<Map.Entry<K, V>> entries;

    TreeRange(TreeMap<K, V> tree, Edge<K> low, Edge<K> high, boolean reversed) {
        // Spec 6.M4: making a view compares its ends, the low key with the high one, or a lone
        // end's key with itself, so a key the order cannot compare fails at once.
        if (low != null || high != null) {
            K a = (low != null ? low : high).key;
            K b = (high != null ? high : low).key;
            if (tree.compare(a, b) > 0) {
                throw new IllegalArgumentException("fromKey > toKey");
            }
        }
        this.tree = tree;
        this.low = low;
        this.high = high;
        this.reversed = reversed;
    }

    // ---- the ends, in the map's own order ----

    private boolean under(Object key) {
        if (low == null) {
            return false;
        }
        int c = tree.compare(key, low.key);
        return c < 0 || (c == 0 && !low.shut);
    }

    private boolean over(Object key) {
        if (high == null) {
            return false;
        }
        int c = tree.compare(key, high.key);
        return c > 0 || (c == 0 && !high.shut);
    }

    private boolean holds(Object key) { return !under(key) && !over(key); }

    /** For an end of a narrower view: an open end may sit on this view's own end key. */
    private boolean admits(Object key, boolean shut) {
        if (shut) {
            return holds(key);
        }
        return (low == null || tree.compare(key, low.key) >= 0) && (high == null || tree.compare(high.key, key) >= 0);
    }

    private MapNode<K, V> bottom() {
        MapNode<K, V> e = low == null ? tree.first() : tree.near(low.key, true, low.shut);
        return e == null || over(e.key) ? null : e;
    }

    private MapNode<K, V> top() {
        MapNode<K, V> e = high == null ? tree.last() : tree.near(high.key, false, high.shut);
        return e == null || under(e.key) ? null : e;
    }

    /** The nearest node of the range above or below key, as TreeMap.near. */
    private MapNode<K, V> near(Object key, boolean above, boolean inclusive) {
        if (above ? under(key) : over(key)) {
            return above ? bottom() : top();
        }
        MapNode<K, V> e = tree.near(key, above, inclusive);
        return e == null || (above ? over(e.key) : under(e.key)) ? null : e;
    }

    // ---- the same in this view's order ----

    private MapNode<K, V> leading() { return reversed ? top() : bottom(); }
    private MapNode<K, V> trailing() { return reversed ? bottom() : top(); }

    /** ceiling (`up`, inclusive), higher, floor and lower in this view's order. */
    private MapNode<K, V> step(Object key, boolean up, boolean inclusive) { return near(key, up != reversed, inclusive); }

    /** Spec 6.M4: a walk finds its first node, then the first node past the far end, where it stops. */
    private Iterator<Map.Entry<K, V>> walk() {
        MapNode<K, V> start = leading();
        Edge<K> far = reversed ? low : high;
        MapNode<K, V> stop = far == null ? null : tree.near(far.key, !reversed, !far.shut);
        return new TreeViews.Walk<K, V>(tree, start, stop, !reversed);
    }

    private MapNode<K, V> inside(Object key) { return holds(key) ? tree.locate(key) : null; }

    private TreeRange<K, V> narrowed(Edge<K> newLow, Edge<K> newHigh) {
        return new TreeRange<K, V>(tree, newLow, newHigh, reversed);
    }

    // ---- Map ----

    /** Counts the range, and counts again only after the map changed (spec 6.M4). */
    public int size() {
        if (low == null && high == null) {
            return tree.size();
        }
        if (countedAt != tree.modCount || count < 0) {
            countedAt = tree.modCount;
            count = 0;
            for (Iterator<Map.Entry<K, V>> it = walk(); it.hasNext(); it.next()) {
                count++;
            }
        }
        return count;
    }

    public boolean containsKey(Object key) { return holds(key) && tree.containsKey(key); }
    public V get(Object key) { return holds(key) ? tree.get(key) : null; }

    public V put(K key, V value) {
        if (!holds(key)) {
            throw new IllegalArgumentException("key out of range");
        }
        return tree.put(key, value);
    }

    public V remove(Object key) { return holds(key) ? tree.remove(key) : null; }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entries == null) {
            entries = new TreeViews.Entries<K, V>(this, tree, this::walk, this::inside);
        }
        return entries;
    }

    public Comparator<? super K> comparator() {
        return reversed ? Collections.reverseOrder(tree.comparator()) : tree.comparator();
    }

    public K firstKey() { return TreeMap.keyOrFail(leading()); }
    public K lastKey() { return TreeMap.keyOrFail(trailing()); }
    public Map.Entry<K, V> firstEntry() { return TreeMap.snapshot(leading()); }
    public Map.Entry<K, V> lastEntry() { return TreeMap.snapshot(trailing()); }
    public Map.Entry<K, V> lowerEntry(K key) { return TreeMap.snapshot(step(key, false, false)); }
    public K lowerKey(K key) { return TreeMap.keyOf(step(key, false, false)); }
    public Map.Entry<K, V> floorEntry(K key) { return TreeMap.snapshot(step(key, false, true)); }
    public K floorKey(K key) { return TreeMap.keyOf(step(key, false, true)); }
    public Map.Entry<K, V> ceilingEntry(K key) { return TreeMap.snapshot(step(key, true, true)); }
    public K ceilingKey(K key) { return TreeMap.keyOf(step(key, true, true)); }
    public Map.Entry<K, V> higherEntry(K key) { return TreeMap.snapshot(step(key, true, false)); }
    public K higherKey(K key) { return TreeMap.keyOf(step(key, true, false)); }
    public Map.Entry<K, V> pollFirstEntry() { return poll(leading()); }
    public Map.Entry<K, V> pollLastEntry() { return poll(trailing()); }

    private Map.Entry<K, V> poll(MapNode<K, V> e) {
        Map.Entry<K, V> out = TreeMap.snapshot(e);
        if (e != null) {
            tree.discard(e);
        }
        return out;
    }

    // Spec 6.M4: a view keeps the one reversed view, key set and entry set it hands out.
    public NavigableMap<K, V> descendingMap() {
        if (mirror == null) {
            mirror = new TreeRange<K, V>(tree, low, high, !reversed);
        }
        return mirror;
    }

    public Set<K> keySet() { return navigableKeySet(); }

    public NavigableSet<K> navigableKeySet() {
        if (keyView == null) {
            keyView = new TreeViews.Keys<K>(this);
        }
        return (NavigableSet<K>) keyView;
    }

    public NavigableSet<K> descendingKeySet() { return descendingMap().navigableKeySet(); }

    // Spec 11: a narrower view's ends must lie in this one; in a reversed view `from` is the high end.
    public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        if (!admits(fromKey, fromInclusive)) {
            throw new IllegalArgumentException("fromKey out of range");
        }
        if (!admits(toKey, toInclusive)) {
            throw new IllegalArgumentException("toKey out of range");
        }
        Edge<K> from = new Edge<K>(fromKey, fromInclusive);
        Edge<K> to = new Edge<K>(toKey, toInclusive);
        return reversed ? narrowed(to, from) : narrowed(from, to);
    }

    public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        if (!admits(toKey, inclusive)) {
            throw new IllegalArgumentException("toKey out of range");
        }
        Edge<K> to = new Edge<K>(toKey, inclusive);
        return reversed ? narrowed(to, high) : narrowed(low, to);
    }

    public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        if (!admits(fromKey, inclusive)) {
            throw new IllegalArgumentException("fromKey out of range");
        }
        Edge<K> from = new Edge<K>(fromKey, inclusive);
        return reversed ? narrowed(low, from) : narrowed(from, high);
    }

    public SortedMap<K, V> subMap(K fromKey, K toKey) { return subMap(fromKey, true, toKey, false); }
    public SortedMap<K, V> headMap(K toKey) { return headMap(toKey, false); }
    public SortedMap<K, V> tailMap(K fromKey) { return tailMap(fromKey, true); }
}
