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
        throw new UnsupportedOperationException("Enum.valueOf arrives in S4");
    }

    protected final void finalize() {
    }
}
