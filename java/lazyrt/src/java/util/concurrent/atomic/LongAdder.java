package java.util.concurrent.atomic;

/** One counter: under the VM lock there is no contention to spread out. */
public class LongAdder extends Number implements java.io.Serializable {
    private final AtomicLong total = new AtomicLong();

    public LongAdder() {
    }

    public void add(long x) {
        total.getAndAdd(x);
    }

    public void increment() {
        add(1L);
    }

    public void decrement() {
        add(-1L);
    }

    public long sum() {
        return total.get();
    }

    public void reset() {
        total.set(0L);
    }

    public long sumThenReset() {
        return total.getAndSet(0L);
    }

    public String toString() {
        return Long.toString(sum());
    }

    public long longValue() {
        return sum();
    }

    public int intValue() {
        return (int) sum();
    }

    public float floatValue() {
        return sum();
    }

    public double doubleValue() {
        return sum();
    }
}
