package java.util;

import java.util.function.Consumer;

/**
 * A spliterator over the array a collection keeps its elements in. The size and the modification count are
 * read at the first use (or given, for a window of a list); a change that happens during the walk is
 * reported once the walk, or the step, is done. Splitting halves the range that is left.
 */
abstract class ArrayWalk<E> implements Spliterator<E> {
    private final int ch;
    /** A heap-ordered queue ends its walk at the first empty slot. */
    private final boolean stopAtNull;
    private int index;
    private int end;
    private int expected;

    ArrayWalk(int ch, boolean stopAtNull) {
        this.ch = ch;
        this.stopAtNull = stopAtNull;
        this.end = -1;
    }

    /** A window [from, to) of a list whose modification count was `expected` when the window was made. */
    ArrayWalk(int ch, int from, int to, int expected) {
        this.ch = ch;
        this.stopAtNull = false;
        this.index = from;
        this.end = to;
        this.expected = expected;
    }

    abstract Object[] array();

    abstract int count();

    abstract int stamp();

    private int end() {
        if (end < 0) {
            expected = stamp();
            end = count();
        }
        return end;
    }

    private void checkStamp() {
        if (stamp() != expected) {
            throw new ConcurrentModificationException();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean tryAdvance(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        int hi = end();
        Object[] a = array();
        if (index < hi && index < a.length) {
            Object e = a[index];
            if (e == null && stopAtNull) {
                index = hi;
                checkStamp();
                return false;
            }
            index++;
            action.accept((E) e);
            checkStamp();
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEachRemaining(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        int hi = end();
        Object[] a = array();
        int i = index;
        index = hi;
        if (hi > a.length) {
            throw new ConcurrentModificationException();
        }
        for (; i < hi; i++) {
            Object e = a[i];
            if (e == null && stopAtNull) {
                break;
            }
            action.accept((E) e);
        }
        checkStamp();
    }

    @Override
    public Spliterator<E> trySplit() {
        int hi = end();
        int lo = index;
        int mid = (lo + hi) >>> 1;
        if (lo >= mid) {
            return null;
        }
        index = mid;
        final int exp = expected;
        return new ArrayWalk<E>(ch, lo, mid, exp) {
            @Override
            Object[] array() { return ArrayWalk.this.array(); }

            @Override
            int count() { return ArrayWalk.this.count(); }

            @Override
            int stamp() { return ArrayWalk.this.stamp(); }
        };
    }

    @Override
    public long estimateSize() { return end() - index; }

    @Override
    public int characteristics() { return ch; }
}
