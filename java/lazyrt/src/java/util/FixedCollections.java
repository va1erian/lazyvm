package java.util;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** The small collections the Collections factories return: empty, single-element, copies, adapters. */
final class FixedCollections {
    private FixedCollections() {
    }

    // Spec 10.X7: three distinct objects; the plain iterator is not a ListIterator.
    static final Object NO_ITERATOR = new NoIterator<Object>();
    static final Object NO_LIST_ITERATOR = new NoListIterator<Object>();
    static final Object NO_ENUMERATION = new Enumeration<Object>() {
        public boolean hasMoreElements() { return false; }
        public Object nextElement() { throw new NoSuchElementException(); }
    };

    private static class NoIterator<E> implements Iterator<E> {
        public boolean hasNext() { return false; }
        public E next() { throw new NoSuchElementException(); }
        public void remove() { throw new IllegalStateException(); }
    }

    private static final class NoListIterator<E> extends NoIterator<E> implements ListIterator<E> {
        public boolean hasPrevious() { return false; }
        public E previous() { throw new NoSuchElementException(); }
        public int nextIndex() { return 0; }
        public int previousIndex() { return -1; }
        public void set(E e) { throw new IllegalStateException(); }
        public void add(E e) { throw new UnsupportedOperationException(); }
    }

    static final class EmptyList<E> extends AbstractList<E> implements RandomAccess, java.io.Serializable {
        public int size() { return 0; }
        public E get(int index) { throw new IndexOutOfBoundsException("Index: " + index); }
        public Iterator<E> iterator() { return Collections.emptyIterator(); }
        public ListIterator<E> listIterator() { return Collections.emptyListIterator(); }
    }

    static final class EmptySet<E> extends AbstractSet<E> implements java.io.Serializable {
        public Iterator<E> iterator() { return Collections.emptyIterator(); }
        public int size() { return 0; }
    }

