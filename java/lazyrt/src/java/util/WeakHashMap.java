package java.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A hash map whose keys are held through weak references (docs/design/weakhashmap.md). Its order
 * rules (W1-W8) differ from HashMap's in every detail, so it shares no code with it.
 */
public class WeakHashMap<K, V> extends AbstractMap<K, V> implements Map<K, V> {
    private static final int MAX_BUCKETS = 1 << 30;
    /** Section 6: the null key is stored as this object, never cleared, with the fixed hash 0. */
    private static final Object NULL_KEY = new Object();

    private WeakEntry<K, V>[] table;
    private int size;
    private int threshold;
    private final float loadFactor;
    /** Where the collector delivers the entries whose keys it cleared (W10). */
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    /** W18: changed by structural modifications only. */
    int modCount;
    private Set<Map.Entry<K, V>> entryView;

    /** W4, W21. */
    public WeakHashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal Initial Capacity: " + initialCapacity);
        }
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Illegal Load factor: " + loadFactor);
        }
        int n = 1;
        while (n < initialCapacity && n < MAX_BUCKETS) {
            n <<= 1;
        }
        this.loadFactor = loadFactor;
        table = newTable(n);
        threshold = (int) (n * loadFactor);
    }

    public WeakHashMap(int initialCapacity) { this(initialCapacity, 0.75f); }

    public WeakHashMap() { this(16, 0.75f); }

    /** W6: sized from the source's size, then putAll. */
    public WeakHashMap(Map<? extends K, ? extends V> m) {
        this(Math.max((int) (m.size() / 0.75f) + 1, 16), 0.75f);
        putAll(m);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> WeakEntry<K, V>[] newTable(int n) { return (WeakEntry<K, V>[]) new WeakEntry<?, ?>[n]; }

    static Object mask(Object key) { return key == null ? NULL_KEY : key; }

    static Object unmask(Object key) { return key == NULL_KEY ? null : key; }

    /** W1: the bucket function, applied to the stored key's hash code once. */
    private static int spread(Object maskedKey) {
        if (maskedKey == NULL_KEY) {
            return 0;
        }
        int h = maskedKey.hashCode();
        int p = h ^ (h >>> 12) ^ (h >>> 20);
        return p ^ (p >>> 4) ^ (p >>> 7);
    }

    /** W15: the same object, else the argument's equals with the stored key. */
    private static boolean matches(Object maskedKey, Object stored) {
        return maskedKey == stored || stored != null && maskedKey.equals(stored);
    }

    /** W10: unlinks every entry the collector cleared; not a modification (W13, W18). */
    private void expunge() {
        for (Object r; (r = queue.poll()) != null;) {
            @SuppressWarnings("unchecked")
            WeakEntry<K, V> dead = (WeakEntry<K, V>) r;
            int i = dead.hash & (table.length - 1);
            WeakEntry<K, V> prev = null;
            for (WeakEntry<K, V> e = table[i]; e != null; prev = e, e = e.next) {
                if (e == dead) {
                    if (prev == null) {
                        table[i] = e.next;
                    } else {
                        prev.next = e.next;
                    }
                    size--;
                    break;
                }
            }
            dead.value = null; // W14: a held entry reads null=null
            dead.next = null;
        }
    }

    /** The table after dropping cleared entries: every public method starts here. */
    WeakEntry<K, V>[] liveTable() {
        expunge();
        return table;
    }

    @Override
    public int size() {
        if (size == 0) {
            return 0;
        }
        expunge();
        return size;
    }

    @Override
    public boolean isEmpty() { return size() == 0; }

    /** W15: one hashCode call, then the bucket's chain. */
    WeakEntry<K, V> getEntry(Object key) {
        Object k = mask(key);
        int h = spread(k);
        WeakEntry<K, V>[] tab = liveTable();
        for (WeakEntry<K, V> e = tab[h & (tab.length - 1)]; e != null; e = e.next) {
            if (e.hash == h && matches(k, e.get())) {
                return e;
            }
        }
        return null;
    }

    @Override
    public V get(Object key) {
        WeakEntry<K, V> e = getEntry(key);
        return e == null ? null : e.value;
    }

    @Override
    public boolean containsKey(Object key) { return getEntry(key) != null; }

    /** W3, W4, W11: a present key keeps its entry and first key object; a new one goes in front. */
    @Override
    public V put(K key, V value) {
        Object k = mask(key);
        int h = spread(k);
        WeakEntry<K, V>[] tab = liveTable();
        int i = h & (tab.length - 1);
        for (WeakEntry<K, V> e = tab[i]; e != null; e = e.next) {
            if (e.hash == h && matches(k, e.get())) {
                V old = e.value;
                e.value = value;
                return old;
            }
        }
        modCount++;
        tab[i] = new WeakEntry<>(k, value, queue, h, tab[i]);
        if (++size >= threshold) {
            resize(tab.length * 2);
        }
        return null;
    }

    /** W4, W5: a larger table; each old chain moves front to back onto the new chains' fronts. */
    private void resize(int buckets) {
        WeakEntry<K, V>[] old = liveTable();
        if (old.length == MAX_BUCKETS) {
            threshold = Integer.MAX_VALUE;
            return;
        }
        WeakEntry<K, V>[] fresh = newTable(buckets);
        for (int j = 0; j < old.length; j++) {
            WeakEntry<K, V> e = old[j];
            old[j] = null;
            while (e != null) {
                WeakEntry<K, V> next = e.next;
                int i = e.hash & (buckets - 1);
                e.next = fresh[i];
                fresh[i] = e;
                e = next;
            }
        }
        table = fresh;
        threshold = (int) (buckets * loadFactor);
    }

    /** W6, W7: presize only a map that is at least half its threshold, then put in order. */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        int s = m.size();
        if (s == 0) {
            return;
        }
        if (s > threshold && size() >= threshold / 2) {
            int target = (int) (s / loadFactor + 1);
            int n = table.length;
            while (n < target && n < MAX_BUCKETS) {
                n <<= 1;
            }
            if (n > table.length) {
                resize(n);
            }
        }
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public V remove(Object key) {
        WeakEntry<K, V> e = removeEntry(key);
        return e == null ? null : e.value;
    }

    /** Unlinks the key's entry; only a removal that finds it is a modification (W18). */
    WeakEntry<K, V> removeEntry(Object key) {
        Object k = mask(key);
        int h = spread(k);
        WeakEntry<K, V>[] tab = liveTable();
        int i = h & (tab.length - 1);
        WeakEntry<K, V> prev = null;
        for (WeakEntry<K, V> e = tab[i]; e != null; prev = e, e = e.next) {
            if (e.hash == h && matches(k, e.get())) {
                if (prev == null) {
                    tab[i] = e.next;
                } else {
                    prev.next = e.next;
                }
                modCount++;
                size--;
                return e;
            }
        }
        return null;
    }

    /** W18: always a modification, even when empty. */
    @Override
    public void clear() {
        while (queue.poll() != null) {
            // Entries already queued belong to the table being dropped.
        }
        modCount++;
        Arrays.fill(table, null);
        size = 0;
        while (queue.poll() != null) {
            // Nor do any the collector queued meanwhile.
        }
    }

    @Override
    public boolean containsValue(Object value) {
        WeakEntry<K, V>[] tab = liveTable();
        for (int i = tab.length - 1; i >= 0; i--) {
            for (WeakEntry<K, V> e = tab[i]; e != null; e = e.next) {
                if (e.get() != null && (value == null ? e.value == null : value.equals(e.value))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** W8, W19: buckets upward, checking for changes after every call. */
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = modCount;
        WeakEntry<K, V>[] tab = liveTable();
        for (WeakEntry<K, V> head : tab) {
            for (WeakEntry<K, V> e = head; e != null; e = e.next) {
                Object key = e.get();
                if (key != null) {
                    action.accept(e.getKey(), e.value);
                    if (modCount != expected) {
                        throw new ConcurrentModificationException();
                    }
                }
            }
        }
    }

    /** W8, W19: each new value is stored before the check. */
    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = modCount;
        WeakEntry<K, V>[] tab = liveTable();
        for (WeakEntry<K, V> head : tab) {
            for (WeakEntry<K, V> e = head; e != null; e = e.next) {
                Object key = e.get();
                if (key != null) {
                    e.value = function.apply(e.getKey(), e.value);
                    if (modCount != expected) {
                        throw new ConcurrentModificationException();
                    }
                }
            }
        }
    }

    /** W17: one view object each. */
    @Override
    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new WeakHashViews.Keys<>(this);
        }
        return keyView;
    }

    @Override
    public Collection<V> values() {
        if (valueView == null) {
            valueView = new WeakHashViews.Values<>(this);
        }
        return valueView;
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new WeakHashViews.Entries<>(this);
        }
        return entryView;
    }

    /** An entry is the weak reference to its key (W10, W14, W16). */
    static final class WeakEntry<K, V> extends WeakReference<Object> implements Map.Entry<K, V> {
        V value;
        final int hash;
        WeakEntry<K, V> next;

        WeakEntry(Object maskedKey, V value, ReferenceQueue<Object> queue, int hash, WeakEntry<K, V> next) {
            super(maskedKey, queue);
            this.value = value;
            this.hash = hash;
            this.next = next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public K getKey() { return (K) unmask(get()); }

        @Override
        public V getValue() { return value; }

        @Override
        public V setValue(V newValue) {
            V old = value;
            value = newValue;
            return old;
        }

        /** W16: the same key object or this key's equals, and equal values. */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> other = (Map.Entry<?, ?>) o;
            K k1 = getKey();
            Object k2 = other.getKey();
            if (k1 == k2 || k1 != null && k1.equals(k2)) {
                Object v2 = other.getValue();
                return value == v2 || value != null && value.equals(v2);
            }
            return false;
        }

        @Override
        public int hashCode() { return Support.hash(getKey()) ^ Support.hash(value); }

        @Override
        public String toString() { return getKey() + "=" + value; }
    }
}
