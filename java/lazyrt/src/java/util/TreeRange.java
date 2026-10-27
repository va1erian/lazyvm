package java.util;

/**
 * A live view of the keys of a TreeMap between two optional bounds, in ascending or descending
 * order: subMap, headMap, tailMap and descendingMap. Spec 11 gives the range messages.
 */
final class TreeRange<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V>, java.io.Serializable {
    private final TreeMap<K, V> m;
    private final boolean fromStart;
    private final K lo;
    private final boolean loIn;
    private final boolean toEnd;
    private final K hi;
    private final boolean hiIn;
    private final boolean descending;
    private transient int count = -1;
    private transient int countedAt;

    TreeRange(TreeMap<K, V> m, boolean fromStart, K lo, boolean loIn, boolean toEnd, K hi, boolean hiIn,
            boolean descending) {
        if (!fromStart && !toEnd) {
            if (m.compare(lo, hi) > 0) {
                throw new IllegalArgumentException("fromKey > toKey");
            }
        } else {
            if (!fromStart) {
                m.compare(lo, lo);
            }
            if (!toEnd) {
                m.compare(hi, hi);
            }
        }
        this.m = m;
        this.fromStart = fromStart;
        this.lo = lo;
        this.loIn = loIn;
        this.toEnd = toEnd;
        this.hi = hi;
        this.hiIn = hiIn;
        this.descending = descending;
    }

    // ---- bounds, in the map's own (ascending) order ----

    private boolean tooLow(Object key) {
        if (fromStart) {
            return false;
        }
        int c = m.compare(key, lo);
        return c < 0 || (c == 0 && !loIn);
    }

    private boolean tooHigh(Object key) {
        if (toEnd) {
            return false;
        }
        int c = m.compare(key, hi);
        return c > 0 || (c == 0 && !hiIn);
    }

    private boolean inRange(Object key) { return !tooLow(key) && !tooHigh(key); }

    /** For the bounds of a narrower view: an exclusive bound may sit on this view's own bound. */
    private boolean admits(Object key, boolean inclusive) {
        if (inclusive) {
            return inRange(key);
        }
        return (fromStart || m.compare(key, lo) >= 0) && (toEnd || m.compare(hi, key) >= 0);
    }

    private MapNode<K, V> lowest() {
        MapNode<K, V> e = fromStart ? m.first() : m.near(lo, true, loIn);
        return e == null || tooHigh(e.key) ? null : e;
    }

    private MapNode<K, V> highest() {
        MapNode<K, V> e = toEnd ? m.last() : m.near(hi, false, hiIn);
        return e == null || tooLow(e.key) ? null : e;
    }

    /** The nearest in-range node above or below key, as TreeMap.near. */
    private MapNode<K, V> near(Object key, boolean above, boolean inclusive) {
        if (above ? tooLow(key) : tooHigh(key)) {
            return above ? lowest() : highest();
        }
        MapNode<K, V> e = m.near(key, above, inclusive);
        return e == null || (above ? tooHigh(e.key) : tooLow(e.key)) ? null : e;
    }

    // ---- the same in this view's order ----

    private MapNode<K, V> firstNode() { return descending ? highest() : lowest(); }
    private MapNode<K, V> lastNode() { return descending ? lowest() : highest(); }

    /** ceiling (`up`, inclusive), higher, floor and lower in this view's order. */
    private MapNode<K, V> step(Object key, boolean up, boolean inclusive) {
        return near(key, up != descending, inclusive);
    }

    /** A walk from the first node in this view's order to the fence: the first node past the range. */
    private Iterator<Map.Entry<K, V>> walk() {
        MapNode<K, V> start = firstNode();
        MapNode<K, V> fence;
        if (descending) {
            fence = fromStart ? null : m.near(lo, false, !loIn);
        } else {
            fence = toEnd ? null : m.near(hi, true, !hiIn);
        }
        return new TreeViews.Walk<K, V>(m, start, fence, !descending);
    }

    /** The node for key if it is in range, else null. */
    private MapNode<K, V> nodeFor(Object key) { return inRange(key) ? m.getNode(key) : null; }

    private TreeRange<K, V> view(boolean fromStart, K lo, boolean loIn, boolean toEnd, K hi, boolean hiIn) {
        return new TreeRange<K, V>(m, fromStart, lo, loIn, toEnd, hi, hiIn, descending);
    }

    // ---- Map ----

