package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/** A {@link LinkedCore} nobody blocks on. {@code addAll} checks everything before it adds. */
public class ConcurrentLinkedQueue<E> extends AbstractQueue<E> implements java.util.Queue<E>, java.io.Serializable {
    private final LinkedCore<E> core = new LinkedCore<>();

    public ConcurrentLinkedQueue() {
    }

    public ConcurrentLinkedQueue(Collection<? extends E> c) {
        for (E e : c) {
            core.offer(e, false);
        }
    }

    public boolean add(E e) {
        return core.offer(e, false);
    }

    public boolean offer(E e) {
        return core.offer(e, false);
    }

    public E poll() {
        return core.poll(true);
    }

    public E peek() {
        return core.peek(true);
    }

    public boolean isEmpty() {
        return core.size() == 0;
    }

    public int size() {
        return core.size();
    }

    public boolean contains(Object o) {
        return core.contains(o);
    }

    public boolean remove(Object o) {
        return core.remove(o, true);
    }

    public boolean addAll(Collection<? extends E> c) {
        if (c == this) {
            throw new IllegalArgumentException();
        }
        return addChecked(core, c);
    }

    /** All or nothing: a null anywhere in {@code c} adds none of it. */
    static <E> boolean addChecked(LinkedCore<E> core, Collection<? extends E> c) {
        ArrayList<E> copy = new ArrayList<>(c);
        for (E e : copy) {
            if (e == null) {
                throw new NullPointerException();
            }
        }
        for (E e : copy) {
            core.offer(e, false);
        }
        return !copy.isEmpty();
    }

    public Object[] toArray() {
        return core.toList().toArray();
    }

    public <T> T[] toArray(T[] a) {
        return core.toList().toArray(a);
    }

    public Iterator<E> iterator() {
        return core.iterator(false);
    }
}
