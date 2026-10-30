package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.SortedSet;

/** A {@link HeapCore} where every head is ready at once. */
public class PriorityBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, java.io.Serializable {
    private final HeapCore<E> core;

    public PriorityBlockingQueue() {
        core = new HeapCore<>(new PriorityQueue<E>());
    }

    public PriorityBlockingQueue(int initialCapacity) {
        this(initialCapacity, null);
    }

    public PriorityBlockingQueue(int initialCapacity, Comparator<? super E> comparator) {
        core = new HeapCore<>(new PriorityQueue<E>(initialCapacity, comparator));
    }

    public PriorityBlockingQueue(Collection<? extends E> c) {
        core = new HeapCore<>(heapOf(c));
    }

    /** A sorted set or another such queue brings its comparator along (concurrent.md). */
    @SuppressWarnings("unchecked")
    private static <E> PriorityQueue<E> heapOf(Collection<? extends E> c) {
        if (c instanceof PriorityBlockingQueue) {
            PriorityBlockingQueue<? extends E> q = (PriorityBlockingQueue<? extends E>) c;
            PriorityQueue<E> heap = new PriorityQueue<>(1, (Comparator<? super E>) q.comparator());
            for (Object e : q.toArray()) {
                heap.offer((E) e);
            }
            return heap;
        }
        if (c instanceof SortedSet) {
            return new PriorityQueue<>((SortedSet<? extends E>) c);
        }
        // Not new PriorityQueue(c): the comparator of a java.util.PriorityQueue is not kept here.
        return new PriorityQueue<>(Arrays.asList((E[]) c.toArray()));
    }

    public boolean add(E e) {
        return core.offer(e);
    }

    public boolean offer(E e) {
        return core.offer(e);
    }

    public void put(E e) {
        core.offer(e);
    }

    public boolean offer(E e, long timeout, TimeUnit unit) {
        return core.offer(e);
    }

    public E poll() {
        return core.poll();
    }

    public E take() throws InterruptedException {
        return core.take(false, 0);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return core.take(true, unit.toNanos(timeout));
    }

    public E peek() {
        return core.peek();
    }

    public Comparator<? super E> comparator() {
        return core.heap.comparator();
    }

    public int size() {
        return core.size();
    }

    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    public boolean remove(Object o) {
        return core.remove(o);
    }

    public boolean contains(Object o) {
        return core.contains(o);
    }

    public Object[] toArray() {
        return core.toArray();
    }

    public String toString() {
        return super.toString();
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        LinkedCore.checkDrainTarget(c, this);
        // Only what is there at the start can move, so a sink feeding this queue ends.
        return core.drainTo(c, Math.min(maxElements, core.size()));
    }

    public void clear() {
        core.clear();
    }

    public <T> T[] toArray(T[] a) {
        return core.toArray(a);
    }

    public Iterator<E> iterator() {
        return core.iterator();
    }
}
