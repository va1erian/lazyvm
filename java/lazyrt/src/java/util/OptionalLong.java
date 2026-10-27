package java.util;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** A long or nothing (docs/design/util.md section 2, rules O1-O8). */
public final class OptionalLong {
    /** O1: the one empty object. */
    private static final OptionalLong EMPTY = new OptionalLong(false, 0);

    private final boolean present;
    private final long value;

    private OptionalLong(boolean present, long value) {
        this.present = present;
        this.value = value;
    }

    public static OptionalLong empty() { return EMPTY; }

    /** O1: a fresh object per call. */
    public static OptionalLong of(long value) { return new OptionalLong(true, value); }

    /** O6. */
    public long getAsLong() {
        if (!present) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public boolean isPresent() { return present; }

    /** O5: a null consumer only fails when there is a value to give it. */
    public void ifPresent(LongConsumer consumer) {
        if (present) {
            consumer.accept(value);
        }
    }

    public long orElse(long other) { return present ? value : other; }

    public long orElseGet(LongSupplier other) { return present ? value : other.getAsLong(); }

    /** O8: the supplier's own exception object is thrown. */
    public <X extends Throwable> long orElseThrow(Supplier<X> exceptionSupplier) throws X {
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
        if (!(obj instanceof OptionalLong)) {
            return false;
        }
        OptionalLong other = (OptionalLong) obj;
        return present && other.present ? value == other.value : present == other.present;
    }

    /** O3: 0 when empty, which an of(0) shares. */
    @Override
    public int hashCode() { return present ? Long.hashCode(value) : 0; }

    /** O2. */
    @Override
    public String toString() { return present ? "OptionalLong[" + value + "]" : "OptionalLong.empty"; }
}
