package java.util;

/** IdentityHashMap's views; their removals and lookups compare by reference too. */
final class IdentityViews {
    private IdentityViews() {
    }

    /** Removes, through the iterator, the first element that is the very object o. */
    static boolean removeSame(Iterator<?> it, Object o) {
        while (it.hasNext()) {
            if (it.next() == o) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    static final class Keys<K, V> extends AbstractSet<K> {
        private final IdentityHashMap<K, V> m;

        Keys(IdentityHashMap<K, V> m) { this.m = m; }
        public Iterator<K> iterator() { return m.walk(e -> e.key); }
        public int size() { return m.size(); }
        public boolean contains(Object o) { return m.containsKey(o); }

        public boolean remove(Object o) {
            int before = m.size();
            m.remove(o);
            return m.size() != before;
        }

        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeWhere(c, true);
        }

        public void clear() { m.clear(); }
    }

    static final class Values<K, V> extends AbstractCollection<V> {
        private final IdentityHashMap<K, V> m;

        Values(IdentityHashMap<K, V> m) { this.m = m; }
        public Iterator<V> iterator() { return m.walk(e -> e.value); }
        public int size() { return m.size(); }
        public boolean contains(Object o) { return m.containsValue(o); }
        public boolean remove(Object o) { return removeSame(iterator(), o); }
        public void clear() { m.clear(); }
    }

    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final IdentityHashMap<K, V> m;

        Entries(IdentityHashMap<K, V> m) { this.m = m; }
        public Iterator<Map.Entry<K, V>> iterator() { return m.walk(e -> e); }
        public int size() { return m.size(); }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return m.hasMapping(e.getKey(), e.getValue());
        }

        public boolean remove(Object o) {
            if (!contains(o)) {
                return false;
            }
            m.remove(((Map.Entry<?, ?>) o).getKey());
            return true;
        }

        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeWhere(c, true);
        }

        public void clear() { m.clear(); }
    }
}
