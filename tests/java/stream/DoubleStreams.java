package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Every method of DoubleStream: compensated sums, NaN, infinities and signed zero. */
public class DoubleStreams {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String l(DoubleStream s) { return Arrays.toString(s.toArray()); }
    static final double NAN = Double.NaN, INF = Double.POSITIVE_INFINITY, NINF = Double.NEGATIVE_INFINITY;
    static double[] tenth(int n) { double[] a = new double[n]; Arrays.fill(a, 0.1); return a; }
    static double naive(double... a) { double s = 0; for (double x : a) s += x; return s; }

    public static void main(String[] args) {
        // sum is compensated; the plain loop is not
        t("naive ten tenths", () -> naive(tenth(10)));
        t("sum ten tenths", () -> DoubleStream.of(tenth(10)).sum());
        t("sum thousand tenths", () -> DoubleStream.of(tenth(1000)).sum());
        t("naive thousand tenths", () -> naive(tenth(1000)));
        t("sum cancelling", () -> DoubleStream.of(1e100, 1.0, -1e100).sum());
        t("naive cancelling", () -> naive(1e100, 1.0, -1e100));
        t("sum small after big", () -> DoubleStream.of(1e16, 1.0, 1.0, 1.0, 1.0).sum());
        t("naive small after big", () -> naive(1e16, 1.0, 1.0, 1.0, 1.0));
        t("sum empty", () -> DoubleStream.empty().sum());
        t("sum -0.0", () -> DoubleStream.of(-0.0).sum());
        t("sum -0.0 -0.0", () -> DoubleStream.of(-0.0, -0.0).sum());
        t("sum NaN", () -> DoubleStream.of(1, NAN, 2).sum());
        t("sum +inf", () -> DoubleStream.of(1, INF, 2).sum());
        t("sum -inf", () -> DoubleStream.of(1, NINF, 2).sum());
        t("sum +inf -inf", () -> DoubleStream.of(INF, NINF).sum());
        t("sum inf inf", () -> DoubleStream.of(INF, INF).sum());
        t("sum inf then finite", () -> DoubleStream.of(INF, 1e300, 1e300).sum());
        t("sum overflow", () -> DoubleStream.of(Double.MAX_VALUE, Double.MAX_VALUE).sum());
        t("sum overflow then back", () -> DoubleStream.of(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE).sum());
        t("sum NaN inf", () -> DoubleStream.of(NAN, INF).sum());
        t("average", () -> DoubleStream.of(1, 2, 4).average());
        t("average tenths", () -> DoubleStream.of(tenth(10)).average());
        t("average empty", () -> DoubleStream.empty().average());
        t("average NaN", () -> DoubleStream.of(1, NAN).average());
        t("average inf", () -> DoubleStream.of(1, INF).average());
        t("average inf -inf", () -> DoubleStream.of(INF, NINF).average());

        // min and max
        t("min", () -> DoubleStream.of(3, -1.5, 2).min());
        t("max", () -> DoubleStream.of(3, -1.5, 2).max());
        t("min empty", () -> DoubleStream.empty().min());
        t("max empty", () -> DoubleStream.empty().max());
        t("min NaN", () -> DoubleStream.of(1, NAN, 2).min());
        t("max NaN", () -> DoubleStream.of(1, NAN, 2).max());
        t("max NaN last", () -> DoubleStream.of(1, 2, NAN).max());
        t("min signed zero", () -> DoubleStream.of(0.0, -0.0).min());
        t("max signed zero", () -> DoubleStream.of(-0.0, 0.0).max());
        t("min signed zero reversed", () -> DoubleStream.of(-0.0, 0.0).min());
        t("max signed zero reversed", () -> DoubleStream.of(0.0, -0.0).max());
        t("min inf", () -> DoubleStream.of(INF, NINF, 0).min());
        t("count", () -> DoubleStream.of(3, 4).count());

        // statistics
        t("stats", () -> DoubleStream.of(1.5, 2.5, 4.0).summaryStatistics());
        t("stats empty", () -> DoubleStream.empty().summaryStatistics());
        t("stats NaN", () -> DoubleStream.of(1, NAN).summaryStatistics());
        t("stats inf", () -> DoubleStream.of(1, INF).summaryStatistics());
        t("stats -inf", () -> DoubleStream.of(NINF, 1).summaryStatistics());
        t("stats inf -inf", () -> DoubleStream.of(INF, NINF).summaryStatistics());
        t("stats tenths", () -> DoubleStream.of(tenth(10)).summaryStatistics());
        t("stats tenths sum", () -> DoubleStream.of(tenth(10)).summaryStatistics().getSum());
        t("stats signed zero", () -> DoubleStream.of(-0.0, 0.0).summaryStatistics());
        t("stats small", () -> DoubleStream.of(0.000001).summaryStatistics());
        t("stats large", () -> DoubleStream.of(1e20, 1e21).summaryStatistics());
        t("stats rounding", () -> DoubleStream.of(1.0000005, 2.0000005).summaryStatistics());

        // reductions
        t("reduce op", () -> DoubleStream.of(2, 3, 4).reduce((a, b) -> a * b));
        t("reduce op empty", () -> DoubleStream.empty().reduce((a, b) -> a * b));
        t("reduce identity", () -> DoubleStream.of(2, 3, 4).reduce(100, (a, b) -> a - b));
        t("collect", () -> DoubleStream.of(1, 2).collect(StringBuilder::new, StringBuilder::append, StringBuilder::append));
        t("anyMatch", () -> DoubleStream.of(1, 2).anyMatch(x -> x > 1));
        t("allMatch", () -> DoubleStream.of(1, 2).allMatch(x -> x > 1));
        t("noneMatch", () -> DoubleStream.of(1, 2).noneMatch(x -> x > 5));
        t("anyMatch NaN", () -> DoubleStream.of(NAN).anyMatch(x -> x == x));
        t("anyMatch empty", () -> DoubleStream.empty().anyMatch(x -> true));
        t("allMatch empty", () -> DoubleStream.empty().allMatch(x -> false));
        t("findFirst", () -> DoubleStream.of(9, 8).findFirst());
        t("findAny", () -> DoubleStream.of(9, 8).findAny());
        t("findFirst empty", () -> DoubleStream.empty().findFirst());
        t("findFirst NaN", () -> DoubleStream.of(NAN).findFirst());
        t("forEach", () -> { StringBuilder sb = new StringBuilder(); DoubleStream.of(1, 2.5).forEach(sb::append); return sb; });
        t("forEachOrdered", () -> { StringBuilder sb = new StringBuilder(); DoubleStream.of(1, 2.5).forEachOrdered(sb::append); return sb; });

        // intermediate operations
        t("map", () -> l(DoubleStream.of(1, 2, 3).map(x -> x / 2)));
        t("mapToObj", () -> DoubleStream.of(0.5, 2).mapToObj(Double::toString).collect(Collectors.toList()));
        t("mapToInt truncates", () -> Arrays.toString(DoubleStream.of(1.9, -1.9, NAN, INF, NINF, 1e20).mapToInt(x -> (int) x).toArray()));
        t("mapToLong truncates", () -> Arrays.toString(DoubleStream.of(1.9, -1.9, NAN, INF, NINF).mapToLong(x -> (long) x).toArray()));
        t("boxed", () -> DoubleStream.of(1, 2).boxed().collect(Collectors.toList()));
        t("flatMap", () -> l(DoubleStream.of(1, 2).flatMap(x -> DoubleStream.of(x, x * 10))));
        t("filter", () -> l(DoubleStream.of(1, NAN, 3).filter(x -> x == x)));
        t("distinct", () -> l(DoubleStream.of(3, 1, 3, NAN, NAN, 0.0, -0.0, 0.0, -0.0).distinct()));
        t("distinct boxed", () -> DoubleStream.of(NAN, NAN).boxed().distinct().count());
        t("sorted", () -> l(DoubleStream.of(3, -1, NAN, 2, INF, NINF, 0.0, -0.0).sorted()));
        t("sorted NaN only", () -> l(DoubleStream.of(NAN, NAN).sorted()));
        t("sorted boxed", () -> DoubleStream.of(3, NAN, 0.0, -0.0, -1).boxed().sorted().collect(Collectors.toList()));
        t("peek", () -> { StringBuilder sb = new StringBuilder(); DoubleStream.of(1, 2).peek(sb::append).sum(); return sb; });
        t("limit", () -> l(DoubleStream.iterate(1, x -> x / 2).limit(5)));
        t("skip", () -> l(DoubleStream.of(1, 2, 3, 4).skip(2)));
        t("parallel", () -> DoubleStream.of(1).parallel().isParallel());
        t("sequential", () -> DoubleStream.of(1).parallel().sequential().isParallel());
        t("unordered", () -> l(DoubleStream.of(2, 1).unordered()));

        // factories
        t("empty", () -> l(DoubleStream.empty()));
        t("of one", () -> l(DoubleStream.of(5)));
        t("of none", () -> l(DoubleStream.of()));
        t("of many", () -> l(DoubleStream.of(5, 6, 7)));
        t("generate", () -> { double[] n = {0}; return l(DoubleStream.generate(() -> n[0] += 0.5).limit(3)); });
        t("concat", () -> l(DoubleStream.concat(DoubleStream.of(1, 2), DoubleStream.of(3))));
        t("builder", () -> { DoubleStream.Builder b = DoubleStream.builder(); DoubleStream.Builder r = b.add(1).add(2); b.accept(3); return (r == b) + " " + l(b.build()); });
        t("builder empty", () -> l(DoubleStream.builder().build()));
        t("builder twice", () -> { DoubleStream.Builder b = DoubleStream.builder(); b.build(); return b.build(); });
        t("builder add after", () -> { DoubleStream.Builder b = DoubleStream.builder(); b.build(); return b.add(1); });
        t("builder as DoubleConsumer", () -> { DoubleStream.Builder b = DoubleStream.builder(); DoubleStream.of(4, 5).forEach(b); return l(b.build()); });
        t("array stream", () -> l(Arrays.stream(new double[] {1, 2, 3, 4}, 1, 3)));

        // iterator and spliterator
        t("iterator", () -> { PrimitiveIterator.OfDouble i = DoubleStream.of(5, 6).iterator(); return i.nextDouble() + " " + i.next() + " " + i.hasNext(); });
        t("iterator exhausted", () -> { PrimitiveIterator.OfDouble i = DoubleStream.of(5).iterator(); i.nextDouble(); return i.nextDouble(); });
        t("iterator forEachRemaining DoubleConsumer", () -> { StringBuilder sb = new StringBuilder(); DoubleStream.of(1, 2, 3).iterator().forEachRemaining((DoubleConsumer) sb::append); return sb; });
        t("iterator forEachRemaining Consumer", () -> { StringBuilder sb = new StringBuilder(); DoubleStream.of(1, 2, 3).iterator().forEachRemaining((Consumer<Double>) sb::append); return sb; });
        t("iterator forEachRemaining null", () -> { DoubleStream.of(1).iterator().forEachRemaining((DoubleConsumer) null); return "no"; });
        t("iterator remove", () -> { PrimitiveIterator.OfDouble i = DoubleStream.of(1).iterator(); i.nextDouble(); i.remove(); return "no"; });
        t("spliterator", () -> { Spliterator.OfDouble s = DoubleStream.of(5, 6).spliterator(); StringBuilder sb = new StringBuilder(); while (s.tryAdvance((DoubleConsumer) sb::append)) sb.append(','); return sb; });
        t("spliterator boxed", () -> { Spliterator.OfDouble s = DoubleStream.of(5, 6).spliterator(); StringBuilder sb = new StringBuilder(); s.tryAdvance((Consumer<Double>) sb::append); s.forEachRemaining((Consumer<Double>) sb::append); return sb; });
    }
}
