package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A key range of a {@link ConcurrentSkipListMap}, in either direction. The bounds are in the
 * map's own order whatever the direction; {@code descending} only swaps what first and last mean.
 */
final class SkipView<K, V> extends AbstractMap<K, V> implements ConcurrentNavigableMap<K, V> {
    private final ConcurrentSkipListMap<K, V> map;
    private final K lo;
    private final boolean loInclusive;
    private final K hi;
    private final boolean hiInclusive;
    private final boolean descending;
    private NavigableSet<K> keys;
    private Collection<V> values;
    private Set<Map.Entry<K, V>> entries;
    private ConcurrentNavigableMap<K, V> reversed;

    SkipView(ConcurrentSkipListMap<K, V> map) {
        this(map, null, false, null, false, false);
    }

    private SkipView(ConcurrentSkipListMap<K, V> map, K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean descending) {
        this.map = map;
        this.lo = lo;
        this.loInclusive = loInclusive;
        this.hi = hi;
        this.hiInclusive = hiInclusive;
        this.descending = descending;
        if (lo != null && hi != null && order(lo, hi) > 0) {
            throw new IllegalArgumentException("inconsistent range");
        }
    }

    @SuppressWarnings("unchecked")
    private int order(Object a, Object b) {
        Comparator<? super K> c = map.comparator();
        return c != null ? c.compare((K) a, (K) b) : ((Comparable<Object>) a).compareTo(b);
    }

    private boolean tooLow(Object key) {
        if (lo == null) {
            return false;
        }
        int c = order(key, lo);
        return c < 0 || (c == 0 && !loInclusive);
    }

    private boolean tooHigh(Object key) {
        if (hi == null) {
            return false;
        }
        int c = order(key, hi);
        return c > 0 || (c == 0 && !hiInclusive);
    }

    private boolean inRange(Object key) {
        return !tooLow(key) && !tooHigh(key);
    }

    private K checked(K key) {
        Objects.requireNonNull(key);
        if (!inRange(key)) {
            throw new IllegalArgumentException("key out of range");
        }
        return key;
    }

    // In the map's own order, ignoring direction: the entry nearest an end of the range.

    private Map.Entry<K, V> lowest() {
        Map.Entry<K, V> e = lo == null ? map.firstEntry() : loInclusive ? map.ceilingEntry(lo) : map.higherEntry(lo);
        return e == null || tooHigh(e.getKey()) ? null : e;
    }

    private Map.Entry<K, V> highest() {
        Map.Entry<K, V> e = hi == null ? map.lastEntry() : hiInclusive ? map.floorEntry(hi) : map.lowerEntry(hi);
        return e == null || tooLow(e.getKey()) ? null : e;
    }

    /** The next entry above {@code key} if {@code up}, else below it; {@code key} itself if {@code orEqual}. */
    private Map.Entry<K, V> near(K key, boolean up, boolean orEqual) {
        Objects.requireNonNull(key);
        if (up && tooLow(key)) {
            return lowest();
        }
        if (!up && tooHigh(key)) {
            return highest();
        }
        Map.Entry<K, V> e = up
                ? (orEqual ? map.ceilingEntry(key) : map.higherEntry(key))
                : (orEqual ? map.floorEntry(key) : map.lowerEntry(key));
        return e == null || !inRange(e.getKey()) ? null : e;
    }

    private Map.Entry<K, V> pollEnd(boolean first) {
        for (;;) {
            Map.Entry<K, V> e = first != descending ? lowest() : highest();
            if (e == null || map.remove(e.getKey(), e.getValue())) {
                return e;
            }
        }
    }

    private K present(Map.Entry<K, V> e) {
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e.getKey();
    }

    public boolean containsKey(Object key) {
        Objects.requireNonNull(key);
        return inRange(key) && map.containsKey(key);
    }

    public V get(Object key) {
        Objects.requireNonNull(key);
        return inRange(key) ? map.get(key) : null;
    }

    public V put(K key, V value) {
        return map.put(checked(key), value);
    }

    public V remove(Object key) {
        Objects.requireNonNull(key);
        return inRange(key) ? map.remove(key) : null;
    }

    public int size() {
        int n = 0;
        for (Walk<K> w = keyWalk(); w.hasNext(); w.next()) {
            n++;
        }
        return n;
    }

    public boolean isEmpty() {
        return lowest() == null;
    }

