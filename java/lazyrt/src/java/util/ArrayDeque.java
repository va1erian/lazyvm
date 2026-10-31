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
    private transient int front;
    private transient int back;

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

    // Spec 4.D1: when an add fills the array it doubles, renumbered so the first is in slot 0. Done
    // before the add changes anything, so that a failed allocation leaves the deque as it was;
    // `newFront` is where the first goes now for that to come out after the add.
    private void growForAdd(int newFront) {
        int n = slots.length;
        if (((back + 1) & (n - 1)) != front) {
            return;
        }
        Object[] bigger = new Object[n << 1];
        int size = n - 1;
        int upper = Math.min(n - front, size);
        System.arraycopy(slots, front, bigger, newFront, upper);
        System.arraycopy(slots, 0, bigger, newFront + upper, size - upper);
        slots = bigger;
        front = newFront;
        back = newFront + size;
    }

    public void addFirst(E e) {
        Objects.requireNonNull(e);
        growForAdd(1);
        front = (front - 1) & mask();
        slots[front] = e;
    }

    public void addLast(E e) {
        Objects.requireNonNull(e);
        growForAdd(0);
        slots[back] = e;
        back = (back + 1) & mask();
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
        E e = (E) slots[front];
        if (e != null) {
            slots[front] = null;
            front = (front + 1) & mask();
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    public E pollLast() {
        int t = (back - 1) & mask();
        E e = (E) slots[t];
        if (e != null) {
            slots[t] = null;
            back = t;
        }
        return e;
    }

    public E getFirst() { return present(peekFirst()); }
    public E getLast() { return present(peekLast()); }

    @SuppressWarnings("unchecked")
    public E peekFirst() { return (E) slots[front]; }

    @SuppressWarnings("unchecked")
    public E peekLast() { return (E) slots[(back - 1) & mask()]; }

    public boolean removeFirstOccurrence(Object o) {
        if (o != null) {
            for (int i = front; i != back; i = (i + 1) & mask()) {
                if (o.equals(slots[i])) {
                    closeGap(i);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean removeLastOccurrence(Object o) {
        if (o != null) {
            for (int i = (back - 1) & mask(); i != ((front - 1) & mask()); i = (i - 1) & mask()) {
                if (o.equals(slots[i])) {
                    closeGap(i);
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
    private boolean closeGap(int i) {
        int m = mask();
        int before = (i - front) & m;
        int after = (back - i) & m;
        if (before < after) {
            for (int j = i; j != front; j = (j - 1) & m) {
                slots[j] = slots[(j - 1) & m];
            }
            slots[front] = null;
            front = (front + 1) & m;
            return false;
        }
        int last = (back - 1) & m;
        for (int j = i; j != last; j = (j + 1) & m) {
            slots[j] = slots[(j + 1) & m];
        }
        slots[last] = null;
        back = last;
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
    public int size() { return (back - front) & mask(); }
    public boolean isEmpty() { return front == back; }
    public Iterator<E> iterator() { return new Walk(false); }
    public Iterator<E> descendingIterator() { return new Walk(true); }

    public boolean contains(Object o) {
        if (o != null) {
            for (int i = front; i != back; i = (i + 1) & mask()) {
                if (o.equals(slots[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean remove(Object o) { return removeFirstOccurrence(o); }

    // Spec 4.D3: clearing empties every slot and moves both ends to slot 0; an empty deque's ends
    // stay where they are.
    public void clear() {
        if (front != back) {
            Arrays.fill(slots, null);
            front = 0;
            back = 0;
        }
    }

    public Object[] toArray() { return toArray(new Object[0]); }

    public <T> T[] toArray(T[] a) {
        int n = size();
        Object[] items = new Object[n];
        for (int i = 0; i < n; i++) {
            items[i] = slots[(front + i) & mask()];
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
        private int pos;
        private int stop;
        private int last = -1;

        Walk(boolean down) {
            this.down = down;
            pos = down ? back : front;
            stop = down ? front : back;
        }

        public boolean hasNext() { return pos != stop; }

        @SuppressWarnings("unchecked")
        public E next() {
            if (pos == stop) {
                throw new NoSuchElementException();
            }
            // Spec 4.D2: a descending walk steps back before it checks, so it moves on even when it fails.
            int at = down ? (pos - 1) & mask() : pos;
            if (down) {
                pos = at;
            }
            E e = (E) slots[at];
            if ((down ? front : back) != stop || e == null) {
                throw new ConcurrentModificationException();
            }
            last = at;
            if (!down) {
                pos = (at + 1) & mask();
            }
            return e;
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            // Spec 4.D2: the slot to remove must still lie between the ends.
            if (((last - front) & mask()) >= size()) {
                throw new ConcurrentModificationException();
            }
            boolean tailMoved = closeGap(last);
            if (tailMoved != down) {
                pos = (pos + (down ? 1 : -1)) & mask();
                stop = down ? front : back;
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
            int i = pos;
            pos = stop;
            while (i != stop) {
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
