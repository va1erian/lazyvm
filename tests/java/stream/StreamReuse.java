package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Linked and consumed state, and the order of argument checks against it. */
public class StreamReuse {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static void r(String label, Runnable r) { t(label, () -> { r.run(); return "ok"; }); }

    public static void main(String[] args) {
        // second use of the same stream, after each kind of operation
        Stream<String> a = Stream.of("a", "b");
        a.map(x -> x);
        r("map then filter", () -> a.filter(x -> true));
        r("map then count", () -> a.count());
        Stream<String> b = Stream.of("a", "b");
        b.count();
        r("count then count", () -> b.count());
        r("count then iterator", () -> b.iterator());
        r("count then spliterator", () -> b.spliterator());
        r("count then parallel", () -> b.parallel());
        r("count then sequential", () -> b.sequential());
        r("count then unordered", () -> b.unordered());
        r("count then onClose", () -> b.onClose(() -> {}));
        r("count then close", () -> b.close());
        t("count then isParallel", () -> b.isParallel());
        Stream<String> c = Stream.of("a");
        c.close();
        r("closed then map", () -> c.map(x -> x));
        r("closed then forEach", () -> c.forEach(x -> {}));
        r("closed then close", () -> c.close());
        Stream<String> d = Stream.of("a");
        d.iterator();
        r("iterator then collect", () -> d.collect(Collectors.toList()));
        Stream<String> e = Stream.of("a");
        e.spliterator();
        r("spliterator then count", () -> e.count());

        // an intermediate operation links the stream even when the pipeline never runs
        Stream<String> f = Stream.of("a");
        Stream<String> g = f.peek(x -> {});
        r("source after peek", () -> f.peek(x -> {}));
        t("fresh pipeline still fine", () -> g.count());

        // each kind
        IntStream i = IntStream.of(1);
        i.sum();
        r("IntStream reuse", () -> i.sum());
        LongStream l = LongStream.of(1);
        l.sum();
        r("LongStream reuse", () -> l.sum());
        DoubleStream dd = DoubleStream.of(1);
        dd.sum();
        r("DoubleStream reuse", () -> dd.sum());
        IntStream i2 = IntStream.of(1);
        i2.boxed();
        r("IntStream boxed then map", () -> i2.map(x -> x));
        r("IntStream boxed then iterator", () -> i2.iterator());

        // reuse check versus null check
        Stream<String> u = Stream.of("a");
        u.count();
        r("used, map(null)", () -> u.map(null));
        r("used, filter(null)", () -> u.filter(null));
        r("used, forEach(null)", () -> u.forEach(null));
        r("used, collect(null)", () -> u.collect((Collector<String, ?, ?>) null));
        r("used, sorted(null)", () -> u.sorted(null));
        r("used, flatMap(null)", () -> u.flatMap(null));
        r("used, limit(-1)", () -> u.limit(-1));
        r("used, skip(-1)", () -> u.skip(-1));
        r("used, onClose(null)", () -> u.onClose(null));
        r("used, reduce(null)", () -> u.reduce(null));
        r("used, toArray(null)", () -> u.toArray(null));
        r("used, max(null)", () -> u.max(null));
        r("used, anyMatch(null)", () -> u.anyMatch(null));
        r("used, concat", () -> Stream.concat(u, Stream.of("b")));
        r("concat null first", () -> Stream.concat(null, Stream.of("b")));
        r("concat null second", () -> Stream.concat(Stream.of("b"), null));
        Stream<String> fresh = Stream.of("a");
        r("fresh, map(null)", () -> fresh.map(null));
        r("fresh after map(null) NPE", () -> fresh.count());
        Stream<String> fresh2 = Stream.of("a");
        r("fresh, limit(-1)", () -> fresh2.limit(-1));
        r("fresh after limit(-1) IAE", () -> fresh2.count());
        Stream<String> fresh3 = Stream.of("a");
        r("fresh, forEach(null)", () -> fresh3.forEach(null));
        r("fresh after forEach(null)", () -> fresh3.count());
        Stream<String> fresh4 = Stream.of("a");
        r("fresh, reduce(null)", () -> fresh4.reduce(null));
        r("fresh after reduce(null)", () -> fresh4.count());
        Stream<String> fresh5 = Stream.of("a");
        r("fresh, collect(null)", () -> fresh5.collect((Collector<String, ?, ?>) null));
        r("fresh after collect(null)", () -> fresh5.count());
        Stream<String> fresh6 = Stream.of("a");
        r("fresh, skip(-2)", () -> fresh6.skip(-2));
        r("fresh, limit(Long.MIN_VALUE)", () -> Stream.of("a").limit(Long.MIN_VALUE));
        t("fresh, sorted(null) ok", () -> Stream.of("b", "a").sorted(null).collect(Collectors.toList()));
        r("fresh, onClose(null)", () -> Stream.of("a").onClose(null));
        r("fresh, toArray(null)", () -> Stream.of("a").toArray(null));
        t("empty iterator hasNext", () -> Stream.empty().iterator().hasNext());
        r("empty iterator next", () -> Stream.empty().iterator().next());

        // argument checks for the primitive streams
        r("IntStream.map(null)", () -> IntStream.of(1).map(null));
        r("IntStream.mapToObj(null)", () -> IntStream.of(1).mapToObj(null));
        r("IntStream.forEach(null)", () -> IntStream.of(1).forEach(null));
        r("IntStream.limit(-1)", () -> IntStream.of(1).limit(-1));
        r("IntStream.skip(-5)", () -> IntStream.of(1).skip(-5));
        t("IntStream.range(5,1).count", () -> IntStream.range(5, 1).count());
        r("IntStream.reduce(null)", () -> IntStream.of(1).reduce(null));
        r("IntStream.reduce(0,null)", () -> IntStream.of(1).reduce(0, null));
        r("IntStream.collect null", () -> IntStream.of(1).collect(null, null, null));
        r("IntStream.concat null", () -> IntStream.concat(null, IntStream.of(1)));
        r("IntStream.iterate null", () -> IntStream.iterate(1, null));
        r("IntStream.generate null", () -> IntStream.generate(null));
        r("LongStream.generate null", () -> LongStream.generate(null));
        r("DoubleStream.iterate null", () -> DoubleStream.iterate(1, null));
        r("Stream.iterate null", () -> Stream.iterate(1, null));
        r("Stream.generate null", () -> Stream.generate(null));
        r("Stream.of null array", () -> Stream.of((Object[]) null));
        t("Stream.of one null", () -> Stream.of((Object) null).count());
        r("IntStream.of null array", () -> IntStream.of((int[]) null));
        r("LongStream.of null array", () -> LongStream.of((long[]) null));
        r("DoubleStream.of null array", () -> DoubleStream.of((double[]) null));
        r("Arrays.stream null", () -> Arrays.stream((String[]) null));
        r("Arrays.stream int null", () -> Arrays.stream((int[]) null));
        r("Arrays.stream range", () -> Arrays.stream(new int[3], 2, 1));
        r("Arrays.stream range hi", () -> Arrays.stream(new int[3], 0, 4));
        r("Arrays.stream range lo", () -> Arrays.stream(new String[3], -1, 2));
        r("Arrays.stream long range", () -> Arrays.stream(new long[3], 1, 5));
        r("Arrays.stream double range", () -> Arrays.stream(new double[3], 4, 5));
        t("Arrays.stream ok empty", () -> Arrays.stream(new int[3], 3, 3).count());
        r("StreamSupport.stream null", () -> StreamSupport.stream((Spliterator<String>) null, false));
        r("StreamSupport.stream supplier null", () -> StreamSupport.stream((Supplier<Spliterator<String>>) null, 0, false));
        t("boxed count", () -> IntStream.empty().boxed().count());
    }
}