    /** Counts the range, and counts again only after the map changed. */
    public int size() {
        if (fromStart && toEnd) {
            return m.size();
        }
        if (countedAt != m.modCount || count < 0) {
            countedAt = m.modCount;
            count = 0;
            for (Iterator<Map.Entry<K, V>> it = walk(); it.hasNext(); it.next()) {
                count++;
            }
        }
        return count;
    }

    public boolean containsKey(Object key) { return inRange(key) && m.containsKey(key); }
    public V get(Object key) { return inRange(key) ? m.get(key) : null; }

    public V put(K key, V value) {
        if (!inRange(key)) {
            throw new IllegalArgumentException("key out of range");
        }
        return m.put(key, value);
    }

    public V remove(Object key) { return inRange(key) ? m.remove(key) : null; }
    public Set<Map.Entry<K, V>> entrySet() { return new TreeViews.Entries<K, V>(this, m, this::walk, this::nodeFor); }

    public Comparator<? super K> comparator() {
        return descending ? Collections.reverseOrder(m.comparator()) : m.comparator();
    }

    public K firstKey() { return TreeMap.keyOrThrow(firstNode()); }
    public K lastKey() { return TreeMap.keyOrThrow(lastNode()); }
    public Map.Entry<K, V> firstEntry() { return TreeMap.snapshot(firstNode()); }
    public Map.Entry<K, V> lastEntry() { return TreeMap.snapshot(lastNode()); }
    public Map.Entry<K, V> lowerEntry(K key) { return TreeMap.snapshot(step(key, false, false)); }
    public K lowerKey(K key) { return TreeMap.keyOrNull(step(key, false, false)); }
    public Map.Entry<K, V> floorEntry(K key) { return TreeMap.snapshot(step(key, false, true)); }
    public K floorKey(K key) { return TreeMap.keyOrNull(step(key, false, true)); }
    public Map.Entry<K, V> ceilingEntry(K key) { return TreeMap.snapshot(step(key, true, true)); }
    public K ceilingKey(K key) { return TreeMap.keyOrNull(step(key, true, true)); }
    public Map.Entry<K, V> higherEntry(K key) { return TreeMap.snapshot(step(key, true, false)); }
    public K higherKey(K key) { return TreeMap.keyOrNull(step(key, true, false)); }
    public Map.Entry<K, V> pollFirstEntry() { return poll(firstNode()); }
    public Map.Entry<K, V> pollLastEntry() { return poll(lastNode()); }

    private Map.Entry<K, V> poll(MapNode<K, V> e) {
        Map.Entry<K, V> out = TreeMap.snapshot(e);
        if (e != null) {
            m.deleteNode(e);
        }
        return out;
    }

    public NavigableMap<K, V> descendingMap() {
        return new TreeRange<K, V>(m, fromStart, lo, loIn, toEnd, hi, hiIn, !descending);
    }

    public Set<K> keySet() { return navigableKeySet(); }
    public NavigableSet<K> navigableKeySet() { return new TreeViews.Keys<K>(this); }
    public NavigableSet<K> descendingKeySet() { return descendingMap().navigableKeySet(); }

    // Spec 11: a narrower view's bounds must lie in this one; in a descending view `from` is the high end.
    public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        if (!admits(fromKey, fromInclusive)) {
            throw new IllegalArgumentException("fromKey out of range");
        }
        if (!admits(toKey, toInclusive)) {
            throw new IllegalArgumentException("toKey out of range");
        }
        return descending
                ? view(false, toKey, toInclusive, false, fromKey, fromInclusive)
                : view(false, fromKey, fromInclusive, false, toKey, toInclusive);
    }

    public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        if (!admits(toKey, inclusive)) {
            throw new IllegalArgumentException("toKey out of range");
        }
        return descending
                ? view(false, toKey, inclusive, toEnd, hi, hiIn)
                : view(fromStart, lo, loIn, false, toKey, inclusive);
    }

    public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        if (!admits(fromKey, inclusive)) {
            throw new IllegalArgumentException("fromKey out of range");
        }
        return descending
                ? view(fromStart, lo, loIn, false, fromKey, inclusive)
                : view(false, fromKey, inclusive, toEnd, hi, hiIn);
    }

    public SortedMap<K, V> subMap(K fromKey, K toKey) { return subMap(fromKey, true, toKey, false); }
    public SortedMap<K, V> headMap(K toKey) { return headMap(toKey, false); }
    public SortedMap<K, V> tailMap(K fromKey) { return tailMap(fromKey, true); }
}
