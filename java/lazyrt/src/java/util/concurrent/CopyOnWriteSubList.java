package java.util.concurrent;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A window on the parent, valid while the parent's array is the one this list last saw: any other
 * write replaces the array, and every operation here then throws (concurrent.md, sub-lists).
 */
final class CopyOnWriteSubList<E> extends AbstractList<E> {
    private static final Object[] NOTHING = new Object[0];

    private final CopyOnWriteArrayList<E> parent;
    private final Object mu;
    private final int offset;
    private int size;
    private Object[] seen;

    CopyOnWriteSubList(CopyOnWriteArrayList<E> parent, int offset, int size) {
        this.parent = parent;
        this.mu = parent.monitor();
        this.offset = offset;
        this.size = size;
        this.seen = parent.snapshot();
    }

    private void check() {
        if (parent.snapshot() != seen) {
            throw new ConcurrentModificationException();
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ",Size: " + size);
        }
    }

    /** Puts {@code in} over the {@code gone} elements at {@code at} of this window. */
    private void replace(int at, int gone, Object[] in) {
        Object[] next = CopyOnWriteArrayList.spliced(seen, offset + at, gone, in);
        parent.publish(next);
        seen = next;
        size += in.length - gone;
    }

    public int size() {
        synchronized (mu) {
            check();
            return size;
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (mu) {
            checkIndex(index);
            check();
            return (E) seen[offset + index];
        }
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        synchronized (mu) {
            checkIndex(index);
            check();
            E old = (E) seen[offset + index];
            replace(index, 1, new Object[] {element});
            return old;
        }
    }

    public boolean add(E e) {
        synchronized (mu) {
            check();
            replace(size, 0, new Object[] {e});
            return true;
        }
    }

    public void add(int index, E element) {
        synchronized (mu) {
            check();
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            replace(index, 0, new Object[] {element});
        }
    }

    public boolean addAll(Collection<? extends E> c) {
        Object[] in = c.toArray();
        if (in.length == 0) {
            return false;
        }
        synchronized (mu) {
            check();
            replace(size, 0, in);
            return true;
        }
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        Object[] in = c.toArray();
        synchronized (mu) {
            check();
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (in.length == 0) {
                return false;
            }
            replace(index, 0, in);
            return true;
        }
    }

    public void clear() {
        synchronized (mu) {
            check();
            replace(0, size, NOTHING);
        }
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        synchronized (mu) {
            checkIndex(index);
            check();
            E old = (E) seen[offset + index];
            replace(index, 1, NOTHING);
            return old;
        }
    }

    public boolean remove(Object o) {
        synchronized (mu) {
            check();
            int at = CopyOnWriteArrayList.indexIn(o, seen, offset, offset + size);
            if (at < 0) {
                return false;
            }
            replace(at - offset, 1, NOTHING);
            return true;
        }
    }

    public Iterator<E> iterator() {
        return listIterator(0);
    }

    public ListIterator<E> listIterator(int index) {
        synchronized (mu) {
            check();
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return new CopyOnWriteArrayList.Snapshot<>(seen, offset, offset + size, index);
        }
    }

    public List<E> subList(int fromIndex, int toIndex) {
        synchronized (mu) {
            check();
            if (fromIndex < 0 || toIndex > size || fromIndex > toIndex) {
                throw new IndexOutOfBoundsException();
            }
            return new CopyOnWriteSubList<>(parent, offset + fromIndex, toIndex - fromIndex);
        }
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        Object[] a;
        int from;
        int to;
        synchronized (mu) {
            check();
            a = seen;
            from = offset;
            to = offset + size;
        }
        for (int i = from; i < to; i++) {
            action.accept((E) a[i]);
        }
    }

    @SuppressWarnings("unchecked")
    public void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator);
        synchronized (mu) {
            check();
            Object[] in = Arrays.copyOfRange(seen, offset, offset + size);
            for (int i = 0; i < in.length; i++) {
                in[i] = operator.apply((E) in[i]);
            }
            replace(0, size, in);
        }
    }

    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        synchronized (mu) {
            check();
            Object[] in = Arrays.copyOfRange(seen, offset, offset + size);
            Arrays.sort((E[]) in, c);
            replace(0, size, in);
        }
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return keepOnly(x -> !c.contains(x));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return keepOnly(c::contains);
    }

    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return keepOnly(x -> !filter.test(x));
    }

    @SuppressWarnings("unchecked")
    private boolean keepOnly(Predicate<? super E> keep) {
        synchronized (mu) {
            check();
            Object[] kept = new Object[size];
            int n = 0;
            for (int i = 0; i < size; i++) {
                if (keep.test((E) seen[offset + i])) {
                    kept[n++] = seen[offset + i];
                }
            }
            if (n == size) {
                return false;
            }
            replace(0, size, Arrays.copyOf(kept, n));
            return true;
        }
    }
}
