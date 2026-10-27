package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Chains of entries in (hash & 0x7FFFFFFF) % n buckets, newest first (spec 2.T1). Iterators and
 * enumerations go from the last bucket down; forEach and replaceAll from the first up.
 */
public class Hashtable<K, V> extends Dictionary<K, V> implements Map<K, V>, Cloneable, java.io.Serializable {
    // Javadoc: the default capacity and load factor.
    private static final int DEFAULT_BUCKETS = 11;
    private static final float DEFAULT_LOAD = 0.75f;

    static final class Link<K, V> implements Map.Entry<K, V> {
        final int hash;
        final K key;
        V value;
        Link<K, V> next;

        Link(int hash, K key, V value, Link<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public K getKey() { return key; }
        public V getValue() { return value; }
        public V setValue(V value) { V old = this.value; this.value = Objects.requireNonNull(value); return old; }
        public boolean equals(Object o) { return AbstractMap.entryEquals(this, o); }
        public int hashCode() { return hash ^ Support.hash(value); }
        // Spec 2.T5: a removed entry's value is null; its text then fails as the oracle's does.
        public String toString() { return key.toString() + "=" + value.toString(); }
    }

    private transient Link<K, V>[] table;
    private transient int entries;
    private int threshold;
    private final float loadFactor;
    transient int modCount;
    /** Spec 2.T4: set while hashCode() runs, so a table reached again through itself counts 0. */
    private transient boolean hashing;
    private transient Set<K> keyView;
    private transient Collection<V> valueView;
    private transient Set<Map.Entry<K, V>> entryView;

    // Spec 2.T2: max(c, 1) buckets, threshold (int)(n * lf).
    public Hashtable(int initialCapacity, float loadFactor) {
        Support.checkCapacity(initialCapacity);
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Illegal Load: " + loadFactor);
        }
        this.loadFactor = loadFactor;
        setTable(Math.max(initialCapacity, 1));
    }

    public Hashtable(int initialCapacity) { this(initialCapacity, DEFAULT_LOAD); }
    public Hashtable() { this(DEFAULT_BUCKETS, DEFAULT_LOAD); }

    // Spec 2.T3: max(2 * size, 11) buckets, then the entries in m's order.
    public Hashtable(Map<? extends K, ? extends V> t) {
        this(Math.max(2 * t.size(), DEFAULT_BUCKETS), DEFAULT_LOAD);
        putAll(t);
    }

    @SuppressWarnings("unchecked")
    private void setTable(int n) {
        table = (Link<K, V>[]) new Link<?, ?>[n];
        threshold = (int) (n * loadFactor);
    }

    private int bucket(int hash) { return (hash & 0x7FFFFFFF) % table.length; }

    /** Spec 8.C3: the entry equal to e, comparing e once with each entry of its key's chain. */
    Link<K, V> linkEqualTo(Map.Entry<?, ?> e) {
        int h = e.getKey().hashCode();
        for (Link<K, V> l = table[bucket(h)]; l != null; l = l.next) {
            if (l.hash == h && l.equals(e)) {
                return l;
            }
        }
        return null;
    }

    private Link<K, V> find(Object key) {
        int h = key.hashCode();
        for (Link<K, V> e = table[bucket(h)]; e != null; e = e.next) {
            // Spec 8.C3: the stored key's equals is asked.
            if (e.hash == h && e.key.equals(key)) {
                return e;
            }
        }
        return null;
    }

    // Spec 2.T2: grow before adding when full, then push the new entry onto its chain.
    private void insertNew(K key, V value) {
        if (entries >= threshold) {
            rehash();
        }
        int h = key.hashCode();
        int i = bucket(h);
        table[i] = new Link<K, V>(h, key, value, table[i]);
        entries++;
        modCount++;
    }

    private void unlink(Link<K, V> target) {
        int i = bucket(target.hash);
        if (table[i] == target) {
            table[i] = target.next;
        } else {
            Link<K, V> p = table[i];
            while (p.next != target) {
                p = p.next;
            }
            p.next = target.next;
        }
        entries--;
        modCount++;
    }

