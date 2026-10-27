package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A HashMap whose nodes are also chained in insertion (or access) order; iteration, forEach
 * and the views follow the chain, so the table's layout never shows (spec 1.H1).
 */
public class LinkedHashMap<K, V> extends HashMap<K, V> implements Map<K, V> {
    static final class Chained<K, V> extends HashMap.Cell<K, V> {
        Chained<K, V> older;
        Chained<K, V> newer;

        Chained(int hash, K key, V value) { super(hash, key, value); }
    }

    private transient Chained<K, V> oldest;
    private transient Chained<K, V> newest;
    private final boolean accessOrder;

    public LinkedHashMap(int initialCapacity, float loadFactor) { this(initialCapacity, loadFactor, false); }
    public LinkedHashMap(int initialCapacity) { this(initialCapacity, DEFAULT_LOAD, false); }
    public LinkedHashMap() { accessOrder = false; }

    public LinkedHashMap(Map<? extends K, ? extends V> m) {
        accessOrder = false;
        absorb(m, false);
    }

    public LinkedHashMap(int initialCapacity, float loadFactor, boolean accessOrder) {
        super(initialCapacity, loadFactor);
        this.accessOrder = accessOrder;
    }

    Cell<K, V> makeCell(int hash, K key, V value) {
        Chained<K, V> n = new Chained<K, V>(hash, key, value);
        append(n);
        return n;
    }

    private void append(Chained<K, V> n) {
        n.older = newest;
        n.newer = null;
        if (newest == null) {
            oldest = n;
        } else {
            newest.newer = n;
        }
        newest = n;
    }

    private void detach(Chained<K, V> n) {
        if (n.older == null) {
            oldest = n.newer;
        } else {
            n.older.newer = n.newer;
        }
        if (n.newer == null) {
            newest = n.older;
        } else {
            n.newer.older = n.older;
        }
    }

    // Spec 1.H13: in access order a use moves the entry to the end, a change unless already last.
    void used(Cell<K, V> e) {
        if (accessOrder && newest != e) {
            Chained<K, V> n = (Chained<K, V>) e;
            detach(n);
            append(n);
            modCount++;
        }
    }

    // Spec 1.H14: after each new key, the eldest entry may be evicted.
    void added(boolean evict) {
        if (evict && oldest != null && removeEldestEntry(oldest)) {
            removeMapping(oldest.key, null, false);
        }
    }

    void dropped(Cell<K, V> e) {
        Chained<K, V> n = (Chained<K, V>) e;
        detach(n);
        n.older = null;
        n.newer = null;
    }

    Cell<K, V> firstNode() { return oldest; }
    Cell<K, V> nextNode(Cell<K, V> e) { return ((Chained<K, V>) e).newer; }

    /** Spec 9.F3: walks the chain, seeing entries appended meanwhile, then reports a change. */
    void traverse(java.util.function.Consumer<Cell<K, V>> visit) {
        int expected = modCount;
        for (Cell<K, V> e = oldest; e != null; e = nextNode(e)) {
            visit.accept(e);
        }
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
    }

    // Spec 1.H15: the stand-in takes the old entry's place in the chain; the old one keeps its links.
    Cell<K, V> replica(Cell<K, V> old) {
        Chained<K, V> o = (Chained<K, V>) old;
        Chained<K, V> c = new Chained<K, V>(o.hash, o.key, o.value);
        c.older = o.older;
        c.newer = o.newer;
        if (c.older == null) {
            oldest = c;
        } else {
            c.older.newer = c;
        }
        if (c.newer == null) {
            newest = c;
        } else {
            c.newer.older = c;
        }
        return c;
    }

    void blank() {
        super.blank();
        oldest = null;
        newest = null;
    }

    public void clear() {
        super.clear();
        oldest = null;
        newest = null;
    }

    public boolean containsValue(Object value) { return super.containsValue(value); }

    public V get(Object key) {
        Cell<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        used(e);
        return e.value;
    }

    public V getOrDefault(Object key, V defaultValue) {
        Cell<K, V> e = find(key);
        if (e == null) {
            return defaultValue;
        }
        used(e);
        return e.value;
    }

    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return false; }
    public Set<K> keySet() { return super.keySet(); }
    public Collection<V> values() { return super.values(); }
    public Set<Map.Entry<K, V>> entrySet() { return super.entrySet(); }
    public void forEach(BiConsumer<? super K, ? super V> action) { super.forEach(action); }
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) { super.replaceAll(function); }
}
