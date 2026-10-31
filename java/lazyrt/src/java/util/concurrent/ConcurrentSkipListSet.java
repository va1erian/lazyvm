package java.util.concurrent;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;

/** The keys of a skip list map, or of a range of one, with {@code add} working within the range. */
public class ConcurrentSkipListSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable, java.io.Serializable {
    /** Replaced only by {@code clone}, on a copy nobody else has yet. */
    private ConcurrentNavigableMap<E, Object> map;

    public ConcurrentSkipListSet() {
        this(new ConcurrentSkipListMap<E, Object>());
    }

    public ConcurrentSkipListSet(Comparator<? super E> comparator) {
        this(new ConcurrentSkipListMap<E, Object>(comparator));
    }

    public ConcurrentSkipListSet(Collection<? extends E> c) {
        this(new ConcurrentSkipListMap<E, Object>());
        addAll(c);
    }

    public ConcurrentSkipListSet(SortedSet<E> s) {
        this(new ConcurrentSkipListMap<E, Object>(s.comparator()));
        addAll(s);
    }

    ConcurrentSkipListSet(ConcurrentNavigableMap<E, Object> map) {
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    public ConcurrentSkipListSet<E> clone() {
        try {
            ConcurrentSkipListSet<E> copy = (ConcurrentSkipListSet<E>) super.clone();
            copy.map = new ConcurrentSkipListMap<>(map);
            return copy;
        } catch (CloneNotSupportedException impossible) {
            throw new InternalError();
        }
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    public boolean add(E e) {
        return map.putIfAbsent(e, Boolean.TRUE) == null;
    }

    public boolean remove(Object o) {
        return map.remove(o, Boolean.TRUE);
    }

    public void clear() {
        map.clear();
    }

    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    public Iterator<E> descendingIterator() {
        return map.descendingKeySet().iterator();
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Set)) {
            return false;
        }
        Collection<?> c = (Collection<?>) o;
        try {
            return containsAll(c) && c.containsAll(this);
        } catch (ClassCastException | NullPointerException unlike) {
            return false;
        }
    }

    public boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object e : c) {
            changed |= remove(e);
        }
        return changed;
    }

    public E lower(E e) {
        return map.lowerKey(e);
    }

    public E floor(E e) {
        return map.floorKey(e);
    }

    public E ceiling(E e) {
        return map.ceilingKey(e);
    }

    public E higher(E e) {
        return map.higherKey(e);
    }

    public E pollFirst() {
        return map.keySet().pollFirst();
    }

    public E pollLast() {
        return map.keySet().pollLast();
    }

    public Comparator<? super E> comparator() {
        return map.comparator();
    }

    public E first() {
        return map.firstKey();
    }

    public E last() {
        return map.lastKey();
    }

    public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
        return new ConcurrentSkipListSet<>(map.subMap(fromElement, fromInclusive, toElement, toInclusive));
    }

    public NavigableSet<E> headSet(E toElement, boolean inclusive) {
        return new ConcurrentSkipListSet<>(map.headMap(toElement, inclusive));
    }

    public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
        return new ConcurrentSkipListSet<>(map.tailMap(fromElement, inclusive));
    }

    public NavigableSet<E> subSet(E fromElement, E toElement) {
        return subSet(fromElement, true, toElement, false);
    }

    public NavigableSet<E> headSet(E toElement) {
        return headSet(toElement, false);
    }

    public NavigableSet<E> tailSet(E fromElement) {
        return tailSet(fromElement, true);
    }

    public NavigableSet<E> descendingSet() {
        return new ConcurrentSkipListSet<>(map.descendingMap());
    }
}
