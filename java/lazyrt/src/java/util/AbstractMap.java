package java.util;

public abstract class AbstractMap<K, V> implements Map<K, V> {
    /** Cached views; a clone must not share them. */
    transient Set<K> keyView;
    transient Collection<V> valueView;

    protected AbstractMap() {
    }

    public int size() { return entrySet().size(); }
    public boolean isEmpty() { return size() == 0; }

    public boolean containsValue(Object value) {
        for (Map.Entry<K, V> e : entrySet()) {
            if (Support.eq(value, e.getValue())) {
                return true;
            }
        }
        return false;
    }

    public boolean containsKey(Object key) { return find(key) != null; }

    public V get(Object key) {
        Map.Entry<K, V> e = find(key);
        return e == null ? null : e.getValue();
    }

    /** The entry whose key equals `key`, found by walking entrySet(). */
    private Map.Entry<K, V> find(Object key) {
        for (Map.Entry<K, V> e : entrySet()) {
            if (Support.eq(key, e.getKey())) {
                return e;
            }
        }
        return null;
    }

    public V put(K key, V value) { throw new UnsupportedOperationException(); }

    public V remove(Object key) {
        for (Iterator<Map.Entry<K, V>> it = entrySet().iterator(); it.hasNext();) {
            Map.Entry<K, V> e = it.next();
            if (Support.eq(key, e.getKey())) {
                V old = e.getValue();
                it.remove();
                return old;
            }
        }
        return null;
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    public void clear() { entrySet().clear(); }

    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new KeyView();
        }
        return keyView;
    }

    public Collection<V> values() {
        if (valueView == null) {
            valueView = new ValueView();
        }
        return valueView;
    }

    public abstract Set<Map.Entry<K, V>> entrySet();

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map)) {
            return false;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        if (m.size() != size()) {
            return false;
        }
        try {
            for (Map.Entry<K, V> e : entrySet()) {
                K k = e.getKey();
                V v = e.getValue();
                if (v == null ? !(m.get(k) == null && m.containsKey(k)) : !v.equals(m.get(k))) {
                    return false;
                }
            }
        } catch (ClassCastException | NullPointerException unlike) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        int h = 0;
        for (Map.Entry<K, V> e : entrySet()) {
            h += e.hashCode();
        }
        return h;
    }

    public String toString() {
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

    protected Object clone() throws CloneNotSupportedException {
        AbstractMap<?, ?> copy = (AbstractMap<?, ?>) super.clone();
        copy.keyView = null;
        copy.valueView = null;
        return copy;
    }

    /** The keys of this map, read through entrySet(). */
    class KeyView extends AbstractSet<K> {
        public Iterator<K> iterator() {
            Iterator<Map.Entry<K, V>> it = entrySet().iterator();
            return new Iterator<K>() {
                public boolean hasNext() { return it.hasNext(); }
                public K next() { return it.next().getKey(); }
                public void remove() { it.remove(); }
            };
        }

        public int size() { return AbstractMap.this.size(); }
        public boolean isEmpty() { return AbstractMap.this.isEmpty(); }
        public void clear() { AbstractMap.this.clear(); }
        public boolean contains(Object k) { return containsKey(k); }
    }

    /** The values of this map, read through entrySet(). */
    class ValueView extends AbstractCollection<V> {
        public Iterator<V> iterator() {
            Iterator<Map.Entry<K, V>> it = entrySet().iterator();
            return new Iterator<V>() {
                public boolean hasNext() { return it.hasNext(); }
                public V next() { return it.next().getValue(); }
                public void remove() { it.remove(); }
            };
        }

        public int size() { return AbstractMap.this.size(); }
        public boolean isEmpty() { return AbstractMap.this.isEmpty(); }
        public void clear() { AbstractMap.this.clear(); }
        public boolean contains(Object v) { return containsValue(v); }
    }

    public static class SimpleEntry<K, V> implements Map.Entry<K, V>, java.io.Serializable {
        private final K key;
        private V value;

        public SimpleEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public SimpleEntry(Map.Entry<? extends K, ? extends V> entry) { this(entry.getKey(), entry.getValue()); }
        public K getKey() { return key; }
        public V getValue() { return value; }

        public V setValue(V value) {
            V old = this.value;
            this.value = value;
            return old;
        }

        public boolean equals(Object o) { return entryEquals(this, o); }
        public int hashCode() { return entryHash(this); }
        public String toString() { return key + "=" + value; }
    }

    public static class SimpleImmutableEntry<K, V> implements Map.Entry<K, V>, java.io.Serializable {
        private final K key;
        private final V value;

        public SimpleImmutableEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public SimpleImmutableEntry(Map.Entry<? extends K, ? extends V> entry) {
            this(entry.getKey(), entry.getValue());
        }

        public K getKey() { return key; }
        public V getValue() { return value; }
        public V setValue(V value) { throw new UnsupportedOperationException(); }
        public boolean equals(Object o) { return entryEquals(this, o); }
        public int hashCode() { return entryHash(this); }
        public String toString() { return key + "=" + value; }
    }

    /** Map.Entry equality as the interface defines it, for every entry class. */
    static boolean entryEquals(Map.Entry<?, ?> e, Object o) {
        if (!(o instanceof Map.Entry)) {
            return false;
        }
        Map.Entry<?, ?> other = (Map.Entry<?, ?>) o;
        return Support.eq(e.getKey(), other.getKey()) && Support.eq(e.getValue(), other.getValue());
    }

    /** Spec 8.C3: entryEquals, but each pair is compared for identity before equals. */
    static boolean entrySame(Map.Entry<?, ?> e, Object o) {
        if (!(o instanceof Map.Entry)) {
            return false;
        }
        Map.Entry<?, ?> other = (Map.Entry<?, ?>) o;
        return Objects.equals(e.getKey(), other.getKey()) && Objects.equals(e.getValue(), other.getValue());
    }

    static int entryHash(Map.Entry<?, ?> e) { return Support.hash(e.getKey()) ^ Support.hash(e.getValue()); }
}
