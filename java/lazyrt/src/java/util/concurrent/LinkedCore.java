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
 * <p>A removed node has a null item and links to nothing but itself, so whatever an idle iterator
 * holds keeps nothing else alive. A self link says it had a neighbour on that side when it left, a null
 * that it was at that end. Every node has a place, rising from head to tail and never reused: an
 * iterator whose node has left finds the first node still in the list beyond that place, or, if nothing
 * was behind that node, starts again at its own end, where only newer elements can be.
 */
final class LinkedCore<E> {
    static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;
        long place;
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
    private long frontPlace;
    private long backPlace;
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
        n.place = front ? --frontPlace : ++backPlace;
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
        n.prev = n.prev == null ? null : n;
        n.next = n.next == null ? null : n;
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
        long deadline = Turnstile.deadline(nanos);
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
        long deadline = Turnstile.deadline(nanos);
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

    /** The argument's {@code equals} is user code, so it runs with the monitor free, one step at a time. */
    boolean contains(Object o) {
        if (o != null) {
            for (Walk w = new Walk(false); w.hasNext();) {
                if (o.equals(w.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first or last occurrence leaves; a null is never there. One that left meanwhile does not count. */
    boolean remove(Object o, boolean fromHead) {
        if (o != null) {
            for (Walk w = new Walk(!fromHead); w.hasNext();) {
                if (o.equals(w.next()) && w.drop()) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Concurrent.md G8. A refused element stays unless {@code lossy}. Otherwise only what is there at
     * the start can move, so a sink feeding this queue ends; a sink may also take the element itself.
     */
    synchronized int drainTo(Collection<? super E> c, int max, boolean lossy) {
        int limit = lossy ? max : Math.min(max, size);
        int moved = 0;
        while (moved < limit && head != null) {
            if (lossy) {
                c.add(unlink(head));
            } else {
                Node<E> n = head;
                c.add(n.item);
                if (n.item != null) {
                    unlink(n);
                }
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
        long deadline = Turnstile.deadline(nanos);
        for (;;) {
            long left = deadline - System.nanoTime();
            synchronized (this) {
                if (n.item == null) {
                    return true;
                }
                if (Thread.interrupted()) {
                    withdraw(n);
                    throw new InterruptedException();
                }
                if (timed && left <= 0) {
                    withdraw(n);
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

    /** The sender itself takes it back: nobody has to wake it, and a permit would stay behind. */
    private void withdraw(Node<E> n) {
        n.sender = null;
        unlink(n);
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

        /** The node after {@code n}; if {@code n} has left, see the class comment. */
        private Node<E> after(Node<E> n) {
            if (n.item != null) {
                return back ? n.prev : n.next;
            }
            Node<E> p = back ? tail : head;
            if ((back ? n.next : n.prev) == null) {
                return p;
            }
            while (p != null && (back ? p.place >= n.place : p.place <= n.place)) {
                p = back ? p.prev : p.next;
            }
            return p;
        }

        public void remove() {
            synchronized (LinkedCore.this) {
                if (last == null) {
                    throw new IllegalStateException();
                }
                drop();
                last = null;
            }
        }

        /** Takes out the element last handed out; false if it has already left. */
        boolean drop() {
            synchronized (LinkedCore.this) {
                if (last.item == null) {
                    return false;
                }
                unlink(last);
                return true;
            }
        }
    }
}
