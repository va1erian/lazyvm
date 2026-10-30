package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import lazy.internal.Atomics;

public class AtomicInteger extends Number implements java.io.Serializable {
    private static final Field VALUE = Atomics.field(AtomicInteger.class, "value");

    private volatile int value;

    public AtomicInteger(int initialValue) {
        value = initialValue;
    }

    public AtomicInteger() {
    }

    public final int get() {
        return value;
    }

    public final void set(int newValue) {
        value = newValue;
    }

    public final void lazySet(int newValue) {
        value = newValue;
    }

    public final int getAndSet(int newValue) {
        return Atomics.getAndSetInt(this, VALUE, newValue);
    }

    public final boolean compareAndSet(int expect, int update) {
        return Atomics.compareAndSetInt(this, VALUE, expect, update);
    }

    public final boolean weakCompareAndSet(int expect, int update) {
        return compareAndSet(expect, update);
    }

    public final int getAndIncrement() {
        return getAndAdd(1);
    }

    public final int getAndDecrement() {
        return getAndAdd(-1);
    }

    public final int getAndAdd(int delta) {
        return Atomics.getAndAddInt(this, VALUE, delta);
    }

    public final int incrementAndGet() {
        return getAndAdd(1) + 1;
    }

    public final int decrementAndGet() {
        return getAndAdd(-1) - 1;
    }

    public final int addAndGet(int delta) {
        return getAndAdd(delta) + delta;
    }

    public final int getAndUpdate(IntUnaryOperator updateFunction) {
        int current;
        do {
            current = value;
        } while (!compareAndSet(current, updateFunction.applyAsInt(current)));
        return current;
    }

    public final int updateAndGet(IntUnaryOperator updateFunction) {
        int current;
        int next;
        do {
            current = value;
            next = updateFunction.applyAsInt(current);
        } while (!compareAndSet(current, next));
        return next;
    }

    public final int getAndAccumulate(int x, IntBinaryOperator accumulatorFunction) {
        int current;
        do {
            current = value;
        } while (!compareAndSet(current, accumulatorFunction.applyAsInt(current, x)));
        return current;
    }

    public final int accumulateAndGet(int x, IntBinaryOperator accumulatorFunction) {
        int current;
        int next;
        do {
            current = value;
            next = accumulatorFunction.applyAsInt(current, x);
        } while (!compareAndSet(current, next));
        return next;
    }

    public String toString() {
        return Integer.toString(value);
    }

    public int intValue() {
        return value;
    }

    public long longValue() {
        return value;
    }

    public float floatValue() {
        return value;
    }

    public double doubleValue() {
        return value;
    }
}
