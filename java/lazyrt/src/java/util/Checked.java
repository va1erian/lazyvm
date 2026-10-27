package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The views of Collections.checked*: whatever goes in is first checked against the element (or
 * key and value) type, with the messages of spec 11; reads go straight to the backing collection.
 */
final class Checked {
    private Checked() {
    }

    static <E> List<E> list(List<E> list, Class<E> elementClass) {
        return list instanceof RandomAccess ? new CRandomList<E>(list, elementClass) : new CList<E>(list, elementClass);
    }

    static class CColl<E> implements Collection<E>, java.io.Serializable {
        final Collection<E> c;
        final Class<E> elementClass;

        CColl(Collection<E> c, Class<E> elementClass) {
            this.c = Objects.requireNonNull(c, "c");
            this.elementClass = Objects.requireNonNull(elementClass, "type");
        }

        @SuppressWarnings("unchecked")
        E check(Object o) {
            if (o != null && !elementClass.isInstance(o)) {
                throw new ClassCastException("Attempt to insert " + o.getClass()
                        + " element into collection with element type " + elementClass);
            }
            return (E) o;
        }

        /** The elements of `more`, all checked before any is added. */
        @SuppressWarnings("unchecked")
        Collection<E> checkAll(Collection<? extends E> more) {
            Object[] a = more.toArray();
            for (Object o : a) {
                check(o);
            }
            return (Collection<E>) Arrays.asList(a);
        }

        public int size() { return c.size(); }
        public boolean isEmpty() { return c.isEmpty(); }
        public boolean contains(Object o) { return c.contains(o); }
        public Object[] toArray() { return c.toArray(); }
        public <T> T[] toArray(T[] a) { return c.toArray(a); }
        public String toString() { return c.toString(); }
        public boolean remove(Object o) { return c.remove(o); }
        public void clear() { c.clear(); }
        public boolean containsAll(Collection<?> coll) { return c.containsAll(coll); }
        public boolean removeAll(Collection<?> coll) { return c.removeAll(coll); }
        public boolean retainAll(Collection<?> coll) { return c.retainAll(coll); }
        // Spec 10.X7: a plain iterator that steps through next(), so forEachRemaining is not forwarded.
        public Iterator<E> iterator() {
            Iterator<E> it = c.iterator();
            return new Iterator<E>() {
                public boolean hasNext() { return it.hasNext(); }
                public E next() { return it.next(); }
                public void remove() { it.remove(); }
            };
        }
        public boolean add(E e) { return c.add(check(e)); }
        public boolean addAll(Collection<? extends E> coll) { return c.addAll(checkAll(coll)); }
        public void forEach(Consumer<? super E> action) { c.forEach(action); }
        public boolean removeIf(Predicate<? super E> filter) { return c.removeIf(filter); }
    }

    static final class CQueue<E> extends CColl<E> implements Queue<E> {
        private final Queue<E> q;

        CQueue(Queue<E> q, Class<E> elementClass) {
            super(q, elementClass);
            this.q = q;
        }

        public boolean offer(E e) { return q.offer(check(e)); }
        public E element() { return q.element(); }
        public E peek() { return q.peek(); }
        public E poll() { return q.poll(); }
        public E remove() { return q.remove(); }
        public boolean equals(Object o) { return o == this || c.equals(o); }
        public int hashCode() { return c.hashCode(); }
    }

    static class CSet<E> extends CColl<E> implements Set<E> {
        CSet(Set<E> s, Class<E> elementClass) {
            super(s, elementClass);
        }

        public boolean equals(Object o) { return o == this || c.equals(o); }
        public int hashCode() { return c.hashCode(); }
    }

    static class CSortedSet<E> extends CSet<E> implements SortedSet<E> {
        private final SortedSet<E> ss;

        CSortedSet(SortedSet<E> s, Class<E> elementClass) {
            super(s, elementClass);
            ss = s;
        }

        public Comparator<? super E> comparator() { return ss.comparator(); }
        public E first() { return ss.first(); }
        public E last() { return ss.last(); }
        public SortedSet<E> subSet(E from, E to) { return new CSortedSet<E>(ss.subSet(from, to), elementClass); }
        public SortedSet<E> headSet(E to) { return new CSortedSet<E>(ss.headSet(to), elementClass); }
        public SortedSet<E> tailSet(E from) { return new CSortedSet<E>(ss.tailSet(from), elementClass); }
    }

