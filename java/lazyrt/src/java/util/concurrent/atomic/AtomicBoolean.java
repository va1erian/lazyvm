package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import lazy.internal.Atomics;

public class AtomicBoolean implements java.io.Serializable {
    private static final Field VALUE = Atomics.field(AtomicBoolean.class, "value");

    /** 0 or 1, because the intrinsics work on ints. */
    private volatile int value;

    public AtomicBoolean(boolean initialValue) {
        value = initialValue ? 1 : 0;
    }

    public AtomicBoolean() {
    }

    public final boolean get() {
        return value != 0;
    }

    public final boolean compareAndSet(boolean expect, boolean update) {
        return Atomics.compareAndSetInt(this, VALUE, expect ? 1 : 0, update ? 1 : 0);
    }

    public boolean weakCompareAndSet(boolean expect, boolean update) {
        return compareAndSet(expect, update);
    }

    public final void set(boolean newValue) {
        value = newValue ? 1 : 0;
    }

    public final void lazySet(boolean newValue) {
        value = newValue ? 1 : 0;
    }

    public final boolean getAndSet(boolean newValue) {
        return Atomics.getAndSetInt(this, VALUE, newValue ? 1 : 0) != 0;
    }

    public String toString() {
        return Boolean.toString(get());
    }
}
