package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class ArrayList<E> extends AbstractList<E> implements List<E>, RandomAccess, Cloneable, java.io.Serializable {
    /** Storage of a list made by new ArrayList() before anything needed room (spec 9.F1). */
    private static final Object[] UNSIZED = {};
    private static final Object[] NONE = {};
    // Spec 3.V2: the capacity new ArrayList() takes when it first needs storage.
    private static final int FIRST_CAPACITY = 10;

    private transient Object[] items;

    /** The storage itself, for a window's forEachRemaining (spec 9.F2). */
    Object[] storage() { return items; }
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
        if (minCapacity > (items == UNSIZED ? FIRST_CAPACITY : 0)) {
            modCount++;
            makeRoom(minCapacity);
        }
    }

    // Spec 3.V2: storage grows by half (at least to what is needed); an unsized list's first storage holds 10.
    private void makeRoom(int needed) {
        if (needed > items.length) {
            int grown = items == UNSIZED ? FIRST_CAPACITY : items.length + (items.length >> 1);
            items = Arrays.copyOf(items, Math.max(grown, needed));
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

    public E get(int index) { return at(index); }

    // ArrayList's own methods read the storage, never an overriding get or size (spec 8.C1).
    @SuppressWarnings("unchecked")
    private E at(int index) {
        checkElement(index);
        return (E) items[index];
    }

    E cursorGet(int i) { return at(i); }
    int cursorLimit() { return size; }

    // The walk stays below the size taken when it began, which never exceeds the storage's length.
    Object[] walkStorage(int i) { return items; }

    public E set(int index, E element) {
        E old = at(index);
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
        // Spec 9.F1: an index past the end is refused first; a negative one counts, then fails.
        if (index < 0) {
            modCount++;
        }
        E old = at(index);
        modCount++;
        removeSlots(index, index + 1);
        return old;
    }

    public boolean remove(Object o) {
        int i = Support.indexOf(items, 0, size, o);
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

    public boolean addAll(Collection<? extends E> c) { return insertAll(size, c); }
    public boolean addAll(int index, Collection<? extends E> c) { return insertAll(index, c); }

    // Spec 9.F1: adding an empty collection still counts as a change.
    private boolean insertAll(int index, Collection<? extends E> c) {
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

    public boolean removeAll(Collection<?> c) { return sift(c, false); }
    public boolean retainAll(Collection<?> c) { return sift(c, true); }

    /**
     * Spec 9.F1, 8.C1: asks c about each element in order, then closes the gaps. If c throws, the
     * elements it already judged still leave and the rest stay; if c changed this list, nothing
     * leaves and ConcurrentModificationException reports it (spec 12).
     */
    private boolean sift(Collection<?> c, boolean keepContained) {
        Objects.requireNonNull(c);
        int expected = modCount;
        boolean[] leaves = new boolean[size];
        int judged = 0;
        boolean removed = false;
        try {
            for (; judged < leaves.length; judged++) {
                boolean in = c.contains(items[judged]);
                if (modCount != expected) {
                    throw new ConcurrentModificationException();
                }
                leaves[judged] = in != keepContained;
            }
        } finally {
            if (modCount == expected) {
                removed = closeGaps(leaves, judged);
            }
        }
        return removed;
    }

    /** Drops each of the first `judged` elements marked in `leaves`, keeping order. */
    private boolean closeGaps(boolean[] leaves, int judged) {
        int w = 0;
        for (int r = 0; r < size; r++) {
            if (r >= judged || !leaves[r]) {
                items[w++] = items[r];
            }
        }
        if (w == size) {
            return false;
        }
        Arrays.fill(items, w, size, null);
        size = w;
        modCount++;
        return true;
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

    public ListIterator<E> listIterator(int index) { return indexOnlyListIterator(index, size); }
    public ListIterator<E> listIterator() { return cursorAt(0); }
    public Iterator<E> iterator() { return super.iterator(); }
    public List<E> subList(int fromIndex, int toIndex) {
        Support.checkSubList(fromIndex, toIndex, size);
        return SubList.of(this, fromIndex, toIndex);
    }

    // Spec 11: an index past the end names the size; a negative one fails in the array access.
    private void checkElement(int index) {
        if (index >= size) {
            throw Support.badIndex(index, size);
        }
    }

    int remainingStyle() { return 1; }
}
