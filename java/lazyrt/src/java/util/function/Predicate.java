package java.util.function;

import java.util.Objects;

@FunctionalInterface
public interface Predicate<T> {
    boolean test(T t);

    default Predicate<T> and(Predicate<? super T> other) {
        Objects.requireNonNull(other);
        return (T t) -> test(t) && other.test(t);
    }

    default Predicate<T> negate() {
        return (T t) -> !test(t);
    }

    default Predicate<T> or(Predicate<? super T> other) {
        Objects.requireNonNull(other);
        return (T t) -> test(t) || other.test(t);
    }

    static <T> Predicate<T> isEqual(Object targetRef) {
        if (targetRef == null) {
            return t -> t == null;
        }
        return t -> targetRef.equals(t);
    }
}
