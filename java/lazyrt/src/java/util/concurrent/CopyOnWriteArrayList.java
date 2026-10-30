package java.util.concurrent;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * An array that is never changed, only replaced under {@code mu}, so a reader or iterator works on
 * the array it read. Index errors come from the array access itself where concurrent.md asks for
 * {@code ArrayIndexOutOfBoundsException}.
 */
public class CopyOnWriteArrayList<E> implements List<E>, RandomAccess, Cloneable, java.io.Serializable {
    /** Replaced by {@code clone}, which must not share it. An array, to survive serialization. */
    private byte[] mu = new byte[0];
    private Object[] array = new Object[0];

    public CopyOnWriteArrayList() {
    }

    public CopyOnWriteArrayList(Collection<? extends E> c) {
        array = plain(c.toArray());
    }

    public CopyOnWriteArrayList(E[] toCopyIn) {
        array = plain(toCopyIn);
    }

    /** A source may hand over an array of a narrower type, which could not hold every E. */
    private static Object[] plain(Object[] a) {
        return Arrays.copyOf(a, a.length, Object[].class);
    }

    final Object monitor() {
        return mu;
    }

    final Object[] snapshot() {
        return array;
    }

    final void publish(Object[] a) {
        array = a;
    }

    public int size() {
        return array.length;
    }

