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

    /** What a Walk returns of each cell. */
    static final int KEYS = 0;
    static final int VALUES = 1;
    static final int ENTRIES = 2;

    /**
     * Spec 9.F2: next() checks for a change before the end; hasNext() is whether a node is left.
     * What it returns is picked by a switch, not a lambda: a call per element costs a frame in the
     * interpreter, and game code walks maps every frame (plan S11-06 part 4).
     */
    static final class Walk<K, V, T> implements Iterator<T> {
        private final HashMap<K, V> map;
        private final int pick;
        private HashMap.Cell<K, V> next;
        private HashMap.Cell<K, V> current;
        private int expected;

        Walk(HashMap<K, V> map, int pick) {
            this.map = map;
            this.pick = pick;
            this.expected = map.modCount;
            this.next = map.size > 0 ? map.firstNode() : null;
        }

        public boolean hasNext() { return next != null; }

        @SuppressWarnings("unchecked")
        public T next() {
            if (map.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            HashMap.Cell<K, V> c = next;
            if (c == null) {
                throw new NoSuchElementException();
            }
            current = c;
            next = map.nextNode(c);
            return (T) (pick == KEYS ? c.key : pick == VALUES ? c.value : c);
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

    /**
     * The spliterator of a plain HashMap view: it reads cells as they are, so an element added ahead
     * of it is met and one removed ahead of it is not; a change is reported when the walk is over.
     */
    static final class Split<K, V, T> extends Spliterators.AbstractSpliterator<T> {
        private final HashMap<K, V> map;
        private final Function<HashMap.Cell<K, V>, T> pick;
        private final int ch;
        private HashMap.Cell<K, V> at;
        private boolean bound;
        private int expected;

        Split(HashMap<K, V> map, Function<HashMap.Cell<K, V>, T> pick, int ch) {
            super(0, 0);
            this.map = map;
            this.pick = pick;
            this.ch = ch;
        }

        private void bind() {
            if (!bound) {
                bound = true;
                expected = map.modCount;
                remaining = map.size;
                at = map.size > 0 ? map.firstNode() : null;
            }
        }

        private void checkEnd() {
            if (map.modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            bind();
            HashMap.Cell<K, V> c = at;
            if (c == null) {
                return false;
            }
            action.accept(pick.apply(c));
            at = map.nextNode(c);
            checkEnd();
            return true;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            bind();
            HashMap.Cell<K, V> c = at;
            at = null;
            while (c != null) {
                action.accept(pick.apply(c));
                c = map.nextNode(c);
            }
            checkEnd();
        }

        @Override
        public Spliterator<T> trySplit() {
            bind();
            return super.trySplit();
        }

        @Override
        public long estimateSize() {
            bind();
            return remaining;
        }

        @Override
        public int characteristics() { return ch; }
    }

    /** A LinkedHashMap's views walk through their iterators; a plain HashMap's through Split. */
    private static <K, V, T> Spliterator<T> viewSplit(HashMap<K, V> map, Collection<T> view,
            Function<HashMap.Cell<K, V>, T> pick, int ch) {
        if (map instanceof LinkedHashMap) {
            return new Late<T>(view, ch | Spliterator.ORDERED | Spliterator.SUBSIZED, null);
        }
        return new Split<K, V, T>(map, pick, ch);
    }

    static final class Keys<K, V> extends AbstractSet<K> {
        private final HashMap<K, V> map;

        Keys(HashMap<K, V> map) { this.map = map; }
        public int size() { return map.size; }
        public void clear() { map.clear(); }
        public Iterator<K> iterator() { return new Walk<K, V, K>(map, KEYS); }
        public Spliterator<K> spliterator() { return viewSplit(map, this, e -> e.key, Spliterator.DISTINCT | Spliterator.SIZED); }
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
        public Iterator<V> iterator() { return new Walk<K, V, V>(map, VALUES); }
        public Spliterator<V> spliterator() { return viewSplit(map, this, e -> e.value, Spliterator.SIZED); }
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
        public Iterator<Map.Entry<K, V>> iterator() { return new Walk<K, V, Map.Entry<K, V>>(map, ENTRIES); }
        public Spliterator<Map.Entry<K, V>> spliterator() { return viewSplit(map, this, e -> e, Spliterator.DISTINCT | Spliterator.SIZED); }

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
