package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * A line of parked callers who all want the same thing: givers or takers, never both, because a
 * newcomer of the other kind meets one of them at once. Fair mode serves the oldest, the other the
 * newest. Looks empty from outside whatever the line holds.
 */
public class SynchronousQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, java.io.Serializable {
    private static final class Party<E> {
        final Thread thread = Thread.currentThread();
        /** What a giver brings; what a taker got. */
        E item;
        boolean met;

        Party(E item) {
            this.item = item;
        }
    }

    private final boolean fair;
    private final ArrayDeque<Party<E>> line = new ArrayDeque<>();
    private boolean lineGives;

    public SynchronousQueue() {
        this(false);
    }

    public SynchronousQueue(boolean fair) {
        this.fair = fair;
    }

    /** A giver gets back its item if met, a taker what it was given; null means nobody came. */
    private E meetNow(E given) {
        synchronized (line) {
            if (line.isEmpty() || lineGives == (given != null)) {
                return null;
            }
            Party<E> other = fair ? line.pollFirst() : line.pollLast();
            E got = other.item;
            other.item = given;
            other.met = true;
            LockSupport.unpark(other.thread);
            return given != null ? given : got;
        }
    }

    private E meet(E given, boolean timed, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        Party<E> me;
        synchronized (line) {
            E result = meetNow(given);
            if (result != null) {
                return result;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (timed && nanos <= 0) {
                return null;
            }
            me = new Party<>(given);
            line.addLast(me);
            lineGives = given != null;
        }
        for (;;) {
            long left = deadline - System.nanoTime();
            synchronized (line) {
                if (me.met) {
                    return me.item;
                }
                if (Thread.interrupted()) {
                    line.remove(me);
                    throw new InterruptedException();
                }
                if (timed && left <= 0) {
                    line.remove(me);
                    return null;
                }
            }
            if (timed) {
                LockSupport.parkNanos(this, left);
            } else {
                LockSupport.park(this);
            }
        }
    }

    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        meet(e, false, 0);
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        return meet(e, true, unit.toNanos(timeout)) != null;
    }

    public boolean offer(E e) {
        Objects.requireNonNull(e);
        return meetNow(e) != null;
    }

    public E take() throws InterruptedException {
        return meet(null, false, 0);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return meet(null, true, unit.toNanos(timeout));
    }

    public E poll() {
        return meetNow(null);
    }

    public boolean isEmpty() {
        return true;
    }

    public int size() {
        return 0;
    }

    public int remainingCapacity() {
        return 0;
    }

    public void clear() {
    }

    public boolean contains(Object o) {
        return false;
    }

    public boolean remove(Object o) {
        return false;
    }

    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    public boolean removeAll(Collection<?> c) {
        return false;
    }

    public boolean retainAll(Collection<?> c) {
        return false;
    }

    public E peek() {
        return null;
    }

    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    public Object[] toArray() {
        return new Object[0];
    }

    public <T> T[] toArray(T[] a) {
        if (a.length > 0) {
            a[0] = null;
        }
        return a;
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        LinkedCore.checkDrainTarget(c, this);
        int moved = 0;
        E e;
        while (moved < maxElements && (e = poll()) != null) {
            c.add(e);
            moved++;
        }
        return moved;
    }
}
