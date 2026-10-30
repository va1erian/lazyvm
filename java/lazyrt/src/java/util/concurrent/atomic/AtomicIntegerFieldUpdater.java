package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import lazy.internal.Atomics;

public abstract class AtomicIntegerFieldUpdater<T> {
    public static <U> AtomicIntegerFieldUpdater<U> newUpdater(Class<U> tclass, String fieldName) {
        return new Checked<U>(new UpdaterField(tclass, fieldName, Atomics.callerClass()));
    }

    protected AtomicIntegerFieldUpdater() {
    }

    public abstract boolean compareAndSet(T obj, int expect, int update);

    public abstract boolean weakCompareAndSet(T obj, int expect, int update);

    public abstract void set(T obj, int newValue);

    public abstract void lazySet(T obj, int newValue);

    public abstract int get(T obj);

    public int getAndSet(T obj, int newValue) {
        int current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, newValue));
        return current;
    }

    /** Private because no concrete method calls another: a subclass's overrides stay independent. */
    private int add(T obj, int delta) {
        int current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, current + delta));
        return current;
    }

    public int getAndIncrement(T obj) {
        return add(obj, 1);
    }

    public int getAndDecrement(T obj) {
        return add(obj, -1);
    }

    public int getAndAdd(T obj, int delta) {
        return add(obj, delta);
    }

    public int incrementAndGet(T obj) {
        return add(obj, 1) + 1;
    }

    public int decrementAndGet(T obj) {
        return add(obj, -1) - 1;
    }

    public int addAndGet(T obj, int delta) {
        return add(obj, delta) + delta;
    }

    public final int getAndUpdate(T obj, IntUnaryOperator updateFunction) {
        int current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, updateFunction.applyAsInt(current)));
        return current;
    }

    public final int updateAndGet(T obj, IntUnaryOperator updateFunction) {
        int current;
        int next;
        do {
            current = get(obj);
            next = updateFunction.applyAsInt(current);
        } while (!compareAndSet(obj, current, next));
        return next;
    }

    public final int getAndAccumulate(T obj, int x, IntBinaryOperator accumulatorFunction) {
        int current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, accumulatorFunction.applyAsInt(current, x)));
        return current;
    }

    public final int accumulateAndGet(T obj, int x, IntBinaryOperator accumulatorFunction) {
        int current;
        int next;
        do {
            current = get(obj);
            next = accumulatorFunction.applyAsInt(current, x);
        } while (!compareAndSet(obj, current, next));
        return next;
    }

    /** The updater {@link #newUpdater} returns. */
    private static final class Checked<T> extends AtomicIntegerFieldUpdater<T> {
        private final UpdaterField target;
        private final Field field;

        Checked(UpdaterField target) {
            if (target.field.getType() != int.class) {
                throw new IllegalArgumentException("Must be integer type");
            }
            target.requireVolatileInstance();
            this.target = target;
            field = target.field;
        }

        @Override
        public boolean compareAndSet(T obj, int expect, int update) {
            target.checkReceiver(obj);
            return Atomics.compareAndSetInt(obj, field, expect, update);
        }

        @Override
        public boolean weakCompareAndSet(T obj, int expect, int update) {
            return compareAndSet(obj, expect, update);
        }

        @Override
        public void set(T obj, int newValue) {
            target.checkReceiver(obj);
            Atomics.getAndSetInt(obj, field, newValue);
        }

        @Override
        public void lazySet(T obj, int newValue) {
            set(obj, newValue);
        }

        @Override
        public int get(T obj) {
            target.checkReceiver(obj);
            return Atomics.getAndAddInt(obj, field, 0);
        }
    }
}
