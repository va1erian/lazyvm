package stream;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.*;
import java.util.stream.*;

/**
 * Double summation on inputs where the compensation and the final combine step matter, the
 * toString of summary-statistics subclasses, and the argument checks and the calls of Random
 * streams on a subclass.
 */
public class StatsKills {
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r);
    }
    static final double BIG = 18014398509481982.0; // 2^54 - 2
    static double[][] SUMS = {
        {BIG, 3.5}, {3.5, BIG}, {-BIG, -3.5}, {-3.5, -BIG}, {BIG, -3.5}, {-3.5, BIG}, {-BIG, 3.5}, {3.5, -BIG},
        {BIG, 3.5, 3.5}, {BIG, 1.5, 1.5, 1.5}, {BIG, 1, 1, 1}, {BIG, 0.5, 0.5, 0.5, 0.5}, {BIG, 3.5, -3.5}, {BIG, BIG, 3.5},
        {1e16, 1, 1, 1, 1}, {1e16, 0.5, 0.5}, {1e300, 1e300, -1e300}, {0.1, 0.2, 0.3}, {1e-300, 1e300, 1e-300},
        {BIG, 2.5}, {BIG, 3.0}, {BIG, 4.5}, {9007199254740993.0, 1.5}, {9007199254740992.0, 1.5}, {9007199254740992.0, 1.0, 1.0},
    };
    /** The exact bits of a double: Double.toString differs between JDK versions on some values. */
    static String B(double d) { return Long.toHexString(Double.doubleToLongBits(d)); }
    static String B(OptionalDouble d) { return d.isPresent() ? B(d.getAsDouble()) : "empty"; }
    static String stats(double[] a) {
        DoubleSummaryStatistics s = DoubleStream.of(a).summaryStatistics();
        return B(s.getSum()) + " " + B(s.getAverage());
    }

    static class MyInt extends IntSummaryStatistics { }
    static class MyLong extends LongSummaryStatistics { }
    static class MyDouble extends DoubleSummaryStatistics { }
    /** Not nested in the test's name space: the simple name is what prints. */
    static class Odd$Name extends LongSummaryStatistics { }

    /** A Random that logs the public draw methods and replays a script of values. */
    static class Scripted extends Random {
        final StringBuilder log = new StringBuilder();
        final long[] longs; final double[] doubles; int li, di;
        Scripted(long[] longs, double[] doubles) { super(1); this.longs = longs; this.doubles = doubles; }
        @Override public int nextInt() { log.append("nextInt() "); return (int) longs[li++ % longs.length]; }
        @Override public int nextInt(int bound) { log.append("nextInt(" + bound + ") "); return 0; }
        @Override public long nextLong() { log.append("nextLong() "); return longs[li++ % longs.length]; }
        @Override public double nextDouble() { log.append("nextDouble() "); return doubles[di++ % doubles.length]; }
        @Override protected int next(int bits) { log.append("next(" + bits + ") "); return 0; }
    }
    static Object run(Scripted r, Supplier<IntStream> s) { return Arrays.toString(s.get().toArray()) + " | " + r.log.toString().trim(); }

    public static void main(String[] args) {
        // Kahan summation: every way to the sum and the average
        for (double[] a : SUMS) {
            String k = Arrays.toString(a);
            t("sum " + k, () -> B(DoubleStream.of(a).sum()));
            t("average " + k, () -> B(DoubleStream.of(a).average()));
            t("stats " + k, () -> stats(a));
            t("summingDouble " + k, () -> B(DoubleStream.of(a).boxed().collect(Collectors.summingDouble(x -> x))));
            t("averagingDouble " + k, () -> B(DoubleStream.of(a).boxed().collect(Collectors.averagingDouble(x -> x))));
            t("summarizingDouble " + k, () -> { DoubleSummaryStatistics s = DoubleStream.of(a).boxed().collect(Collectors.summarizingDouble(x -> x)); return B(s.getSum()) + " " + B(s.getAverage()); });
            t("reduce " + k, () -> B(DoubleStream.of(a).reduce(0, Double::sum)));
        }
        t("combine halves", () -> {
            DoubleSummaryStatistics x = DoubleStream.of(BIG).summaryStatistics(), y = DoubleStream.of(3.5).summaryStatistics();
            x.combine(y); return B(x.getSum()) + " " + B(x.getAverage());
        });
        t("combine halves swapped", () -> {
            DoubleSummaryStatistics x = DoubleStream.of(3.5).summaryStatistics(), y = DoubleStream.of(BIG).summaryStatistics();
            x.combine(y); return B(x.getSum()) + " " + B(x.getAverage());
        });
        t("combine negative", () -> {
            DoubleSummaryStatistics x = DoubleStream.of(-BIG).summaryStatistics(), y = DoubleStream.of(-3.5).summaryStatistics();
            x.combine(y); return B(x.getSum());
        });
        t("combine with compensation", () -> {
            DoubleSummaryStatistics x = DoubleStream.of(BIG, 3.5).summaryStatistics(), y = DoubleStream.of(3.5, 3.5).summaryStatistics();
            x.combine(y); return B(x.getSum());
        });
        t("combine with compensation 2", () -> {
            DoubleSummaryStatistics x = DoubleStream.of(3.5, 3.5).summaryStatistics(), y = DoubleStream.of(BIG, 3.5).summaryStatistics();
            x.combine(y); return B(x.getSum());
        });
        t("accept one by one", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); s.accept(BIG); s.accept(3.5); s.accept(-3.5); return B(s.getSum()); });
        t("sum after sum", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); s.accept(BIG); s.accept(3.5); return B(s.getSum()) + " " + B(s.getSum()); });

        // statistics subclass names
        t("Int subclass", () -> new MyInt());
        t("Long subclass", () -> new MyLong());
        t("Double subclass", () -> new MyDouble());
        t("Long subclass filled", () -> { MyLong s = new MyLong(); s.accept(5); s.accept(7L); return s; });
        t("Double subclass filled", () -> { MyDouble s = new MyDouble(); s.accept(5); s.accept(7.25); return s; });
        t("Int subclass filled", () -> { MyInt s = new MyInt(); s.accept(5); s.accept(-7); return s; });
        t("Long anonymous", () -> new LongSummaryStatistics() { });
        t("Double anonymous", () -> new DoubleSummaryStatistics() { });
        t("Long local class", () -> { class Local extends LongSummaryStatistics { } return new Local(); });
        t("Double local class", () -> { class Local extends DoubleSummaryStatistics { } return new Local(); });
        t("Long dollar name", () -> new Odd$Name());
        t("Long plain", () -> new LongSummaryStatistics());
        t("Double plain", () -> new DoubleSummaryStatistics());
        t("Int plain", () -> new IntSummaryStatistics());
        t("Long from stream", () -> LongStream.of(1, 2).summaryStatistics());
        t("Double from stream", () -> DoubleStream.of(1, 2).summaryStatistics());
        t("Long subclass via collect", () -> LongStream.of(1, 2).collect(MyLong::new, MyLong::accept, MyLong::combine));
        t("Double subclass via collect", () -> DoubleStream.of(1, 2).collect(MyDouble::new, MyDouble::accept, MyDouble::combine));
        t("Long subclass class name", () -> new MyLong().getClass().getSimpleName());

        // Random streams: arguments are checked at the call, size before bounds
        t("ints size and bounds", () -> new Random(1).ints(-1, 5, 3));
        t("ints size and equal bounds", () -> new Random(1).ints(-1, 5, 5));
        t("ints size ok bounds bad", () -> new Random(1).ints(3, 5, 3));
        t("ints size bad bounds ok", () -> new Random(1).ints(-1, 3, 5));
        t("ints size MIN and bounds", () -> new Random(1).ints(Long.MIN_VALUE, 5, 3));
        t("longs size and bounds", () -> new Random(1).longs(-1, 5, 3));
        t("longs size ok bounds bad", () -> new Random(1).longs(3, 5, 3));
        t("longs size bad bounds ok", () -> new Random(1).longs(-1, 3, 5));
        t("doubles size and bounds", () -> new Random(1).doubles(-1, 5, 3));
        t("doubles size ok bounds bad", () -> new Random(1).doubles(3, 5, 3));
        t("doubles size bad bounds ok", () -> new Random(1).doubles(-1, 3, 5));
        t("doubles size and NaN", () -> new Random(1).doubles(-1, Double.NaN, 3));
        t("doubles size ok and NaN", () -> new Random(1).doubles(3, 0, Double.NaN));
        t("doubles size ok origin NaN", () -> new Random(1).doubles(3, Double.NaN, 1));
        t("doubles infinite origin", () -> new Random(1).doubles(3, Double.NEGATIVE_INFINITY, 1).count());
        t("TLR ints size and bounds", () -> ThreadLocalRandom.current().ints(-1, 5, 3));
        t("TLR longs size and bounds", () -> ThreadLocalRandom.current().longs(-1, 5, 3));
        t("TLR doubles size and bounds", () -> ThreadLocalRandom.current().doubles(-1, 5, 3));
        t("TLR ints bounded no size", () -> ThreadLocalRandom.current().ints(5, 3));
        t("TLR ints size negative alone", () -> ThreadLocalRandom.current().ints(-1));
        t("TLR longs size negative alone", () -> ThreadLocalRandom.current().longs(-1));
        t("TLR doubles size negative alone", () -> ThreadLocalRandom.current().doubles(-1));
        t("subclass ints size and bounds", () -> new Scripted(new long[] {1}, new double[] {0.5}).ints(-1, 5, 3));

        // a bounded stream of a Random subclass: which public methods are called
        long MAX = Integer.MAX_VALUE, MIN = Integer.MIN_VALUE;
        t("ints full range", () -> { Scripted r = new Scripted(new long[] {MAX, 5, MIN, -1}, new double[] {0}); return run(r, () -> r.ints(3, Integer.MIN_VALUE, Integer.MAX_VALUE)); });
        t("ints full range unbounded size", () -> { Scripted r = new Scripted(new long[] {MAX, 5, MIN, -1}, new double[] {0}); return run(r, () -> r.ints(Integer.MIN_VALUE, Integer.MAX_VALUE).limit(3)); });
        t("ints overflowing range", () -> { Scripted r = new Scripted(new long[] {2000000000, -2000000000, -2100000000, 1999999999, 7}, new double[] {0}); return run(r, () -> r.ints(4, -2000000000, 2000000000)); });
        t("ints half range", () -> { Scripted r = new Scripted(new long[] {0}, new double[] {0}); return run(r, () -> r.ints(2, -1000000000, 1000000000)); });
        t("ints range just overflowing", () -> { Scripted r = new Scripted(new long[] {-1, 0, 1, 2}, new double[] {0}); return run(r, () -> r.ints(3, 0, Integer.MAX_VALUE)); });
        t("ints range overflowing by one", () -> { Scripted r = new Scripted(new long[] {-1, 0, 1, 2}, new double[] {0}); return run(r, () -> r.ints(3, -1, Integer.MAX_VALUE)); });
        t("ints range origin MIN", () -> { Scripted r = new Scripted(new long[] {-5, MIN, 0, MAX}, new double[] {0}); return run(r, () -> r.ints(3, Integer.MIN_VALUE, 0)); });
        t("ints range bound MAX", () -> { Scripted r = new Scripted(new long[] {-5, MIN, 0, MAX, 3}, new double[] {0}); return run(r, () -> r.ints(3, 0, Integer.MAX_VALUE)); });
        t("ints small range", () -> { Scripted r = new Scripted(new long[] {3}, new double[] {0}); return run(r, () -> r.ints(2, 5, 10)); });
        t("ints power of two", () -> { Scripted r = new Scripted(new long[] {3}, new double[] {0}); return run(r, () -> r.ints(2, 0, 16)); });
        t("ints one value", () -> { Scripted r = new Scripted(new long[] {3}, new double[] {0}); return run(r, () -> r.ints(2, 5, 6)); });
        t("ints unbounded", () -> { Scripted r = new Scripted(new long[] {3, 4}, new double[] {0}); return run(r, () -> r.ints(3)); });
        t("ints unbounded infinite", () -> { Scripted r = new Scripted(new long[] {3, 4}, new double[] {0}); return run(r, () -> r.ints().limit(3)); });
        t("longs full range", () -> { Scripted r = new Scripted(new long[] {Long.MAX_VALUE, 5, Long.MIN_VALUE, -1}, new double[] {0}); return Arrays.toString(r.longs(3, Long.MIN_VALUE, Long.MAX_VALUE).toArray()) + " | " + r.log.toString().trim(); });
        t("longs overflowing range", () -> { Scripted r = new Scripted(new long[] {Long.MAX_VALUE - 1, -(Long.MAX_VALUE - 1), -5, 7, 9}, new double[] {0}); return Arrays.toString(r.longs(3, -Long.MAX_VALUE + 1, Long.MAX_VALUE - 1).toArray()) + " | " + r.log.toString().trim(); });
        t("longs power of two", () -> { Scripted r = new Scripted(new long[] {13, 14}, new double[] {0}); return Arrays.toString(r.longs(2, 0, 16).toArray()) + " | " + r.log.toString().trim(); });
        t("longs small range", () -> { Scripted r = new Scripted(new long[] {13, 14, 15}, new double[] {0}); return Arrays.toString(r.longs(2, 5, 10).toArray()) + " | " + r.log.toString().trim(); });
        t("longs rejection", () -> { Scripted r = new Scripted(new long[] {Long.MAX_VALUE, Long.MAX_VALUE - 1, 5}, new double[] {0}); return Arrays.toString(r.longs(2, 0, 10).toArray()) + " | " + r.log.toString().trim(); });
        t("longs unbounded", () -> { Scripted r = new Scripted(new long[] {3, 4}, new double[] {0}); return Arrays.toString(r.longs(3).toArray()) + " | " + r.log.toString().trim(); });
        t("doubles bounded", () -> { Scripted r = new Scripted(new long[] {0}, new double[] {0.25, 0.5, 0.999999999999}); return Arrays.toString(r.doubles(3, 10, 20).toArray()) + " | " + r.log.toString().trim(); });
        t("doubles bounded at the top", () -> { Scripted r = new Scripted(new long[] {0}, new double[] {1.0, 1.0}); return Arrays.toString(r.doubles(2, 0.0, 1.0).toArray()) + " | " + r.log.toString().trim(); });
        t("doubles unbounded", () -> { Scripted r = new Scripted(new long[] {0}, new double[] {0.25, 0.5}); return Arrays.toString(r.doubles(2).toArray()) + " | " + r.log.toString().trim(); });
        t("doubles infinite bound", () -> { Scripted r = new Scripted(new long[] {0}, new double[] {0.25, 0.5}); return Arrays.toString(r.doubles(2, 0.0, Double.POSITIVE_INFINITY).toArray()) + " | " + r.log.toString().trim(); });
    }
}
