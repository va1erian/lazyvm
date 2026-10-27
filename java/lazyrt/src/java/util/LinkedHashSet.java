package java.util;

/** A HashSet over a LinkedHashMap, so it iterates in insertion order. */
public class LinkedHashSet<E> extends HashSet<E> implements Set<E>, Cloneable, java.io.Serializable {
    public LinkedHashSet(int initialCapacity, float loadFactor) {
        super(new LinkedHashMap<E, Object>(initialCapacity, loadFactor));
    }

    public LinkedHashSet(int initialCapacity) {
        super(new LinkedHashMap<E, Object>(initialCapacity));
    }

    public LinkedHashSet() {
        super(new LinkedHashMap<E, Object>());
    }

    public LinkedHashSet(Collection<? extends E> c) {
        super(new LinkedHashMap<E, Object>());
        addAll(c);
    }
}
