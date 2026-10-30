package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import lazy.internal.Atomics;

public abstract class AtomicLongFieldUpdater<T> {
    public static <U> AtomicLongFieldUpdater<U> newUpdater(Class<U> tclass, String fieldName) {
        return new Checked<U>(new UpdaterField(tclass, fieldName, Atomics.callerClass()));
    }

    protected AtomicLongFieldUpdater() {
    }

    public abstract boolean compareAndSet(T obj, long expect, long update);

    public abstract boolean weakCompareAndSet(T obj, long expect, long update);

    public abstract void set(T obj, long newValue);

    public abstract void lazySet(T obj, long newValue);

    public abstract long get(T obj);

    public long getAndSet(T obj, long newValue) {
        long current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, newValue));
        return current;
    }

    /** Private because no concrete method calls another: a subclass's overrides stay independent. */
    private long add(T obj, long delta) {
        long current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, current + delta));
        return current;
    }

    public long getAndIncrement(T obj) {
        return add(obj, 1L);
    }

    public long getAndDecrement(T obj) {
        return add(obj, -1L);
    }

    public long getAndAdd(T obj, long delta) {
        return add(obj, delta);
    }

    public long incrementAndGet(T obj) {
        return add(obj, 1L) + 1L;
    }

    public long decrementAndGet(T obj) {
        return add(obj, -1L) - 1L;
    }

    public long addAndGet(T obj, long delta) {
        return add(obj, delta) + delta;
    }

    public final long getAndUpdate(T obj, LongUnaryOperator updateFunction) {
        long current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, updateFunction.applyAsLong(current)));
        return current;
    }

    public final long updateAndGet(T obj, LongUnaryOperator updateFunction) {
        long current;
        long next;
        do {
            current = get(obj);
            next = updateFunction.applyAsLong(current);
        } while (!compareAndSet(obj, current, next));
        return next;
    }

    public final long getAndAccumulate(T obj, long x, LongBinaryOperator accumulatorFunction) {
        long current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, accumulatorFunction.applyAsLong(current, x)));
        return current;
    }

    public final long accumulateAndGet(T obj, long x, LongBinaryOperator accumulatorFunction) {
        long current;
        long next;
        do {
            current = get(obj);
            next = accumulatorFunction.applyAsLong(current, x);
        } while (!compareAndSet(obj, current, next));
        return next;
    }

    /** The updater {@link #newUpdater} returns. */
    private static final class Checked<T> extends AtomicLongFieldUpdater<T> {
        private final UpdaterField target;
        private final Field field;

        Checked(UpdaterField target) {
            if (target.field.getType() != long.class) {
                throw new IllegalArgumentException("Must be long type");
            }
            target.requireVolatileInstance();
            this.target = target;
            field = target.field;
        }

        @Override
        public boolean compareAndSet(T obj, long expect, long update) {
            target.checkReceiver(obj);
            return Atomics.compareAndSetLong(obj, field, expect, update);
        }

        @Override
        public boolean weakCompareAndSet(T obj, long expect, long update) {
            return compareAndSet(obj, expect, update);
        }

        @Override
        public void set(T obj, long newValue) {
            target.checkReceiver(obj);
            Atomics.getAndSetLong(obj, field, newValue);
        }

        @Override
        public void lazySet(T obj, long newValue) {
            set(obj, newValue);
        }

        @Override
        public long get(T obj) {
            target.checkReceiver(obj);
            return Atomics.getAndAddLong(obj, field, 0L);
        }
    }
}
