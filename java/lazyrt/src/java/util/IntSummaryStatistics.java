package java.util;

import java.util.function.IntConsumer;

public class IntSummaryStatistics implements IntConsumer {
    private long n;
    private long total;
    private int smallest = Integer.MAX_VALUE;
    private int largest = Integer.MIN_VALUE;

    public IntSummaryStatistics() {
    }

    @Override
    public void accept(int value) {
        n++;
        total += value;
        smallest = Math.min(smallest, value);
        largest = Math.max(largest, value);
    }

    public void combine(IntSummaryStatistics other) {
        n += other.n;
        total += other.total;
        smallest = Math.min(smallest, other.smallest);
        largest = Math.max(largest, other.largest);
    }

    public final long getCount() {
        return n;
    }

    public final long getSum() {
        return total;
    }

    public final int getMin() {
        return smallest;
    }

    public final int getMax() {
        return largest;
    }

    public final double getAverage() {
        return n > 0 ? (double) total / n : 0.0;
    }

    @Override
    public String toString() {
        return String.format("%s{count=%d, sum=%d, min=%d, average=%f, max=%d}", getClass().getSimpleName(), n,
                total, smallest, getAverage(), largest);
    }
}
