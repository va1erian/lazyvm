package java.util;

/** The view returned by AbstractList.subList: a window of the parent, offset by `from`. */
class SubList<E> extends AbstractList<E> {
    private final AbstractList<E> backing;
    /** The list whose count an ArrayList window compares with: the ArrayList itself. */
    private final AbstractList<E> root;
    private final int start;
    /** Where this window starts in the root list, fixed when it is made. */
    private final int offset;
    private int size;
    /** Spec 9.F2: a window of an ArrayList (or of such a window) iterates like ArrayList itself. */
    private final boolean indexed;

    SubList(AbstractList<E> backing, int start, int to) {
        this.backing = backing;
        this.start = start;
        this.size = to - start;
        this.indexed = backing instanceof ArrayList || (backing instanceof SubList && ((SubList<E>) backing).indexed);
        this.root = indexed && backing instanceof SubList ? ((SubList<E>) backing).root : backing;
        this.offset = start + (backing instanceof SubList ? ((SubList<E>) backing).offset : 0);
        // Spec 9.F2: an ArrayList window starts in step with the ArrayList, even under a stale window.
        this.modCount = (indexed ? root : backing).modCount;
    }

    // Spec 9.F2: an ArrayList window's iterator sets through the ArrayList, so after forEachRemaining
    // it may write the element just past the window.
    void iteratorSet(int i, E e) {
        if (indexed) {
            root.set(offset + i, e);
        } else {
            set(i, e);
        }
    }

    /** The ArrayList's storage, which an ArrayList window reads and writes at its offset. */
    private Object[] slots() { return ((ArrayList<E>) root).storage(); }

    /**
     * Spec 9.F2: an ArrayList window's forEachRemaining reads the ArrayList's storage as it was when
     * the walk began and watches only the window's own count, so a change made to the ArrayList
     * directly shows only in the check at the end.
     */
    Object[] walkStorage(int i) {
        if (!indexed) {
            return null;
        }
        Object[] slots = slots();
        if (offset + i >= slots.length) {
            throw new ConcurrentModificationException();
        }
        return slots;
    }

    int walkOffset() { return offset; }
    int remainingStyle() { return indexed ? 2 : 0; }

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

    // Spec 9.F2: an ArrayList window reads and writes the ArrayList's storage, not through its parent.
    @SuppressWarnings("unchecked")
    public E get(int index) {
        Support.checkIndex(index, size);
        checkUnchanged();
        return indexed ? (E) slots()[offset + index] : backing.get(index + start);
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        Support.checkIndex(index, size);
        checkUnchanged();
        if (!indexed) {
            return backing.set(index + start, element);
        }
        Object[] slots = slots();
        int at = offset + index;
        E old = (E) slots[at];
        slots[at] = element;
        return old;
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
        // Spec 9.F2: a window that is not an ArrayList's asks its own size(), which checks for a change.
        Support.checkSubList(fromIndex, toIndex, fromIndex < 0 || indexed ? size : size());
        return of(this, fromIndex, toIndex);
    }

    /** After a change made through the parent: adopt its modCount and the new size. */
    private void resync(int delta) {
        modCount = backing.modCount;
        size += delta;
    }

    // Spec 9.F2: an ArrayList window compares with the ArrayList; other windows with their parent.
    private boolean stale() { return (indexed ? root : backing).modCount != modCount; }

    private void checkUnchanged() {
        if (stale()) {
            throw new ConcurrentModificationException();
        }
    }
}
