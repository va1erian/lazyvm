package java.util;

/** The view returned by AbstractList.subList: a window of the parent, offset by `from`. */
class SubList<E> extends AbstractList<E> {
    private final AbstractList<E> backing;
    private final int start;
    private int size;
    /** Spec 9.F2: a window of an ArrayList (or of such a window) iterates like ArrayList itself. */
    private final boolean indexed;

    SubList(AbstractList<E> backing, int start, int to) {
        this.backing = backing;
        this.start = start;
        this.size = to - start;
        this.modCount = backing.modCount;
        this.indexed = backing instanceof ArrayList || (backing instanceof SubList && ((SubList<E>) backing).indexed);
    }

    int cursorLimit() { return size; }
    void checkFresh() { checkUnchanged(); }

    /** A window of a RandomAccess list is RandomAccess too. */
    private static final class Fast<E> extends SubList<E> implements RandomAccess {
        Fast(AbstractList<E> backing, int start, int to) { super(backing, start, to); }
    }

    /** The window [from, to) of `parent`, whose bounds the caller has checked. */
    static <E> List<E> of(AbstractList<E> backing, int start, int to) {
        return backing instanceof RandomAccess ? new Fast<E>(backing, start, to) : new SubList<E>(backing, start, to);
    }

    public E get(int index) {
        Support.checkIndex(index, size);
        checkUnchanged();
        return backing.get(index + start);
    }

    public E set(int index, E element) {
        Support.checkIndex(index, size);
        checkUnchanged();
        return backing.set(index + start, element);
    }

    public int size() {
        checkUnchanged();
        return size;
    }

    public void add(int index, E element) {
        Support.checkPosition(index, size);
        checkUnchanged();
        backing.add(index + start, element);
        resync(1);
    }

    public E remove(int index) {
        Support.checkIndex(index, size);
        checkUnchanged();
        E old = backing.remove(index + start);
        resync(-1);
        return old;
    }

    protected void removeRange(int fromIndex, int toIndex) {
        checkUnchanged();
        backing.removeRange(fromIndex + start, toIndex + start);
        resync(fromIndex - toIndex);
    }

    public boolean addAll(Collection<? extends E> c) { return addAll(size, c); }

    public boolean addAll(int index, Collection<? extends E> c) {
        Support.checkPosition(index, size);
        int n = c.size();
        if (n == 0) {
            return false;
        }
        checkUnchanged();
        backing.addAll(start + index, c);
        resync(n);
        return true;
    }

    public Iterator<E> iterator() { return listIterator(); }

    // Spec 8.C1: the window's iterators are the backing list's listIterator(start + index); spec 9.F2:
    // a window of an ArrayList uses the index iterator instead.
    public ListIterator<E> listIterator(int index) {
        checkUnchanged();
        Support.checkPosition(index, size);
        if (indexed) {
            return cursorAt(index);
        }
        ListIterator<E> it = backing.listIterator(start + index);
        return new ListIterator<E>() {
            public boolean hasNext() { return nextIndex() < size; }

            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return it.next();
            }

            public boolean hasPrevious() { return previousIndex() >= 0; }

            public E previous() {
                if (!hasPrevious()) {
                    throw new NoSuchElementException();
                }
                return it.previous();
            }

            public int nextIndex() { return it.nextIndex() - start; }
            public int previousIndex() { return it.previousIndex() - start; }

            public void remove() {
                it.remove();
                resync(-1);
            }

            public void set(E e) { it.set(e); }

            public void add(E e) {
                it.add(e);
                resync(1);
            }
        };
    }

    public List<E> subList(int fromIndex, int toIndex) {
        Support.checkSubList(fromIndex, toIndex, size);
        return of(this, fromIndex, toIndex);
    }

    /** After a change made through the parent: adopt its modCount and the new size. */
    private void resync(int delta) {
        modCount = backing.modCount;
        size += delta;
    }

    private void checkUnchanged() {
        if (backing.modCount != modCount) {
            throw new ConcurrentModificationException();
        }
    }
}
