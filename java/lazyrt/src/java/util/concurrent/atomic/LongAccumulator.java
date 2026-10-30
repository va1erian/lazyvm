package java.util.concurrent.atomic;

import java.util.function.LongBinaryOperator;

/** One value: under the VM lock there is no contention to spread out. */
public class LongAccumulator extends Number implements java.io.Serializable {
    private final LongBinaryOperator function;
    private final long identity;
    private final AtomicLong value;

    public LongAccumulator(LongBinaryOperator accumulatorFunction, long identity) {
        function = accumulatorFunction;
        this.identity = identity;
        value = new AtomicLong(identity);
    }

    public void accumulate(long x) {
        value.accumulateAndGet(x, function);
    }

    public long get() {
        return value.get();
    }

    public void reset() {
        value.set(identity);
    }

    public long getThenReset() {
        return value.getAndSet(identity);
    }

    public String toString() {
        return Long.toString(get());
    }

    public long longValue() {
        return get();
    }

    public int intValue() {
        return (int) get();
    }

    public float floatValue() {
        return get();
    }

    public double doubleValue() {
        return get();
    }
}
