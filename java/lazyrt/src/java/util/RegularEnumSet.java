package java.util;

/** Spec 10.X2: the EnumSet class for enums of at most 64 constants (one word of bits). */
final class RegularEnumSet<E extends Enum<E>> extends BitEnumSet<E> {
    RegularEnumSet(Class<E> enumClass, Enum<?>[] constants) {
        super(enumClass, constants);
    }
}