    public boolean containsValue(Object value) {
        Objects.requireNonNull(value);
        for (Walk<V> w = valueWalk(); w.hasNext();) {
            if (value.equals(w.next())) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        while (pollEnd(true) != null) {
            // Each poll takes one more; another thread may be adding.
        }
    }

    public V putIfAbsent(K key, V value) {
        return map.putIfAbsent(checked(key), value);
    }

    public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key);
        return inRange(key) && map.remove(key, value);
    }

    public boolean replace(K key, V oldValue, V newValue) {
        return map.replace(checked(key), oldValue, newValue);
    }

    public V replace(K key, V value) {
        return map.replace(checked(key), value);
    }

    public Comparator<? super K> comparator() {
        return descending ? Collections.reverseOrder(map.comparator()) : map.comparator();
    }

    public K firstKey() {
        return present(firstEntry());
    }

    public K lastKey() {
        return present(lastEntry());
    }

    public Map.Entry<K, V> lowerEntry(K key) {
        return near(key, descending, false);
    }

    public K lowerKey(K key) {
        return ConcurrentSkipListMap.keyOf(lowerEntry(key));
    }

    public Map.Entry<K, V> floorEntry(K key) {
        return near(key, descending, true);
    }

    public K floorKey(K key) {
        return ConcurrentSkipListMap.keyOf(floorEntry(key));
    }

    public Map.Entry<K, V> ceilingEntry(K key) {
        return near(key, !descending, true);
    }

    public K ceilingKey(K key) {
        return ConcurrentSkipListMap.keyOf(ceilingEntry(key));
    }

    public Map.Entry<K, V> higherEntry(K key) {
        return near(key, !descending, false);
    }

    public K higherKey(K key) {
        return ConcurrentSkipListMap.keyOf(higherEntry(key));
    }

    public Map.Entry<K, V> firstEntry() {
        return descending ? highest() : lowest();
    }

    public Map.Entry<K, V> lastEntry() {
        return descending ? lowest() : highest();
    }

    public Map.Entry<K, V> pollFirstEntry() {
        return pollEnd(true);
    }

    public Map.Entry<K, V> pollLastEntry() {
        return pollEnd(false);
    }

    /** A view of this one, narrower in the map's own order; a null bound keeps the current one. */
    private SkipView<K, V> narrowed(K newLo, boolean newLoInclusive, K newHi, boolean newHiInclusive, boolean direction) {
        K l = lo;
        boolean li = loInclusive;
        K h = hi;
        boolean hi2 = hiInclusive;
        if (newLo != null) {
            if (lo != null && beyond(order(newLo, lo), loInclusive, newLoInclusive, true)) {
                throw new IllegalArgumentException("key out of range");
            }
            l = newLo;
            li = newLoInclusive;
        }
        if (newHi != null) {
            if (hi != null && beyond(order(newHi, hi), hiInclusive, newHiInclusive, false)) {
                throw new IllegalArgumentException("key out of range");
            }
            h = newHi;
            hi2 = newHiInclusive;
        }
        return new SkipView<>(map, l, li, h, hi2, direction);
    }

    /**
     * Whether a new bound is outside the old bound on its own side: {@code c} compares them. Only
     * that side counts here; a new bound past the far end is then an inconsistent range. It may
     * sit on an excluded old bound only if it excludes it too.
     */
    private static boolean beyond(int c, boolean oldInclusive, boolean newInclusive, boolean lower) {
        return (lower ? c < 0 : c > 0) || (c == 0 && !oldInclusive && newInclusive);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        Objects.requireNonNull(fromKey);
        Objects.requireNonNull(toKey);
        return descending
                ? narrowed(toKey, toInclusive, fromKey, fromInclusive, true)
                : narrowed(fromKey, fromInclusive, toKey, toInclusive, false);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        Objects.requireNonNull(toKey);
        return descending ? narrowed(toKey, inclusive, null, false, true) : narrowed(null, false, toKey, inclusive, false);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        Objects.requireNonNull(fromKey);
        return descending ? narrowed(null, false, fromKey, inclusive, true) : narrowed(fromKey, inclusive, null, false, false);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
        return subMap(fromKey, true, toKey, false);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey) {
        return headMap(toKey, false);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
        return tailMap(fromKey, true);
    }

    public ConcurrentNavigableMap<K, V> descendingMap() {
        if (reversed == null) {
            reversed = narrowed(null, false, null, false, !descending);
        }
        return reversed;
    }

    public NavigableSet<K> navigableKeySet() {
        return keySet();
    }

    public NavigableSet<K> keySet() {
        if (keys == null) {
            keys = new SkipKeys<>(this);
        }
        return keys;
    }

    public NavigableSet<K> descendingKeySet() {
        return descendingMap().navigableKeySet();
    }

    public Collection<V> values() {
        if (values == null) {
            values = new ValueView();
        }
        return values;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entries == null) {
            entries = new EntryView();
        }
        return entries;
    }

    private final class ValueView extends AbstractCollection<V> {
        @Override
        public Iterator<V> iterator() {
            return valueWalk();
        }

        @Override
        public int size() {
            return SkipView.this.size();
        }

        @Override
        public boolean contains(Object o) {
            return containsValue(o);
        }

        @Override
        public void clear() {
            SkipView.this.clear();
        }
    }

    private final class EntryView extends AbstractSet<Map.Entry<K, V>> {
        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new Walk<Map.Entry<K, V>>() {
                @Override
                Map.Entry<K, V> make(Map.Entry<K, V> e) {
                    return e;
                }
            };
        }

        @Override
        public int size() {
            return SkipView.this.size();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            V v = get(e.getKey());
            return v != null && v.equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return SkipView.this.remove(e.getKey(), e.getValue());
        }

        @Override
        public void clear() {
            SkipView.this.clear();
        }
    }

    Walk<K> keyWalk() {
        return new Walk<K>() {
            @Override
            K make(Map.Entry<K, V> e) {
                return e.getKey();
            }
        };
    }

    private Walk<V> valueWalk() {
        return new Walk<V>() {
            @Override
            V make(Map.Entry<K, V> e) {
                return e.getValue();
            }
        };
    }

    /** Finds its next step by searching from the last key, so nothing can invalidate it (G6). */
    abstract class Walk<E> implements Iterator<E> {
        private Map.Entry<K, V> next = firstEntry();
        private K last;

        abstract E make(Map.Entry<K, V> e);

        public final boolean hasNext() {
            return next != null;
        }

        public final E next() {
            Map.Entry<K, V> e = next;
            if (e == null) {
                throw new NoSuchElementException();
            }
            last = e.getKey();
            next = higherEntry(last);
            return make(e);
        }

        public final void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            map.remove(last);
            last = null;
        }
    }
}
