package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import lazy.internal.Atomics;

public class AtomicLong extends Number implements java.io.Serializable {
    private static final Field VALUE = Atomics.field(AtomicLong.class, "value");

    private volatile long value;

    public AtomicLong(long initialValue) {
        value = initialValue;
    }

    public AtomicLong() {
    }

    public final long get() {
        return value;
    }

    public final void set(long newValue) {
        value = newValue;
    }

    public final void lazySet(long newValue) {
        value = newValue;
    }

    public final long getAndSet(long newValue) {
        return Atomics.getAndSetLong(this, VALUE, newValue);
    }

    public final boolean compareAndSet(long expect, long update) {
        return Atomics.compareAndSetLong(this, VALUE, expect, update);
    }

    public final boolean weakCompareAndSet(long expect, long update) {
        return compareAndSet(expect, update);
    }

    public final long getAndIncrement() {
        return getAndAdd(1L);
    }

    public final long getAndDecrement() {
        return getAndAdd(-1L);
    }

    public final long getAndAdd(long delta) {
        return Atomics.getAndAddLong(this, VALUE, delta);
    }

    public final long incrementAndGet() {
        return getAndAdd(1L) + 1L;
    }

    public final long decrementAndGet() {
        return getAndAdd(-1L) - 1L;
    }

    public final long addAndGet(long delta) {
        return getAndAdd(delta) + delta;
    }

    public final long getAndUpdate(LongUnaryOperator updateFunction) {
        long current;
        do {
            current = value;
        } while (!compareAndSet(current, updateFunction.applyAsLong(current)));
        return current;
    }

    public final long updateAndGet(LongUnaryOperator updateFunction) {
        long current;
        long next;
        do {
            current = value;
            next = updateFunction.applyAsLong(current);
        } while (!compareAndSet(current, next));
        return next;
    }

    public final long getAndAccumulate(long x, LongBinaryOperator accumulatorFunction) {
        long current;
        do {
            current = value;
        } while (!compareAndSet(current, accumulatorFunction.applyAsLong(current, x)));
        return current;
    }

    public final long accumulateAndGet(long x, LongBinaryOperator accumulatorFunction) {
        long current;
        long next;
        do {
            current = value;
            next = accumulatorFunction.applyAsLong(current, x);
        } while (!compareAndSet(current, next));
        return next;
    }

    public String toString() {
        return Long.toString(value);
    }

    public int intValue() {
        return (int) value;
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
