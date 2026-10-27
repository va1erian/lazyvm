package java.util;

import java.util.function.Consumer;

/**
 * A circular array of slots: elements sit in consecutive slots from `head` up to, not including,
 * `tail` (spec 4.D1). The array length is a power of two, so indexes wrap with a mask.
 */
public class ArrayDeque<E> extends AbstractCollection<E> implements Deque<E>, Cloneable, java.io.Serializable {
    // Spec 4.D1: the fewest slots, and the slots of new ArrayDeque().
    private static final int MIN_SLOTS = 8;
    private static final int DEFAULT_SLOTS = 16;

    private transient Object[] slots;
    private transient int head;
    private transient int tail;

    public ArrayDeque() { slots = new Object[DEFAULT_SLOTS]; }
    public ArrayDeque(int numElements) { slots = new Object[slotsFor(numElements)]; }

    public ArrayDeque(Collection<? extends E> c) {
        slots = new Object[slotsFor(c.size())];
        for (E e : c) {
            addLast(e);
        }
    }

    // Spec 4.D1: 8 slots below 8 elements, else the smallest power of two above n.
    private static int slotsFor(int n) {
        if (n < MIN_SLOTS) {
            return MIN_SLOTS;
        }
        int slots = MIN_SLOTS;
        while (slots <= n && slots > 0) {
            slots <<= 1;
        }
        return slots > 0 ? slots : 1 << 30;
    }

    private int mask() { return slots.length - 1; }

    // Spec 4.D1: when an add fills the array it doubles, renumbered so the first is in slot 0.
    private void doubleIfFull() {
        if (head != tail) {
            return;
        }
        int n = slots.length;
        Object[] bigger = new Object[n << 1];
        System.arraycopy(slots, head, bigger, 0, n - head);
        System.arraycopy(slots, 0, bigger, n - head, head);
        slots = bigger;
        head = 0;
        tail = n;
    }

    public void addFirst(E e) {
        Objects.requireNonNull(e);
        head = (head - 1) & mask();
        slots[head] = e;
        doubleIfFull();
    }

    public void addLast(E e) {
        Objects.requireNonNull(e);
        slots[tail] = e;
        tail = (tail + 1) & mask();
        doubleIfFull();
    }

    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    public E removeFirst() { return present(pollFirst()); }
    public E removeLast() { return present(pollLast()); }

    private static <E> E present(E e) {
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    public E pollFirst() {
        E e = (E) slots[head];
        if (e != null) {
            slots[head] = null;
            head = (head + 1) & mask();
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    public E pollLast() {
        int t = (tail - 1) & mask();
        E e = (E) slots[t];
        if (e != null) {
            slots[t] = null;
            tail = t;
        }
        return e;
    }

    public E getFirst() { return present(peekFirst()); }
    public E getLast() { return present(peekLast()); }

    @SuppressWarnings("unchecked")
    public E peekFirst() { return (E) slots[head]; }

    @SuppressWarnings("unchecked")
    public E peekLast() { return (E) slots[(tail - 1) & mask()]; }

    public boolean removeFirstOccurrence(Object o) {
        if (o != null) {
            for (int i = head; i != tail; i = (i + 1) & mask()) {
                if (o.equals(slots[i])) {
                    delete(i);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean removeLastOccurrence(Object o) {
        if (o != null) {
            for (int i = (tail - 1) & mask(); i != ((head - 1) & mask()); i = (i - 1) & mask()) {
                if (o.equals(slots[i])) {
                    delete(i);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Spec 4.D3: removes slot i by shifting the shorter side: the elements before it one slot
     * toward the end, or those after it one slot toward the front. Returns whether `tail` moved.
     */
    private boolean delete(int i) {
        int m = mask();
        int before = (i - head) & m;
        int after = (tail - i) & m;
        if (before < after) {
            for (int j = i; j != head; j = (j - 1) & m) {
                slots[j] = slots[(j - 1) & m];
            }
            slots[head] = null;
            head = (head + 1) & m;
            return false;
        }
        int last = (tail - 1) & m;
        for (int j = i; j != last; j = (j + 1) & m) {
            slots[j] = slots[(j + 1) & m];
        }
        slots[last] = null;
        tail = last;
        return true;
    }

    public boolean add(E e) {
        addLast(e);
        return true;
    }

    public boolean offer(E e) { return offerLast(e); }
    public E remove() { return removeFirst(); }
    public E poll() { return pollFirst(); }
    public E element() { return getFirst(); }
    public E peek() { return peekFirst(); }
    public void push(E e) { addFirst(e); }
    public E pop() { return removeFirst(); }
    public int size() { return (tail - head) & mask(); }
    public boolean isEmpty() { return head == tail; }
    public Iterator<E> iterator() { return new Walk(false); }
    public Iterator<E> descendingIterator() { return new Walk(true); }

    public boolean contains(Object o) {
        if (o != null) {
            for (int i = head; i != tail; i = (i + 1) & mask()) {
                if (o.equals(slots[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean remove(Object o) { return removeFirstOccurrence(o); }

    // Spec 4.D3: clearing empties every slot and moves both ends to slot 0.
    public void clear() {
        Arrays.fill(slots, null);
        head = 0;
        tail = 0;
    }

    public Object[] toArray() { return toArray(new Object[0]); }

    public <T> T[] toArray(T[] a) {
        int n = size();
        Object[] items = new Object[n];
        for (int i = 0; i < n; i++) {
            items[i] = slots[(head + i) & mask()];
        }
        return Support.toArray(items, n, a);
    }

    // Spec 4.D1: a clone copies the array as it is.
    @SuppressWarnings("unchecked")
    public ArrayDeque<E> clone() {
        try {
            ArrayDeque<E> copy = (ArrayDeque<E>) super.clone();
            copy.slots = slots.clone();
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Spec 4.D2: an iterator remembers the end it walks toward (the tail, or the head when
     * descending) and fails only when that end moves or it meets an empty slot.
     */
    private final class Walk implements Iterator<E> {
        private final boolean down;
        private int cursor;
        private int fence;
        private int last = -1;

        Walk(boolean down) {
            this.down = down;
            cursor = down ? tail : head;
            fence = down ? head : tail;
        }

        public boolean hasNext() { return cursor != fence; }

        @SuppressWarnings("unchecked")
        public E next() {
            if (cursor == fence) {
                throw new NoSuchElementException();
            }
            int at = down ? (cursor - 1) & mask() : cursor;
            E e = (E) slots[at];
            if ((down ? head : tail) != fence || e == null) {
                throw new ConcurrentModificationException();
            }
            last = at;
            cursor = down ? at : (at + 1) & mask();
            return e;
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            boolean tailMoved = delete(last);
            if (tailMoved != down) {
                cursor = (cursor + (down ? 1 : -1)) & mask();
                fence = down ? head : tail;
            }
            last = -1;
        }

        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            Object[] a = slots;
            int m = a.length - 1;
            if (down) {
                while (hasNext()) {
                    action.accept(next());
                }
                return;
            }
            int i = cursor;
            cursor = fence;
            while (i != fence) {
                E e = (E) a[i];
                i = (i + 1) & m;
                if (e == null) {
                    throw new ConcurrentModificationException();
                }
                action.accept(e);
            }
        }
    }
}
