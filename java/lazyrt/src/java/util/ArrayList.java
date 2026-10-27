package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class ArrayList<E> extends AbstractList<E> implements List<E>, RandomAccess, Cloneable, java.io.Serializable {
    /** Storage of a list made by new ArrayList() before anything needed room (spec 9.F1). */
    private static final Object[] UNSIZED = {};
    private static final Object[] NONE = {};

    private transient Object[] items;
    private int size;

    public ArrayList(int initialCapacity) {
        Support.checkCapacity(initialCapacity);
        items = initialCapacity == 0 ? NONE : new Object[initialCapacity];
    }

    public ArrayList() { items = UNSIZED; }

    public ArrayList(Collection<? extends E> c) {
        items = Support.plainArray(c.toArray());
        size = items.length;
        if (size == 0) {
            items = NONE;
        }
    }

    public void trimToSize() {
        modCount++;
        if (size < items.length) {
            items = size == 0 ? NONE : Arrays.copyOf(items, size);
        }
    }

    // Spec 9.F1: any positive request counts as a change, except that an unsized list counts only
    // requests above its default capacity of 10.
    public void ensureCapacity(int minCapacity) {
        if (minCapacity > (items == UNSIZED ? 10 : 0)) {
            modCount++;
            makeRoom(minCapacity);
        }
    }

    private void makeRoom(int needed) {
        if (needed > items.length) {
            items = Arrays.copyOf(items, Math.max(needed, items.length * 2));
        }
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public boolean contains(Object o) { return indexOf(o) >= 0; }
    public int indexOf(Object o) { return Support.indexOf(items, 0, size, o); }
    public int lastIndexOf(Object o) { return Support.lastIndexOf(items, size - 1, o); }

    public Object clone() {
        try {
            @SuppressWarnings("unchecked")
            ArrayList<E> copy = (ArrayList<E>) super.clone();
            copy.items = Arrays.copyOf(items, size);
            copy.modCount = 0;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public Object[] toArray() { return Arrays.copyOf(items, size); }
    public <T> T[] toArray(T[] a) { return Support.toArray(items, size, a); }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        checkElement(index);
        return (E) items[index];
    }

    public E set(int index, E element) {
        E old = get(index);
        items[index] = element;
        return old;
    }

    public boolean add(E e) {
        modCount++;
        makeRoom(size + 1);
        items[size++] = e;
        return true;
    }

    public void add(int index, E element) {
        Support.checkPosition(index, size);
        modCount++;
        makeRoom(size + 1);
        System.arraycopy(items, index, items, index + 1, size - index);
        items[index] = element;
        size++;
    }

    public E remove(int index) {
        E old = get(index);
        modCount++;
        removeSlots(index, index + 1);
        return old;
    }

    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0) {
            return false;
        }
        modCount++;
        removeSlots(i, i + 1);
        return true;
    }

    public void clear() {
        modCount++;
        Arrays.fill(items, 0, size, null);
        size = 0;
    }

    public boolean addAll(Collection<? extends E> c) { return addAll(size, c); }

    // Spec 9.F1: adding an empty collection still counts as a change.
    public boolean addAll(int index, Collection<? extends E> c) {
        Support.checkPosition(index, size);
        Object[] more = c.toArray();
        modCount++;
        makeRoom(size + more.length);
        System.arraycopy(items, index, items, index + more.length, size - index);
        System.arraycopy(more, 0, items, index, more.length);
        size += more.length;
        return more.length != 0;
    }

    protected void removeRange(int fromIndex, int toIndex) {
        modCount++;
        removeSlots(fromIndex, toIndex);
    }

    private void removeSlots(int from, int to) {
        System.arraycopy(items, to, items, from, size - to);
        int newSize = size - (to - from);
        Arrays.fill(items, newSize, size, null);
        size = newSize;
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeIf(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeIf(e -> !c.contains(e));
    }

    @SuppressWarnings("unchecked")
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        int kept = Support.sweep(this, items, size, (Predicate<Object>) filter);
        if (kept == size) {
            return false;
        }
        size = kept;
        modCount++;
        return true;
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> action) { Support.forEach(this, items, size, (Consumer<Object>) action); }

    @SuppressWarnings("unchecked")
    public void replaceAll(UnaryOperator<E> operator) {
        Support.replaceAll(this, items, size, (UnaryOperator<Object>) operator);
        modCount++;
    }

    // Spec 7.S2: sorts its own storage, then checks for and bumps the modification count.
    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        int expected = modCount;
        Arrays.sort((E[]) items, 0, size, c);
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
        modCount++;
    }

    public ListIterator<E> listIterator(int index) { return indexOnlyListIterator(index); }
    public ListIterator<E> listIterator() { return listIterator(0); }
    public Iterator<E> iterator() { return super.iterator(); }
    public List<E> subList(int fromIndex, int toIndex) { return super.subList(fromIndex, toIndex); }

    // Spec 11: an index past the end names the size; a negative one fails in the array access.
    private void checkElement(int index) {
        if (index >= size) {
            throw Support.badIndex(index, size);
        }
    }
}
