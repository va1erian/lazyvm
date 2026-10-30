package java.util.concurrent.atomic;

import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import lazy.internal.Atomics;

public class AtomicReferenceArray<E> implements java.io.Serializable {
    /** Always an Object[], so a store never depends on the type of the array given. */
    private final Object[] array;

    public AtomicReferenceArray(int length) {
        array = new Object[length];
    }

    public AtomicReferenceArray(E[] array) {
        this.array = Arrays.copyOf(array, array.length, Object[].class);
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

    @SuppressWarnings("unchecked")
    public final E get(int i) {
        return (E) array[checked(i)];
    }

    public final void set(int i, E newValue) {
        array[checked(i)] = newValue;
    }

    public final void lazySet(int i, E newValue) {
        array[checked(i)] = newValue;
    }

    @SuppressWarnings("unchecked")
    public final E getAndSet(int i, E newValue) {
        return (E) Atomics.getAndSetRef(array, checked(i), newValue);
    }

    public final boolean compareAndSet(int i, E expect, E update) {
        return Atomics.compareAndSetRef(array, checked(i), expect, update);
    }

    public final boolean weakCompareAndSet(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    public final E getAndUpdate(int i, UnaryOperator<E> updateFunction) {
        E current;
        do {
            current = get(i);
        } while (!compareAndSet(i, current, updateFunction.apply(current)));
        return current;
    }

    public final E updateAndGet(int i, UnaryOperator<E> updateFunction) {
        E current;
        E next;
        do {
            current = get(i);
            next = updateFunction.apply(current);
        } while (!compareAndSet(i, current, next));
        return next;
    }

    public final E getAndAccumulate(int i, E x, BinaryOperator<E> accumulatorFunction) {
        E current;
        do {
            current = get(i);
        } while (!compareAndSet(i, current, accumulatorFunction.apply(current, x)));
        return current;
    }

    public final E accumulateAndGet(int i, E x, BinaryOperator<E> accumulatorFunction) {
        E current;
        E next;
        do {
            current = get(i);
            next = accumulatorFunction.apply(current, x);
        } while (!compareAndSet(i, current, next));
        return next;
    }

    public String toString() {
        return Arrays.toString(array);
    }
}
