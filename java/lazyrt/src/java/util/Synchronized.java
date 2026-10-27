package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The views of Collections.synchronized*: each call holds the view's lock (the view itself, or
 * the lock of the view it came from). Iterators are not locked; callers hold the lock themselves.
 */
final class Synchronized {
    private Synchronized() {
    }

    static <E> List<E> list(List<E> list, Object mutex) {
        return list instanceof RandomAccess ? new SRandomList<E>(list, mutex) : new SList<E>(list, mutex);
    }

    static class SColl<E> implements Collection<E>, java.io.Serializable {
        final Collection<E> c;
        final Object mutex;

        SColl(Collection<E> c, Object mutex) {
            this.c = Objects.requireNonNull(c);
            this.mutex = mutex == null ? this : mutex;
        }

        public int size() { synchronized (mutex) { return c.size(); } }
        public boolean isEmpty() { synchronized (mutex) { return c.isEmpty(); } }
        public boolean contains(Object o) { synchronized (mutex) { return c.contains(o); } }
        public Object[] toArray() { synchronized (mutex) { return c.toArray(); } }
        public <T> T[] toArray(T[] a) { synchronized (mutex) { return c.toArray(a); } }
        public Iterator<E> iterator() { return c.iterator(); }
        public boolean add(E e) { synchronized (mutex) { return c.add(e); } }
        public boolean remove(Object o) { synchronized (mutex) { return c.remove(o); } }
        public boolean containsAll(Collection<?> coll) { synchronized (mutex) { return c.containsAll(coll); } }
        public boolean addAll(Collection<? extends E> coll) { synchronized (mutex) { return c.addAll(coll); } }
        public boolean removeAll(Collection<?> coll) { synchronized (mutex) { return c.removeAll(coll); } }
        public boolean retainAll(Collection<?> coll) { synchronized (mutex) { return c.retainAll(coll); } }
        public void clear() { synchronized (mutex) { c.clear(); } }
        public String toString() { synchronized (mutex) { return c.toString(); } }
        public void forEach(Consumer<? super E> action) { synchronized (mutex) { c.forEach(action); } }
        public boolean removeIf(Predicate<? super E> filter) { synchronized (mutex) { return c.removeIf(filter); } }
    }

    static class SSet<E> extends SColl<E> implements Set<E> {
        SSet(Set<E> s, Object mutex) {
            super(s, mutex);
        }

        public boolean equals(Object o) { if (this == o) { return true; } synchronized (mutex) { return c.equals(o); } }
        public int hashCode() { synchronized (mutex) { return c.hashCode(); } }
    }

    static class SSortedSet<E> extends SSet<E> implements SortedSet<E> {
        private final SortedSet<E> ss;

        SSortedSet(SortedSet<E> s, Object mutex) {
            super(s, mutex);
            ss = s;
        }

        public Comparator<? super E> comparator() { synchronized (mutex) { return ss.comparator(); } }
        public SortedSet<E> subSet(E from, E to) { synchronized (mutex) { return new SSortedSet<E>(ss.subSet(from, to), mutex); } }
        public SortedSet<E> headSet(E to) { synchronized (mutex) { return new SSortedSet<E>(ss.headSet(to), mutex); } }
        public SortedSet<E> tailSet(E from) { synchronized (mutex) { return new SSortedSet<E>(ss.tailSet(from), mutex); } }
        public E first() { synchronized (mutex) { return ss.first(); } }
        public E last() { synchronized (mutex) { return ss.last(); } }
    }

    static class SNavigableSet<E> extends SSortedSet<E> implements NavigableSet<E> {
        private final NavigableSet<E> ns;

        SNavigableSet(NavigableSet<E> s, Object mutex) {
            super(s, mutex);
            ns = s;
        }

        public E lower(E e) { synchronized (mutex) { return ns.lower(e); } }
        public E floor(E e) { synchronized (mutex) { return ns.floor(e); } }
        public E ceiling(E e) { synchronized (mutex) { return ns.ceiling(e); } }
        public E higher(E e) { synchronized (mutex) { return ns.higher(e); } }
        public E pollFirst() { synchronized (mutex) { return ns.pollFirst(); } }
        public E pollLast() { synchronized (mutex) { return ns.pollLast(); } }
        public NavigableSet<E> descendingSet() { synchronized (mutex) { return new SNavigableSet<E>(ns.descendingSet(), mutex); } }
        public Iterator<E> descendingIterator() { return descendingSet().iterator(); }
        public NavigableSet<E> subSet(E from, boolean fromIn, E to, boolean toIn) {
            synchronized (mutex) { return new SNavigableSet<E>(ns.subSet(from, fromIn, to, toIn), mutex); }
        }
        public NavigableSet<E> headSet(E to, boolean in) { synchronized (mutex) { return new SNavigableSet<E>(ns.headSet(to, in), mutex); } }
        public NavigableSet<E> tailSet(E from, boolean in) { synchronized (mutex) { return new SNavigableSet<E>(ns.tailSet(from, in), mutex); } }
    }

