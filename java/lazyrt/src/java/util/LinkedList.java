package java.util;

/**
 * A doubly-linked ring with one sentinel node: the sentinel's next is the first element and its
 * prev the last, so no operation needs a special case for an end of the list.
 */
public class LinkedList<E> extends AbstractSequentialList<E>
        implements List<E>, Deque<E>, Cloneable, java.io.Serializable {
    private static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) { this.item = item; }
    }

    private transient Node<E> ring = newRing();
    private transient int size;

    public LinkedList() {
    }

    public LinkedList(Collection<? extends E> c) { addAll(c); }

    private static <E> Node<E> newRing() {
        Node<E> r = new Node<E>(null);
        r.prev = r;
        r.next = r;
        return r;
    }

    /** Links a new node holding e just before `at`. */
    private void linkBefore(E e, Node<E> at) {
        Node<E> n = new Node<E>(e);
        n.prev = at.prev;
        n.next = at;
        at.prev.next = n;
        at.prev = n;
        size++;
        modCount++;
    }

    private E unlink(Node<E> n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
        size--;
        modCount++;
        return n.item;
    }

    /** The node at an index already checked to be in [0, size), walked from the nearer end. */
    private Node<E> node(int index) {
        Node<E> n = ring;
        if (index < size / 2) {
            for (int i = -1; i < index; i++) {
                n = n.next;
            }
        } else {
            for (int i = size; i > index; i--) {
                n = n.prev;
            }
        }
        return n;
    }

    /** The node at a position in [0, size]; position size is the sentinel. */
    private Node<E> nodeAt(int position) { return position == size ? ring : node(position); }

    private Node<E> first() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return ring.next;
    }

    private Node<E> last() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return ring.prev;
    }

    public E getFirst() { return first().item; }
    public E getLast() { return last().item; }
    public E removeFirst() { return unlink(first()); }
    public E removeLast() { return unlink(last()); }
    public void addFirst(E e) { linkBefore(e, ring.next); }
    public void addLast(E e) { linkBefore(e, ring); }
    public boolean contains(Object o) { return indexOf(o) >= 0; }
    public int size() { return size; }

    public boolean add(E e) {
        linkBefore(e, ring);
        return true;
    }

    public boolean remove(Object o) { return removeFirstOccurrence(o); }

    // Spec 9.F1: adding nothing is not a change for LinkedList.
    public boolean addAll(Collection<? extends E> c) { return addAll(size, c); }

    @SuppressWarnings("unchecked")
    public boolean addAll(int index, Collection<? extends E> c) {
        Support.checkPosition(index, size);
        Object[] more = c.toArray();
        Node<E> at = nodeAt(index);
        for (Object e : more) {
            linkBefore((E) e, at);
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
        return node(index).item;
    }

    public E set(int index, E element) {
        Support.checkIndex(index, size);
        Node<E> n = node(index);
        E old = n.item;
        n.item = element;
        return old;
    }

    public void add(int index, E element) {
        Support.checkPosition(index, size);
        linkBefore(element, nodeAt(index));
    }

    public E remove(int index) {
        Support.checkIndex(index, size);
        return unlink(node(index));
    }

    public int indexOf(Object o) {
        int i = 0;
        for (Node<E> n = ring.next; n != ring; n = n.next, i++) {
            if (Support.eq(o, n.item)) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        int i = size - 1;
        for (Node<E> n = ring.prev; n != ring; n = n.prev, i--) {
            if (Support.eq(o, n.item)) {
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

    public E peekFirst() { return size == 0 ? null : ring.next.item; }
    public E peekLast() { return size == 0 ? null : ring.prev.item; }
    public E pollFirst() { return size == 0 ? null : unlink(ring.next); }
    public E pollLast() { return size == 0 ? null : unlink(ring.prev); }
    public void push(E e) { addFirst(e); }
    public E pop() { return removeFirst(); }

    public boolean removeFirstOccurrence(Object o) {
        for (Node<E> n = ring.next; n != ring; n = n.next) {
            if (Support.eq(o, n.item)) {
                unlink(n);
                return true;
            }
        }
        return false;
    }

    public boolean removeLastOccurrence(Object o) {
        for (Node<E> n = ring.prev; n != ring; n = n.prev) {
            if (Support.eq(o, n.item)) {
                unlink(n);
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
            for (Node<E> n = ring.next; n != ring; n = n.next) {
                copy.add(n.item);
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public Object[] toArray() {
        Object[] out = new Object[size];
        int i = 0;
        for (Node<E> n = ring.next; n != ring; n = n.next) {
            out[i++] = n.item;
        }
        return out;
    }

    public <T> T[] toArray(T[] a) { return Support.storeEach(toArray(), size, a); }

    /**
     * Spec 9.F2: next() checks for modification before the end; hasNext() is index < size().
     */
    private final class Walker implements ListIterator<E> {
        private Node<E> next;
        private Node<E> last;
        private int index;
        private int expected = modCount;

        Walker(int start) {
            next = nodeAt(start);
            index = start;
        }

        public boolean hasNext() { return index < size; }

        public E next() {
            checkUnchanged();
            if (index >= size) {
                throw new NoSuchElementException();
            }
            last = next;
            next = next.next;
            index++;
            return last.item;
        }

        public boolean hasPrevious() { return index > 0; }

        public E previous() {
            checkUnchanged();
            if (index <= 0) {
                throw new NoSuchElementException();
            }
            next = next.prev;
            last = next;
            index--;
            return last.item;
        }

        public int nextIndex() { return index; }
        public int previousIndex() { return index - 1; }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            checkUnchanged();
            if (next == last) {
                next = last.next;
            } else {
                index--;
            }
            unlink(last);
            last = null;
            expected = modCount;
        }

        public void set(E e) {
            if (last == null) {
                throw new IllegalStateException();
            }
            checkUnchanged();
            last.item = e;
        }

        public void add(E e) {
            checkUnchanged();
            last = null;
            linkBefore(e, next);
            index++;
            expected = modCount;
        }

        private void checkUnchanged() {
            if (modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }
}
