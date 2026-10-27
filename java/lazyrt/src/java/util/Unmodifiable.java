package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The read-only views of Collections.unmodifiable*: reads go to the backing collection, every
 * change throws UnsupportedOperationException, and views and entries handed out are read-only too.
 */
final class Unmodifiable {
    private Unmodifiable() {
    }

    static UnsupportedOperationException refused() {
        return new UnsupportedOperationException();
    }

    static <E> List<E> list(List<? extends E> list) {
        return list instanceof RandomAccess ? new URandomList<E>(list) : new UList<E>(list);
    }

    static <E> Iterator<E> readOnly(Iterator<? extends E> it) {
        return new Iterator<E>() {
            public boolean hasNext() { return it.hasNext(); }
            public E next() { return it.next(); }
            public void remove() { throw refused(); }
            public void forEachRemaining(Consumer<? super E> action) { it.forEachRemaining(action); }
        };
    }

    static class Coll<E> implements Collection<E>, java.io.Serializable {
        final Collection<? extends E> c;

        Coll(Collection<? extends E> c) {
            this.c = Objects.requireNonNull(c);
        }

        public int size() { return c.size(); }
        public boolean isEmpty() { return c.isEmpty(); }
        public boolean contains(Object o) { return c.contains(o); }
        public Object[] toArray() { return c.toArray(); }
        public <T> T[] toArray(T[] a) { return c.toArray(a); }
        public String toString() { return c.toString(); }
        public boolean containsAll(Collection<?> coll) { return c.containsAll(coll); }
        public Iterator<E> iterator() { return readOnly(c.iterator()); }
        public void forEach(Consumer<? super E> action) { c.forEach(action); }
        public boolean add(E e) { throw refused(); }
        public boolean remove(Object o) { throw refused(); }
        public boolean addAll(Collection<? extends E> coll) { throw refused(); }
        public boolean removeAll(Collection<?> coll) { throw refused(); }
        public boolean retainAll(Collection<?> coll) { throw refused(); }
        public void clear() { throw refused(); }
        public boolean removeIf(Predicate<? super E> filter) { throw refused(); }
    }

    static class USet<E> extends Coll<E> implements Set<E> {
        USet(Set<? extends E> s) {
            super(s);
        }

        public boolean equals(Object o) { return o == this || c.equals(o); }
        public int hashCode() { return c.hashCode(); }
    }

    static class USortedSet<E> extends USet<E> implements SortedSet<E> {
        private final SortedSet<E> ss;

        USortedSet(SortedSet<E> s) {
            super(s);
            ss = s;
        }

        public Comparator<? super E> comparator() { return ss.comparator(); }
        public SortedSet<E> subSet(E from, E to) { return new USortedSet<E>(ss.subSet(from, to)); }
        public SortedSet<E> headSet(E to) { return new USortedSet<E>(ss.headSet(to)); }
        public SortedSet<E> tailSet(E from) { return new USortedSet<E>(ss.tailSet(from)); }
        public E first() { return ss.first(); }
        public E last() { return ss.last(); }
    }

    static class UNavigableSet<E> extends USortedSet<E> implements NavigableSet<E> {
        private final NavigableSet<E> ns;

        UNavigableSet(NavigableSet<E> s) {
            super(s);
            ns = s;
        }

        public E lower(E e) { return ns.lower(e); }
        public E floor(E e) { return ns.floor(e); }
        public E ceiling(E e) { return ns.ceiling(e); }
        public E higher(E e) { return ns.higher(e); }
        public E pollFirst() { throw refused(); }
        public E pollLast() { throw refused(); }
        public NavigableSet<E> descendingSet() { return new UNavigableSet<E>(ns.descendingSet()); }
        public Iterator<E> descendingIterator() { return descendingSet().iterator(); }
        public NavigableSet<E> subSet(E from, boolean fromIn, E to, boolean toIn) {
            return new UNavigableSet<E>(ns.subSet(from, fromIn, to, toIn));
        }
        public NavigableSet<E> headSet(E to, boolean in) { return new UNavigableSet<E>(ns.headSet(to, in)); }
        public NavigableSet<E> tailSet(E from, boolean in) { return new UNavigableSet<E>(ns.tailSet(from, in)); }
    }

    static class UList<E> extends Coll<E> implements List<E> {
        final List<? extends E> l;