    static class SList<E> extends SColl<E> implements List<E> {
        private final List<E> l;

        SList(List<E> l, Object mutex) {
            super(l, mutex);
            this.l = l;
        }

        public boolean equals(Object o) { if (this == o) { return true; } synchronized (mutex) { return l.equals(o); } }
        public int hashCode() { synchronized (mutex) { return l.hashCode(); } }
        public E get(int index) { synchronized (mutex) { return l.get(index); } }
        public E set(int index, E element) { synchronized (mutex) { return l.set(index, element); } }
        public void add(int index, E element) { synchronized (mutex) { l.add(index, element); } }
        public E remove(int index) { synchronized (mutex) { return l.remove(index); } }
        public int indexOf(Object o) { synchronized (mutex) { return l.indexOf(o); } }
        public int lastIndexOf(Object o) { synchronized (mutex) { return l.lastIndexOf(o); } }
        public boolean addAll(int index, Collection<? extends E> c) { synchronized (mutex) { return l.addAll(index, c); } }
        public ListIterator<E> listIterator() { return l.listIterator(); }
        public ListIterator<E> listIterator(int index) { return l.listIterator(index); }
        public List<E> subList(int from, int to) { synchronized (mutex) { return list(l.subList(from, to), mutex); } }
        public void replaceAll(UnaryOperator<E> operator) { synchronized (mutex) { l.replaceAll(operator); } }
        public void sort(Comparator<? super E> c) { synchronized (mutex) { l.sort(c); } }
    }

    static final class SRandomList<E> extends SList<E> implements RandomAccess {
        SRandomList(List<E> l, Object mutex) {
            super(l, mutex);
        }
    }

    static class SMap<K, V> implements Map<K, V>, java.io.Serializable {
        private final Map<K, V> m;
        final Object mutex;

        SMap(Map<K, V> m, Object mutex) {
            this.m = Objects.requireNonNull(m);
            this.mutex = mutex == null ? this : mutex;
        }

