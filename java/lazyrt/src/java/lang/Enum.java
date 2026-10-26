package java.lang;

import java.io.Serializable;

public abstract class Enum<E extends Enum<E>> implements Comparable<E>, Serializable {
    private final String name;
    private final int ordinal;

    protected Enum(String name, int ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public final String name() {
        return name;
    }

    public final int ordinal() {
        return ordinal;
    }

    public String toString() {
        return name;
    }

    public final boolean equals(Object other) {
        return this == other;
    }

    public final int hashCode() {
        return super.hashCode();
    }

    protected final Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public final int compareTo(E o) {
        Enum<?> other = o;
        if (getDeclaringClass() != other.getDeclaringClass()) {
            throw new ClassCastException();
        }
        return ordinal - other.ordinal;
    }

    @SuppressWarnings("unchecked")
    public final Class<E> getDeclaringClass() {
        Class<?> c = getClass();
        Class<?> sup = c.getSuperclass();
        return (sup == Enum.class) ? (Class<E>) c : (Class<E>) sup;
    }

    public static <T extends Enum<T>> T valueOf(Class<T> enumType, String name) {
        T[] constants = enumType.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException(enumType.getName() + " is not an enum type");
        }
        if (name == null) {
            throw new NullPointerException("Name is null");
        }
        for (T c : constants) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException(
                "No enum constant " + enumType.getName().replace('$', '.') + "." + name);
    }

    protected final void finalize() {
    }
}
