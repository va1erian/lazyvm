package java.util;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** A int or nothing (docs/design/util.md section 2, rules O1-O8). */
public final class OptionalInt {
    /** O1: the one empty object. */
    private static final OptionalInt EMPTY = new OptionalInt(false, 0);

    private final boolean present;
    private final int value;

    private OptionalInt(boolean present, int value) {
        this.present = present;
        this.value = value;
    }

    public static OptionalInt empty() { return EMPTY; }

    /** O1: a fresh object per call. */
    public static OptionalInt of(int value) { return new OptionalInt(true, value); }

    /** O6. */
    public int getAsInt() {
        if (!present) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public boolean isPresent() { return present; }

    /** O5: a null consumer only fails when there is a value to give it. */
    public void ifPresent(IntConsumer consumer) {
        if (present) {
            consumer.accept(value);
        }
    }

    public int orElse(int other) { return present ? value : other; }

    public int orElseGet(IntSupplier other) { return present ? value : other.getAsInt(); }

    /** O8: the supplier's own exception object is thrown. */
    public <X extends Throwable> int orElseThrow(Supplier<X> exceptionSupplier) throws X {
        if (!present) {
            throw exceptionSupplier.get();
        }
        return value;
    }

    /** O4. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OptionalInt)) {
            return false;
        }
        OptionalInt other = (OptionalInt) obj;
        return present && other.present ? value == other.value : present == other.present;
    }

    /** O3: 0 when empty, which an of(0) shares. */
    @Override
    public int hashCode() { return present ? value : 0; }

    /** O2. */
    @Override
    public String toString() { return present ? "OptionalInt[" + value + "]" : "OptionalInt.empty"; }
}
