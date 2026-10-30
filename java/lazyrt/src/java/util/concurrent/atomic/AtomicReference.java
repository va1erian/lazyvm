package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import lazy.internal.Atomics;

public class AtomicReference<V> implements java.io.Serializable {
    private static final Field VALUE = Atomics.field(AtomicReference.class, "value");

    private volatile V value;

    public AtomicReference(V initialValue) {
        value = initialValue;
    }

    public AtomicReference() {
    }

    public final V get() {
        return value;
    }

    public final void set(V newValue) {
        value = newValue;
    }

    public final void lazySet(V newValue) {
        value = newValue;
    }

    public final boolean compareAndSet(V expect, V update) {
        return Atomics.compareAndSetRef(this, VALUE, expect, update);
    }

    public final boolean weakCompareAndSet(V expect, V update) {
        return compareAndSet(expect, update);
    }

    @SuppressWarnings("unchecked")
    public final V getAndSet(V newValue) {
        return (V) Atomics.getAndSetRef(this, VALUE, newValue);
    }

    public final V getAndUpdate(UnaryOperator<V> updateFunction) {
        V current;
        do {
            current = value;
        } while (!compareAndSet(current, updateFunction.apply(current)));
        return current;
    }

    public final V updateAndGet(UnaryOperator<V> updateFunction) {
        V current;
        V next;
        do {
            current = value;
            next = updateFunction.apply(current);
        } while (!compareAndSet(current, next));
        return next;
    }

    public final V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
        V current;
        do {
            current = value;
        } while (!compareAndSet(current, accumulatorFunction.apply(current, x)));
        return current;
    }

    public final V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
        V current;
        V next;
        do {
            current = value;
            next = accumulatorFunction.apply(current, x);
        } while (!compareAndSet(current, next));
        return next;
    }

    public String toString() {
        return String.valueOf(value);
    }
}
