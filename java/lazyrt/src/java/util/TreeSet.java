package java.util;

/** The keys of a TreeMap (or of one of its range views), each mapped to one shared marker. */
public class TreeSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable, java.io.Serializable {
    private static final Object IN = new Object();

    private transient NavigableMap<E, Object> backing;

    TreeSet(NavigableMap<E, Object> backing) { this.backing = backing; }
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

    public Iterator<E> iterator() { return backing.navigableKeySet().iterator(); }
    public Iterator<E> descendingIterator() { return backing.descendingKeySet().iterator(); }
    public NavigableSet<E> descendingSet() { return new TreeSet<E>(backing.descendingMap()); }
    public int size() { return backing.size(); }
    public boolean isEmpty() { return backing.isEmpty(); }
    public boolean contains(Object o) { return backing.containsKey(o); }
    public boolean add(E e) { return backing.put(e, IN) == null; }
    public boolean remove(Object o) { return backing.remove(o) == IN; }
    public void clear() { backing.clear(); }
    public Comparator<? super E> comparator() { return backing.comparator(); }
    public E first() { return backing.firstKey(); }
    public E last() { return backing.lastKey(); }
    public E lower(E e) { return backing.lowerKey(e); }
    public E floor(E e) { return backing.floorKey(e); }
    public E ceiling(E e) { return backing.ceilingKey(e); }
    public E higher(E e) { return backing.higherKey(e); }
    public E pollFirst() { return TreeViews.Keys.keyOf(backing.pollFirstEntry()); }
    public E pollLast() { return TreeViews.Keys.keyOf(backing.pollLastEntry()); }

    public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
        return new TreeSet<E>(backing.subMap(fromElement, fromInclusive, toElement, toInclusive));
    }

    public NavigableSet<E> headSet(E toElement, boolean inclusive) {
        return new TreeSet<E>(backing.headMap(toElement, inclusive));
    }

    public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
        return new TreeSet<E>(backing.tailMap(fromElement, inclusive));
    }

    public SortedSet<E> subSet(E fromElement, E toElement) { return subSet(fromElement, true, toElement, false); }
    public SortedSet<E> headSet(E toElement) { return headSet(toElement, false); }
    public SortedSet<E> tailSet(E fromElement) { return tailSet(fromElement, true); }

    // Spec 6.M3: a sorted set added to an empty TreeSet of the same order is built in directly.
    @SuppressWarnings("unchecked")
    public boolean addAll(Collection<? extends E> c) {
        if (backing instanceof TreeMap && c.size() > 0 && c instanceof SortedSet) {
            TreeMap<E, Object> tm = (TreeMap<E, Object>) backing;
            if (tm.size() == 0 && Objects.equals(tm.comparator(), ((SortedSet<?>) c).comparator())) {
                tm.build(c.size(), c.iterator(), false, IN);
                return true;
            }
        }
        return super.addAll(c);
    }

    @SuppressWarnings("unchecked")
    public Object clone() {
        try {
            TreeSet<E> copy = (TreeSet<E>) super.clone();
            copy.backing = new TreeMap<E, Object>(backing);
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }
}
