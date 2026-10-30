package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import lazy.internal.Atomics;

public abstract class AtomicReferenceFieldUpdater<T, V> {
    public static <U, W> AtomicReferenceFieldUpdater<U, W> newUpdater(Class<U> tclass, Class<W> vclass,
            String fieldName) {
        return new Checked<U, W>(new UpdaterField(tclass, fieldName, Atomics.callerClass()), vclass);
    }

    protected AtomicReferenceFieldUpdater() {
    }

    public abstract boolean compareAndSet(T obj, V expect, V update);

    public abstract boolean weakCompareAndSet(T obj, V expect, V update);

    public abstract void set(T obj, V newValue);

    public abstract void lazySet(T obj, V newValue);

    public abstract V get(T obj);

    public V getAndSet(T obj, V newValue) {
        V current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, newValue));
        return current;
    }

    public final V getAndUpdate(T obj, UnaryOperator<V> updateFunction) {
        V current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, updateFunction.apply(current)));
        return current;
    }

    public final V updateAndGet(T obj, UnaryOperator<V> updateFunction) {
        V current;
        V next;
        do {
            current = get(obj);
            next = updateFunction.apply(current);
        } while (!compareAndSet(obj, current, next));
        return next;
    }

    public final V getAndAccumulate(T obj, V x, BinaryOperator<V> accumulatorFunction) {
        V current;
        do {
            current = get(obj);
        } while (!compareAndSet(obj, current, accumulatorFunction.apply(current, x)));
        return current;
    }

    public final V accumulateAndGet(T obj, V x, BinaryOperator<V> accumulatorFunction) {
        V current;
        V next;
        do {
            current = get(obj);
            next = accumulatorFunction.apply(current, x);
        } while (!compareAndSet(obj, current, next));
        return next;
    }

    /** The updater {@link #newUpdater} returns. */
    private static final class Checked<T, V> extends AtomicReferenceFieldUpdater<T, V> {
        private final UpdaterField target;
        private final Field field;
        private final Class<V> values;

        Checked(UpdaterField target, Class<V> vclass) {
            if (target.field.getType() != vclass) {
                throw new ClassCastException();
            }
            if (vclass.isPrimitive()) {
                throw new IllegalArgumentException("Must be reference type");
            }
            target.requireVolatileInstance();
            this.target = target;
            field = target.field;
            values = vclass;
        }

        private void checkValue(V value) {
            if (value != null && !values.isInstance(value)) {
                throw new ClassCastException();
            }
        }

        @Override
        public boolean compareAndSet(T obj, V expect, V update) {
            target.checkReceiver(obj);
            checkValue(update);
            return Atomics.compareAndSetRef(obj, field, expect, update);
        }

        @Override
        public boolean weakCompareAndSet(T obj, V expect, V update) {
            return compareAndSet(obj, expect, update);
        }

        @Override
        public void set(T obj, V newValue) {
            target.checkReceiver(obj);
            checkValue(newValue);
            Atomics.getAndSetRef(obj, field, newValue);
        }

        @Override
        public void lazySet(T obj, V newValue) {
            set(obj, newValue);
        }

        @Override
        @SuppressWarnings("unchecked")
        public V get(T obj) {
            target.checkReceiver(obj);
            return (V) Atomics.getRef(obj, field);
        }
    }
}
