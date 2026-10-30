package java.util.concurrent.atomic;

import java.util.function.DoubleBinaryOperator;

/** One value, as raw bits because the intrinsics compare longs, not doubles. */
public class DoubleAccumulator extends Number implements java.io.Serializable {
    private final DoubleBinaryOperator function;
    private final long identityBits;
    private final AtomicLong bits;

    public DoubleAccumulator(DoubleBinaryOperator accumulatorFunction, double identity) {
        function = accumulatorFunction;
        identityBits = Double.doubleToRawLongBits(identity);
        bits = new AtomicLong(identityBits);
    }

    public void accumulate(double x) {
        long current;
        long next;
        do {
            current = bits.get();
            next = Double.doubleToRawLongBits(function.applyAsDouble(Double.longBitsToDouble(current), x));
        } while (!bits.compareAndSet(current, next));
    }

    public double get() {
        return Double.longBitsToDouble(bits.get());
    }

    public void reset() {
        bits.set(identityBits);
    }

    public double getThenReset() {
        return Double.longBitsToDouble(bits.getAndSet(identityBits));
    }

    public String toString() {
        return Double.toString(get());
    }

    public double doubleValue() {
        return get();
    }

    public long longValue() {
        return (long) get();
    }

    public int intValue() {
        return (int) get();
    }

    public float floatValue() {
        return (float) get();
    }
}