    /** A map no call can change: the Map defaults that could store or remove refuse at once. */
    abstract static class FixedMap<K, V> extends AbstractMap<K, V> implements java.io.Serializable {
        public V putIfAbsent(K key, V value) { throw Unmodifiable.refused(); }
        public boolean remove(Object key, Object value) { throw Unmodifiable.refused(); }
        public boolean replace(K key, V oldValue, V newValue) { throw Unmodifiable.refused(); }
        public V replace(K key, V value) { throw Unmodifiable.refused(); }
        public V computeIfAbsent(K key, Function<? super K, ? extends V> f) { throw Unmodifiable.refused(); }
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> f) { throw Unmodifiable.refused(); }
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> f) { throw Unmodifiable.refused(); }
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> f) { throw Unmodifiable.refused(); }
    }

    // Spec 10.X7: all three views of the empty map are the empty set itself.
    static final class EmptyMap<K, V> extends FixedMap<K, V> {
        public Set<K> keySet() { return Collections.emptySet(); }
        public Collection<V> values() { return Collections.emptySet(); }
        public Set<Map.Entry<K, V>> entrySet() { return Collections.emptySet(); }
    }

    /** n copies of one element. */
    static final class Copies<E> extends AbstractList<E> implements RandomAccess, java.io.Serializable {
        private final int n;
        private final E element;

        Copies(int n, E element) {
            this.n = n;
            this.element = element;
        }

        public int size() { return n; }

        public E get(int index) {
            Support.checkIndex(index, n);
            return element;
        }

        public boolean contains(Object o) { return n != 0 && Support.eq(o, element); }
        public int indexOf(Object o) { return contains(o) ? 0 : -1; }
        public int lastIndexOf(Object o) { return contains(o) ? n - 1 : -1; }

        // Spec 10.X7: a range of copies is again a list of copies, with its plain iterator.
        public List<E> subList(int fromIndex, int toIndex) {
            Support.checkSubList(fromIndex, toIndex, n);
            return new Copies<E>(toIndex - fromIndex, element);
        }
    }

    /** An iterator over one element that cannot remove it. */
    static <E> Iterator<E> once(E e) {
        return new Iterator<E>() {
            private boolean done;

            public boolean hasNext() { return !done; }

            public E next() {
                if (done) {
                    throw new NoSuchElementException();
                }
                done = true;
                return e;
            }

            public void remove() { throw new UnsupportedOperationException(); }
        };
    }

    static final class One<E> extends AbstractSet<E> implements java.io.Serializable {
        private final E element;

        One(E element) { this.element = element; }
        public Iterator<E> iterator() { return once(element); }
        public int size() { return 1; }
        public boolean contains(Object o) { return Support.eq(o, element); }
        public boolean removeIf(Predicate<? super E> filter) { throw Unmodifiable.refused(); }
    }

    static final class OneList<E> extends AbstractList<E> implements RandomAccess, java.io.Serializable {
        private final E element;

        OneList(E element) { this.element = element; }
        public int size() { return 1; }

        public E get(int index) {
            if (index != 0) {
                throw Support.badIndex(index, 1);
            }
            return element;
        }

        public Iterator<E> iterator() { return once(element); }
        public boolean contains(Object o) { return Support.eq(o, element); }

        public void sort(Comparator<? super E> c) {
        }
    }

    static final class OneMap<K, V> extends FixedMap<K, V> {
        private final K key;
        private final V value;

        OneMap(K key, V value) {
            this.key = key;
            this.value = value;
        }

        // Spec 10.X6: refused without calling the function.
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> f) { throw Unmodifiable.refused(); }

        public int size() { return 1; }
        public boolean containsKey(Object k) { return Support.eq(k, key); }
        public boolean containsValue(Object v) { return Support.eq(v, value); }
        public V get(Object k) { return containsKey(k) ? value : null; }

        // Spec 10.X7: each view is a one-element set, made once (values() too is a set).
        private transient Set<K> keys;
        private transient Set<V> vals;
        private transient Set<Map.Entry<K, V>> pairs;

        public Set<K> keySet() {
            if (keys == null) {
                keys = new One<K>(key);
            }
            return keys;
        }

        public Collection<V> values() {
            if (vals == null) {
                vals = new One<V>(value);
            }
            return vals;
        }

        public Set<Map.Entry<K, V>> entrySet() {
            if (pairs == null) {
                pairs = new One<Map.Entry<K, V>>(new AbstractMap.SimpleImmutableEntry<K, V>(key, value));
            }
            return pairs;
        }
    }

    /** Collections.newSetFromMap: the keys of a map, each mapped to true. */
    static final class MapBackedSet<E> extends AbstractSet<E> implements java.io.Serializable {
        private final Map<E, Boolean> m;
        private final Set<E> keys;

        MapBackedSet(Map<E, Boolean> m) {
            if (!m.isEmpty()) {
                throw new IllegalArgumentException("Map is non-empty");
            }
            this.m = m;
            this.keys = m.keySet();
        }

        public boolean add(E e) { return m.put(e, Boolean.TRUE) == null; }
        public boolean remove(Object o) { return m.remove(o) != null; }
        public boolean contains(Object o) { return m.containsKey(o); }
        public void clear() { m.clear(); }
        public int size() { return m.size(); }
        public Iterator<E> iterator() { return keys.iterator(); }
        public boolean removeAll(Collection<?> c) { return keys.removeAll(c); }
        public boolean retainAll(Collection<?> c) { return keys.retainAll(c); }
        public String toString() { return keys.toString(); }
    }

    /** Collections.asLifoQueue: a queue whose head is the deque's first element, added at the front. */
    static final class LifoQueue<E> extends AbstractQueue<E> implements java.io.Serializable {
        private final Deque<E> d;

        LifoQueue(Deque<E> d) { this.d = d; }

        public boolean add(E e) {
            d.addFirst(e);
            return true;
        }

        public boolean offer(E e) { return d.offerFirst(e); }
        public E poll() { return d.pollFirst(); }
        public E remove() { return d.removeFirst(); }
        public E peek() { return d.peekFirst(); }
        public E element() { return d.getFirst(); }
        public void clear() { d.clear(); }
        public int size() { return d.size(); }
        public boolean contains(Object o) { return d.contains(o); }
        public boolean remove(Object o) { return d.remove(o); }
        public Iterator<E> iterator() { return d.iterator(); }
        public String toString() { return d.toString(); }
    }
}
