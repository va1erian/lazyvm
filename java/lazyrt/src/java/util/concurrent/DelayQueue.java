package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.PriorityQueue;

/** A {@link HeapCore} whose head may leave once its delay has run out. */
public class DelayQueue<E extends Delayed> extends AbstractQueue<E> implements BlockingQueue<E> {
    private final HeapCore<E> core = new HeapCore<E>(new PriorityQueue<E>()) {
        @Override
        long delayOf(E head) {
            return head.getDelay(TimeUnit.NANOSECONDS);
        }
    };

    public DelayQueue() {
    }

    public DelayQueue(Collection<? extends E> c) {
        addAll(c);
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

    public int size() {
        return core.size();
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        LinkedCore.checkDrainTarget(c, this);
        return core.drainTo(c, maxElements);
    }

    public void clear() {
        core.clear();
    }

    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    public Object[] toArray() {
        return core.toArray();
    }

    public <T> T[] toArray(T[] a) {
        return core.toArray(a);
    }

    public boolean remove(Object o) {
        return core.remove(o);
    }

    public Iterator<E> iterator() {
        return core.iterator();
    }
}
