package java.util.concurrent.atomic;

import java.util.Arrays;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import lazy.internal.Atomics;

public class AtomicLongArray implements java.io.Serializable {
    private final long[] array;

    public AtomicLongArray(int length) {
        array = new long[length];
    }

    public AtomicLongArray(long[] array) {
        this.array = array.clone();
    }

    private int checked(int i) {
        if (i < 0 || i >= array.length) {
            throw new IndexOutOfBoundsException("index " + i);
        }
        return i;
    }

    public final int length() {
        return array.length;
    }

    public final long get(int i) {
        return array[checked(i)];
    }

    public final void set(int i, long newValue) {
        array[checked(i)] = newValue;
    }

    public final void lazySet(int i, long newValue) {
        array[checked(i)] = newValue;
    }

    public final long getAndSet(int i, long newValue) {
        return Atomics.getAndSetLong(array, checked(i), newValue);
    }

    public final boolean compareAndSet(int i, long expect, long update) {
        return Atomics.compareAndSetLong(array, checked(i), expect, update);
    }

    public final boolean weakCompareAndSet(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    public final long getAndIncrement(int i) {
        return getAndAdd(i, 1L);
    }

    public final long getAndDecrement(int i) {
        return getAndAdd(i, -1L);
    }

    public final long getAndAdd(int i, long delta) {
        return Atomics.getAndAddLong(array, checked(i), delta);
    }

    public final long incrementAndGet(int i) {
        return getAndAdd(i, 1L) + 1L;
    }

    public final long decrementAndGet(int i) {
        return getAndAdd(i, -1L) - 1L;
    }

    public long addAndGet(int i, long delta) {
        return getAndAdd(i, delta) + delta;
    }

    public final long getAndUpdate(int i, LongUnaryOperator updateFunction) {
        long current;
        do {
            current = get(i);
        } while (!compareAndSet(i, current, updateFunction.applyAsLong(current)));
        return current;
    }

    public final long updateAndGet(int i, LongUnaryOperator updateFunction) {
        long current;
        long next;
        do {
            current = get(i);
            next = updateFunction.applyAsLong(current);
        } while (!compareAndSet(i, current, next));
        return next;
    }

    public final long getAndAccumulate(int i, long x, LongBinaryOperator accumulatorFunction) {
        long current;
        do {
            current = get(i);
        } while (!compareAndSet(i, current, accumulatorFunction.applyAsLong(current, x)));
        return current;
    }

    public final long accumulateAndGet(int i, long x, LongBinaryOperator accumulatorFunction) {
        long current;
        long next;
        do {
            current = get(i);
            next = accumulatorFunction.applyAsLong(current, x);
        } while (!compareAndSet(i, current, next));
        return next;
    }

    public String toString() {
        return Arrays.toString(array);
    }
}
