package java.util;

import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

public class LongSummaryStatistics implements LongConsumer, IntConsumer {
    private long n;
    private long total;
    private long smallest = Long.MAX_VALUE;
    private long largest = Long.MIN_VALUE;

    public LongSummaryStatistics() {
    }

    /** An int widens to a long, so an int stream can feed these statistics. */
    @Override
    public void accept(int value) {
        accept((long) value);
    }

    @Override
    public void accept(long value) {
        n++;
        total += value;
        smallest = Math.min(smallest, value);
        largest = Math.max(largest, value);
    }

    public void combine(LongSummaryStatistics other) {
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

    public final long getMin() {
        return smallest;
    }

    public final long getMax() {
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