    // Spec 2.T2: 2n + 1 buckets, old buckets taken from n - 1 down, each entry pushed to the front.
    protected void rehash() {
        Link<K, V>[] old = table;
        setTable(old.length * 2 + 1);
        modCount++;
        for (int i = old.length - 1; i >= 0; i--) {
            for (Link<K, V> e = old[i]; e != null;) {
                Link<K, V> next = e.next;
                int j = bucket(e.hash);
                e.next = table[j];
                table[j] = e;
                e = next;
            }
        }
    }

    public synchronized int size() { return entries; }
    public synchronized boolean isEmpty() { return entries == 0; }
    public synchronized Enumeration<K> keys() { return entries == 0 ? Collections.emptyEnumeration() : walk(e -> e.key, false); }
    public synchronized Enumeration<V> elements() { return entries == 0 ? Collections.emptyEnumeration() : walk(e -> e.value, false); }
    public synchronized boolean containsKey(Object key) { return find(key) != null; }
    public boolean containsValue(Object value) { return contains(value); }

    public synchronized boolean contains(Object value) {
        Objects.requireNonNull(value);
        for (int i = table.length - 1; i >= 0; i--) {
            for (Link<K, V> e = table[i]; e != null; e = e.next) {
                // Spec 8.C3: the stored value's equals is asked.
                if (e.value.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized V get(Object key) {
        Link<K, V> e = find(key);
        return e == null ? null : e.value;
    }

    public synchronized V put(K key, V value) {
        Objects.requireNonNull(value);
        Link<K, V> e = find(key);
        if (e != null) {
            V old = e.value;
            e.value = value;
            return old;
        }
        insertNew(key, value);
        return null;
    }

    /** Removal by key also clears the entry's value, as an entry held from iteration then shows. */
    public synchronized V remove(Object key) {
        Link<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        unlink(e);
        V old = e.value;
        e.value = null;
        return old;
    }

    public synchronized void putAll(Map<? extends K, ? extends V> t) {
        for (Map.Entry<? extends K, ? extends V> e : t.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    public synchronized void clear() {
        Arrays.fill(table, null);
        modCount++;
        entries = 0;
    }

    // Spec 2.T3: a clone copies the chains as they are.
    @SuppressWarnings("unchecked")
    public synchronized Object clone() {
        try {
            Hashtable<K, V> copy = (Hashtable<K, V>) super.clone();
            copy.table = table.clone();
            for (int i = 0; i < table.length; i++) {
                copy.table[i] = copyChain(table[i]);
            }
            copy.keyView = null;
            copy.valueView = null;
            copy.entryView = null;
            copy.modCount = 0;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    private static <K, V> Link<K, V> copyChain(Link<K, V> e) {
        return e == null ? null : new Link<K, V>(e.hash, e.key, e.value, copyChain(e.next));
    }

    public synchronized String toString() {
        Iterator<Map.Entry<K, V>> it = entrySet().iterator();
        if (!it.hasNext()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        while (true) {
            Map.Entry<K, V> e = it.next();
            sb.append(Support.show(e.getKey(), this, "(this Map)")).append('=');
            sb.append(Support.show(e.getValue(), this, "(this Map)"));
            if (!it.hasNext()) {
                return sb.append('}').toString();
            }
            sb.append(", ");
        }
    }

    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new HashtableViews.Keys<K, V>(this);
        }
        return keyView;
    }

    public Collection<V> values() {
        if (valueView == null) {
            valueView = new HashtableViews.Values<K, V>(this);
        }
        return valueView;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new HashtableViews.Entries<K, V>(this);
        }
        return entryView;
    }

    public synchronized boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map)) {
            return false;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        if (m.size() != entries) {
            return false;
        }
        try {
            for (Map.Entry<K, V> e : entrySet()) {
                if (!e.getValue().equals(m.get(e.getKey()))) {
                    return false;
                }
            }
        } catch (ClassCastException | NullPointerException unlike) {
            return false;
        }
        return true;
    }

    // Spec 2.T4: a nested call while this table's hash is being computed counts 0.
    public synchronized int hashCode() {
        if (entries == 0 || hashing) {
            return 0;
        }
        hashing = true;
        try {
            int h = 0;
            for (Link<K, V> head : table) {
                for (Link<K, V> e = head; e != null; e = e.next) {
                    h += e.key.hashCode() ^ e.value.hashCode();
                }
            }
            return h;
        } finally {
            hashing = false;
        }
    }

    // ---- iteration (spec 2.T1, T3) ----

    private <T> HashtableViews.Walk<K, V, T> walk(Function<Link<K, V>, T> pick, boolean removable) {
        return new HashtableViews.Walk<K, V, T>(this, table, pick, removable);
    }

    /** Spec 2.T3: an iterator of a table that is empty now yields nothing, whatever is added later. */
    <T> Iterator<T> iterator(Function<Link<K, V>, T> pick) {
        return entries == 0 ? Collections.emptyIterator() : walk(pick, true);
    }

    void removeEntry(Link<K, V> e) { unlink(e); }

    // ---- Map defaults: forEach and replaceAll go from bucket 0 up (spec 2.T1) ----

    public synchronized V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v == null ? defaultValue : v;
    }

    // Spec 9.F3: Hashtable checks for a change after every action.
    public synchronized void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = modCount;
        for (Link<K, V> head : table) {
            for (Link<K, V> e = head; e != null; e = e.next) {
                action.accept(e.key, e.value);
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = modCount;
        for (Link<K, V> head : table) {
            for (Link<K, V> e = head; e != null; e = e.next) {
                e.value = Objects.requireNonNull(function.apply(e.key, e.value));
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    public synchronized V putIfAbsent(K key, V value) {
        Objects.requireNonNull(value);
        Link<K, V> e = find(key);
        if (e != null) {
            return e.value;
        }
        insertNew(key, value);
        return null;
    }

    public synchronized boolean remove(Object key, Object value) {
        Objects.requireNonNull(value);
        Link<K, V> e = find(key);
        if (e == null || !e.value.equals(value)) {
            return false;
        }
        unlink(e);
        e.value = null;
        return true;
    }

    public synchronized boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        Link<K, V> e = find(key);
        if (e == null || !e.value.equals(oldValue)) {
            return false;
        }
        e.value = newValue;
        return true;
    }

    public synchronized V replace(K key, V value) {
        Objects.requireNonNull(value);
        Link<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        V old = e.value;
        e.value = value;
        return old;
    }

    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        Link<K, V> e = find(key);
        if (e != null) {
            return e.value;
        }
        int seen = modCount;
        V v = mappingFunction.apply(key);
        return v == null ? null : finish(seen, e, key, v);
    }

    public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Link<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        int seen = modCount;
        return finish(seen, e, key, remappingFunction.apply(key, e.value));
    }

    public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Link<K, V> e = find(key);
        int seen = modCount;
        return finish(seen, e, key, remappingFunction.apply(key, e == null ? null : e.value));
    }

    // Section 12: records v for key after a remapping, as HashMap.finish does: v is dropped when
    // the entry e it started from has left the table, and goes to an entry the function added.
    private V finish(int seen, Link<K, V> e, K key, V v) {
        Link<K, V> at = modCount == seen ? e : find(key);
        if (e != null && at != e) {
            return v;
        }
        if (at != null) {
            return settle(at, v);
        }
        if (v != null) {
            insertNew(key, v);
        }
        return v;
    }

    public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Link<K, V> e = find(key);
        if (e != null) {
            int seen = modCount;
            return finish(seen, e, key, remappingFunction.apply(e.value, value));
        }
        if (value != null) {
            insertNew(key, value);
        }
        return value;
    }

    /** A remapped value replaces e's; null removes e. */
    private V settle(Link<K, V> e, V v) {
        if (v == null) {
            unlink(e);
        } else {
            e.value = v;
        }
        return v;
    }
}
