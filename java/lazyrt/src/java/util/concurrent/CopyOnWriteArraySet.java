package java.util.concurrent;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A {@link CopyOnWriteArrayList} that refuses duplicates, so it keeps insertion order. */
public class CopyOnWriteArraySet<E> extends AbstractSet<E> implements java.io.Serializable {
    private final CopyOnWriteArrayList<E> list = new CopyOnWriteArrayList<>();

    public CopyOnWriteArraySet() {
    }

    public CopyOnWriteArraySet(Collection<? extends E> c) {
        list.addAllAbsent(c);
    }

    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public boolean contains(Object o) {
        return list.contains(o);
    }

    public Object[] toArray() {
        return list.toArray();
    }

    public <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }

    public void clear() {
        list.clear();
    }

    public boolean remove(Object o) {
        return list.remove(o);
    }

    public boolean add(E e) {
        return list.addIfAbsent(e);
    }

    public boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    public boolean addAll(Collection<? extends E> c) {
        return list.addAllAbsent(c) > 0;
    }

    public boolean removeAll(Collection<?> c) {
        return list.removeAll(c);
    }

    public boolean retainAll(Collection<?> c) {
        return list.retainAll(c);
    }

    public Iterator<E> iterator() {
        return list.iterator();
    }

    /** Matches each of the other set's elements against an unmatched one of ours: no size() call. */
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Set)) {
            return false;
        }
        Object[] mine = list.toArray();
        boolean[] matched = new boolean[mine.length];
        int count = 0;
        for (Object other : (Set<?>) o) {
            int i = 0;
            while (i < mine.length && (matched[i] || !Objects.equals(other, mine[i]))) {
                i++;
            }
            if (i == mine.length) {
                return false;
            }
            matched[i] = true;
            count++;
        }
        return count == mine.length;
    }

    public boolean removeIf(Predicate<? super E> filter) {
        return list.removeIf(filter);
    }

    public void forEach(Consumer<? super E> action) {
        list.forEach(action);
    }
}