    static final class CNavigableSet<E> extends CSortedSet<E> implements NavigableSet<E> {
        private final NavigableSet<E> ns;

        CNavigableSet(NavigableSet<E> s, Class<E> elementClass) {
            super(s, elementClass);
            ns = s;
        }

        public E lower(E e) { return ns.lower(e); }
        public E floor(E e) { return ns.floor(e); }
        public E ceiling(E e) { return ns.ceiling(e); }
        public E higher(E e) { return ns.higher(e); }
        public E pollFirst() { return ns.pollFirst(); }
        public E pollLast() { return ns.pollLast(); }
        public NavigableSet<E> descendingSet() { return new CNavigableSet<E>(ns.descendingSet(), elementClass); }
        public Iterator<E> descendingIterator() { return ns.descendingIterator(); }
        public NavigableSet<E> subSet(E from, boolean fromIn, E to, boolean toIn) {
            return new CNavigableSet<E>(ns.subSet(from, fromIn, to, toIn), elementClass);
        }
        public NavigableSet<E> headSet(E to, boolean in) { return new CNavigableSet<E>(ns.headSet(to, in), elementClass); }
        public NavigableSet<E> tailSet(E from, boolean in) { return new CNavigableSet<E>(ns.tailSet(from, in), elementClass); }
    }

    static class CList<E> extends CColl<E> implements List<E> {
        private final List<E> l;

        CList(List<E> l, Class<E> elementClass) {
            super(l, elementClass);
            this.l = l;
        }

        public boolean equals(Object o) { return o == this || l.equals(o); }
        public int hashCode() { return l.hashCode(); }
        public E get(int index) { return l.get(index); }
        public E remove(int index) { return l.remove(index); }
        public int indexOf(Object o) { return l.indexOf(o); }
        public int lastIndexOf(Object o) { return l.lastIndexOf(o); }
        public E set(int index, E element) { return l.set(index, check(element)); }
        public void add(int index, E element) { l.add(index, check(element)); }
        public boolean addAll(int index, Collection<? extends E> c) { return l.addAll(index, checkAll(c)); }
        public ListIterator<E> listIterator() { return listIterator(0); }
        public List<E> subList(int from, int to) { return list(l.subList(from, to), elementClass); }
        public void replaceAll(UnaryOperator<E> op) { Objects.requireNonNull(op); l.replaceAll(e -> check(op.apply(e))); }
        public void sort(Comparator<? super E> c) { l.sort(c); }

        public ListIterator<E> listIterator(int index) {
            ListIterator<E> it = l.listIterator(index);
            return new ListIterator<E>() {
                public boolean hasNext() { return it.hasNext(); }
                public E next() { return it.next(); }
                public boolean hasPrevious() { return it.hasPrevious(); }
                public E previous() { return it.previous(); }
                public int nextIndex() { return it.nextIndex(); }
                public int previousIndex() { return it.previousIndex(); }
                public void remove() { it.remove(); }
                public void set(E e) { it.set(check(e)); }
                public void add(E e) { it.add(check(e)); }
                // Spec 10.X7: unlike iterator(), the list iterator hands its forEachRemaining on.
                public void forEachRemaining(java.util.function.Consumer<? super E> action) { it.forEachRemaining(action); }
            };
        }
    }

    static final class CRandomList<E> extends CList<E> implements RandomAccess {
        CRandomList(List<E> l, Class<E> elementClass) {
            super(l, elementClass);
        }
    }

    static class CMap<K, V> implements Map<K, V>, java.io.Serializable {
        private final Map<K, V> m;
        final Class<K> keyClass;
        final Class<V> valueClass;

        CMap(Map<K, V> m, Class<K> keyClass, Class<V> valueClass) {
            this.m = Objects.requireNonNull(m);
            this.keyClass = Objects.requireNonNull(keyClass);
            this.valueClass = Objects.requireNonNull(valueClass);
        }

        void check(Object k, Object v) {
            if (k != null && !keyClass.isInstance(k)) {
                throw new ClassCastException("Attempt to insert " + k.getClass()
                        + " key into map with key type " + keyClass);
            }
            checkValue(valueClass, v);
        }

