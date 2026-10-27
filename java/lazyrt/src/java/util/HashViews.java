package java.util;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The key, value and entry views of a HashMap (and so of LinkedHashMap, which only changes the
 * iteration order). Spec 1.H11: removing through a view is map-style except through iterators.
 */
final class HashViews {
    private HashViews() {
    }

    /** Spec 9.F2: next() checks for a change before the end; hasNext() is whether a node is left. */
    static final class Walk<K, V, T> implements Iterator<T> {
        private final HashMap<K, V> map;
        private final Function<HashMap.Cell<K, V>, T> pick;
        private HashMap.Cell<K, V> next;
        private HashMap.Cell<K, V> current;
        private int expected;

        Walk(HashMap<K, V> map, Function<HashMap.Cell<K, V>, T> pick) {
            this.map = map;
            this.pick = pick;
            this.expected = map.modCount;
            this.next = map.size > 0 ? map.firstNode() : null;
        }

        public boolean hasNext() { return next != null; }

        public T next() {
            if (map.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            if (next == null) {
                throw new NoSuchElementException();
            }
            current = next;
            next = map.nextNode(current);
            return pick.apply(current);
        }

        public void remove() {
            if (current == null) {
                throw new IllegalStateException();
            }
            if (map.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            map.unlink(current, false);
            current = null;
            expected = map.modCount;
        }
    }

    static final class Keys<K, V> extends AbstractSet<K> {
        private final HashMap<K, V> map;

        Keys(HashMap<K, V> map) { this.map = map; }
        public int size() { return map.size; }
        public void clear() { map.clear(); }
        public Iterator<K> iterator() { return new Walk<K, V, K>(map, e -> e.key); }
        public boolean contains(Object o) { return map.containsKey(o); }
        public boolean remove(Object o) { return map.removeMapping(o, null, false) != null; }

        public void forEach(Consumer<? super K> action) {
            Objects.requireNonNull(action);
            map.traverse(e -> action.accept(e.key));
        }
    }

    static final class Values<K, V> extends AbstractCollection<V> {
        private final HashMap<K, V> map;

        Values(HashMap<K, V> map) { this.map = map; }
        public int size() { return map.size; }
        public void clear() { map.clear(); }
        public Iterator<V> iterator() { return new Walk<K, V, V>(map, e -> e.value); }
        public boolean contains(Object o) { return map.containsValue(o); }

        public void forEach(Consumer<? super V> action) {
            Objects.requireNonNull(action);
            map.traverse(e -> action.accept(e.value));
        }
    }

    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final HashMap<K, V> map;

        Entries(HashMap<K, V> map) { this.map = map; }
        public int size() { return map.size; }
        public void clear() { map.clear(); }
        public Iterator<Map.Entry<K, V>> iterator() { return new Walk<K, V, Map.Entry<K, V>>(map, e -> e); }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            HashMap.Cell<K, V> e = map.find(entry.getKey());
            return e != null && e.equals(entry);
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            return map.removeMapping(entry.getKey(), entry.getValue(), true) != null;
        }

        public void forEach(Consumer<? super Map.Entry<K, V>> action) {
            Objects.requireNonNull(action);
            map.traverse(e -> action.accept(e));
        }
    }
}
