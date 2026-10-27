package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A chained hash table keyed by reference: keys (and values, where the Javadoc says so) are
 * compared with == and hashed with System.identityHashCode. Spec 12: the order is not observable.
 */
public class IdentityHashMap<K, V> extends AbstractMap<K, V> implements Map<K, V>, java.io.Serializable, Cloneable {
    // Javadoc: the default expected maximum size.
    private static final int DEFAULT_EXPECTED = 21;

    static final class Node<K, V> implements Map.Entry<K, V> {
        final K key;
        V value;
        Node<K, V> next;

        Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public K getKey() { return key; }
        public V getValue() { return value; }
        public V setValue(V v) { V old = value; value = v; return old; }
        public String toString() { return key + "=" + value; }
        public int hashCode() { return System.identityHashCode(key) ^ System.identityHashCode(value); }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return e.getKey() == key && e.getValue() == value;
        }
    }

    private transient Node<K, V>[] table;
    private transient int size;
    transient int modCount;
    private transient Set<Map.Entry<K, V>> entryView;

    public IdentityHashMap() { this(DEFAULT_EXPECTED); }

    public IdentityHashMap(int expectedMaxSize) {
        if (expectedMaxSize < 0) {
            throw new IllegalArgumentException("expectedMaxSize is negative: " + expectedMaxSize);
        }
        table = newTable(HashMap.tableSizeFor(Math.max(2 * expectedMaxSize, 2)));
    }

    public IdentityHashMap(Map<? extends K, ? extends V> m) {
        this(m.size());
        putAll(m);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int n) { return (Node<K, V>[]) new Node<?, ?>[n]; }
    private int bucket(Object key) { return HashMap.spread(System.identityHashCode(key)) & (table.length - 1); }

    private Node<K, V> find(Object key) {
        for (Node<K, V> e = table[bucket(key)]; e != null; e = e.next) {
            if (e.key == key) {
                return e;
            }
        }
        return null;
    }

    private void grow() {
        Node<K, V>[] old = table;
        table = newTable(old.length * 2);
        for (Node<K, V> head : old) {
            for (Node<K, V> e = head; e != null;) {
                Node<K, V> next = e.next;
                int i = bucket(e.key);
                e.next = table[i];
                table[i] = e;
                e = next;
            }
        }
    }

    final void unlink(Node<K, V> target) {
        int i = bucket(target.key);
        if (table[i] == target) {
            table[i] = target.next;
        } else {
            Node<K, V> p = table[i];
            while (p.next != target) {
                p = p.next;
            }
            p.next = target.next;
        }
        size--;
        modCount++;
    }

    /** Walks every node; spec 9.F2: next() checks for a change before the end. */
    final <T> Iterator<T> walk(java.util.function.Function<Node<K, V>, T> pick) {
        return new Iterator<T>() {
            private int index;
            private Node<K, V> next = advance(null);
            private Node<K, V> last;
            private int expected = modCount;

            private Node<K, V> advance(Node<K, V> from) {
                if (from != null && from.next != null) {
                    return from.next;
                }
                while (index < table.length) {
                    Node<K, V> e = table[index++];
                    if (e != null) {
                        return e;
                    }
                }
                return null;
            }

            public boolean hasNext() { return next != null; }

            public T next() {
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
                if (next == null) {
                    throw new NoSuchElementException();
                }
                last = next;
                next = advance(last);
                return pick.apply(last);
            }

            public void remove() {
                if (last == null) {
                    throw new IllegalStateException();
                }
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
                unlink(last);
                expected = modCount;
                last = null;
            }
        };
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public V get(Object key) {
        Node<K, V> e = find(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Object key) { return find(key) != null; }

    public boolean containsValue(Object value) {
        for (Node<K, V> head : table) {
            for (Node<K, V> e = head; e != null; e = e.next) {
                if (e.value == value) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether key maps to the very object value. */
    final boolean hasMapping(Object key, Object value) {
        Node<K, V> e = find(key);
        return e != null && e.value == value;
    }

    public V put(K key, V value) {
        Node<K, V> e = find(key);
        if (e != null) {
            return e.setValue(value);
        }
        if (size >= table.length * 3 / 4) {
            grow();
        }
        int i = bucket(key);
        table[i] = new Node<K, V>(key, value, table[i]);
        size++;
        modCount++;
        return null;
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    public V remove(Object key) {
        Node<K, V> e = find(key);
        if (e == null) {
            return null;
        }
        unlink(e);
        return e.value;
    }

    public void clear() {
        modCount++;
        Arrays.fill(table, null);
        size = 0;
    }

    // Javadoc: another IdentityHashMap is equal when it holds the very same key-value pairs.
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof IdentityHashMap) {
            IdentityHashMap<?, ?> m = (IdentityHashMap<?, ?>) o;
            if (m.size != size) {
                return false;
            }
            for (Node<K, V> head : table) {
                for (Node<K, V> e = head; e != null; e = e.next) {
                    if (!m.hasMapping(e.key, e.value)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return super.equals(o);
    }

    public int hashCode() {
        int h = 0;
        for (Node<K, V> head : table) {
            for (Node<K, V> e = head; e != null; e = e.next) {
                h += e.hashCode();
            }
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    public Object clone() {
        try {
            IdentityHashMap<K, V> copy = (IdentityHashMap<K, V>) super.clone();
            copy.table = newTable(table.length);
            copy.size = 0;
            copy.modCount = 0;
            copy.entryView = null;
            copy.putAll(this);
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new IdentityViews.Keys<K, V>(this);
        }
        return keyView;
    }

    public Collection<V> values() {
        if (valueView == null) {
            valueView = new IdentityViews.Values<K, V>(this);
        }
        return valueView;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new IdentityViews.Entries<K, V>(this);
        }
        return entryView;
    }

    // Spec 9.F3: IdentityHashMap checks for a change after every action.
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = modCount;
        for (Node<K, V> head : table) {
            for (Node<K, V> e = head; e != null; e = e.next) {
                action.accept(e.key, e.value);
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = modCount;
        for (Node<K, V> head : table) {
            for (Node<K, V> e = head; e != null; e = e.next) {
                e.value = function.apply(e.key, e.value);
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }
}
