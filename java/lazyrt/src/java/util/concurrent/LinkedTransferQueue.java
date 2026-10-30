package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;

/**
 * An unbounded {@link LinkedCore}, where a sender may stay blocked until its element has left.
 * Its take and timed poll do not look at the flag unless they must wait, and {@code drainTo} loses
 * an element the target refused (concurrent.md G5, G8).
 */
public class LinkedTransferQueue<E> extends AbstractQueue<E> implements TransferQueue<E>, java.io.Serializable {
    private final LinkedCore<E> core = new LinkedCore<>(Integer.MAX_VALUE, false);

    public LinkedTransferQueue() {
    }

    public LinkedTransferQueue(Collection<? extends E> c) {
        for (E e : c) {
            core.offer(e, false);
        }
    }

    public void put(E e) {
        core.offer(e, false);
    }

    public boolean offer(E e, long timeout, TimeUnit unit) {
        return core.offer(e, false);
    }

    public boolean offer(E e) {
        return core.offer(e, false);
    }

    public boolean add(E e) {
        return core.offer(e, false);
    }

    public boolean tryTransfer(E e) {
        return core.handOver(e);
    }

    public void transfer(E e) throws InterruptedException {
        if (!core.handOver(e)) {
            core.awaitTaken(core.linkPending(e), false, 0);
        }
    }

    public boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        return core.handOver(e) || core.awaitTaken(core.linkPending(e), true, nanos);
    }

    public E take() throws InterruptedException {
        return core.take(true);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return core.poll(true, unit.toNanos(timeout));
    }

    public E poll() {
        return core.poll(true);
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        LinkedCore.checkDrainTarget(c, this);
        return core.drainTo(c, maxElements, true);
    }

    public Iterator<E> iterator() {
        return core.iterator(false);
    }

    public E peek() {
        return core.peek(true);
    }

    public boolean isEmpty() {
        return core.size() == 0;
    }

    public boolean hasWaitingConsumer() {
        return core.waitingConsumers() != 0;
    }

    public int size() {
        return core.size();
    }

    public int getWaitingConsumerCount() {
        return core.waitingConsumers();
    }

    public boolean remove(Object o) {
        return core.remove(o, true);
    }

    public boolean contains(Object o) {
        return core.contains(o);
    }

    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }
}
