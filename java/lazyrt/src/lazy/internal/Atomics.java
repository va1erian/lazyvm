package lazy.internal;

import java.lang.reflect.Field;

/**
 * Read-modify-write operations on one int, long or reference variable, each
 * atomic because its native runs without giving up the VM lock
 * (docs/design/atomics.md). A field is named by its {@link Field}, which the
 * native checks on every call: kind, receiver, and the value of a reference
 * store. Static fields ignore {@code target}. Plain reads and writes need no
 * help here: the bytecodes already do them.
 */
public final class Atomics {
    private Atomics() {
    }

    /** A field of a library class, by a name the class itself guarantees. */
    public static Field field(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new InternalError(name);
        }
    }

    public static native boolean compareAndSetInt(Object target, Field f, int expect, int update);

    public static native int getAndSetInt(Object target, Field f, int value);

    public static native int getAndAddInt(Object target, Field f, int delta);

    public static native boolean compareAndSetLong(Object target, Field f, long expect, long update);

    public static native long getAndSetLong(Object target, Field f, long value);

    public static native long getAndAddLong(Object target, Field f, long delta);

    public static native boolean compareAndSetRef(Object target, Field f, Object expect, Object update);

    public static native Object getAndSetRef(Object target, Field f, Object value);

    /** Reads a reference field; int and long reads are {@code getAndAdd} of zero. */
    public static native Object getRef(Object target, Field f);

    public static native boolean compareAndSetInt(int[] array, int i, int expect, int update);

    public static native int getAndSetInt(int[] array, int i, int value);

    public static native int getAndAddInt(int[] array, int i, int delta);

    public static native boolean compareAndSetLong(long[] array, int i, long expect, long update);

    public static native long getAndSetLong(long[] array, int i, long value);

    public static native long getAndAddLong(long[] array, int i, long delta);

    public static native boolean compareAndSetRef(Object[] array, int i, Object expect, Object update);

    public static native Object getAndSetRef(Object[] array, int i, Object value);

    /**
     * The class whose code called the method that calls this, for the access
     * checks of caller-sensitive methods; null when the VM itself called it.
     * Reflective calls ({@code Method.invoke}) are looked through.
     */
    public static native Class<?> callerClass();
}
