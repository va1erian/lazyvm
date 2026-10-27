package java.util;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** A value or nothing (docs/design/util.md section 2, rules O1-O8). */
public final class Optional<T> {
    /** O1: the one empty object, shared by every route that ends with no value. */
    private static final Optional<?> EMPTY = new Optional<>(null);

    private final T value;

    private Optional(T value) { this.value = value; }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> empty() { return (Optional<T>) EMPTY; }

    /** O1: a fresh object per call, with no cache of common values. */
    public static <T> Optional<T> of(T value) { return new Optional<>(Objects.requireNonNull(value)); }

    public static <T> Optional<T> ofNullable(T value) { return value == null ? empty() : of(value); }

    /** O6. */
    public T get() {
        if (value == null) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public boolean isPresent() { return value != null; }

    /** O5: a null consumer only fails when there is a value to give it. */
    public void ifPresent(Consumer<? super T> consumer) {
        if (value != null) {
            consumer.accept(value);
        }
    }

    /** O5, O7: the argument is checked first; a passing test returns this very object. */
    public Optional<T> filter(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate);
        return value != null && predicate.test(value) ? this : empty();
    }

    /** O7: always a new object (or the shared empty one), never the receiver. */
    public <U> Optional<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper);
        return value == null ? empty() : Optional.ofNullable(mapper.apply(value));
    }

    /** O5, O7: the function's own Optional comes back unchanged; null from it is an error. */
    public <U> Optional<U> flatMap(Function<? super T, Optional<U>> mapper) {
        Objects.requireNonNull(mapper);
        return value == null ? empty() : Objects.requireNonNull(mapper.apply(value));
    }

    public T orElse(T other) { return value != null ? value : other; }

    public T orElseGet(Supplier<? extends T> other) { return value != null ? value : other.get(); }

    /** O8: the supplier's own exception object is thrown (a null one becomes the NPE of O5). */
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if (value == null) {
            throw exceptionSupplier.get();
        }
        return value;
    }

    /** O4: only another Optional, compared through the value's equals. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Optional)) {
            return false;
        }
        Object other = ((Optional<?>) obj).value;
        return value == null ? other == null : other != null && value.equals(other);
    }

    /** O3. */
    @Override
    public int hashCode() { return value == null ? 0 : value.hashCode(); }

    /** O2: String.valueOf of the value, so a null text prints as null. */
    @Override
    public String toString() { return value == null ? "Optional.empty" : "Optional[" + String.valueOf(value) + "]"; }
}
