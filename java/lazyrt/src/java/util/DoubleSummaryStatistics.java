package java.util;

import java.util.function.DoubleConsumer;

public class DoubleSummaryStatistics implements DoubleConsumer {
    private long n;
    // Compensated running sum (docs/design/streams.md P3): `acc` is the sum, `err` the stored correction,
    // `plain` an uncompensated sum that only decides what an infinite result looks like.
    private double acc;
    private double err;
    private double plain;
    private double smallest = Double.POSITIVE_INFINITY;
    private double largest = Double.NEGATIVE_INFINITY;

    public DoubleSummaryStatistics() {
    }

    @Override
    public void accept(double value) {
        n++;
        plain += value;
        add(value);
        smallest = Math.min(smallest, value);
        largest = Math.max(largest, value);
    }

    public void combine(DoubleSummaryStatistics other) {
        double otherAcc = other.acc;
        double otherErr = other.err;
        double otherPlain = other.plain;
        long otherN = other.n;
        n += otherN;
        plain += otherPlain;
        add(otherAcc);
        add(otherErr);
        smallest = Math.min(smallest, other.smallest);
        largest = Math.max(largest, other.largest);
    }

    /** One step of Kahan summation. */
    private void add(double v) {
        double t = v - err;
        double s = acc + t;
        err = (s - acc) - t;
        acc = s;
    }

    public final long getCount() {
        return n;
    }

    public final double getSum() {
        double r = acc + err;
        return Double.isNaN(r) && Double.isInfinite(plain) ? plain : r;
    }

    public final double getMin() {
        return smallest;
    }

    public final double getMax() {
        return largest;
    }

    public final double getAverage() {
        return n > 0 ? getSum() / n : 0.0;
    }

    @Override
    public String toString() {
        return String.format("%s{count=%d, sum=%f, min=%f, average=%f, max=%f}", getClass().getSimpleName(), n,
                getSum(), smallest, getAverage(), largest);
    }
}
