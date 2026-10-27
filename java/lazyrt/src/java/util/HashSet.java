package java.util;

/** A HashMap of its elements (spec 1.H1), each mapped to one shared marker value. */
public class HashSet<E> extends AbstractSet<E> implements Set<E>, Cloneable, java.io.Serializable {
    private static final Object IN = new Object();

    private transient HashMap<E, Object> backing;

    public HashSet() { backing = new HashMap<>(); }

    // Spec 1.H4: room for c.size() / .75 + 1 elements, at least 16, then one add per element.
    public HashSet(Collection<? extends E> c) {
        backing = new HashMap<>(Math.max((int) (c.size() / .75f) + 1, 16));
        addAll(c);
    }

    public HashSet(int initialCapacity, float loadFactor) { backing = new HashMap<>(initialCapacity, loadFactor); }
    public HashSet(int initialCapacity) { backing = new HashMap<>(initialCapacity); }

    /** For LinkedHashSet: a set over the given (linked) map. */
    HashSet(HashMap<E, Object> backing) { this.backing = backing; }
    public Iterator<E> iterator() { return backing.keySet().iterator(); }
    public int size() { return backing.size(); }
    public boolean isEmpty() { return backing.isEmpty(); }
    public boolean contains(Object o) { return backing.containsKey(o); }
    public boolean add(E e) { return backing.put(e, IN) == null; }
    public boolean remove(Object o) { return backing.remove(o) == IN; }
    public void clear() { backing.clear(); }

    // Spec 1.H4: a clone of the set is a clone of its map.
    @SuppressWarnings("unchecked")
    public Object clone() {
        try {
            HashSet<E> copy = (HashSet<E>) super.clone();
            copy.backing = (HashMap<E, Object>) backing.clone();
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }
}
