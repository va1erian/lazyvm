package java.util.concurrent;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import lazy.internal.Turnstile;

/**
 * The heap behind {@code PriorityBlockingQueue} and {@code DelayQueue}, guarded by its turnstile's
 * monitor. Only a head that is ready can leave; a queue with a delay says when through
 * {@link #delayOf}. Takers queue, the first sleeps until the head is due, and an insert wakes it to
 * look again at what may be a new head.
 */
class HeapCore<E> implements Turnstile.Gate {
    final PriorityQueue<E> heap;
    private final Turnstile takers = new Turnstile(false, this, this);

    HeapCore(PriorityQueue<E> heap) {
        this.heap = heap;
    }

    /** Nanoseconds until this head may leave; not positive when it may now. */
    long delayOf(E head) {
        return 0;
    }

    private E pollReady() {
        E head = heap.peek();
        return head == null || delayOf(head) > 0 ? null : heap.poll();
    }

    @SuppressWarnings("unchecked")
    public boolean enter(Turnstile.Waiter w) {
        LinkedCore.Slot<E> s = (LinkedCore.Slot<E>) w;
        s.item = pollReady();
        return s.item != null;
    }

    public long patience(Turnstile.Waiter w) {
        E head = heap.peek();
        return head == null ? Long.MAX_VALUE : delayOf(head);
    }

    /** The heap only compares from the second element on; concurrent.md rejects a first one too. */
    synchronized boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        if (heap.isEmpty() && heap.comparator() == null) {
            Comparable<?> checked = (Comparable<?>) e;
        }
        try {
            heap.offer(e);
        } catch (RuntimeException | Error refused) {
            dropLastSlot();
            throw refused;
        }
        takers.wakeHead();
        return true;
    }

    /** A failed compare leaves the heap one slot longer, without the element: it must not count. */
    private void dropLastSlot() {
        Iterator<E> it = heap.iterator();
        while (it.hasNext()) {
            it.next();
        }
        it.remove();
    }

    synchronized E poll() {
        return pollReady();
    }

    /** The flag counts even with an element ready (concurrent.md G5). */
    E take(boolean timed, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        LinkedCore.Slot<E> w;
        synchronized (this) {
            if (takers.open()) {
                E e = pollReady();
                if (e != null) {
                    return e;
                }
            }
            w = new LinkedCore.Slot<>(null, true);
            takers.enqueue(w);
        }
        if (takers.pass(w, true, timed, deadline) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return w.item;
    }

    synchronized E peek() {
        return heap.peek();
    }

    synchronized int size() {
        return heap.size();
    }

    synchronized boolean remove(Object o) {
        return heap.remove(o);
    }

    synchronized boolean contains(Object o) {
        return heap.contains(o);
    }

    synchronized void clear() {
        heap.clear();
    }

    synchronized Object[] toArray() {
        return heap.toArray();
    }

    synchronized <T> T[] toArray(T[] a) {
        return heap.toArray(a);
    }

    /** Concurrent.md G8: a refused element stays. */
    synchronized int drainTo(Collection<? super E> c, int max) {
        int moved = 0;
        while (moved < max) {
            E head = heap.peek();
            if (head == null || delayOf(head) > 0) {
                break;
            }
            c.add(head);
            heap.poll();
            moved++;
        }
        return moved;
    }

    Iterator<E> iterator() {
        return new Snapshot(toArray());
    }

    /** {@code remove} takes out that very element, not one equal to it. */
    private final class Snapshot implements Iterator<E> {
        private final Object[] elements;
        private int at;
        private boolean removable;

        Snapshot(Object[] elements) {
            this.elements = elements;
        }

        public boolean hasNext() {
            return at < elements.length;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (at >= elements.length) {
                throw new NoSuchElementException();
            }
            removable = true;
            return (E) elements[at++];
        }

        public void remove() {
            if (!removable) {
                throw new IllegalStateException();
            }
            removable = false;
            synchronized (HeapCore.this) {
                Iterator<E> it = heap.iterator();
                while (it.hasNext()) {
                    if (it.next() == elements[at - 1]) {
                        it.remove();
                        return;
                    }
                }
            }
        }
    }
}
