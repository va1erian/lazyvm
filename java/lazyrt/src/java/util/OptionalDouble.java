package java.util;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** A double or nothing (docs/design/util.md section 2, rules O1-O8). */
public final class OptionalDouble {
    /** O1: the one empty object. */
    private static final OptionalDouble EMPTY = new OptionalDouble(false, 0);

    private final boolean present;
    private final double value;

    private OptionalDouble(boolean present, double value) {
        this.present = present;
        this.value = value;
    }

    public static OptionalDouble empty() { return EMPTY; }

    /** O1: a fresh object per call. */
    public static OptionalDouble of(double value) { return new OptionalDouble(true, value); }

    /** O6: asking an empty object for its value is an error with a fixed message. */
    public double getAsDouble() {
        if (!present) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public boolean isPresent() { return present; }

    /** O5: a null consumer only fails when there is a value to give it. */
    public void ifPresent(DoubleConsumer consumer) {
        if (present) {
            consumer.accept(value);
        }
    }

    public double orElse(double other) { return present ? value : other; }

    public double orElseGet(DoubleSupplier other) { return present ? value : other.getAsDouble(); }

    /** O8: the supplier's own exception object is thrown. */
    public <X extends Throwable> double orElseThrow(Supplier<X> exceptionSupplier) throws X {
        if (!present) {
            throw exceptionSupplier.get();
        }
        return value;
    }

    /** O4: only the same class, both empty or both present; Double.compare semantics: NaN equals NaN, 0.0 differs from -0.0. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OptionalDouble)) {
            return false;
        }
        OptionalDouble other = (OptionalDouble) obj;
        return present && other.present ? Double.compare(value, other.value) == 0 : present == other.present;
    }

    /** O3: 0 when empty, which an of(0) shares. */
    @Override
    public int hashCode() { return present ? Double.hashCode(value) : 0; }

    /** O2: the value as its wrapper's toString prints it, or the class name and .empty. */
    @Override
    public String toString() { return present ? "OptionalDouble[" + value + "]" : "OptionalDouble.empty"; }
}
