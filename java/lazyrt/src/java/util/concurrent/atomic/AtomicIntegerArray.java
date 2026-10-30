package java.util.concurrent.atomic;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import lazy.internal.Atomics;

public class AtomicIntegerArray implements java.io.Serializable {
    private final int[] array;

    public AtomicIntegerArray(int length) {
        array = new int[length];
    }

    public AtomicIntegerArray(int[] array) {
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

    public final int get(int i) {
        return array[checked(i)];
    }

    public final void set(int i, int newValue) {
        array[checked(i)] = newValue;
    }

    public final void lazySet(int i, int newValue) {
        array[checked(i)] = newValue;
    }

    public final int getAndSet(int i, int newValue) {
        return Atomics.getAndSetInt(array, checked(i), newValue);
    }

    public final boolean compareAndSet(int i, int expect, int update) {
        return Atomics.compareAndSetInt(array, checked(i), expect, update);
    }

    public final boolean weakCompareAndSet(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    public final int getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    public final int getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    public final int getAndAdd(int i, int delta) {
        return Atomics.getAndAddInt(array, checked(i), delta);
    }

    public final int incrementAndGet(int i) {
        return getAndAdd(i, 1) + 1;
    }

    public final int decrementAndGet(int i) {
        return getAndAdd(i, -1) - 1;
    }

    public final int addAndGet(int i, int delta) {
        return getAndAdd(i, delta) + delta;
    }

    public final int getAndUpdate(int i, IntUnaryOperator updateFunction) {
        int current;
        do {
            current = get(i);
        } while (!compareAndSet(i, current, updateFunction.applyAsInt(current)));
        return current;
    }

    public final int updateAndGet(int i, IntUnaryOperator updateFunction) {
        int current;
        int next;
        do {
            current = get(i);
            next = updateFunction.applyAsInt(current);
        } while (!compareAndSet(i, current, next));
        return next;
    }

    public final int getAndAccumulate(int i, int x, IntBinaryOperator accumulatorFunction) {
        int current;
        do {
            current = get(i);
        } while (!compareAndSet(i, current, accumulatorFunction.applyAsInt(current, x)));
        return current;
    }

    public final int accumulateAndGet(int i, int x, IntBinaryOperator accumulatorFunction) {
        int current;
        int next;
        do {
            current = get(i);
            next = accumulatorFunction.applyAsInt(current, x);
        } while (!compareAndSet(i, current, next));
        return next;
    }

    public String toString() {
        return Arrays.toString(array);
    }
}
