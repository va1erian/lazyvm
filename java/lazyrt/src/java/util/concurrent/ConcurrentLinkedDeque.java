package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Both ends of a {@link LinkedCore} nobody blocks on. Unlike the blocking deque, removing a null
 * occurrence is an error here (concurrent.md, "ConcurrentLinkedQueue").
 */
public class ConcurrentLinkedDeque<E> extends AbstractCollection<E> implements Deque<E>, java.io.Serializable {
    private final LinkedCore<E> core = new LinkedCore<>();

    public ConcurrentLinkedDeque() {
    }

    public ConcurrentLinkedDeque(Collection<? extends E> c) {
        for (E e : c) {
            core.offer(e, false);
        }
    }

    public void addFirst(E e) {
        core.offer(e, true);
    }

    public void addLast(E e) {
        core.offer(e, false);
    }

    public boolean offerFirst(E e) {
        return core.offer(e, true);
    }

    public boolean offerLast(E e) {
        return core.offer(e, false);
    }

    public E peekFirst() {
        return core.peek(true);
    }

    public E peekLast() {
        return core.peek(false);
    }

    private E present(E e) {
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    public E getFirst() {
        return present(core.peek(true));
    }

    public E getLast() {
        return present(core.peek(false));
    }

    public E pollFirst() {
        return core.poll(true);
    }

    public E pollLast() {
        return core.poll(false);
    }

    public E removeFirst() {
        return present(core.poll(true));
    }

    public E removeLast() {
        return present(core.poll(false));
    }

    public boolean offer(E e) {
        return core.offer(e, false);
    }

    public boolean add(E e) {
        return core.offer(e, false);
    }

    public E poll() {
        return core.poll(true);
    }

    public E peek() {
        return core.peek(true);
    }

    public E remove() {
        return removeFirst();
    }

    public E pop() {
        return removeFirst();
    }

    public E element() {
        return getFirst();
    }

    public void push(E e) {
        core.offer(e, true);
    }

    public boolean removeFirstOccurrence(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        return core.remove(o, true);
    }

    public boolean removeLastOccurrence(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        return core.remove(o, false);
    }

    public boolean contains(Object o) {
        return core.contains(o);
    }

    public boolean isEmpty() {
        return core.size() == 0;
    }

    public int size() {
        return core.size();
    }

    public boolean remove(Object o) {
        return removeFirstOccurrence(o);
    }

    public boolean addAll(Collection<? extends E> c) {
        if (c == this) {
            throw new IllegalArgumentException();
        }
        return ConcurrentLinkedQueue.addChecked(core, c);
    }

    public void clear() {
        core.clear();
    }

    public Object[] toArray() {
        return core.toList().toArray();
    }

    public <T> T[] toArray(T[] a) {
        return core.toList().toArray(a);
    }

    public Iterator<E> iterator() {
        return core.iterator(false);
    }

    public Iterator<E> descendingIterator() {
        return core.iterator(true);
    }
}
