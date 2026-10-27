package java.util;

/**
 * A doubly-linked ring with one sentinel node: the sentinel's next is the first element and its
 * prev the last, so no operation needs a special case for an end of the list.
 */
public class LinkedList<E> extends AbstractSequentialList<E>
        implements List<E>, Deque<E>, Cloneable, java.io.Serializable {
    private static final class Link<E> {
        E element;
        Link<E> back;
        Link<E> ahead;

        Link(E element) { this.element = element; }
    }

    private transient Link<E> ring = newRing();
    private transient int size;

    public LinkedList() {
    }

    public LinkedList(Collection<? extends E> c) { addAll(c); }

    private static <E> Link<E> newRing() {
        Link<E> r = new Link<E>(null);
        r.back = r;
        r.ahead = r;
        return r;
    }

    /** Links a new node holding e just before `at`. */
    private void insertBefore(E e, Link<E> at) {
        Link<E> n = new Link<E>(e);
        n.back = at.back;
        n.ahead = at;
        at.back.ahead = n;
        at.back = n;
        size++;
        modCount++;
    }

    private E cut(Link<E> n) {
        n.back.ahead = n.ahead;
        n.ahead.back = n.back;
        size--;
        modCount++;
        return n.element;
    }

    /** The node at an index already checked to be in [0, size), walked from the nearer end. */
    private Link<E> seek(int index) {
        Link<E> n = ring;
        if (index < size / 2) {
            for (int i = -1; i < index; i++) {
                n = n.ahead;
            }
        } else {
            for (int i = size; i > index; i--) {
                n = n.back;
            }
        }
        return n;
    }

    /** The node at a position in [0, size]; position size is the sentinel. */
    private Link<E> seekPosition(int position) { return position == size ? ring : seek(position); }

    private Link<E> first() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return ring.ahead;
    }

    private Link<E> last() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return ring.back;
    }

    public E getFirst() { return first().element; }
    public E getLast() { return last().element; }
    public E removeFirst() { return cut(first()); }
    public E removeLast() { return cut(last()); }
    public void addFirst(E e) { insertBefore(e, ring.ahead); }
    public void addLast(E e) { insertBefore(e, ring); }
    public boolean contains(Object o) { return indexOf(o) >= 0; }
    public int size() { return size; }

    public boolean add(E e) {
        insertBefore(e, ring);
        return true;
    }

    public boolean remove(Object o) { return removeFirstOccurrence(o); }

    // Spec 9.F1: adding nothing is not a change for LinkedList.
    public boolean addAll(Collection<? extends E> c) { return addAll(size, c); }

    @SuppressWarnings("unchecked")
    public boolean addAll(int index, Collection<? extends E> c) {
        Support.checkPosition(index, size);
        Object[] more = c.toArray();
        Link<E> at = seekPosition(index);
        for (Object e : more) {
            insertBefore((E) e, at);
        }
        return more.length != 0;
    }

    public void clear() {
        ring = newRing();
        size = 0;
        modCount++;
    }

    public E get(int index) {
        Support.checkIndex(index, size);
        return seek(index).element;
    }

    public E set(int index, E element) {
        Support.checkIndex(index, size);
        Link<E> n = seek(index);
        E old = n.element;
        n.element = element;
        return old;
    }

    public void add(int index, E element) {
        Support.checkPosition(index, size);
        insertBefore(element, seekPosition(index));
    }

    public E remove(int index) {
        Support.checkIndex(index, size);
        return cut(seek(index));
    }

    public int indexOf(Object o) {
        int i = 0;
        for (Link<E> n = ring.ahead; n != ring; n = n.ahead, i++) {
            if (Support.eq(o, n.element)) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        int i = size - 1;
        for (Link<E> n = ring.back; n != ring; n = n.back, i--) {
            if (Support.eq(o, n.element)) {
                return i;
            }
        }
        return -1;
    }

    public E peek() { return peekFirst(); }
    public E element() { return getFirst(); }
    public E poll() { return pollFirst(); }
    public E remove() { return removeFirst(); }
    public boolean offer(E e) { return add(e); }

    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    public E peekFirst() { return size == 0 ? null : ring.ahead.element; }
    public E peekLast() { return size == 0 ? null : ring.back.element; }
    public E pollFirst() { return size == 0 ? null : cut(ring.ahead); }
    public E pollLast() { return size == 0 ? null : cut(ring.back); }
    public void push(E e) { addFirst(e); }
    public E pop() { return removeFirst(); }

    public boolean removeFirstOccurrence(Object o) {
        for (Link<E> n = ring.ahead; n != ring; n = n.ahead) {
            if (Support.eq(o, n.element)) {
                cut(n);
                return true;
            }
        }
        return false;
    }

    public boolean removeLastOccurrence(Object o) {
        for (Link<E> n = ring.back; n != ring; n = n.back) {
            if (Support.eq(o, n.element)) {
                cut(n);
                return true;
            }
        }
        return false;
    }

    // Spec 11: a bad start names the index and the size.
    public ListIterator<E> listIterator(int index) {
        Support.checkPosition(index, size);
        return new Walker(index);
    }

    public Iterator<E> descendingIterator() {
        ListIterator<E> it = new Walker(size);
        return new Iterator<E>() {
            public boolean hasNext() { return it.hasPrevious(); }
            public E next() { return it.previous(); }
            public void remove() { it.remove(); }
        };
    }

    public Object clone() {
        try {
            @SuppressWarnings("unchecked")
            LinkedList<E> copy = (LinkedList<E>) super.clone();
            copy.ring = newRing();
            copy.size = 0;
            copy.modCount = 0;
            for (Link<E> n = ring.ahead; n != ring; n = n.ahead) {
                copy.add(n.element);
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public Object[] toArray() {
        Object[] out = new Object[size];
        int i = 0;
        for (Link<E> n = ring.ahead; n != ring; n = n.ahead) {
            out[i++] = n.element;
        }
        return out;
    }

    public <T> T[] toArray(T[] a) { return Support.storeEach(toArray(), size, a); }

    /**
     * Spec 9.F2: next() checks for modification before the end; hasNext() is index < size().
     */
    private final class Walker implements ListIterator<E> {
        private Link<E> next;
        private Link<E> last;
        private int index;
        private int expected = modCount;

        Walker(int start) {
            next = seekPosition(start);
            index = start;
        }

        public boolean hasNext() { return index < size; }

        public E next() {
            checkUnchanged();
            if (index >= size) {
                throw new NoSuchElementException();
            }
            last = next;
            next = next.ahead;
            index++;
            return last.element;
        }

        public boolean hasPrevious() { return index > 0; }

        public E previous() {
            checkUnchanged();
            if (index <= 0) {
                throw new NoSuchElementException();
            }
            next = next.back;
            last = next;
            index--;
            return last.element;
        }

        public int nextIndex() { return index; }
        public int previousIndex() { return index - 1; }

        // Spec 9.F2: remove() checks for a change first, set() for a missing element first.
        public void remove() {
            checkUnchanged();
            if (last == null) {
                throw new IllegalStateException();
            }
            if (next == last) {
                next = last.ahead;
            } else {
                index--;
            }
            cut(last);
            last = null;
            expected = modCount;
        }

        public void set(E e) {
            if (last == null) {
                throw new IllegalStateException();
            }
            checkUnchanged();
            last.element = e;
        }

        public void add(E e) {
            checkUnchanged();
            last = null;
            insertBefore(e, next);
            index++;
            expected = modCount;
        }

        // Spec 9.F2: walks while nothing changed, then checks, even with nothing left to visit.
        public void forEachRemaining(java.util.function.Consumer<? super E> action) {
            Objects.requireNonNull(action);
            while (modCount == expected && index < size) {
                last = next;
                next = next.ahead;
                index++;
                action.accept(last.element);
            }
            checkUnchanged();
        }

        private void checkUnchanged() {
            if (modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }
}
