package java.util;

public abstract class AbstractList<E> extends AbstractCollection<E> implements List<E> {
    protected transient int modCount = 0;

    protected AbstractList() {
    }

    public boolean add(E e) {
        add(size(), e);
        return true;
    }

    public abstract E get(int index);

    public E set(int index, E element) { throw new UnsupportedOperationException(); }
    public void add(int index, E element) { throw new UnsupportedOperationException(); }
    public E remove(int index) { throw new UnsupportedOperationException(); }

    // Spec 8.C1: indexOf walks listIterator(), lastIndexOf walks listIterator(size()) backwards.
    public int indexOf(Object o) {
        for (ListIterator<E> it = listIterator(); it.hasNext();) {
            if (Support.eq(o, it.next())) {
                return it.previousIndex();
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        for (ListIterator<E> it = listIterator(size()); it.hasPrevious();) {
            if (Support.eq(o, it.previous())) {
                return it.nextIndex();
            }
        }
        return -1;
    }

    public void clear() { removeRange(0, size()); }

    public boolean addAll(int index, Collection<? extends E> c) {
        Support.checkPosition(index, size());
        boolean changed = false;
        for (E e : c) {
            add(index++, e);
            changed = true;
        }
        return changed;
    }

    // Spec 10.X7: the plain iterator is not a ListIterator.
    public Iterator<E> iterator() { return new Walk(0); }
    public ListIterator<E> listIterator() { return listIterator(0); }

    public ListIterator<E> listIterator(int index) {
        Support.checkPosition(index, size());
        return new Cursor(index);
    }

    /** The end the index iterator compares with in hasNext(); a window answers without checking. */
    int cursorLimit() { return size(); }

    /** Fails if this list is a view whose backing list changed; the index iterator checks it. */
    void checkFresh() {
    }

    /** The index iterator's set(e) at index i; an ArrayList window may write past its end (F2). */
    void iteratorSet(int i, E e) { set(i, e); }

    /** forEachRemaining's walk from i to end while this list's count stays `expected`; returns where it stopped. */
    int walkRest(int i, int end, int expected, java.util.function.Consumer<? super E> action) {
        while (i != end && modCount == expected) {
            action.accept(get(i++));
        }
        return i;
    }

    /**
     * Spec 9.F2: how the index iterator's forEachRemaining runs: 0 one next() at a time; 1 as
     * ArrayList and Vector (nothing at the end, then the last element visited is removable); 2 as a
     * window of an ArrayList (the same, but nothing is removable afterwards).
     */
    int remainingStyle() { return 0; }

    /** The index iterator, started at an index the caller checked. */
    final ListIterator<E> cursorAt(int index) { return new Cursor(index); }

    /** For ArrayList and Vector, whose bad iterator start names only the index (spec 11). */
    final ListIterator<E> indexOnlyListIterator(int index) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return new Cursor(index);
    }

    public List<E> subList(int fromIndex, int toIndex) {
        Support.checkSubList(fromIndex, toIndex, size());
        return SubList.of(this, fromIndex, toIndex);
    }

    // Spec 8.C1: equals walks both listIterator()s in step; equals runs on the receiver's element.
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof List)) {
            return false;
        }
        ListIterator<E> mine = listIterator();
        ListIterator<?> theirs = ((List<?>) o).listIterator();
        while (mine.hasNext() && theirs.hasNext()) {
            if (!Support.eq(mine.next(), theirs.next())) {
                return false;
            }
        }
        return !(mine.hasNext() || theirs.hasNext());
    }

    public int hashCode() {
        int h = 1;
        for (E e : this) {
            h = 31 * h + Support.hash(e);
        }
        return h;
    }

    protected void removeRange(int fromIndex, int toIndex) {
        ListIterator<E> it = listIterator(fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            it.next();
            it.remove();
        }
    }

    /**
     * The iterator of an indexed list: one get(i) per step (spec 8.C1), and a modCount check
     * before the end check (spec 9.F2).
     */
    private class Walk implements Iterator<E> {
        int next;
        int last = -1;
        int expected = modCount;

        Walk(int start) { next = start; }
        public boolean hasNext() { return next != cursorLimit(); }

        public E next() {
            checkUnchanged();
            try {
                E e = get(next);
                last = next++;
                return e;
            } catch (IndexOutOfBoundsException end) {
                checkUnchanged();
                throw new NoSuchElementException();
            }
        }

        public void forEachRemaining(java.util.function.Consumer<? super E> action) {
            int style = remainingStyle();
            if (style == 0) {
                Iterator.super.forEachRemaining(action);
                return;
            }
            Objects.requireNonNull(action);
            int end = cursorLimit();
            int i = next;
            if (i >= end) {
                return;
            }
            i = walkRest(i, end, expected, action);
            next = i;
            last = style == 1 ? i - 1 : i;
            checkUnchanged();
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            checkUnchanged();
            try {
                AbstractList.this.remove(last);
            } catch (IndexOutOfBoundsException gone) {
                throw new ConcurrentModificationException();
            }
            if (last < next) {
                next--;
            }
            last = -1;
            expected = modCount;
        }

        final void checkUnchanged() {
            checkFresh();
            if (modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }

    /** The list iterator: the walk plus moving back, set and add. */
    private final class Cursor extends Walk implements ListIterator<E> {
        Cursor(int start) { super(start); }
        public boolean hasPrevious() { return next != 0; }

        public E previous() {
            checkUnchanged();
            try {
                E e = get(next - 1);
                last = --next;
                return e;
            } catch (IndexOutOfBoundsException end) {
                checkUnchanged();
                throw new NoSuchElementException();
            }
        }

        public int nextIndex() { return next; }
        public int previousIndex() { return next - 1; }

        public void set(E e) {
            if (last < 0) {
                throw new IllegalStateException();
            }
            checkUnchanged();
            try {
                iteratorSet(last, e);
            } catch (IndexOutOfBoundsException gone) {
                throw new ConcurrentModificationException();
            }
            expected = modCount;
        }

        public void add(E e) {
            checkUnchanged();
            try {
                AbstractList.this.add(next, e);
                next++;
            } catch (IndexOutOfBoundsException gone) {
                throw new ConcurrentModificationException();
            }
            last = -1;
            expected = modCount;
        }
    }
}