        <R extends V> R checked(K k, R v) {
            check(k, v);
            return v;
        }

        public int size() { return m.size(); }
        public boolean isEmpty() { return m.isEmpty(); }
        public boolean containsKey(Object key) { return m.containsKey(key); }
        public boolean containsValue(Object v) { return m.containsValue(v); }
        public V get(Object key) { return m.get(key); }
        public V remove(Object key) { return m.remove(key); }
        public void clear() { m.clear(); }
        public Set<K> keySet() { return m.keySet(); }
        public Collection<V> values() { return m.values(); }
        public boolean equals(Object o) { return o == this || m.equals(o); }
        public int hashCode() { return m.hashCode(); }
        public String toString() { return m.toString(); }
        public V getOrDefault(Object k, V dflt) { return m.getOrDefault(k, dflt); }
        public void forEach(BiConsumer<? super K, ? super V> action) { m.forEach(action); }
        public Set<Map.Entry<K, V>> entrySet() { return new CEntrySet<K, V>(m.entrySet(), valueClass); }
        public V put(K key, V value) { check(key, value); return m.put(key, value); }
        public V putIfAbsent(K key, V value) { check(key, value); return m.putIfAbsent(key, value); }
        public boolean remove(Object key, Object value) { return m.remove(key, value); }
        public boolean replace(K key, V oldValue, V newValue) { check(key, newValue); return m.replace(key, oldValue, newValue); }
        public V replace(K key, V value) { check(key, value); return m.replace(key, value); }

        public void putAll(Map<? extends K, ? extends V> t) {
            Map<K, V> copy = new LinkedHashMap<K, V>();
            for (Map.Entry<? extends K, ? extends V> e : t.entrySet()) {
                copy.put(e.getKey(), checked(e.getKey(), e.getValue()));
            }
            m.putAll(copy);
        }

        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> f) {
            Objects.requireNonNull(f);
            m.replaceAll((k, v) -> checked(k, f.apply(k, v)));
        }

