package java.util;

/** A HashMap of its elements (spec 1.H1), each mapped to one shared marker value. */
public class HashSet<E> extends AbstractSet<E> implements Set<E>, Cloneable, java.io.Serializable {
    private static final Object PRESENT = new Object();

    private transient HashMap<E, Object> map;

    public HashSet() { map = new HashMap<>(); }

    // Spec 1.H4: room for c.size() / .75 + 1 elements, at least 16, then one add per element.
    public HashSet(Collection<? extends E> c) {
        map = new HashMap<>(Math.max((int) (c.size() / .75f) + 1, 16));
        addAll(c);
    }

    public HashSet(int initialCapacity, float loadFactor) { map = new HashMap<>(initialCapacity, loadFactor); }
    public HashSet(int initialCapacity) { map = new HashMap<>(initialCapacity); }

    /** For LinkedHashSet: a set over the given (linked) map. */
    HashSet(HashMap<E, Object> map) { this.map = map; }
    public Iterator<E> iterator() { return map.keySet().iterator(); }
    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public boolean contains(Object o) { return map.containsKey(o); }
    public boolean add(E e) { return map.put(e, PRESENT) == null; }
    public boolean remove(Object o) { return map.remove(o) == PRESENT; }
    public void clear() { map.clear(); }

    // Spec 1.H4: a clone of the set is a clone of its map.
    @SuppressWarnings("unchecked")
    public Object clone() {
        try {
            HashSet<E> copy = (HashSet<E>) super.clone();
            copy.map = (HashMap<E, Object>) map.clone();
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }
}
