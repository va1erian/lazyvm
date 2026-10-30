package java.util.concurrent.atomic;

/** A {@link DoubleAccumulator} of sums: atomicity lives there. */
public class DoubleAdder extends Number implements java.io.Serializable {
    private final DoubleAccumulator total = new DoubleAccumulator(Double::sum, 0.0);

    public DoubleAdder() {
    }

    public void add(double x) {
        total.accumulate(x);
    }

    public double sum() {
        return total.get();
    }

    public void reset() {
        total.reset();
    }

    public double sumThenReset() {
        return total.getThenReset();
    }

    public String toString() {
        return total.toString();
    }

    public double doubleValue() {
        return sum();
    }

    public float floatValue() {
        return (float) sum();
    }

    public long longValue() {
        return (long) sum();
    }

    public int intValue() {
        return (int) sum();
    }
}
