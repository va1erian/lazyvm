package java.lang;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A variable with one value per thread. Values live in a map on the
 * {@code Thread}, keyed by identity; they go when the thread ends or on
 * {@code remove} (the map holds its keys strongly, docs/design/threads.md).
 */
public class ThreadLocal<T> {
    public ThreadLocal() {
    }

    protected T initialValue() {
        return null;
    }

    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        if (supplier == null) {
            throw new NullPointerException();
        }
        return new SuppliedThreadLocal<S>(supplier);
    }

    @SuppressWarnings("unchecked")
    public T get() {
        Map<ThreadLocal<?>, Object> map = mapOf(Thread.currentThread(), false);
        if (map != null && map.containsKey(this)) {
            return (T) map.get(this);
        }
        T value = initialValue();
        mapOf(Thread.currentThread(), true).put(this, value);
        return value;
    }

    public void set(T value) {
        mapOf(Thread.currentThread(), true).put(this, value);
    }

    public void remove() {
        Map<ThreadLocal<?>, Object> map = mapOf(Thread.currentThread(), false);
        if (map != null) {
            map.remove(this);
        }
    }

    /** The thread's map for this kind of local, made on demand when `create`. */
    Map<ThreadLocal<?>, Object> mapOf(Thread t, boolean create) {
        if (t.locals == null && create) {
            t.locals = new IdentityHashMap<ThreadLocal<?>, Object>();
        }
        return t.locals;
    }

    private static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {
        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = supplier;
        }

        protected T initialValue() {
            return supplier.get();
        }
    }
}
