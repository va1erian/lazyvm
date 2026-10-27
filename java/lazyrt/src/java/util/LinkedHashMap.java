package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A HashMap whose nodes are also chained in insertion (or access) order; iteration, forEach
 * and the views follow the chain, so the table's layout never shows (spec 1.H1).
 */
public class LinkedHashMap<K, V> extends HashMap<K, V> implements Map<K, V> {
    static final class Linked<K, V> extends HashMap.Node<K, V> {
        Linked<K, V> before;
        Linked<K, V> after;

        Linked(int hash, K key, V value) { super(hash, key, value); }
    }

    private transient Linked<K, V> head;
    private transient Linked<K, V> tail;
    private final boolean accessOrder;

    public LinkedHashMap(int initialCapacity, float loadFactor) { this(initialCapacity, loadFactor, false); }
    public LinkedHashMap(int initialCapacity) { this(initialCapacity, DEFAULT_LOAD, false); }
    public LinkedHashMap() { accessOrder = false; }

    public LinkedHashMap(Map<? extends K, ? extends V> m) {
        accessOrder = false;
        copyIn(m, false);
    }

    public LinkedHashMap(int initialCapacity, float loadFactor, boolean accessOrder) {
        super(initialCapacity, loadFactor);
        this.accessOrder = accessOrder;
    }

    Node<K, V> newNode(int hash, K key, V value) {
        Linked<K, V> n = new Linked<K, V>(hash, key, value);
        append(n);
        return n;
    }

    private void append(Linked<K, V> n) {
        n.before = tail;
        n.after = null;
        if (tail == null) {
            head = n;
        } else {
            tail.after = n;
        }
        tail = n;
    }

    private void detach(Linked<K, V> n) {
        if (n.before == null) {
            head = n.after;
        } else {
            n.before.after = n.after;
        }
        if (n.after == null) {
            tail = n.before;
        } else {
            n.after.before = n.before;
        }
    }

    // Spec 1.H13: in access order a use moves the entry to the end, a change unless already last.
    void accessed(Node<K, V> e) {
        if (accessOrder && tail != e) {
            Linked<K, V> n = (Linked<K, V>) e;
            detach(n);
            append(n);
            modCount++;
        }
    }

    // Spec 1.H14: after each new key, the eldest entry may be evicted.
    void inserted(boolean evict) {
        if (evict && head != null && removeEldestEntry(head)) {
            removeKey(head.key, null, false);
        }
    }

    void removed(Node<K, V> e) {
        Linked<K, V> n = (Linked<K, V>) e;
        detach(n);
        n.before = null;
        n.after = null;
    }

    Node<K, V> firstNode() { return head; }
    Node<K, V> nextNode(Node<K, V> e) { return ((Linked<K, V>) e).after; }

    void reset() {
        super.reset();
        head = null;
        tail = null;
    }

    public void clear() {
        super.clear();
        head = null;
        tail = null;
    }

    public boolean containsValue(Object value) { return super.containsValue(value); }

    public V get(Object key) {
        Node<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        accessed(e);
        return e.value;
    }

    public V getOrDefault(Object key, V defaultValue) {
        Node<K, V> e = find(key);
        if (e == null) {
            return defaultValue;
        }
        accessed(e);
        return e.value;
    }

    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return false; }
    public Set<K> keySet() { return super.keySet(); }
    public Collection<V> values() { return super.values(); }
    public Set<Map.Entry<K, V>> entrySet() { return super.entrySet(); }
    public void forEach(BiConsumer<? super K, ? super V> action) { super.forEach(action); }
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) { super.replaceAll(function); }
}
