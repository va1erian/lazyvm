package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;

/** Same contract as {@link ArrayBlockingQueue}, with a default capacity and no fair mode. */
public class LinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, java.io.Serializable {
    private final LinkedCore<E> core;

    public LinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    public LinkedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        core = new LinkedCore<>(capacity, false);
    }

    public LinkedBlockingQueue(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        for (E e : c) {
            core.offer(e, false);
        }
    }

    public int size() {
        return core.size();
    }

    public int remainingCapacity() {
        return core.remainingCapacity();
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

    public boolean offer(E e) {
        return core.offer(e, false);
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

    public E poll() {
        return core.poll(true);
    }

    public E peek() {
        return core.peek(true);
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