        public int size() { synchronized (mutex) { return m.size(); } }
        public boolean isEmpty() { synchronized (mutex) { return m.isEmpty(); } }
        public boolean containsKey(Object key) { synchronized (mutex) { return m.containsKey(key); } }
        public boolean containsValue(Object value) { synchronized (mutex) { return m.containsValue(value); } }
        public V get(Object key) { synchronized (mutex) { return m.get(key); } }
        public V put(K key, V value) { synchronized (mutex) { return m.put(key, value); } }
        public V remove(Object key) { synchronized (mutex) { return m.remove(key); } }
        public void putAll(Map<? extends K, ? extends V> map) { synchronized (mutex) { m.putAll(map); } }
        public void clear() { synchronized (mutex) { m.clear(); } }
        public Set<K> keySet() { synchronized (mutex) { return new SSet<K>(m.keySet(), mutex); } }
        public Set<Map.Entry<K, V>> entrySet() { synchronized (mutex) { return new SSet<Map.Entry<K, V>>(m.entrySet(), mutex); } }
        public Collection<V> values() { synchronized (mutex) { return new SColl<V>(m.values(), mutex); } }
        public boolean equals(Object o) { if (this == o) { return true; } synchronized (mutex) { return m.equals(o); } }
        public int hashCode() { synchronized (mutex) { return m.hashCode(); } }
        public String toString() { synchronized (mutex) { return m.toString(); } }
        public V getOrDefault(Object k, V dflt) { synchronized (mutex) { return m.getOrDefault(k, dflt); } }
        public void forEach(BiConsumer<? super K, ? super V> action) { synchronized (mutex) { m.forEach(action); } }
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> f) { synchronized (mutex) { m.replaceAll(f); } }
        public V putIfAbsent(K key, V value) { synchronized (mutex) { return m.putIfAbsent(key, value); } }
        public boolean remove(Object key, Object value) { synchronized (mutex) { return m.remove(key, value); } }
        public boolean replace(K key, V oldValue, V newValue) { synchronized (mutex) { return m.replace(key, oldValue, newValue); } }
        public V replace(K key, V value) { synchronized (mutex) { return m.replace(key, value); } }
        public V computeIfAbsent(K key, Function<? super K, ? extends V> f) { synchronized (mutex) { return m.computeIfAbsent(key, f); } }
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> f) {
            synchronized (mutex) { return m.computeIfPresent(key, f); }
        }
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> f) { synchronized (mutex) { return m.compute(key, f); } }
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> f) {
            synchronized (mutex) { return m.merge(key, value, f); }
        }
    }

    static class SSortedMap<K, V> extends SMap<K, V> implements SortedMap<K, V> {
        private final SortedMap<K, V> sm;

        SSortedMap(SortedMap<K, V> m, Object mutex) {
            super(m, mutex);
            sm = m;
        }

        public Comparator<? super K> comparator() { synchronized (mutex) { return sm.comparator(); } }
        public SortedMap<K, V> subMap(K from, K to) { synchronized (mutex) { return new SSortedMap<K, V>(sm.subMap(from, to), mutex); } }
        public SortedMap<K, V> headMap(K to) { synchronized (mutex) { return new SSortedMap<K, V>(sm.headMap(to), mutex); } }
        public SortedMap<K, V> tailMap(K from) { synchronized (mutex) { return new SSortedMap<K, V>(sm.tailMap(from), mutex); } }
        public K firstKey() { synchronized (mutex) { return sm.firstKey(); } }
        public K lastKey() { synchronized (mutex) { return sm.lastKey(); } }
    }

    static class SNavigableMap<K, V> extends SSortedMap<K, V> implements NavigableMap<K, V> {
        private final NavigableMap<K, V> nm;

        SNavigableMap(NavigableMap<K, V> m, Object mutex) {
            super(m, mutex);
            nm = m;
        }

        public Map.Entry<K, V> lowerEntry(K key) { synchronized (mutex) { return nm.lowerEntry(key); } }
        public K lowerKey(K key) { synchronized (mutex) { return nm.lowerKey(key); } }
        public Map.Entry<K, V> floorEntry(K key) { synchronized (mutex) { return nm.floorEntry(key); } }
        public K floorKey(K key) { synchronized (mutex) { return nm.floorKey(key); } }
        public Map.Entry<K, V> ceilingEntry(K key) { synchronized (mutex) { return nm.ceilingEntry(key); } }
        public K ceilingKey(K key) { synchronized (mutex) { return nm.ceilingKey(key); } }
        public Map.Entry<K, V> higherEntry(K key) { synchronized (mutex) { return nm.higherEntry(key); } }
        public K higherKey(K key) { synchronized (mutex) { return nm.higherKey(key); } }
        public Map.Entry<K, V> firstEntry() { synchronized (mutex) { return nm.firstEntry(); } }
        public Map.Entry<K, V> lastEntry() { synchronized (mutex) { return nm.lastEntry(); } }
        public Map.Entry<K, V> pollFirstEntry() { synchronized (mutex) { return nm.pollFirstEntry(); } }
        public Map.Entry<K, V> pollLastEntry() { synchronized (mutex) { return nm.pollLastEntry(); } }
        public NavigableMap<K, V> descendingMap() { synchronized (mutex) { return new SNavigableMap<K, V>(nm.descendingMap(), mutex); } }
        public NavigableSet<K> navigableKeySet() { synchronized (mutex) { return new SNavigableSet<K>(nm.navigableKeySet(), mutex); } }
        public NavigableSet<K> descendingKeySet() { synchronized (mutex) { return new SNavigableSet<K>(nm.descendingKeySet(), mutex); } }
        public NavigableMap<K, V> subMap(K from, boolean fromIn, K to, boolean toIn) {
            synchronized (mutex) { return new SNavigableMap<K, V>(nm.subMap(from, fromIn, to, toIn), mutex); }
        }
        public NavigableMap<K, V> headMap(K to, boolean in) { synchronized (mutex) { return new SNavigableMap<K, V>(nm.headMap(to, in), mutex); } }
        public NavigableMap<K, V> tailMap(K from, boolean in) { synchronized (mutex) { return new SNavigableMap<K, V>(nm.tailMap(from, in), mutex); } }
    }
}
