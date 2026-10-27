package java.util;

/** The keys of a TreeMap (or of one of its range views), each mapped to one shared marker. */
public class TreeSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable, java.io.Serializable {
    private static final Object PRESENT = new Object();

    private transient NavigableMap<E, Object> m;

    TreeSet(NavigableMap<E, Object> m) { this.m = m; }
    public TreeSet() { this(new TreeMap<E, Object>()); }
    public TreeSet(Comparator<? super E> comparator) { this(new TreeMap<E, Object>(comparator)); }

    public TreeSet(Collection<? extends E> c) {
        this();
        addAll(c);
    }

    public TreeSet(SortedSet<E> s) {
        this(s.comparator());
        addAll(s);
    }

    public Iterator<E> iterator() { return m.navigableKeySet().iterator(); }
    public Iterator<E> descendingIterator() { return m.descendingKeySet().iterator(); }
    public NavigableSet<E> descendingSet() { return new TreeSet<E>(m.descendingMap()); }
    public int size() { return m.size(); }
    public boolean isEmpty() { return m.isEmpty(); }
    public boolean contains(Object o) { return m.containsKey(o); }
    public boolean add(E e) { return m.put(e, PRESENT) == null; }
    public boolean remove(Object o) { return m.remove(o) == PRESENT; }
    public void clear() { m.clear(); }
    public Comparator<? super E> comparator() { return m.comparator(); }
    public E first() { return m.firstKey(); }
    public E last() { return m.lastKey(); }
    public E lower(E e) { return m.lowerKey(e); }
    public E floor(E e) { return m.floorKey(e); }
    public E ceiling(E e) { return m.ceilingKey(e); }
    public E higher(E e) { return m.higherKey(e); }
    public E pollFirst() { return TreeViews.Keys.keyOf(m.pollFirstEntry()); }
    public E pollLast() { return TreeViews.Keys.keyOf(m.pollLastEntry()); }

    public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
        return new TreeSet<E>(m.subMap(fromElement, fromInclusive, toElement, toInclusive));
    }

    public NavigableSet<E> headSet(E toElement, boolean inclusive) {
        return new TreeSet<E>(m.headMap(toElement, inclusive));
    }

    public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
        return new TreeSet<E>(m.tailMap(fromElement, inclusive));
    }

    public SortedSet<E> subSet(E fromElement, E toElement) { return subSet(fromElement, true, toElement, false); }
    public SortedSet<E> headSet(E toElement) { return headSet(toElement, false); }
    public SortedSet<E> tailSet(E fromElement) { return tailSet(fromElement, true); }

    // Spec 6.M3: a sorted set added to an empty TreeSet of the same order is built in directly.
    @SuppressWarnings("unchecked")
    public boolean addAll(Collection<? extends E> c) {
        if (m instanceof TreeMap && c.size() > 0 && c instanceof SortedSet) {
            TreeMap<E, Object> tm = (TreeMap<E, Object>) m;
            if (tm.size() == 0 && Objects.equals(tm.comparator(), ((SortedSet<?>) c).comparator())) {
                tm.build(c.size(), c.iterator(), false, PRESENT);
                return true;
            }
        }
        return super.addAll(c);
    }

    @SuppressWarnings("unchecked")
    public Object clone() {
        try {
            TreeSet<E> copy = (TreeSet<E>) super.clone();
            copy.m = new TreeMap<E, Object>(m);
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }
}
