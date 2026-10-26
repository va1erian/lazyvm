package java.lang;

/** The mirror of a loaded class; the VM creates every instance. */
public final class Class<T> implements java.io.Serializable {
    private transient int vmClassId;

    private Class() {
    }

    public String toString() {
        String kind = isInterface() ? "interface " : (isPrimitive() ? "" : "class ");
        return kind + getName();
    }

    public native String getName();

    public native boolean isInstance(Object obj);

    public native boolean isAssignableFrom(Class<?> cls);

    public native boolean isInterface();

    public native boolean isArray();

    public native boolean isPrimitive();

    public native Class<? super T> getSuperclass();

    public native Class<?> getComponentType();

    public native int getModifiers();

    static native Class<?> getPrimitiveClass(String name);

    private static native Class<?> forName0(String name, boolean initialize)
            throws ClassNotFoundException;

    public static Class<?> forName(String className) throws ClassNotFoundException {
        return forName0(className, true);
    }

    public boolean desiredAssertionStatus() {
        return false;
    }

    public boolean isEnum() {
        return (getModifiers() & 0x4000) != 0;
    }

    public boolean isAnnotation() {
        return (getModifiers() & 0x2000) != 0;
    }

    @SuppressWarnings("unchecked")
    public T cast(Object obj) {
        if (obj != null && !isInstance(obj)) {
            throw new ClassCastException("Cannot cast " + obj.getClass().getName() + " to " + getName());
        }
        return (T) obj;
    }

    @SuppressWarnings("unchecked")
    public <U> Class<? extends U> asSubclass(Class<U> clazz) {
        if (clazz.isAssignableFrom(this)) {
            return (Class<? extends U>) this;
        }
        throw new ClassCastException(toString());
    }

    /** The unqualified name; "" for anonymous classes. */
    public String getSimpleName() {
        if (isArray()) {
            return getComponentType().getSimpleName() + "[]";
        }
        String name = getName();
        String s = name.substring(name.lastIndexOf('.') + 1);
        int dollar = s.lastIndexOf('$');
        if (dollar < 0 || dollar == s.length() - 1) {
            return s;
        }
        // Anonymous classes are Outer$1; local ones are Outer$1Name.
        int i = dollar + 1;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return s.substring(i);
    }
}