        UList(List<? extends E> l) {
            super(l);
            this.l = l;
        }

        public boolean equals(Object o) { return o == this || l.equals(o); }
        public int hashCode() { return l.hashCode(); }
        public E get(int index) { return l.get(index); }
        public int indexOf(Object o) { return l.indexOf(o); }
        public int lastIndexOf(Object o) { return l.lastIndexOf(o); }
        public E set(int index, E element) { throw refused(); }
        public void add(int index, E element) { throw refused(); }
        public E remove(int index) { throw refused(); }
        public boolean addAll(int index, Collection<? extends E> c) { throw refused(); }
        public void replaceAll(UnaryOperator<E> operator) { throw refused(); }
        public void sort(Comparator<? super E> c) { throw refused(); }
        public ListIterator<E> listIterator() { return listIterator(0); }
        public List<E> subList(int from, int to) { return list(l.subList(from, to)); }

        public ListIterator<E> listIterator(int index) {
            ListIterator<? extends E> it = l.listIterator(index);
            return new ListIterator<E>() {
                public boolean hasNext() { return it.hasNext(); }
                public E next() { return it.next(); }
                public boolean hasPrevious() { return it.hasPrevious(); }
                public E previous() { return it.previous(); }
                public int nextIndex() { return it.nextIndex(); }
                public int previousIndex() { return it.previousIndex(); }
                public void remove() { throw refused(); }
                public void set(E e) { throw refused(); }
                public void add(E e) { throw refused(); }
            };
        }
    }

    static final class URandomList<E> extends UList<E> implements RandomAccess {
        URandomList(List<? extends E> l) {
            super(l);
        }
    }

    static class UMap<K, V> implements Map<K, V>, java.io.Serializable {
        private final Map<? extends K, ? extends V> m;

        UMap(Map<? extends K, ? extends V> m) {
            this.m = Objects.requireNonNull(m);
        }

