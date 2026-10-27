package java.util;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A hash map whose keys are held weakly (docs/design/weakhashmap.md, rules W1 to W26).
 *
 * Design. The table is an array of singly linked chains of {@link WeakLink}s; each link is itself
 * the weak reference to its key and the {@code Map.Entry} the entry view hands out. All links
 * share one ReferenceQueue. {@link #drain} runs first in every public method that reads or
 * changes the table: it polls the queue and cuts each cleared link out of its chain, forgetting
 * its value. Cutting never touches the cut link's own {@code next}, which is what lets a view
 * iterator ({@link WeakViews.Cursor}) parked on a dropped link carry on (W22). The iterators walk
 * the buckets from the top index down (W2) and hold the keys they announce and return (W13);
 * forEach and replaceAll walk upwards (W8). Growth relinks every chain into a fresh table (W5).
 */
public class WeakHashMap<K, V> extends AbstractMap<K, V> implements Map<K, V> {
    private static final int DEFAULT_BUCKETS = 16;
    private static final float DEFAULT_LOAD = 0.75f;
    /** Section 7: tables stop growing at 2^30 buckets. */
    private static final int MOST_BUCKETS = 1 << 30;

    /** Read by the view iterators, which walk it directly. */
    WeakLink<K, V>[] table;
    /** Links in the table, cleared ones not yet drained included. */
    private int count;
    private int threshold;
    private final float loadFactor;
    /** W18: bumped by every structural change; the iterators and callbacks compare it. */
    int edits;
    private final ReferenceQueue<Object> cleared = new ReferenceQueue<>();
    private Set<Map.Entry<K, V>> entryView;

    public WeakHashMap(int initialCapacity, float loadFactor) {
        // W21: the capacity is checked before the load factor.
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal Initial Capacity: " + initialCapacity);
        }
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Illegal Load factor: " + loadFactor);
        }
        this.loadFactor = loadFactor;
        table = newTable(HashMap.powerOfTwoAtLeast(initialCapacity));
        threshold = limitFor(table.length);
    }

    public WeakHashMap(int initialCapacity) { this(initialCapacity, DEFAULT_LOAD); }
    public WeakHashMap() { this(DEFAULT_BUCKETS, DEFAULT_LOAD); }

    /** W6: sized for m at the default load factor, then filled by putAll. */
    public WeakHashMap(Map<? extends K, ? extends V> m) {
        this(Math.max((int) (m.size() / DEFAULT_LOAD) + 1, DEFAULT_BUCKETS), DEFAULT_LOAD);
        putAll(m);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> WeakLink<K, V>[] newTable(int n) { return (WeakLink<K, V>[]) new WeakLink<?, ?>[n]; }

    /** W4: the float product, so an infinite load factor saturates to Integer.MAX_VALUE. */
    private int limitFor(int buckets) { return (int) (buckets * loadFactor); }

    /** W15: the key's hash code, called once per lookup; section 6: the null key hashes to 0. */
    private static int hashOf(Object key) { return key == null ? 0 : key.hashCode(); }

    /** W1: the bucket of hash code h in a table of n buckets (n a power of two). */
    static int bucket(int h, int n) {
        int p = h ^ (h >>> 12) ^ (h >>> 20);
        return (p ^ (p >>> 4) ^ (p >>> 7)) & (n - 1);
    }

    /** W10: cuts every link the collector has cleared out of the table and forgets its value. */
    void drain() {
        for (Reference<?> r; (r = cleared.poll()) != null;) {
            @SuppressWarnings("unchecked")
            WeakLink<K, V> gone = (WeakLink<K, V>) r;
            cut(gone);
            gone.value = null;
        }
    }

    /**
     * Unlinks {@code target} from its chain if it is still there; its own {@code next} is left
     * alone (W22). Not a modification by itself: callers that remove a mapping bump edits.
     */
    private boolean cut(WeakLink<K, V> target) {
        int b = bucket(target.hash, table.length);
        WeakLink<K, V> prev = null;
        for (WeakLink<K, V> l = table[b]; l != null; prev = l, l = l.next) {
            if (l == target) {
                if (prev == null) {
                    table[b] = l.next;
                } else {
                    prev.next = l.next;
                }
                count--;
                return true;
            }
        }
        return false;
    }

    /**
     * W15: the live link for key, whose hash was h. Links of another hash are passed without
     * calls; a matching hash compares identity, then key.equals(stored). Cleared links never match.
     */
    private WeakLink<K, V> find(Object key, int h) {
        for (WeakLink<K, V> l = table[bucket(h, table.length)]; l != null; l = l.next) {
            if (l.hash != h) {
                continue;
            }
            if (key == null) {
                if (l.holdsNull) {
                    return l;
                }
                continue;
            }
            Object stored = l.get();
            if (stored != null && (stored == key || key.equals(stored))) {
                return l;
            }
        }
        return null;
    }

    /** The link for key after draining, or null. Used by the entry view (W24). */
    WeakLink<K, V> linkFor(Object key) {
        drain();
        return find(key, hashOf(key));
    }

    public int size() {
        drain();
        return count;
    }

    public boolean isEmpty() { return size() == 0; }

    public V get(Object key) {
        WeakLink<K, V> l = linkFor(key);
        return l == null ? null : l.value;
    }

    public boolean containsKey(Object key) { return linkFor(key) != null; }

    /** W25: in iteration order (W2), argument.equals(stored value), no identity shortcut. */
    public boolean containsValue(Object value) {
        drain();
        for (int i = table.length - 1; i >= 0; i--) {
            for (WeakLink<K, V> l = table[i]; l != null; l = l.next) {
                if (l.live() && Support.eq(value, l.value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** W3, W11: a present key keeps its link and key object; a new key goes to the chain's front. */
    public V put(K key, V value) {
        drain();
        int h = hashOf(key);
        WeakLink<K, V> l = find(key, h);
        if (l != null) {
            return l.setValue(value);
        }
        int b = bucket(h, table.length);
        table[b] = new WeakLink<>(key, h, value, table[b], cleared);
        edits++;
        if (++count >= threshold) {
            grow(table.length << 1);
        }
        return null;
    }

    public V remove(Object key) {
        WeakLink<K, V> l = linkFor(key);
        if (l == null) {
            return null;
        }
        unmap(l);
        return l.value;
    }

    /** Removes a link known to be in the table, as a modification (W18). */
    private void unmap(WeakLink<K, V> l) {
        cut(l);
        edits++;
    }

    /**
     * W16, W24: entrySet().remove. Hashes the entry's key once, then asks each live link of that
     * hash whether it equals the entry: the stored key's equals, never the argument's.
     */
    boolean removeEntry(Map.Entry<?, ?> e) {
        drain();
        int h = hashOf(e.getKey());
        for (WeakLink<K, V> l = table[bucket(h, table.length)]; l != null; l = l.next) {
            if (l.hash == h && l.live() && l.equals(e)) {
                unmap(l);
                return true;
            }
        }
        return false;
    }

    /** W6, W7: presize once for a large m, then put each entry in m's iteration order. */
    public void putAll(Map<? extends K, ? extends V> m) {
        int s = m.size();
        if (s == 0) {
            return;
        }
        drain();
        if (s > threshold) {
            presize(s);
        }
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    private void presize(int incoming) {
        int target = (int) (incoming / loadFactor + 1);
        int n = table.length;
        while (n < target && n < MOST_BUCKETS) {
            n <<= 1;
        }
        // W7: a map less than half way to its threshold keeps its table.
        if (n > table.length && count >= threshold / 2) {
            grow(n);
        }
    }

    /**
     * W4, W5: moves every link (cleared ones too: drain finds them in their new bucket) into a
     * table of n buckets, each old chain front to back onto the front of its new bucket.
     */
    private void grow(int n) {
        if (table.length >= MOST_BUCKETS) {
            threshold = Integer.MAX_VALUE;
            return;
        }
        WeakLink<K, V>[] fresh = newTable(n);
        for (WeakLink<K, V> l : table) {
            while (l != null) {
                WeakLink<K, V> after = l.next;
                int b = bucket(l.hash, n);
                l.next = fresh[b];
                fresh[b] = l;
                l = after;
            }
        }
        table = fresh;
        threshold = n >= MOST_BUCKETS ? Integer.MAX_VALUE : limitFor(n);
    }

    /**
     * W18: always a modification. Only the bucket heads are emptied: an iterator that has
     * already taken a link's successor still sees it (W23). Queued links are discarded too.
     */
    public void clear() {
        Arrays.fill(table, null);
        count = 0;
        edits++;
        while (cleared.poll() != null) {
            // Their links are no longer in the table; nothing else to do.
        }
    }

    /** W8, W19: buckets upwards, chains front to back, checking for changes after each call. */
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        drain();
        int expected = edits;
        for (WeakLink<K, V> head : table) {
            for (WeakLink<K, V> l = head; l != null; l = l.next) {
                K key = l.getKey();
                if (key != null || l.holdsNull) {
                    action.accept(key, l.value);
                    unchanged(expected);
                }
            }
        }
    }

    /** W8, W19: as forEach; each new value is stored before the check. */
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        drain();
        int expected = edits;
        for (WeakLink<K, V> head : table) {
            for (WeakLink<K, V> l = head; l != null; l = l.next) {
                K key = l.getKey();
                if (key != null || l.holdsNull) {
                    l.value = function.apply(key, l.value);
                    unchanged(expected);
                }
            }
        }
    }

    private void unchanged(int expected) {
        if (edits != expected) {
            throw new ConcurrentModificationException();
        }
    }

    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new WeakViews.Keys<>(this);
        }
        return keyView;
    }

    public Collection<V> values() {
        if (valueView == null) {
            valueView = new WeakViews.Values<>(this);
        }
        return valueView;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new WeakViews.Entries<>(this);
        }
        return entryView;
    }
}
