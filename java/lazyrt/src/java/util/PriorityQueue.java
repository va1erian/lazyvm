package java.util;

/**
 * A binary heap in an array: the children of slot i are 2i+1 and 2i+2 (spec 5.P1). Iteration
 * and toArray show the array as it is.
 */
public class PriorityQueue<E> extends AbstractQueue<E> implements java.io.Serializable {
    // Javadoc: the default initial capacity.
    private static final int DEFAULT_SLOTS = 11;

    private transient Object[] heap;
    private int size;
    private final Comparator<? super E> comparator;
    transient int modCount;

    public PriorityQueue() { this(DEFAULT_SLOTS, null); }
    public PriorityQueue(int initialCapacity) { this(initialCapacity, null); }
    public PriorityQueue(Comparator<? super E> comparator) { this(DEFAULT_SLOTS, comparator); }

    public PriorityQueue(int initialCapacity, Comparator<? super E> comparator) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException();
        }
        this.heap = new Object[initialCapacity];
        this.comparator = comparator;
    }

    // Spec 5.P3: a sorted set or another queue is copied in its order with no comparisons.
    @SuppressWarnings("unchecked")
    public PriorityQueue(Collection<? extends E> c) {
        if (c instanceof SortedSet) {
            comparator = (Comparator<? super E>) ((SortedSet<? extends E>) c).comparator();
            take(c.toArray(), false);
        } else if (c instanceof PriorityQueue) {
            comparator = (Comparator<? super E>) ((PriorityQueue<? extends E>) c).comparator();
            take(c.toArray(), false);
        } else {
            comparator = null;
            take(c.toArray(), true);
        }
    }

    @SuppressWarnings("unchecked")
    public PriorityQueue(PriorityQueue<? extends E> c) {
        comparator = (Comparator<? super E>) c.comparator();
        take(c.toArray(), false);
    }

    @SuppressWarnings("unchecked")
    public PriorityQueue(SortedSet<? extends E> c) {
        comparator = (Comparator<? super E>) c.comparator();
        take(c.toArray(), false);
    }

    /** Adopts the elements a; spec 5.P3: unordered input is heapified from slot n/2 - 1 down. */
    @SuppressWarnings("unchecked")
    private void take(Object[] a, boolean makeHeap) {
        a = Support.plainArray(a);
        for (Object o : a) {
            Objects.requireNonNull(o);
        }
        heap = a.length == 0 ? new Object[1] : a;
        size = a.length;
        if (makeHeap) {
            for (int i = (size >>> 1) - 1; i >= 0; i--) {
                sink(i, (E) heap[i]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private int compare(Object x, Object y) {
        return comparator == null ? Support.NATURAL.compare(x, y) : ((Comparator<Object>) comparator).compare(x, y);
    }

    // Spec 5.P1: a new element climbs while it orders before its parent.
    private void climb(int k, E x) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object p = heap[parent];
            if (compare(x, p) >= 0) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = x;
    }

    // Spec 5.P1: x sinks below the lesser child (the right one only if the left orders after it).
    private void sink(int k, E x) {
        int half = size >>> 1;
        while (k < half) {
            int child = 2 * k + 1;
            Object c = heap[child];
            int right = child + 1;
            if (right < size && compare(c, heap[right]) > 0) {
                c = heap[child = right];
            }
            if (compare(x, c) <= 0) {
                break;
            }
            heap[k] = c;
            k = child;
        }
        heap[k] = x;
    }

    public boolean add(E e) { return offer(e); }

    public boolean offer(E e) {
        Objects.requireNonNull(e);
        modCount++;
        if (size == heap.length) {
            heap = Arrays.copyOf(heap, size * 2 + 2);
        }
        int at = size++;
        if (at == 0) {
            heap[0] = e;
        } else {
            climb(at, e);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public E peek() { return size == 0 ? null : (E) heap[0]; }
    private int indexOf(Object o) { return o == null ? -1 : Support.indexOf(heap, 0, size, o); }

    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0) {
            return false;
        }
        takeOut(i);
        return true;
    }

    public boolean contains(Object o) { return indexOf(o) >= 0; }
    public Object[] toArray() { return Arrays.copyOf(heap, size); }
    public <T> T[] toArray(T[] a) { return Support.toArray(heap, size, a); }
    public Iterator<E> iterator() { return new Walk(); }
    public int size() { return size; }

    public void clear() {
        modCount++;
        Arrays.fill(heap, 0, size, null);
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        if (size == 0) {
            return null;
        }
        modCount++;
        E top = (E) heap[0];
        E last = (E) heap[--size];
        heap[size] = null;
        if (size != 0) {
            sink(0, last);
        }
        return top;
    }

    /**
     * Spec 5.P1: removes slot i, refilling it with the last element (down, then up if it stayed).
     * Returns that element if it climbed above i, else null.
     */
    @SuppressWarnings("unchecked")
    private E takeOut(int i) {
        modCount++;
        int s = --size;
        E moved = (E) heap[s];
        heap[s] = null;
        if (s == i) {
            return null;
        }
        sink(i, moved);
        if (heap[i] == moved) {
            climb(i, moved);
            if (heap[i] != moved) {
                return moved;
            }
        }
        return null;
    }

    public Comparator<? super E> comparator() { return comparator; }

    /**
     * Spec 5.P2: walks the array; an element that a removal moved up past the cursor is kept
     * aside and returned once the array is done.
     */
    private final class Walk implements Iterator<E> {
        private int nextSlot;
        private int last = -1;
        private ArrayDeque<E> displaced;
        private E lastDisplaced;
        private int expected = modCount;

        public boolean hasNext() { return nextSlot < size || (displaced != null && !displaced.isEmpty()); }

        @SuppressWarnings("unchecked")
        public E next() {
            if (expected != modCount) {
                throw new ConcurrentModificationException();
            }
            if (nextSlot < size) {
                last = nextSlot++;
                return (E) heap[last];
            }
            if (displaced != null) {
                last = -1;
                lastDisplaced = displaced.poll();
                if (lastDisplaced != null) {
                    return lastDisplaced;
                }
            }
            throw new NoSuchElementException();
        }

        public void remove() {
            if (expected != modCount) {
                throw new ConcurrentModificationException();
            }
            if (last != -1) {
                E moved = takeOut(last);
                last = -1;
                if (moved == null) {
                    nextSlot--;
                } else {
                    if (displaced == null) {
                        displaced = new ArrayDeque<E>();
                    }
                    displaced.add(moved);
                }
            } else if (lastDisplaced != null) {
                takeOutSame(lastDisplaced);
                lastDisplaced = null;
            } else {
                throw new IllegalStateException();
            }
            expected = modCount;
        }
    }

    /** Removes the very object o (not merely an equal one). */
    private void takeOutSame(Object o) {
        for (int i = 0; i < size; i++) {
            if (heap[i] == o) {
                takeOut(i);
                return;
            }
        }
    }
}
