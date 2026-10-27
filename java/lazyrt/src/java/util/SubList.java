package java.util;

/** The view returned by AbstractList.subList: a window of the parent, offset by `from`. */
class SubList<E> extends AbstractList<E> {
    private final AbstractList<E> parent;
    private final int from;
    private int size;

    SubList(AbstractList<E> parent, int from, int to) {
        this.parent = parent;
        this.from = from;
        this.size = to - from;
        this.modCount = parent.modCount;
    }

    /** A window of a RandomAccess list is RandomAccess too. */
    private static final class Fast<E> extends SubList<E> implements RandomAccess {
        Fast(AbstractList<E> parent, int from, int to) { super(parent, from, to); }
    }

    /** The window [from, to) of `parent`, whose bounds the caller has checked. */
    static <E> List<E> of(AbstractList<E> parent, int from, int to) {
        return parent instanceof RandomAccess ? new Fast<E>(parent, from, to) : new SubList<E>(parent, from, to);
    }

    public E get(int index) {
        Support.checkIndex(index, size);
        checkUnchanged();
        return parent.get(index + from);
    }

    public E set(int index, E element) {
        Support.checkIndex(index, size);
        checkUnchanged();
        return parent.set(index + from, element);
    }

    public int size() {
        checkUnchanged();
        return size;
    }

    public void add(int index, E element) {
        Support.checkPosition(index, size);
        checkUnchanged();
        parent.add(index + from, element);
        resync(1);
    }

    public E remove(int index) {
        Support.checkIndex(index, size);
        checkUnchanged();
        E old = parent.remove(index + from);
        resync(-1);
        return old;
    }

    protected void removeRange(int fromIndex, int toIndex) {
        checkUnchanged();
        parent.removeRange(fromIndex + from, toIndex + from);
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
        parent.addAll(from + index, c);
        resync(n);
        return true;
    }

    public Iterator<E> iterator() { return listIterator(); }

    // Spec 8.C1: the window's iterators are the parent's listIterator(from + index).
    public ListIterator<E> listIterator(int index) {
        checkUnchanged();
        Support.checkPosition(index, size);
        ListIterator<E> it = parent.listIterator(from + index);
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

            public int nextIndex() { return it.nextIndex() - from; }
            public int previousIndex() { return it.previousIndex() - from; }

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
        modCount = parent.modCount;
        size += delta;
    }

    private void checkUnchanged() {
        if (parent.modCount != modCount) {
            throw new ConcurrentModificationException();
        }
    }
}
