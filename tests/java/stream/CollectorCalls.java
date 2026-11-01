package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Which methods of a Collector the library calls, how often and in which order; and the
 * order in which partitioningBy and groupingBy create and finish their downstream containers.
 * Also: inner streams of flatMap are made sequential.
 */
public class CollectorCalls {
    static StringBuilder log = new StringBuilder();
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r + (log.length() == 0 ? "" : " | " + log.toString().trim()));
        log.setLength(0);
    }

    /** The container of Logging: prints as id=sum, so identity-finished results are stable. */
    static final class Box {
        final int id; int sum;
        Box(int id) { this.id = id; }
        public String toString() { return "c" + id + "=" + sum; }
    }

    /** A collector whose every method logs, summing into a numbered container. */
    static final class Logging implements Collector<Integer, Box, String> {
        final String tag; final Set<Characteristics> ch; int made;
        Logging(String tag, Characteristics... ch) {
            this.tag = tag;
            Set<Characteristics> s = EnumSet.noneOf(Characteristics.class);
            s.addAll(Arrays.asList(ch));
            this.ch = s;
        }
        public Supplier<Box> supplier() { log.append(tag).append(".supplier() "); return () -> { Box c = new Box(++made); log.append(tag).append(".new").append(c.id).append(' '); return c; }; }
        public BiConsumer<Box, Integer> accumulator() { log.append(tag).append(".accumulator() "); return (c, x) -> { c.sum += x; log.append(tag).append(".acc").append(c.id).append('+').append(x).append(' '); }; }
        public BinaryOperator<Box> combiner() { log.append(tag).append(".combiner() "); return (a, b) -> { log.append(tag).append(".combine "); a.sum += b.sum; return a; }; }
        public Function<Box, String> finisher() { log.append(tag).append(".finisher() "); return c -> { log.append(tag).append(".finish").append(c.id).append(' '); return "c" + c.id + "=" + c.sum; }; }
        public Set<Characteristics> characteristics() { log.append(tag).append(".characteristics() "); return ch; }
    }

    /** Collects with a collector that was built before the call; the marker shows where the factory ended. */
    static Object made(Stream<Integer> s, Collector<Integer, ?, ?> c) { log.append("<made> "); return s.collect(c); }
    static <T> Consumer<T> pk(String tag) { return x -> log.append(tag).append(x).append(' '); }

    public static void main(String[] args) {
        // the order in which collect(Collector) asks the collector for its parts
        t("collect sequential", () -> Stream.of(1, 2).collect(new Logging("L")));
        t("collect empty", () -> Stream.<Integer>empty().collect(new Logging("L")));
        t("collect sequential identity", () -> Stream.of(1, 2).collect(new Logging("L", Collector.Characteristics.IDENTITY_FINISH)));
        t("collect unordered", () -> Stream.of(1, 2).collect(new Logging("L", Collector.Characteristics.UNORDERED)));
        t("collect concurrent", () -> Stream.of(1, 2).collect(new Logging("L", Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED)));
        t("collect twice same collector", () -> { Logging l = new Logging("L"); Stream.of(1).collect(l); log.append("| "); return Stream.of(2).collect(l); });
        t("IntStream boxed collect", () -> IntStream.of(1, 2).boxed().collect(new Logging("L")));
        t("collect after filter", () -> Stream.of(1, 2, 3).filter(x -> x > 1).peek(pk("p")).collect(new Logging("L")));
        t("collect limit", () -> Stream.of(1, 2, 3).limit(2).collect(new Logging("L")));
        t("collect 3 args sequential", () -> Stream.of(1, 2).collect(() -> { log.append("sup "); return new ArrayList<Integer>(); }, (l, x) -> log.append("acc" + x + " "), (a, b) -> log.append("comb ")));
        t("IntStream collect 3 args", () -> IntStream.of(1, 2).collect(() -> { log.append("sup "); return new ArrayList<Integer>(); }, (l, x) -> log.append("acc" + x + " "), (a, b) -> log.append("comb ")));

        // downstream collectors of partitioningBy and groupingBy
        t("partitioningBy", () -> made(Stream.of(1, 2, 3, 4), Collectors.partitioningBy(x -> x % 2 == 0, new Logging("D"))));
        t("partitioningBy empty", () -> made(Stream.<Integer>empty(), Collectors.partitioningBy(x -> x % 2 == 0, new Logging("D"))));
        t("partitioningBy identity", () -> made(Stream.of(1, 2, 3, 4), Collectors.partitioningBy(x -> x % 2 == 0, new Logging("D", Collector.Characteristics.IDENTITY_FINISH))));
        t("partitioningBy one side", () -> made(Stream.of(2, 4), Collectors.partitioningBy(x -> x % 2 == 0, new Logging("D"))));
        t("partitioningBy get", () -> { Map<Boolean, String> m = Stream.of(1, 2).collect(Collectors.partitioningBy(x -> x % 2 == 0, new Logging("D"))); log.append("| "); return m.get(true) + " " + m.get(false); });
        t("partitioningBy default downstream", () -> Stream.of(1, 2, 3, 4).collect(Collectors.partitioningBy(x -> x % 2 == 0)));
        t("partitioningBy counting", () -> Stream.of(1, 2, 3, 4).collect(Collectors.partitioningBy(x -> x > 3, Collectors.counting())));
        t("partitioningBy stateful supplier", () -> {
            int[] n = {0};
            Collector<Integer, List<String>, List<String>> c = Collector.of(
                () -> { List<String> l = new ArrayList<>(); l.add("made" + ++n[0]); return l; },
                (l, x) -> l.add("x" + x), (a, b) -> a, l -> { l.add("finished"); return l; });
            return Stream.of(1, 2, 3, 4).collect(Collectors.partitioningBy(x -> x % 2 == 0, c));
        });
        t("partitioningBy stateful supplier identity", () -> {
            int[] n = {0};
            Collector<Integer, List<String>, List<String>> c = Collector.of(
                () -> { List<String> l = new ArrayList<>(); l.add("made" + ++n[0]); return l; },
                (l, x) -> l.add("x" + x), (a, b) -> a);
            return Stream.of(1, 2, 3, 4).collect(Collectors.partitioningBy(x -> x % 2 == 0, c));
        });
        t("partitioningBy shared container", () -> {
            List<Integer> shared = new ArrayList<>();
            Collector<Integer, List<Integer>, Integer> c = Collector.of(() -> shared, List::add, (a, b) -> a, List::size);
            return Stream.of(1, 2, 3, 4).collect(Collectors.partitioningBy(x -> x % 2 == 0, c));
        });
        t("partitioningBy throwing finisher", () -> {
            Collector<Integer, List<Integer>, Integer> c = Collector.of(ArrayList::new, List::add, (a, b) -> a, l -> { log.append("fin" + l + " "); throw new IllegalStateException("fin"); });
            return Stream.of(1, 2).collect(Collectors.partitioningBy(x -> x % 2 == 0, c));
        });
        t("partitioningBy predicate throws", () -> made(Stream.of(1, 2), Collectors.partitioningBy(x -> { throw new IllegalStateException("pred"); }, new Logging("D"))));
        t("groupingBy", () -> made(Stream.of(1, 2, 3, 4), Collectors.groupingBy(x -> x % 2, TreeMap::new, new Logging("D"))));
        t("groupingBy identity", () -> made(Stream.of(1, 2, 3, 4), Collectors.groupingBy(x -> x % 2, TreeMap::new, new Logging("D", Collector.Characteristics.IDENTITY_FINISH))));
        t("groupingBy empty", () -> made(Stream.<Integer>empty(), Collectors.groupingBy(x -> x % 2, TreeMap::new, new Logging("D"))));
        t("groupingBy stateful supplier", () -> {
            int[] n = {0};
            Collector<Integer, List<String>, List<String>> c = Collector.of(
                () -> { List<String> l = new ArrayList<>(); l.add("made" + ++n[0]); return l; },
                (l, x) -> l.add("x" + x), (a, b) -> a, l -> { l.add("finished"); return l; });
            return Stream.of(3, 2, 1, 4).collect(Collectors.groupingBy(x -> x % 2, TreeMap::new, c));
        });
        t("groupingBy mapFactory order", () -> made(Stream.of(3, 2, 1, 4), Collectors.groupingBy(x -> { log.append("key" + x + " "); return x % 2; }, () -> { log.append("map "); return new TreeMap<>(); }, new Logging("D"))));
        t("groupingBy finisher order", () -> made(Stream.of(5, 4, 3), Collectors.groupingBy(x -> x, LinkedHashMap::new, new Logging("D"))));
        t("groupingByConcurrent", () -> made(Stream.of(1, 2), Collectors.groupingByConcurrent(x -> x % 2, new Logging("D"))));
        t("groupingByConcurrent concurrent downstream", () -> made(Stream.of(1, 2), Collectors.groupingByConcurrent(x -> x % 2, new Logging("D", Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED))));
        t("mapping", () -> made(Stream.of(1, 2), Collectors.mapping(x -> x * 2, new Logging("D"))));
        t("collectingAndThen", () -> made(Stream.of(1, 2), Collectors.collectingAndThen(new Logging("D"), s -> { log.append("then "); return s + "!"; })));
        t("collectingAndThen identity", () -> made(Stream.of(1, 2), Collectors.collectingAndThen(new Logging("D", Collector.Characteristics.IDENTITY_FINISH), s -> { log.append("then "); return s + "!"; })));
        t("toMap", () -> Stream.of(1, 2).collect(Collectors.toMap(x -> x, x -> x, (a, b) -> a, () -> { log.append("map "); return new TreeMap<>(); })));
        t("joining", () -> Stream.of(1, 2).map(String::valueOf).collect(Collectors.joining(",")));
        t("Collector.of getters", () -> {
            Collector<Integer, List<Integer>, List<Integer>> c = Collector.of(ArrayList::new, List::add, (a, b) -> a);
            log.append("a ");
            return c.supplier() == c.supplier();
        });
        t("Collectors.toList getters", () -> { Collector<Integer, ?, List<Integer>> c = Collectors.toList(); return c.supplier() == c.supplier(); });
        t("Collectors.toList sequential collect", () -> Stream.of(1, 2).collect(Collectors.toList()));

        // inner streams of flatMap are made sequential before they are pushed
        t("flatMap inner parallel", () -> {
            Stream<Integer>[] inner = new Stream[1];
            Stream<Integer> in = Stream.of(1, 2).parallel();
            Object r = Stream.of(0).flatMap(x -> { inner[0] = in; return in; }).collect(Collectors.toList());
            return r + " innerParallel=" + inner[0].isParallel();
        });
        t("flatMap inner parallel, outer parallel", () -> {
            Stream<Integer> in = Stream.of(1, 2).parallel();
            Object r = Stream.of(0).parallel().flatMap(x -> in).collect(Collectors.toList());
            return r + " innerParallel=" + in.isParallel();
        });
        t("flatMap inner parallel before run", () -> {
            Stream<Integer> in = Stream.of(1, 2).parallel();
            Stream<Integer> m = Stream.of(0).flatMap(x -> in);
            return "before=" + in.isParallel() + " m.parallel=" + m.isParallel();
        });
        t("flatMap inner derived parallel", () -> {
            Stream<Integer>[] inner = new Stream[1];
            Stream.of(0).flatMap(x -> { inner[0] = Stream.of(1, 2).map(y -> y).parallel(); return inner[0]; }).count();
            return inner[0].isParallel();
        });
        t("IntStream flatMap inner parallel", () -> {
            IntStream in = IntStream.of(1, 2).parallel();
            int s = IntStream.of(0).flatMap(x -> in).sum();
            return s + " innerParallel=" + in.isParallel();
        });
        t("LongStream flatMap inner parallel", () -> {
            LongStream in = LongStream.of(1, 2).parallel();
            long s = LongStream.of(0).flatMap(x -> in).sum();
            return s + " innerParallel=" + in.isParallel();
        });
        t("DoubleStream flatMap inner parallel", () -> {
            DoubleStream in = DoubleStream.of(1, 2).parallel();
            double s = DoubleStream.of(0).flatMap(x -> in).sum();
            return s + " innerParallel=" + in.isParallel();
        });
        t("flatMapToInt inner parallel", () -> {
            IntStream in = IntStream.of(1, 2).parallel();
            int s = Stream.of(0).flatMapToInt(x -> in).sum();
            return s + " innerParallel=" + in.isParallel();
        });
        t("flatMapToLong inner parallel", () -> {
            LongStream in = LongStream.of(1, 2).parallel();
            long s = Stream.of(0).flatMapToLong(x -> in).sum();
            return s + " innerParallel=" + in.isParallel();
        });
        t("flatMapToDouble inner parallel", () -> {
            DoubleStream in = DoubleStream.of(1, 2).parallel();
            double s = Stream.of(0).flatMapToDouble(x -> in).sum();
            return s + " innerParallel=" + in.isParallel();
        });
        t("flatMap inner sequential stays", () -> {
            Stream<Integer> in = Stream.of(1, 2);
            Stream.of(0).flatMap(x -> in).count();
            return in.isParallel();
        });
        t("flatMap iterator inner parallel", () -> {
            Stream<Integer> in = Stream.of(1, 2).parallel();
            Iterator<Integer> it = Stream.of(0).flatMap(x -> in).iterator();
            it.hasNext();
            return in.isParallel();
        });
        t("flatMap outer stays parallel", () -> {
            Stream<Integer> o = Stream.of(0).parallel();
            Stream<Integer> m = o.flatMap(x -> Stream.of(x));
            m.count();
            return o.isParallel() + " " + m.isParallel();
        });
    }
}