    public boolean isEmpty() {
        return array.length == 0;
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    public int indexOf(Object o) {
        Object[] a = array;
        return indexIn(o, a, 0, a.length);
    }

    public int indexOf(E e, int index) {
        Object[] a = array;
        return indexIn(e, a, index, a.length);
    }

    public int lastIndexOf(Object o) {
        Object[] a = array;
        return lastIndexIn(o, a, a.length - 1);
    }

    public int lastIndexOf(E e, int index) {
        return lastIndexIn(e, array, index);
    }

    static int indexIn(Object o, Object[] a, int from, int to) {
        for (int i = from; i < to; i++) {
            if (o == null ? a[i] == null : o.equals(a[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexIn(Object o, Object[] a, int from) {
        for (int i = from; i >= 0; i--) {
            if (o == null ? a[i] == null : o.equals(a[i])) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    public Object clone() {
        try {
            CopyOnWriteArrayList<E> copy = (CopyOnWriteArrayList<E>) super.clone();
            copy.mu = new byte[0];
            return copy;
        } catch (CloneNotSupportedException impossible) {
            throw new InternalError();
        }
    }

    public Object[] toArray() {
        return array.clone();
    }

    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        Object[] mine = array;
        if (a.length < mine.length) {
            return (T[]) Arrays.copyOf(mine, mine.length, a.getClass());
        }
        System.arraycopy(mine, 0, a, 0, mine.length);
        if (a.length > mine.length) {
            a[mine.length] = null;
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        return (E) array[index];
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        synchronized (mu) {
            Object[] a = array;
            E old = (E) a[index];
            // The same element is no write: a sub-list stays valid (concurrent.md).
            if (old != element) {
                Object[] b = a.clone();
                b[index] = element;
                array = b;
            }
            return old;
        }
    }

    public boolean add(E e) {
        synchronized (mu) {
            Object[] a = array;
            Object[] b = Arrays.copyOf(a, a.length + 1);
            b[a.length] = e;
            array = b;
            return true;
        }
    }

    public void add(int index, E element) {
        synchronized (mu) {
            Object[] a = array;
            if (index < 0 || index > a.length) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + a.length);
            }
            array = spliced(a, index, 0, new Object[] {element});
        }
    }

    /** {@code a} with {@code gone} elements at {@code at} replaced by all of {@code in}. */
    static Object[] spliced(Object[] a, int at, int gone, Object[] in) {
        Object[] b = new Object[a.length - gone + in.length];
        System.arraycopy(a, 0, b, 0, at);
        System.arraycopy(in, 0, b, at, in.length);
        System.arraycopy(a, at + gone, b, at + in.length, a.length - at - gone);
        return b;
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        synchronized (mu) {
            Object[] a = array;
            E old = (E) a[index];
            array = spliced(a, index, 1, new Object[0]);
            return old;
        }
    }

    /**
     * This and {@code addIfAbsent} run the argument's {@code equals}, user code, with {@code mu} free:
     * it may take a lock another writer holds. The answer stands only if the array is still that one.
     */
    public boolean remove(Object o) {
        for (;;) {
            Object[] a = array;
            int at = indexIn(o, a, 0, a.length);
            if (at < 0) {
                return false;
            }
            synchronized (mu) {
                if (array == a) {
                    array = spliced(a, at, 1, new Object[0]);
                    return true;
                }
            }
        }
    }

    public boolean addIfAbsent(E e) {
        for (;;) {
            Object[] a = array;
            if (indexIn(e, a, 0, a.length) >= 0) {
                return false;
            }
            synchronized (mu) {
                if (array == a) {
                    return add(e);
                }
            }
        }
    }

    public boolean containsAll(Collection<?> c) {
        Object[] a = array;
        for (Object o : c) {
            if (indexIn(o, a, 0, a.length) < 0) {
                return false;
            }
        }
        return true;
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return keepOnly(x -> !c.contains(x));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return keepOnly(c::contains);
    }

    public int addAllAbsent(Collection<? extends E> c) {
        Object[] in = c.toArray();
        synchronized (mu) {
            Object[] a = array;
            Object[] b = Arrays.copyOf(a, a.length + in.length);
            int n = a.length;
            for (Object o : in) {
                if (indexIn(o, b, 0, n) < 0) {
                    b[n++] = o;
                }
            }
            if (n == a.length) {
                return 0;
            }
            array = Arrays.copyOf(b, n);
            return n - a.length;
        }
    }

    public void clear() {
        synchronized (mu) {
            // A new array even if empty: a clear counts as a write, for sub-lists.
            array = new Object[0];
        }
    }

    public boolean addAll(Collection<? extends E> c) {
        Object[] in = c.toArray();
        synchronized (mu) {
            if (in.length == 0) {
                return false;
            }
            array = spliced(array, array.length, 0, in);
            return true;
        }
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        Object[] in = c.toArray();
        synchronized (mu) {
            Object[] a = array;
            if (index < 0 || index > a.length) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + a.length);
            }
            if (in.length == 0) {
                return false;
            }
            array = spliced(a, index, 0, in);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (Object o : array) {
            action.accept((E) o);
        }
    }

    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return keepOnly(x -> !filter.test(x));
    }

    /** Whether any element failed {@code keep} and so left. */
    @SuppressWarnings("unchecked")
    private boolean keepOnly(Predicate<? super E> keep) {
        synchronized (mu) {
            Object[] a = array;
            Object[] kept = new Object[a.length];
            int n = 0;
            for (Object x : a) {
                if (keep.test((E) x)) {
                    kept[n++] = x;
                }
            }
            if (n == a.length) {
                return false;
            }
            array = Arrays.copyOf(kept, n);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator);
        synchronized (mu) {
            Object[] b = array.clone();
            for (int i = 0; i < b.length; i++) {
                b[i] = operator.apply((E) b[i]);
            }
            array = b;
        }
    }

    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        synchronized (mu) {
            Object[] b = array.clone();
            Arrays.sort((E[]) b, c);
            array = b;
        }
    }

    public String toString() {
        return Arrays.toString(array);
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof List)) {
            return false;
        }
        Iterator<?> other = ((List<?>) o).iterator();
        for (Object x : array) {
            if (!other.hasNext() || !Objects.equals(x, other.next())) {
                return false;
            }
        }
        return !other.hasNext();
    }

    public int hashCode() {
        int h = 1;
        for (Object x : array) {
            h = 31 * h + (x == null ? 0 : x.hashCode());
        }
        return h;
    }

    public Iterator<E> iterator() {
        Object[] a = array;
        return new Snapshot<>(a, 0, a.length, 0);
    }

    public ListIterator<E> listIterator() {
        Object[] a = array;
        return new Snapshot<>(a, 0, a.length, 0);
    }

    public ListIterator<E> listIterator(int index) {
        Object[] a = array;
        if (index < 0 || index > a.length) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return new Snapshot<>(a, 0, a.length, index);
    }

    public List<E> subList(int fromIndex, int toIndex) {
        synchronized (mu) {
            if (fromIndex < 0 || toIndex > array.length || fromIndex > toIndex) {
                throw new IndexOutOfBoundsException();
            }
            return new CopyOnWriteSubList<>(this, fromIndex, toIndex - fromIndex);
        }
    }

    /** Walks a slice of one array: nothing can change under it, and it changes nothing. */
    static final class Snapshot<E> implements ListIterator<E> {
        private final Object[] a;
        private final int start;
        private final int end;
        private int at;

        Snapshot(Object[] a, int start, int end, int index) {
            this.a = a;
            this.start = start;
            this.end = end;
            this.at = start + index;
        }

        public boolean hasNext() {
            return at < end;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (at >= end) {
                throw new NoSuchElementException();
            }
            return (E) a[at++];
        }

        public boolean hasPrevious() {
            return at > start;
        }

        @SuppressWarnings("unchecked")
        public E previous() {
            if (at <= start) {
                throw new NoSuchElementException();
            }
            return (E) a[--at];
        }

        public int nextIndex() {
            return at - start;
        }

        public int previousIndex() {
            return at - start - 1;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

        public void set(E e) {
            throw new UnsupportedOperationException();
        }

        public void add(E e) {
            throw new UnsupportedOperationException();
        }
    }
}