        public V computeIfAbsent(K key, Function<? super K, ? extends V> f) {
            Objects.requireNonNull(f);
            return m.computeIfAbsent(key, k -> checked(k, f.apply(k)));
        }

        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> f) {
            Objects.requireNonNull(f);
            return m.computeIfPresent(key, (k, v) -> checked(k, f.apply(k, v)));
        }

        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> f) {
            Objects.requireNonNull(f);
            return m.compute(key, (k, v) -> checked(k, f.apply(k, v)));
        }

        // Spec 10.X4: an absent key's value is stored unchecked; only f's result is checked.
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> f) {
            Objects.requireNonNull(f);
            return m.merge(key, value, (old, v) -> checked(null, f.apply(old, v)));
        }
    }

    static void checkValue(Class<?> valueClass, Object v) {
        if (v != null && !valueClass.isInstance(v)) {
            throw new ClassCastException("Attempt to insert " + v.getClass()
                    + " value into map with value type " + valueClass);
        }
    }

    static <K, V> Map.Entry<K, V> entry(Map.Entry<K, V> e, Class<V> valueClass) {
        return e == null ? null : new CEntry<K, V>(e, valueClass);
    }

    /** An entry whose setValue checks the value type. */
    static final class CEntry<K, V> implements Map.Entry<K, V> {
        private final Map.Entry<K, V> e;
        private final Class<V> valueClass;

        CEntry(Map.Entry<K, V> e, Class<V> valueClass) {
            this.e = e;
            this.valueClass = valueClass;
        }

        public K getKey() { return e.getKey(); }
        public V getValue() { return e.getValue(); }
        public V setValue(V value) { checkValue(valueClass, value); return e.setValue(value); }
        public int hashCode() { return e.hashCode(); }
        public boolean equals(Object o) { return o == this || AbstractMap.entryEquals(this, o); }
        public String toString() { return e.toString(); }
    }

    /** The entry set of a checked map: it hands out checked entries. */
    static final class CEntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final Set<Map.Entry<K, V>> s;
        private final Class<V> valueClass;

        CEntrySet(Set<Map.Entry<K, V>> s, Class<V> valueClass) {
            this.s = s;
            this.valueClass = valueClass;
        }

        public int size() { return s.size(); }
        public boolean contains(Object o) { return s.contains(o); }
        public boolean remove(Object o) { return s.remove(o); }
        public void clear() { s.clear(); }

        // Spec 10.X4: the element is cast to Map.Entry before add refuses it; addAll always refuses.
        // Both override Set's methods (the element type is Map.Entry), so they stay public.
        public boolean add(Map.Entry<K, V> e) { throw Unmodifiable.refused(); }
        public boolean addAll(Collection<? extends Map.Entry<K, V>> c) { throw Unmodifiable.refused(); }

        public Iterator<Map.Entry<K, V>> iterator() {
            Iterator<Map.Entry<K, V>> it = s.iterator();
            return new Iterator<Map.Entry<K, V>>() {
                public boolean hasNext() { return it.hasNext(); }
                public Map.Entry<K, V> next() { return new CEntry<K, V>(it.next(), valueClass); }
                public void remove() { it.remove(); }
            };
        }
    }

    static class CSortedMap<K, V> extends CMap<K, V> implements SortedMap<K, V> {
        private final SortedMap<K, V> sm;

        CSortedMap(SortedMap<K, V> m, Class<K> keyClass, Class<V> valueClass) {
            super(m, keyClass, valueClass);
            sm = m;
        }

        public Comparator<? super K> comparator() { return sm.comparator(); }
        public K firstKey() { return sm.firstKey(); }
        public K lastKey() { return sm.lastKey(); }
        public SortedMap<K, V> subMap(K from, K to) { return new CSortedMap<K, V>(sm.subMap(from, to), keyClass, valueClass); }
        public SortedMap<K, V> headMap(K to) { return new CSortedMap<K, V>(sm.headMap(to), keyClass, valueClass); }
        public SortedMap<K, V> tailMap(K from) { return new CSortedMap<K, V>(sm.tailMap(from), keyClass, valueClass); }
    }

    static final class CNavigableMap<K, V> extends CSortedMap<K, V> implements NavigableMap<K, V> {
        private final NavigableMap<K, V> nm;

        CNavigableMap(NavigableMap<K, V> m, Class<K> keyClass, Class<V> valueClass) {
            super(m, keyClass, valueClass);
            nm = m;
        }

        public Map.Entry<K, V> lowerEntry(K key) { return entry(nm.lowerEntry(key), valueClass); }
        public K lowerKey(K key) { return nm.lowerKey(key); }
        public Map.Entry<K, V> floorEntry(K key) { return entry(nm.floorEntry(key), valueClass); }
        public K floorKey(K key) { return nm.floorKey(key); }
        public Map.Entry<K, V> ceilingEntry(K key) { return entry(nm.ceilingEntry(key), valueClass); }
        public K ceilingKey(K key) { return nm.ceilingKey(key); }
        public Map.Entry<K, V> higherEntry(K key) { return entry(nm.higherEntry(key), valueClass); }
        public K higherKey(K key) { return nm.higherKey(key); }
        public Map.Entry<K, V> firstEntry() { return entry(nm.firstEntry(), valueClass); }
        public Map.Entry<K, V> lastEntry() { return entry(nm.lastEntry(), valueClass); }
        public Map.Entry<K, V> pollFirstEntry() { return entry(nm.pollFirstEntry(), valueClass); }
        public Map.Entry<K, V> pollLastEntry() { return entry(nm.pollLastEntry(), valueClass); }
        public NavigableMap<K, V> descendingMap() { return new CNavigableMap<K, V>(nm.descendingMap(), keyClass, valueClass); }
        public NavigableSet<K> navigableKeySet() { return new CNavigableSet<K>(nm.navigableKeySet(), keyClass); }
        public NavigableSet<K> descendingKeySet() { return new CNavigableSet<K>(nm.descendingKeySet(), keyClass); }
        public NavigableMap<K, V> subMap(K from, boolean fromIn, K to, boolean toIn) {
            return new CNavigableMap<K, V>(nm.subMap(from, fromIn, to, toIn), keyClass, valueClass);
        }
        public NavigableMap<K, V> headMap(K to, boolean in) { return new CNavigableMap<K, V>(nm.headMap(to, in), keyClass, valueClass); }
        public NavigableMap<K, V> tailMap(K from, boolean in) { return new CNavigableMap<K, V>(nm.tailMap(from, in), keyClass, valueClass); }
    }
}
