package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** IntSummaryStatistics, LongSummaryStatistics and DoubleSummaryStatistics used directly. */
public class SummaryStatistics {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static class MyStats extends IntSummaryStatistics { }
    static final double NAN = Double.NaN, INF = Double.POSITIVE_INFINITY, NINF = Double.NEGATIVE_INFINITY;
    static DoubleSummaryStatistics ds(double... v) { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); for (double d : v) s.accept(d); return s; }
    static String parts(DoubleSummaryStatistics s) { return s.getCount() + " " + s.getSum() + " " + s.getMin() + " " + s.getAverage() + " " + s.getMax(); }

    public static void main(String[] args) {
        // Int
        t("int new", () -> new IntSummaryStatistics());
        t("int new parts", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); return s.getCount() + " " + s.getSum() + " " + s.getMin() + " " + s.getAverage() + " " + s.getMax(); });
        t("int accept", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); s.accept(5); s.accept(-3); s.accept(8); return s + " " + s.getAverage(); });
        t("int types", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); s.accept(1); return ((Object) s.getCount()).getClass().getSimpleName() + ((Object) s.getSum()).getClass().getSimpleName() + ((Object) s.getMin()).getClass().getSimpleName() + ((Object) s.getAverage()).getClass().getSimpleName(); });
        t("int sum is long", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); s.accept(Integer.MAX_VALUE); s.accept(Integer.MAX_VALUE); return s.getSum() + " " + s.getAverage(); });
        t("int combine", () -> { IntSummaryStatistics a = new IntSummaryStatistics(), b = new IntSummaryStatistics(); a.accept(1); a.accept(9); b.accept(-4); b.accept(3); a.combine(b); return a + " | " + b; });
        t("int combine empty", () -> { IntSummaryStatistics a = new IntSummaryStatistics(), b = new IntSummaryStatistics(); a.accept(1); a.combine(b); return a; });
        t("int combine into empty", () -> { IntSummaryStatistics a = new IntSummaryStatistics(), b = new IntSummaryStatistics(); b.accept(7); a.combine(b); return a; });
        t("int combine self", () -> { IntSummaryStatistics a = new IntSummaryStatistics(); a.accept(2); a.accept(5); a.combine(a); return a; });
        t("int combine null", () -> { new IntSummaryStatistics().combine(null); return "no"; });
        t("int as IntConsumer", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); IntConsumer c = s; IntStream.of(1, 2, 3).forEach(c); return s; });
        t("int andThen", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); StringBuilder sb = new StringBuilder(); IntStream.of(4, 5).forEach(s.andThen(x -> sb.append(x))); return s.getCount() + " " + sb; });
        t("int toString negative average", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); s.accept(-1); s.accept(-2); return s; });
        t("int toString rounding", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); s.accept(1); s.accept(1); s.accept(2); return s; });
        t("int toString repeating", () -> { IntSummaryStatistics s = new IntSummaryStatistics(); s.accept(1); s.accept(2); s.accept(2); s.accept(2); s.accept(2); s.accept(2); s.accept(2); return s; });
        t("int toString class name", () -> new IntSummaryStatistics().toString().startsWith("IntSummaryStatistics{"));
        t("int hashCode inherited", () -> { IntSummaryStatistics a = new IntSummaryStatistics(); return a.equals(a) + " " + a.equals(new IntSummaryStatistics()); });
        t("int subclass toString", () -> new MyStats());
        t("int anonymous subclass toString", () -> new IntSummaryStatistics() { }.toString());
        t("int subclass accept", () -> { IntSummaryStatistics s = new IntSummaryStatistics() { public void accept(int v) { super.accept(v * 2); } }; IntStream.of(1, 2).forEach(s); return s.getSum(); });
        t("int stream collect", () -> IntStream.of(3, 1, 2).collect(IntSummaryStatistics::new, IntSummaryStatistics::accept, IntSummaryStatistics::combine));
        t("int boxed summarizing", () -> Stream.of(3, 1, 2).collect(Collectors.summarizingInt(x -> x)).getMax());

        // Long
        t("long new", () -> new LongSummaryStatistics());
        t("long accept long", () -> { LongSummaryStatistics s = new LongSummaryStatistics(); s.accept(5L); s.accept(-3L); s.accept(1L << 40); return s; });
        t("long accept int", () -> { LongSummaryStatistics s = new LongSummaryStatistics(); s.accept(7); s.accept(-1); return s; });
        t("long types", () -> { LongSummaryStatistics s = new LongSummaryStatistics(); s.accept(1); return ((Object) s.getCount()).getClass().getSimpleName() + ((Object) s.getSum()).getClass().getSimpleName() + ((Object) s.getMin()).getClass().getSimpleName() + ((Object) s.getAverage()).getClass().getSimpleName(); });
        t("long sum wraps", () -> { LongSummaryStatistics s = new LongSummaryStatistics(); s.accept(Long.MAX_VALUE); s.accept(1); return s.getSum() + " " + (s.getAverage() < 0); });
        t("long combine", () -> { LongSummaryStatistics a = new LongSummaryStatistics(), b = new LongSummaryStatistics(); a.accept(10); b.accept(-20); b.accept(30); a.combine(b); return a; });
        t("long combine null", () -> { new LongSummaryStatistics().combine(null); return "no"; });
        t("long as LongConsumer", () -> { LongSummaryStatistics s = new LongSummaryStatistics(); LongConsumer c = s; LongStream.of(1, 2, 3).forEach(c); return s; });
        t("long as IntConsumer", () -> { LongSummaryStatistics s = new LongSummaryStatistics(); IntConsumer c = s; IntStream.of(1, 2, 3).forEach(c); return s; });
        t("long stream collect", () -> LongStream.of(3, 1, 2).collect(LongSummaryStatistics::new, LongSummaryStatistics::accept, LongSummaryStatistics::combine));

        // Double
        t("double new", () -> new DoubleSummaryStatistics());
        t("double new parts", () -> parts(new DoubleSummaryStatistics()));
        t("double accept", () -> parts(ds(1.5, -2.5, 4)));
        t("double types", () -> { DoubleSummaryStatistics s = ds(1); return ((Object) s.getCount()).getClass().getSimpleName() + ((Object) s.getSum()).getClass().getSimpleName() + ((Object) s.getMin()).getClass().getSimpleName() + ((Object) s.getAverage()).getClass().getSimpleName(); });
        t("double compensated", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); for (int i = 0; i < 10; i++) s.accept(0.1); return s.getSum() + " " + s.getAverage(); });
        t("double compensated many", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); for (int i = 0; i < 1000; i++) s.accept(0.1); return s.getSum(); });
        t("double 1e16 plus ones", () -> parts(ds(1e16, 1, 1, 1, 1)));
        t("double NaN", () -> parts(ds(1, NAN, 2)));
        t("double NaN first", () -> parts(ds(NAN, 1, 2)));
        t("double inf", () -> parts(ds(1, INF, 2)));
        t("double -inf", () -> parts(ds(NINF, 5)));
        t("double inf -inf", () -> parts(ds(INF, NINF)));
        t("double inf inf", () -> parts(ds(INF, INF)));
        t("double inf then big", () -> parts(ds(INF, 1e308, 1e308)));
        t("double overflow", () -> parts(ds(Double.MAX_VALUE, Double.MAX_VALUE)));
        t("double signed zero", () -> parts(ds(0.0, -0.0)));
        t("double signed zero reversed", () -> parts(ds(-0.0, 0.0)));
        t("double only negative zero", () -> parts(ds(-0.0)));
        t("double min value", () -> parts(ds(Double.MIN_VALUE, 0)));
        t("double combine", () -> { DoubleSummaryStatistics a = ds(0.1, 0.1, 0.1, 0.1, 0.1), b = ds(0.1, 0.1, 0.1, 0.1, 0.1); a.combine(b); return parts(a) + " | " + parts(b); });
        t("double combine small", () -> { DoubleSummaryStatistics a = ds(1e16, 1), b = ds(1, 1, 1); a.combine(b); return parts(a); });
        t("double combine compensation carries", () -> { DoubleSummaryStatistics a = ds(0.1, 0.2, 0.3), b = ds(0.4, 0.5, 0.6, 0.7); a.combine(b); return parts(a); });
        t("double combine into empty", () -> { DoubleSummaryStatistics a = new DoubleSummaryStatistics(); a.combine(ds(3, 4)); return parts(a); });
        t("double combine empty", () -> { DoubleSummaryStatistics a = ds(3, 4); a.combine(new DoubleSummaryStatistics()); return parts(a); });
        t("double combine NaN", () -> { DoubleSummaryStatistics a = ds(3, 4); a.combine(ds(NAN)); return parts(a); });
        t("double combine inf", () -> { DoubleSummaryStatistics a = ds(3, 4); a.combine(ds(INF)); return parts(a); });
        t("double combine inf -inf", () -> { DoubleSummaryStatistics a = ds(INF); a.combine(ds(NINF)); return parts(a); });
        t("double combine self", () -> { DoubleSummaryStatistics a = ds(0.5, 1.5); a.combine(a); return parts(a); });
        t("double combine null", () -> { new DoubleSummaryStatistics().combine(null); return "no"; });
        t("double as DoubleConsumer", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); DoubleConsumer c = s; DoubleStream.of(1, 2, 3).forEach(c); return s; });
        t("double as Consumer via andThen", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); DoubleStream.of(1, 2).forEach(s.andThen(x -> {})); return s.getCount(); });
        t("double toString exact", () -> ds(1.0000005).toString());
        t("double toString rounding up", () -> ds(0.0000005).toString());
        t("double toString big", () -> ds(123456789.123456789, 1e15).toString());
        t("double toString negative", () -> ds(-1.25, -2.5).toString());
        t("double toString NaN", () -> ds(NAN).toString());
        t("double toString tiny", () -> ds(Double.MIN_VALUE).toString());
        t("double stream collect", () -> DoubleStream.of(3, 1, 2).collect(DoubleSummaryStatistics::new, DoubleSummaryStatistics::accept, DoubleSummaryStatistics::combine));
        t("double getMax after only NaN", () -> ds(NAN).getMax());
        t("double getMin after -0.0 then 0.0", () -> ds(-0.0, 0.0).getMin());
        t("double getAverage empty", () -> new DoubleSummaryStatistics().getAverage());
        t("double getSum after inf cancels", () -> ds(INF, 1, NINF).getSum());
        t("double sum of many equal", () -> { DoubleSummaryStatistics s = new DoubleSummaryStatistics(); for (int i = 0; i < 100; i++) s.accept(1.1); return s.getSum(); });
        t("naive sum of many equal", () -> { double s = 0; for (int i = 0; i < 100; i++) s += 1.1; return s; });
    }
}
