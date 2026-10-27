package java.util;

/**
 * A set of constants of one enum, kept as bits by ordinal (BitEnumSet). Spec 10.X2: the
 * factories return a RegularEnumSet for at most 64 constants, else a JumboEnumSet.
 */
public abstract class EnumSet<E extends Enum<E>> extends AbstractSet<E> implements Cloneable, java.io.Serializable {
    // Spec 10.X2: the most constants a RegularEnumSet holds.
    private static final int REGULAR_LIMIT = 64;

    final Class<E> enumClass;
    final Enum<?>[] constants;

    EnumSet(Class<E> enumClass, Enum<?>[] constants) {
        this.enumClass = enumClass;
        this.constants = constants;
    }

    public static <E extends Enum<E>> EnumSet<E> noneOf(Class<E> enumClass) {
        Enum<?>[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            throw new ClassCastException(enumClass + " not an enum");
        }
        return constants.length <= REGULAR_LIMIT
                ? new RegularEnumSet<E>(enumClass, constants)
                : new JumboEnumSet<E>(enumClass, constants);
    }

    public static <E extends Enum<E>> EnumSet<E> allOf(Class<E> enumClass) {
        EnumSet<E> s = noneOf(enumClass);
        s.addRange(0, s.constants.length);
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> copyOf(EnumSet<E> s) { return s.clone(); }

    public static <E extends Enum<E>> EnumSet<E> copyOf(Collection<E> c) {
        if (c instanceof EnumSet) {
            return ((EnumSet<E>) c).clone();
        }
        if (c.isEmpty()) {
            throw new IllegalArgumentException("Collection is empty");
        }
        Iterator<E> it = c.iterator();
        EnumSet<E> s = of(it.next());
        while (it.hasNext()) {
            s.add(it.next());
        }
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> complementOf(EnumSet<E> s) {
        EnumSet<E> result = copyOf(s);
        result.complement();
        return result;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e) {
        EnumSet<E> s = noneOf(e.getDeclaringClass());
        s.add(e);
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2) {
        EnumSet<E> s = of(e1);
        s.add(e2);
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3) {
        EnumSet<E> s = of(e1, e2);
        s.add(e3);
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3, E e4) {
        EnumSet<E> s = of(e1, e2, e3);
        s.add(e4);
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3, E e4, E e5) {
        EnumSet<E> s = of(e1, e2, e3, e4);
        s.add(e5);
        return s;
    }

    @SafeVarargs
    public static <E extends Enum<E>> EnumSet<E> of(E first, E... rest) {
        EnumSet<E> s = of(first);
        for (E e : rest) {
            s.add(e);
        }
        return s;
    }

    public static <E extends Enum<E>> EnumSet<E> range(E from, E to) {
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        EnumSet<E> s = noneOf(from.getDeclaringClass());
        s.addRange(from.ordinal(), to.ordinal() + 1);
        return s;
    }

    @SuppressWarnings("unchecked")
    public EnumSet<E> clone() {
        try {
            EnumSet<E> copy = (EnumSet<E>) super.clone();
            copy.ownBits();
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /** Adds the constants with ordinals in [from, to). */
    abstract void addRange(int from, int to);

    /** Makes this set hold exactly the constants it did not hold. */
    abstract void complement();

    /** After a clone: stop sharing the bit storage with the original. */
    abstract void ownBits();
}
