package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** When, how often and in which order the functions of a pipeline run. */
public class StreamLaziness {
    static StringBuilder log = new StringBuilder();
    static <T> Consumer<T> pk(String tag) { return x -> log.append(tag).append(x).append(' '); }
    static <T> Predicate<T> ft(String tag, Predicate<T> p) { return x -> { log.append(tag).append(x).append(' '); return p.test(x); }; }
    static <T, R> Function<T, R> mp(String tag, Function<T, R> f) { return x -> { log.append(tag).append(x).append(' '); return f.apply(x); }; }
    static UnaryOperator<Integer> un(String tag, UnaryOperator<Integer> f) { return x -> { log.append(tag).append(x).append(' '); return f.apply(x); }; }
    static void show(String label, Object result) {
        System.out.println(label + ": " + log.toString().trim() + " => " + result);
        log.setLength(0);
    }

    public static void main(String[] args) {
        List<Integer> list = Arrays.asList(1, 2, 3, 4);

        // nothing runs without a terminal operation
        Stream<Integer> idle = list.stream().peek(pk("p")).map(mp("m", x -> x + 1)).filter(ft("f", x -> true));
        show("no terminal", "idle");
        // one element goes through the whole pipeline before the next starts
        show("interleave", list.stream().peek(pk("p")).filter(ft("f", x -> x % 2 == 0)).map(mp("m", x -> x * 10)).peek(pk("q")).collect(Collectors.toList()));
        show("parallel flag", list.stream().peek(pk("a")).map(mp("b", x -> x)).sequential().parallel().isParallel());

        // count() in Java 8 runs the pipeline even when the size is known
        show("count sized", list.stream().peek(pk("p")).count());
        show("count map sized", list.stream().map(mp("m", x -> x)).count());
        show("count of()", Stream.of(1, 2).peek(pk("p")).count());
        show("count array", Arrays.stream(new int[] {5, 6}).peek(x -> pk("i").accept(x)).count());
        show("count range", IntStream.range(0, 3).peek(x -> pk("i").accept(x)).count());
        show("count limit sized", list.stream().peek(pk("p")).limit(2).count());
        show("count skip sized", list.stream().peek(pk("p")).skip(3).count());
        show("count sorted", list.stream().peek(pk("p")).sorted().count());

        // short-circuiting terminals
        show("findFirst", list.stream().peek(pk("p")).findFirst());
        show("findAny", list.stream().peek(pk("p")).findAny());
        show("anyMatch", list.stream().peek(pk("p")).anyMatch(x -> x == 2));
        show("allMatch", list.stream().peek(pk("p")).allMatch(x -> x < 3));
        show("noneMatch", list.stream().peek(pk("p")).noneMatch(x -> x == 3));
        show("anyMatch empty", Stream.<Integer>empty().anyMatch(x -> true));
        show("allMatch empty", Stream.<Integer>empty().allMatch(x -> false));
        show("noneMatch empty", Stream.<Integer>empty().noneMatch(x -> true));
        show("limit", list.stream().peek(pk("p")).limit(2).peek(pk("q")).collect(Collectors.toList()));
        show("limit 0", list.stream().peek(pk("p")).limit(0).collect(Collectors.toList()));
        show("skip", list.stream().peek(pk("p")).skip(2).peek(pk("q")).collect(Collectors.toList()));
        show("skip all", list.stream().peek(pk("p")).skip(10).collect(Collectors.toList()));
        show("skip limit", list.stream().peek(pk("p")).skip(1).limit(2).peek(pk("q")).collect(Collectors.toList()));
        show("limit skip", list.stream().peek(pk("p")).limit(3).skip(1).peek(pk("q")).collect(Collectors.toList()));
        show("filter limit", list.stream().peek(pk("p")).filter(ft("f", x -> x > 1)).limit(2).collect(Collectors.toList()));

        // infinite sources and how many times their functions are called
        int[] gen = {0};
        show("generate limit", Stream.generate(() -> { gen[0]++; log.append("g "); return gen[0]; }).limit(3).collect(Collectors.toList()));
        show("generate skip limit", Stream.generate(() -> { gen[0]++; log.append("g "); return gen[0]; }).skip(2).limit(1).collect(Collectors.toList()));
        show("iterate limit 1", Stream.iterate(1, un("f", x -> x + 1)).limit(1).collect(Collectors.toList()));
        show("iterate limit 3", Stream.iterate(1, un("f", x -> x + 1)).limit(3).collect(Collectors.toList()));
        show("iterate findFirst", Stream.iterate(1, un("f", x -> x + 1)).peek(pk("p")).findFirst());
        show("iterate anyMatch", Stream.iterate(1, un("f", x -> x * 2)).peek(pk("p")).anyMatch(x -> x > 5));
        show("int iterate", IntStream.iterate(1, x -> { log.append("f "); return x + 1; }).limit(3).sum());
        show("int generate", IntStream.generate(() -> { log.append("g "); return 7; }).limit(2).sum());
        show("long iterate", LongStream.iterate(1L, x -> { log.append("f "); return x * 3; }).limit(3).boxed().collect(Collectors.toList()));
        show("double iterate", DoubleStream.iterate(1.5, x -> { log.append("f "); return x * 2; }).limit(3).boxed().collect(Collectors.toList()));
        show("iterate peek limit", Stream.iterate(1, x -> x + 1).peek(pk("p")).limit(2).map(mp("m", x -> x)).collect(Collectors.toList()));

        // sorted is a barrier, distinct is not
        show("sorted barrier", Stream.of(3, 1, 2).peek(pk("p")).sorted().peek(pk("q")).collect(Collectors.toList()));
        show("sorted limit", Stream.of(3, 1, 2).peek(pk("p")).sorted().limit(1).peek(pk("q")).collect(Collectors.toList()));
        show("sorted findFirst", Stream.of(3, 1, 2).peek(pk("p")).sorted().peek(pk("q")).findFirst());
        show("sorted cmp", Stream.of(3, 1, 2).sorted(Comparator.reverseOrder()).peek(pk("q")).collect(Collectors.toList()));
        show("distinct", Stream.of(1, 2, 1, 3, 2).peek(pk("p")).distinct().peek(pk("q")).collect(Collectors.toList()));
        show("distinct limit", Stream.of(1, 2, 1, 3, 2).peek(pk("p")).distinct().limit(2).peek(pk("q")).collect(Collectors.toList()));
        show("distinct nulls", Stream.of("a", null, "a", null).distinct().collect(Collectors.toList()));
        show("sorted on iterator", list.stream().peek(pk("p")).sorted(Comparator.reverseOrder()).iterator().next());

        // flatMap
        show("flatMap", Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x * 10).peek(pk("i"))).peek(pk("q")).collect(Collectors.toList()));
        show("flatMap findFirst", Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x * 10, x * 100).peek(pk("i"))).findFirst());
        show("flatMap limit", Stream.of(1, 2, 3).peek(pk("o")).flatMap(x -> Stream.of(x, x * 10, x * 100).peek(pk("i"))).limit(2).collect(Collectors.toList()));
        show("flatMap anyMatch", Stream.of(1, 2).flatMap(x -> Stream.of(x, x * 10).peek(pk("i"))).anyMatch(x -> x == 1));
        show("flatMap null", Stream.of(1, 2, 3).flatMap(x -> x == 2 ? null : Stream.of(x)).collect(Collectors.toList()));
        show("flatMap empty", Stream.of(1, 2).flatMap(x -> Stream.<Integer>empty()).collect(Collectors.toList()));
        show("flatMap closes", Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(() -> log.append("close" + x + " "))).peek(pk("q")).collect(Collectors.toList()));
        show("flatMap closes findFirst", Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 10).onClose(() -> log.append("close" + x + " "))).peek(pk("q")).findFirst());
        show("flatMap src limit", Stream.of(1, 2).flatMap(x -> src("s" + x, 4)).limit(2).collect(Collectors.toList()));
        show("flatMap src findFirst", Stream.of(1, 2).flatMap(x -> src("s" + x, 4)).findFirst());
        show("flatMap src anyMatch", Stream.of(1, 2).flatMap(x -> src("s" + x, 4)).anyMatch(x -> x == 1));
        show("flatMap src sorted", Stream.of(1, 2).flatMap(x -> src("s" + x, 2)).sorted().findFirst());
        show("flatMap iterator", iter(Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x * 10, x * 100).peek(pk("i"))).iterator()));
        show("flatMap list", Stream.of(Arrays.asList(1, 2), Arrays.<Integer>asList(), Arrays.asList(3)).flatMap(List::stream).collect(Collectors.toList()));
        show("flatMapToInt", Stream.of("ab", "c").flatMapToInt(String::chars).boxed().collect(Collectors.toList()));
        show("flatMapToLong", Stream.of(1, 2).flatMapToLong(x -> LongStream.of(x, x * 2L)).boxed().collect(Collectors.toList()));
        show("flatMapToDouble", Stream.of(1, 2).flatMapToDouble(x -> DoubleStream.of(x / 2.0)).boxed().collect(Collectors.toList()));
        show("IntStream flatMap", IntStream.of(1, 2).flatMap(x -> IntStream.of(x, -x)).boxed().collect(Collectors.toList()));
        show("IntStream flatMap null", IntStream.of(1, 2).flatMap(x -> x == 1 ? null : IntStream.of(x)).boxed().collect(Collectors.toList()));
        show("LongStream flatMap", LongStream.of(1, 2).flatMap(x -> LongStream.of(x, -x)).boxed().collect(Collectors.toList()));
        show("DoubleStream flatMap", DoubleStream.of(1, 2).flatMap(x -> DoubleStream.of(x, -x)).boxed().collect(Collectors.toList()));
        show("nested flatMap", Stream.of(1, 2).flatMap(a -> Stream.of(10, 20).flatMap(b -> Stream.of(a + b, a * b))).collect(Collectors.toList()));

        // iterator and spliterator pull one element at a time from a simple pipeline
        Iterator<Integer> it = list.stream().peek(pk("p")).map(mp("m", x -> x * 2)).iterator();
        show("iterator created", "nothing");
        it.hasNext(); show("hasNext", "true");
        it.hasNext(); show("hasNext again", "true");
        it.next(); show("next", "-");
        it.next(); show("next 2", "-");
        Spliterator<Integer> sp = list.stream().peek(pk("p")).spliterator();
        show("spliterator created", "nothing");
        sp.tryAdvance(x -> log.append("got" + x + " "));
        show("tryAdvance", "-");
        sp.forEachRemaining(x -> log.append("got" + x + " "));
        show("forEachRemaining", "-");
        System.out.println(sp.tryAdvance(x -> {}));

        // concat
        show("concat", Stream.concat(Stream.of(1, 2).peek(pk("a")), Stream.of(3, 4).peek(pk("b"))).limit(3).collect(Collectors.toList()));
        show("concat findFirst", Stream.concat(Stream.of(1, 2).peek(pk("a")), Stream.of(3, 4).peek(pk("b"))).findFirst());
        show("concat close", Stream.concat(Stream.of(1).onClose(() -> log.append("c1 ")), Stream.of(2).onClose(() -> log.append("c2 "))).peek(pk("p")).collect(Collectors.toList()));
        show("concat count", Stream.concat(Stream.of(1, 2).peek(pk("a")), Stream.of(3).peek(pk("b"))).count());

        // forEachOrdered, reduce, collect, min/max call order
        show("forEachOrdered", list.stream().peek(pk("p")).sequential().collect(StringBuilder::new, (sb, x) -> sb.append(x), StringBuilder::append));
        show("reduce", list.stream().reduce((a, b) -> { log.append("(" + a + "," + b + ") "); return a + b; }));
        show("reduce identity", list.stream().reduce(100, (a, b) -> { log.append("(" + a + "," + b + ") "); return a + b; }));
        show("reduce 3", list.stream().reduce(0, (a, b) -> { log.append("(" + a + "," + b + ") "); return a + b; }, (a, b) -> { log.append("combine "); return a + b; }));
        show("max", list.stream().max((a, b) -> { log.append("(" + a + "," + b + ") "); return a - b; }));
        show("min", list.stream().min((a, b) -> { log.append("(" + a + "," + b + ") "); return a - b; }));
        show("max ties", Stream.of("bb", "aa", "cc").max((a, b) -> { log.append("(" + a + "," + b + ") "); return 0; }));
        show("min ties", Stream.of("bb", "aa", "cc").min((a, b) -> { log.append("(" + a + "," + b + ") "); return 0; }));
        show("sorted ties", Stream.of("bb", "aa", "cc", "d").sorted((a, b) -> a.length() - b.length()).collect(Collectors.toList()));
        show("collect 3", Stream.of(1, 2, 3).collect(() -> { log.append("sup "); return new ArrayList<Integer>(); }, (l, x) -> { log.append("acc" + x + " "); l.add(x); }, (l, m) -> log.append("comb ")));

        // an exception from a function leaves the stream used
        Stream<Integer> boom = list.stream().peek(pk("p")).map(x -> { if (x == 3) throw new IllegalStateException("boom"); return x; });
        try { boom.count(); } catch (IllegalStateException e) { System.out.println("caught " + e.getMessage()); }
        show("after boom", "-");
        try { boom.count(); } catch (IllegalStateException e) { System.out.println("again " + e.getMessage()); }
        // the same pipeline function sees every element of every use, but a supplier of streams is the way to rerun
        Supplier<Stream<Integer>> sup = () -> list.stream().filter(x -> x > 2);
        System.out.println(sup.get().count() + " " + sup.get().findFirst());
    }

    /** A stream over 1..n whose iterator logs every step. */
    static Stream<Integer> src(String tag, int n) {
        Iterator<Integer> it = new Iterator<Integer>() {
            int i = 0;
            public boolean hasNext() { log.append(tag).append("h "); return i < n; }
            public Integer next() { log.append(tag).append("n "); return ++i; }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }

    static String iter(Iterator<Integer> it) {
        StringBuilder sb = new StringBuilder();
        while (it.hasNext()) { sb.append("h "); Integer x = it.next(); sb.append("n").append(x).append(' '); log.append("| "); }
        return sb.toString().trim();
    }
}
