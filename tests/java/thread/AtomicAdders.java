package thread;

import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** The adders and accumulators on one thread, including their function's arguments. */
public class AtomicAdders {
    static void p(Object o) {
        System.out.println(o);
    }

    static void numbers(Number n) {
        p(n + " " + n.intValue() + " " + n.longValue() + " " + Float.floatToRawIntBits(n.floatValue()) + " "
                + Double.doubleToRawLongBits(n.doubleValue()) + " " + n.shortValue() + " " + n.byteValue());
    }

    static void longAdder() {
        LongAdder a = new LongAdder();
        p(a + " " + a.sum());
        a.increment();
        a.increment();
        a.decrement();
        a.add(1L << 40);
        numbers(a);
        p(a.sumThenReset() + " " + a.sum());
        a.add(Long.MAX_VALUE);
        a.increment();
        numbers(a);
        a.reset();
        numbers(a);
        p(a.equals(new LongAdder()) + " " + a.equals(a));
    }

    static void doubleAdder() {
        DoubleAdder a = new DoubleAdder();
        p(a + " " + a.sum());
        a.add(1.5);
        a.add(-0.25);
        numbers(a);
        p(a.sumThenReset() + " " + a.sum());
        a.add(-0.0);
        p(a.sum());
        a.add(Double.NaN);
        numbers(a);
        a.reset();
        a.add(Double.POSITIVE_INFINITY);
        numbers(a);
        a.add(Double.NEGATIVE_INFINITY);
        p(a.sum());
        a.reset();
        a.add(1e300);
        a.add(1e300);
        numbers(a);
        a.reset();
        a.add(3e10);
        numbers(a);
        p(a.equals(new DoubleAdder()) + " " + a.equals(a));
    }

    static void longAccumulator() {
        LongAccumulator a = new LongAccumulator((x, y) -> x * 10 + y, 1);
        p(a + " " + a.get());
        a.accumulate(2);
        a.accumulate(3);
        numbers(a);
        p(a.getThenReset() + " " + a.get());
        a.accumulate(5);
        a.reset();
        numbers(a);
        LongAccumulator max = new LongAccumulator(Long::max, Long.MIN_VALUE);
        p(max);
        max.accumulate(-5);
        max.accumulate(-9);
        p(max.get() + " " + max.getThenReset() + " " + max.get());
        int[] calls = new int[1];
        LongAccumulator counted = new LongAccumulator((x, y) -> {
            calls[0]++;
            p("called " + x + " " + y);
            return x + y;
        }, 3);
        p("calls " + calls[0]);
        counted.accumulate(4);
        counted.accumulate(0);
        p("calls " + calls[0] + " " + counted);
        LongAccumulator nothing = new LongAccumulator(null, 8);
        p(nothing + " " + nothing.get());
        try {
            nothing.accumulate(1);
            p("no exception");
        } catch (NullPointerException e) {
            p("npe " + e.getMessage() + " " + nothing);
        }
        LongAccumulator throwing = new LongAccumulator((x, y) -> {
            throw new IllegalStateException("from function");
        }, 2);
        try {
            throwing.accumulate(1);
        } catch (IllegalStateException e) {
            p(e.getMessage() + " " + throwing);
        }
        p(a.equals(new LongAccumulator((x, y) -> x, 1)) + " " + a.equals(a));
    }

    static void doubleAccumulator() {
        DoubleAccumulator a = new DoubleAccumulator((x, y) -> x * 10 + y, 1);
        p(a + " " + a.get());
        a.accumulate(2);
        a.accumulate(0.5);
        numbers(a);
        p(a.getThenReset() + " " + a.get());
        a.accumulate(5);
        a.reset();
        numbers(a);
        DoubleAccumulator negativeZero = new DoubleAccumulator((x, y) -> x * y, -0.0);
        p(negativeZero);
        negativeZero.accumulate(1.0);
        p(negativeZero.get() + " " + negativeZero.getThenReset() + " " + negativeZero);
        DoubleAccumulator nan = new DoubleAccumulator(Math::max, Double.NaN);
        numbers(nan);
        nan.accumulate(1);
        p(nan);
        nan.reset();
        p(nan);
        p(Double.doubleToRawLongBits(new DoubleAccumulator(Math::max, Double.longBitsToDouble(0x7ff8000000000123L)).get()));
        DoubleAccumulator counted = new DoubleAccumulator((x, y) -> {
            p("called " + x + " " + y);
            return x + y;
        }, 3);
        counted.accumulate(4);
        p(counted);
        DoubleAccumulator nothing = new DoubleAccumulator(null, 8);
        p(nothing + " " + nothing.get());
        try {
            nothing.accumulate(1);
            p("no exception");
        } catch (NullPointerException e) {
            p("npe " + e.getMessage() + " " + nothing);
        }
        p(a.equals(new DoubleAccumulator((x, y) -> x, 1)) + " " + a.equals(a));
    }

    public static void main(String[] args) {
        longAdder();
        doubleAdder();
        longAccumulator();
        doubleAccumulator();
    }
}
