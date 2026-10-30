package java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.LockSupport;
import lazy.internal.Turnstile;

/**
 * The doubly linked list behind every FIFO queue and deque of this package, guarded by its own
 * monitor. Blocked threads queue on one {@link Turnstile} per direction, over that same monitor.
 * A plain monitor because these calls are short and this VM runs one thread at a time: it costs
 * about a fortieth of a {@code ReentrantLock}.
 *
 * <p>A removed node has a null item and keeps its links, which is all a weak iterator needs:
 * walking them back leads to the nearest node still in the list, or off the end.
 */
final class LinkedCore<E> {
    static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;
        /** A thread blocked until this element leaves the list ({@code LinkedTransferQueue}). */
        Thread sender;

        Node(E item) {
            this.item = item;
        }
    }

    /** A blocked call, with what it brings or what it got, and the end it works on. */
    static final class Slot<E> extends Turnstile.Waiter {
        E item;
        final boolean front;

        Slot(E item, boolean front) {
            super(1, true);
            this.item = item;
            this.front = front;
        }
    }

    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;
    /** Null for the queues that never block. */
    private final Turnstile takers;
    private final Turnstile putters;

    /** A list nobody waits on. */
    LinkedCore() {
        capacity = Integer.MAX_VALUE;
        takers = null;
        putters = null;
    }

    LinkedCore(int capacity, boolean fair) {
        this.capacity = capacity;
        takers = new Turnstile(fair, new Turnstile.Gate() {
            @SuppressWarnings("unchecked")
            public boolean enter(Turnstile.Waiter w) {
                Slot<E> s = (Slot<E>) w;
                if (size == 0) {
                    return false;
                }
                s.item = unlink(s.front ? head : tail);
                return true;
            }
        }, this);
        putters = new Turnstile(fair, new Turnstile.Gate() {
            @SuppressWarnings("unchecked")
            public boolean enter(Turnstile.Waiter w) {
                Slot<E> s = (Slot<E>) w;
                if (size >= capacity) {
                    return false;
                }
                link(s.item, s.front);
                return true;
            }
        }, this);
    }

    private Node<E> link(E e, boolean front) {
        Node<E> n = new Node<>(e);
        if (head == null) {
            head = n;
            tail = n;
        } else if (front) {
            n.next = head;
            head.prev = n;
            head = n;
        } else {
            n.prev = tail;
            tail.next = n;
            tail = n;
        }
        size++;
        if (takers != null) {
            takers.wakeHead();
        }
        return n;
    }

    private E unlink(Node<E> n) {
        E e = n.item;
        n.item = null;
        if (n.prev == null) {
            head = n.next;
        } else {
            n.prev.next = n.next;
        }
        if (n.next == null) {
            tail = n.prev;
        } else {
            n.next.prev = n.prev;
        }
        size--;
        if (putters != null) {
            putters.wakeHead();
        }
        if (n.sender != null) {
            LockSupport.unpark(n.sender);
        }
        return e;
    }

    private static void requireElement(Object e) {
        if (e == null) {
            throw new NullPointerException();
        }
    }

    int size() {
        return size;
    }

    int remainingCapacity() {
        return capacity - size;
    }

    synchronized boolean offer(E e, boolean front) {
        requireElement(e);
        if (size >= capacity) {
            return false;
        }
        link(e, front);
        return true;
    }

    void put(E e, boolean front) throws InterruptedException {
        requireElement(e);
        Slot<E> w;
        synchronized (this) {
            if (size < capacity && putters.open()) {
                link(e, front);
                return;
            }
            w = new Slot<>(e, front);
            putters.enqueue(w);
        }
        if (putters.pass(w, true, false, 0) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
    }

    /** The timed form looks at the flag first in every class that has one (concurrent.md G5). */
    boolean offer(E e, boolean front, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        Slot<E> w;
        synchronized (this) {
            if (size < capacity && putters.open()) {
                link(e, front);
                return true;
            }
            w = new Slot<>(e, front);
            putters.enqueue(w);
        }
        int result = putters.pass(w, true, true, deadline);
        if (result == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return result == Turnstile.ENTERED;
    }

    synchronized E poll(boolean front) {
        return size == 0 ? null : unlink(front ? head : tail);
    }

    E take(boolean front) throws InterruptedException {
        Slot<E> w;
        synchronized (this) {
            if (size > 0 && takers.open()) {
                return unlink(front ? head : tail);
            }
            w = new Slot<>(null, front);
            takers.enqueue(w);
        }
        if (takers.pass(w, true, false, 0) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return w.item;
    }

    E poll(boolean front, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        Slot<E> w;
        synchronized (this) {
            if (size > 0 && takers.open()) {
                return unlink(front ? head : tail);
            }
            w = new Slot<>(null, front);
            takers.enqueue(w);
        }
        if (takers.pass(w, true, true, deadline) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return w.item;
    }

    synchronized E peek(boolean front) {
        Node<E> n = front ? head : tail;
        return n == null ? null : n.item;
    }

    synchronized boolean contains(Object o) {
        return o != null && find(o, true) != null;
    }

    private Node<E> find(Object o, boolean fromHead) {
        for (Node<E> n = fromHead ? head : tail; n != null; n = fromHead ? n.next : n.prev) {
            if (o.equals(n.item)) {
                return n;
            }
        }
        return null;
    }

    /** The first or last occurrence leaves; a null is never there. */
    synchronized boolean remove(Object o, boolean fromHead) {
        Node<E> n = o == null ? null : find(o, fromHead);
        if (n == null) {
            return false;
        }
        unlink(n);
        return true;
    }

    synchronized void clear() {
        while (head != null) {
            unlink(head);
        }
    }

    synchronized ArrayList<E> toList() {
        ArrayList<E> out = new ArrayList<>(size);
        for (Node<E> n = head; n != null; n = n.next) {
            out.add(n.item);
        }
        return out;
    }

    /** Concurrent.md G8. A refused element stays unless {@code lossy}. */
    synchronized int drainTo(Collection<? super E> c, int max, boolean lossy) {
        int moved = 0;
        while (moved < max && head != null) {
            if (lossy) {
                c.add(unlink(head));
            } else {
                c.add(head.item);
                unlink(head);
            }
            moved++;
        }
        return moved;
    }

    /** The argument checks every queue's {@code drainTo} starts with, in the oracle's order. */
    static void checkDrainTarget(Collection<?> target, Collection<?> queue) {
        if (target == null) {
            throw new NullPointerException();
        }
        if (target == queue) {
            throw new IllegalArgumentException();
        }
    }

    // LinkedTransferQueue: an element whose sender stays blocked until the element has left.

    int waitingConsumers() {
        return takers.length();
    }

    /** Appends {@code e} if a consumer is waiting with nothing assigned to it yet. */
    synchronized boolean handOver(E e) {
        requireElement(e);
        if (takers.length() <= size) {
            return false;
        }
        link(e, false);
        return true;
    }

    synchronized Node<E> linkPending(E e) {
        requireElement(e);
        Node<E> n = link(e, false);
        n.sender = Thread.currentThread();
        return n;
    }

    /** Waits until the element has left; a timeout or interrupt withdraws it. Whether it left. */
    boolean awaitTaken(Node<E> n, boolean timed, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        for (;;) {
            long left = deadline - System.nanoTime();
            synchronized (this) {
                if (n.item == null) {
                    return true;
                }
                if (Thread.interrupted()) {
                    unlink(n);
                    throw new InterruptedException();
                }
                if (timed && left <= 0) {
                    unlink(n);
                    return false;
                }
            }
            if (timed) {
                LockSupport.parkNanos(this, left);
            } else {
                LockSupport.park(this);
            }
        }
    }

    Iterator<E> iterator(boolean descending) {
        return new Walk(descending);
    }

    /** Holds the next element from the moment it finds it, so hasNext() is a promise (G6). */
    private final class Walk implements Iterator<E> {
        private final boolean back;
        private Node<E> next;
        private E nextItem;
        private Node<E> last;

        Walk(boolean back) {
            this.back = back;
            synchronized (LinkedCore.this) {
                next = back ? tail : head;
                nextItem = next == null ? null : next.item;
            }
        }

        public boolean hasNext() {
            return next != null;
        }

        public E next() {
            synchronized (LinkedCore.this) {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                E e = nextItem;
                last = next;
                next = after(next);
                nextItem = next == null ? null : next.item;
                return e;
            }
        }

        /** The node after {@code n}: if {@code n} has left, after its nearest predecessor still in. */
        private Node<E> after(Node<E> n) {
            Node<E> anchor = n;
            while (anchor != null && anchor.item == null) {
                anchor = back ? anchor.next : anchor.prev;
            }
            if (anchor == null) {
                return back ? tail : head;
            }
            return back ? anchor.prev : anchor.next;
        }

        public void remove() {
            synchronized (LinkedCore.this) {
                if (last == null) {
                    throw new IllegalStateException();
                }
                if (last.item != null) {
                    unlink(last);
                }
                last = null;
            }
        }
    }
}
