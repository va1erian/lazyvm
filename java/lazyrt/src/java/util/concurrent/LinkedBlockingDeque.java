package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Both ends of a {@link LinkedCore}. Unlike the two queues, {@code take} and {@code put} do not
 * look at the flag unless they must wait; the timed forms do (concurrent.md, "LinkedBlockingDeque").
 */
public class LinkedBlockingDeque<E> extends AbstractQueue<E> implements BlockingDeque<E>, java.io.Serializable {
    private final LinkedCore<E> core;

    public LinkedBlockingDeque() {
        this(Integer.MAX_VALUE);
    }

    public LinkedBlockingDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        core = new LinkedCore<>(capacity, false);
    }

    public LinkedBlockingDeque(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        for (E e : c) {
            core.offer(e, false);
        }
    }

    public void addFirst(E e) {
        if (!core.offer(e, true)) {
            throw new IllegalStateException("Deque full");
        }
    }

    public void addLast(E e) {
        if (!core.offer(e, false)) {
            throw new IllegalStateException("Deque full");
        }
    }

    public boolean offerFirst(E e) {
        return core.offer(e, true);
    }

    public boolean offerLast(E e) {
        return core.offer(e, false);
    }

    public void putFirst(E e) throws InterruptedException {
        core.put(e, true);
    }

    public void putLast(E e) throws InterruptedException {
        core.put(e, false);
    }

    public boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        return core.offer(e, true, unit.toNanos(timeout));
    }

    public boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        return core.offer(e, false, unit.toNanos(timeout));
    }

    public E removeFirst() {
        return present(core.poll(true));
    }

    public E removeLast() {
        return present(core.poll(false));
    }

    private E present(E e) {
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    public E pollFirst() {
        return core.poll(true);
    }

    public E pollLast() {
        return core.poll(false);
    }

    public E takeFirst() throws InterruptedException {
        return core.take(true);
    }

    public E takeLast() throws InterruptedException {
        return core.take(false);
    }

    public E pollFirst(long timeout, TimeUnit unit) throws InterruptedException {
        return timedPoll(true, unit.toNanos(timeout));
    }

    public E pollLast(long timeout, TimeUnit unit) throws InterruptedException {
        return timedPoll(false, unit.toNanos(timeout));
    }

    private E timedPoll(boolean front, long nanos) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return core.poll(front, nanos);
    }

    public E getFirst() {
        return present(core.peek(true));
    }

    public E getLast() {
        return present(core.peek(false));
    }

    public E peekFirst() {
        return core.peek(true);
    }

    public E peekLast() {
        return core.peek(false);
    }

    public boolean removeFirstOccurrence(Object o) {
        return core.remove(o, true);
    }

    public boolean removeLastOccurrence(Object o) {
        return core.remove(o, false);
    }

    public boolean add(E e) {
        addLast(e);
        return true;
    }

    public boolean offer(E e) {
        return core.offer(e, false);
    }

    public void put(E e) throws InterruptedException {
        core.put(e, false);
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        return offerLast(e, timeout, unit);
    }

    public E remove() {
        return removeFirst();
    }

    public E poll() {
        return core.poll(true);
    }

    public E take() throws InterruptedException {
        return core.take(true);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pollFirst(timeout, unit);
    }

    public E element() {
        return getFirst();
    }

    public E peek() {
        return core.peek(true);
    }

    public int remainingCapacity() {
        return core.remainingCapacity();
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        LinkedCore.checkDrainTarget(c, this);
        return core.drainTo(c, maxElements, false);
    }

    public void push(E e) {
        addFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    public boolean remove(Object o) {
        return core.remove(o, true);
    }

    public int size() {
        return core.size();
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

    public Iterator<E> iterator() {
        return core.iterator(false);
    }

    public Iterator<E> descendingIterator() {
        return core.iterator(true);
    }
}
