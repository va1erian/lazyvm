package java.util;

/** Spec 10.X2: the EnumSet class for enums of more than 64 constants. */
final class JumboEnumSet<E extends Enum<E>> extends BitEnumSet<E> {
    JumboEnumSet(Class<E> enumClass, Enum<?>[] constants) {
        super(enumClass, constants);
    }
}
