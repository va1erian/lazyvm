package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;

/**
 * Bounded FIFO on a {@link LinkedCore}, which here is nothing but cheaper to write than a ring:
 * the program only sees the contract. The blocking calls look at the flag before anything else
 * (concurrent.md G5).
 */
public class ArrayBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, java.io.Serializable {
    private final LinkedCore<E> core;

    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    public ArrayBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        core = new LinkedCore<>(capacity, fair);
    }

    public ArrayBlockingQueue(int capacity, boolean fair, Collection<? extends E> c) {
        this(capacity, fair);
        for (E e : c) {
            if (e == null) {
                throw new NullPointerException();
            }
            if (!core.offer(e, false)) {
                throw new IllegalArgumentException();
            }
        }
    }

    public boolean add(E e) {
        return super.add(e);
    }

    public boolean offer(E e) {
        return core.offer(e, false);
    }

    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        core.put(e, false);
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        return core.offer(e, false, unit.toNanos(timeout));
    }

    public E poll() {
        return core.poll(true);
    }

    public E take() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return core.take(true);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return core.poll(true, nanos);
    }

    public E peek() {
        return core.peek(true);
    }

    public int size() {
        return core.size();
    }

    public int remainingCapacity() {
        return core.remainingCapacity();
    }

    public boolean remove(Object o) {
        return core.remove(o, true);
    }

    public boolean contains(Object o) {
        return core.contains(o);
    }

    public Object[] toArray() {
        return core.toList().toArray();
    }

    public <T> T[] toArray(T[] a) {
        return core.toList().toArray(a);
    }

    public String toString() {
        return super.toString();
    }

    public void clear() {
        core.clear();
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        LinkedCore.checkDrainTarget(c, this);
        return core.drainTo(c, maxElements, false);
    }

    public Iterator<E> iterator() {
        return core.iterator(false);
    }
}