        public int size() { return m.size(); }
        public boolean isEmpty() { return m.isEmpty(); }
        public boolean containsKey(Object key) { return m.containsKey(key); }
        public boolean containsValue(Object value) { return m.containsValue(value); }
        public V get(Object key) { return m.get(key); }
        public boolean equals(Object o) { return o == this || m.equals(o); }
        public int hashCode() { return m.hashCode(); }
        public String toString() { return m.toString(); }
        public Set<K> keySet() { return new USet<K>(m.keySet()); }
        public Collection<V> values() { return new Coll<V>(m.values()); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public Set<Map.Entry<K, V>> entrySet() { return new UEntrySet<K, V>((Set) m.entrySet()); }

        @SuppressWarnings("unchecked")
        public V getOrDefault(Object k, V defaultValue) { return ((Map<K, V>) m).getOrDefault(k, defaultValue); }

        public void forEach(BiConsumer<? super K, ? super V> action) { m.forEach(action); }
        public V put(K key, V value) { throw refused(); }
        public V remove(Object key) { throw refused(); }
        public void putAll(Map<? extends K, ? extends V> map) { throw refused(); }
        public void clear() { throw refused(); }
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> f) { throw refused(); }
        public V putIfAbsent(K key, V value) { throw refused(); }
        public boolean remove(Object key, Object value) { throw refused(); }
        public boolean replace(K key, V oldValue, V newValue) { throw refused(); }
        public V replace(K key, V value) { throw refused(); }
        public V computeIfAbsent(K key, Function<? super K, ? extends V> f) { throw refused(); }
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> f) { throw refused(); }
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> f) { throw refused(); }
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> f) { throw refused(); }
    }

    static class USortedMap<K, V> extends UMap<K, V> implements SortedMap<K, V> {
        private final SortedMap<K, V> sm;

        USortedMap(SortedMap<K, V> m) {
            super(m);
            sm = m;
        }

        public Comparator<? super K> comparator() { return sm.comparator(); }
        public SortedMap<K, V> subMap(K from, K to) { return new USortedMap<K, V>(sm.subMap(from, to)); }
        public SortedMap<K, V> headMap(K to) { return new USortedMap<K, V>(sm.headMap(to)); }
        public SortedMap<K, V> tailMap(K from) { return new USortedMap<K, V>(sm.tailMap(from)); }
        public K firstKey() { return sm.firstKey(); }
        public K lastKey() { return sm.lastKey(); }
    }

    static class UNavigableMap<K, V> extends USortedMap<K, V> implements NavigableMap<K, V> {
        private final NavigableMap<K, V> nm;

        UNavigableMap(NavigableMap<K, V> m) {
            super(m);
            nm = m;
        }

        public Map.Entry<K, V> lowerEntry(K key) { return entry(nm.lowerEntry(key)); }
        public K lowerKey(K key) { return nm.lowerKey(key); }
        public Map.Entry<K, V> floorEntry(K key) { return entry(nm.floorEntry(key)); }
        public K floorKey(K key) { return nm.floorKey(key); }
        public Map.Entry<K, V> ceilingEntry(K key) { return entry(nm.ceilingEntry(key)); }
        public K ceilingKey(K key) { return nm.ceilingKey(key); }
        public Map.Entry<K, V> higherEntry(K key) { return entry(nm.higherEntry(key)); }
        public K higherKey(K key) { return nm.higherKey(key); }
        public Map.Entry<K, V> firstEntry() { return entry(nm.firstEntry()); }
        public Map.Entry<K, V> lastEntry() { return entry(nm.lastEntry()); }
        public Map.Entry<K, V> pollFirstEntry() { throw refused(); }
        public Map.Entry<K, V> pollLastEntry() { throw refused(); }
        public NavigableMap<K, V> descendingMap() { return new UNavigableMap<K, V>(nm.descendingMap()); }
        public NavigableSet<K> navigableKeySet() { return new UNavigableSet<K>(nm.navigableKeySet()); }
        public NavigableSet<K> descendingKeySet() { return new UNavigableSet<K>(nm.descendingKeySet()); }
        public NavigableMap<K, V> subMap(K from, boolean fromIn, K to, boolean toIn) {
            return new UNavigableMap<K, V>(nm.subMap(from, fromIn, to, toIn));
        }
        public NavigableMap<K, V> headMap(K to, boolean in) { return new UNavigableMap<K, V>(nm.headMap(to, in)); }
        public NavigableMap<K, V> tailMap(K from, boolean in) { return new UNavigableMap<K, V>(nm.tailMap(from, in)); }
    }

    static <K, V> Map.Entry<K, V> entry(Map.Entry<K, V> e) {
        return e == null ? null : new UEntry<K, V>(e);
    }

    /** An entry whose setValue is refused. */
    static final class UEntry<K, V> implements Map.Entry<K, V> {
        private final Map.Entry<? extends K, ? extends V> e;

        UEntry(Map.Entry<? extends K, ? extends V> e) {
            this.e = Objects.requireNonNull(e);
        }

        public K getKey() { return e.getKey(); }
        public V getValue() { return e.getValue(); }
        public V setValue(V value) { throw refused(); }
        public int hashCode() { return e.hashCode(); }
        public boolean equals(Object o) { return o == this || AbstractMap.entryEquals(this, o); }
        public String toString() { return e.toString(); }
    }

    /** The entry set of an unmodifiable map: it hands out read-only entries only. */
    static final class UEntrySet<K, V> extends USet<Map.Entry<K, V>> {
        UEntrySet(Set<Map.Entry<K, V>> s) {
            super(s);
        }

        @SuppressWarnings("unchecked")
        public Iterator<Map.Entry<K, V>> iterator() {
            Iterator<Map.Entry<K, V>> it = (Iterator<Map.Entry<K, V>>) c.iterator();
            return new Iterator<Map.Entry<K, V>>() {
                public boolean hasNext() { return it.hasNext(); }
                public Map.Entry<K, V> next() { return new UEntry<K, V>(it.next()); }
                public void remove() { throw refused(); }
            };
        }

        public void forEach(Consumer<? super Map.Entry<K, V>> action) {
            Objects.requireNonNull(action);
            for (Map.Entry<K, V> e : this) {
                action.accept(e);
            }
        }

        @SuppressWarnings("unchecked")
        public Object[] toArray() {
            Object[] a = c.toArray();
            for (int i = 0; i < a.length; i++) {
                a[i] = new UEntry<K, V>((Map.Entry<K, V>) a[i]);
            }
            return a;
        }

        public <T> T[] toArray(T[] a) {
            Object[] items = toArray();
            return Support.toArray(items, items.length, a);
        }
    }
}
