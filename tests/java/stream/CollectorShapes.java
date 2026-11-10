package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Characteristics and null handling of Collectors' factories, and Collector.of. */
public class CollectorShapes {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static Collector<String, ?, ?> o4(Collector.Characteristics... ch) { return Collector.<String, ArrayList<String>>of(ArrayList::new, (l, x) -> l.add(x), (p, q) -> p, ch); }
    static Collector<String, ?, ?> o5(Collector.Characteristics... ch) { return Collector.<String, ArrayList<String>, Integer>of(ArrayList::new, (l, x) -> l.add(x), (p, q) -> p, l -> l.size(), ch); }
    static void c(String label, Collector<?, ?, ?> c) { System.out.println(label + ": " + c.characteristics()); }
    static void nulls(String label, Supplier<Collector<? super String, ?, ?>> make) {
        t(label + " built", () -> { make.get(); return "ok"; });
        t(label + " empty", () -> Stream.<String>empty().collect(make.get()) != null);
        t(label + " one", () -> Stream.of("a").collect(make.get()));
    }

    public static void main(String[] args) {
        // characteristics of the factory collectors
        c("toList", Collectors.toList());
        c("toSet", Collectors.toSet());
        c("toCollection", Collectors.toCollection(ArrayList::new));
        c("joining", Collectors.joining());
        c("joining 3", Collectors.joining(",", "[", "]"));
        c("mapping", Collectors.mapping(String::length, Collectors.toList()));
        c("mapping set", Collectors.mapping(String::length, Collectors.toSet()));
        c("mapping counting", Collectors.mapping(String::length, Collectors.counting()));
        c("collectingAndThen", Collectors.collectingAndThen(Collectors.toList(), l -> l));
        c("collectingAndThen set", Collectors.collectingAndThen(Collectors.toSet(), l -> l));
        c("counting", Collectors.counting());
        c("minBy", Collectors.minBy(Comparator.<String>naturalOrder()));
        c("maxBy", Collectors.maxBy(Comparator.<String>naturalOrder()));
        c("summingInt", Collectors.summingInt(String::length));
        c("summingLong", Collectors.summingLong(String::length));
        c("summingDouble", Collectors.summingDouble(String::length));
        c("averagingInt", Collectors.averagingInt(String::length));
        c("averagingLong", Collectors.averagingLong(String::length));
        c("averagingDouble", Collectors.averagingDouble(String::length));
        c("reducing 1", Collectors.reducing((a, b) -> a));
        c("reducing 2", Collectors.reducing("", (a, b) -> a));
        c("reducing 3", Collectors.reducing(0, String::length, Integer::sum));
        c("groupingBy", Collectors.groupingBy(String::length));
        c("groupingBy counting", Collectors.groupingBy(String::length, Collectors.counting()));
        c("groupingBy toList", Collectors.groupingBy(String::length, Collectors.toList()));
        c("groupingBy 3", Collectors.groupingBy(String::length, TreeMap::new, Collectors.toList()));
        c("groupingBy 3 counting", Collectors.groupingBy(String::length, TreeMap::new, Collectors.counting()));
        c("groupingByConcurrent", Collectors.groupingByConcurrent(String::length));
        c("groupingByConcurrent counting", Collectors.groupingByConcurrent(String::length, Collectors.counting()));
        c("groupingByConcurrent 3", Collectors.groupingByConcurrent(String::length, ConcurrentHashMap::new, Collectors.toList()));
        c("groupingByConcurrent 3 counting", Collectors.groupingByConcurrent(String::length, ConcurrentHashMap::new, Collectors.counting()));
        c("groupingByConcurrent toSet", Collectors.groupingByConcurrent(String::length, Collectors.toSet()));
        c("groupingBy toSet", Collectors.groupingBy(String::length, Collectors.toSet()));
        c("partitioningBy", Collectors.partitioningBy(s -> true));
        c("partitioningBy counting", Collectors.partitioningBy(s -> true, Collectors.counting()));
        c("partitioningBy toList", Collectors.partitioningBy(s -> true, Collectors.toList()));
        c("toMap", Collectors.toMap(s -> s, s -> s));
        c("toMap 3", Collectors.toMap(s -> s, s -> s, (a, b) -> a));
        c("toMap 4", Collectors.toMap(s -> s, s -> s, (a, b) -> a, TreeMap::new));
        c("toConcurrentMap", Collectors.toConcurrentMap(s -> s, s -> s));
        c("toConcurrentMap 3", Collectors.toConcurrentMap(s -> s, s -> s, (a, b) -> a));
        c("toConcurrentMap 4", Collectors.toConcurrentMap(s -> s, s -> s, (a, b) -> a, ConcurrentHashMap::new));
        c("summarizingInt", Collectors.summarizingInt(String::length));
        c("summarizingLong", Collectors.summarizingLong(String::length));
        c("summarizingDouble", Collectors.summarizingDouble(String::length));

        // the Collector.of factories
        c("of 4", o4());
        c("of 4 identity", o4(Collector.Characteristics.IDENTITY_FINISH));
        c("of 4 all", o4(Collector.Characteristics.IDENTITY_FINISH, Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED));
        c("of 4 dup", o4(Collector.Characteristics.UNORDERED, Collector.Characteristics.UNORDERED));
        c("of 5", o5());
        c("of 5 identity", o5(Collector.Characteristics.IDENTITY_FINISH));
        c("of 5 unordered", o5(Collector.Characteristics.UNORDERED));
        t("enum", () -> Arrays.toString(Collector.Characteristics.values()));
        t("enum valueOf", () -> Collector.Characteristics.valueOf("CONCURRENT").ordinal());
        t("characteristics immutable", () -> Collectors.toList().characteristics().add(Collector.Characteristics.UNORDERED));
        t("characteristics immutable remove", () -> Collectors.toList().characteristics().remove(Collector.Characteristics.IDENTITY_FINISH));
        t("characteristics immutable of", () -> o4().characteristics().add(Collector.Characteristics.UNORDERED));
        t("characteristics type", () -> Collectors.toSet().characteristics().contains(Collector.Characteristics.UNORDERED));

        // Collector.of null arguments
        t("of null supplier", () -> Collector.<String, ArrayList<String>>of(null, (l, x) -> l.add(x), (p, q) -> p));
        t("of null accumulator", () -> Collector.<String, ArrayList<String>>of(ArrayList::new, null, (p, q) -> p));
        t("of null combiner", () -> Collector.<String, ArrayList<String>>of(ArrayList::new, (l, x) -> l.add(x), null));
        t("of null finisher", () -> Collector.<String, ArrayList<String>, Object>of(ArrayList::new, (l, x) -> l.add(x), (p, q) -> p, null));
        t("of null characteristics", () -> o4((Collector.Characteristics[]) null));
        t("of null characteristic element", () -> o4((Collector.Characteristics) null));
        t("of 5 null characteristics", () -> o5((Collector.Characteristics[]) null));
        t("of 5 null element", () -> o5((Collector.Characteristics) null));

        // the parts of a collector
        Collector<String, ?, List<String>> tl = Collectors.toList();
        t("toList parts", () -> { @SuppressWarnings("unchecked") Collector<String, Object, Object> cc = (Collector<String, Object, Object>) (Collector<?, ?, ?>) tl;
            Object a = cc.supplier().get(); cc.accumulator().accept(a, "x"); return a.getClass().getName() + a + (cc.finisher().apply(a) == a) + cc.combiner().apply(a, a); });
        t("toSet parts", () -> { @SuppressWarnings("unchecked") Collector<String, Object, Object> cc = (Collector<String, Object, Object>) (Collector<?, ?, ?>) Collectors.toSet();
            Object a = cc.supplier().get(); cc.accumulator().accept(a, "x"); cc.accumulator().accept(a, "x"); return a.getClass().getName() + a + cc.combiner().apply(a, cc.supplier().get()); });
        t("joining parts", () -> { @SuppressWarnings("unchecked") Collector<CharSequence, Object, Object> cc = (Collector<CharSequence, Object, Object>) (Collector<?, ?, ?>) Collectors.joining("-");
            Object a = cc.supplier().get(); cc.accumulator().accept(a, "x"); cc.accumulator().accept(a, "y"); Object b = cc.supplier().get(); cc.accumulator().accept(b, "z");
            return cc.finisher().apply(cc.combiner().apply(a, b)); });
        t("of parts", () -> { Collector<String, ArrayList<String>, Integer> cc = Collector.<String, ArrayList<String>, Integer>of(ArrayList::new, (l, x) -> l.add(x), (p, q) -> { p.addAll(q); return p; }, ArrayList::size);
            ArrayList<String> a = cc.supplier().get(); cc.accumulator().accept(a, "q"); return cc.finisher().apply(cc.combiner().apply(a, a)); });
        t("of 4 finisher is identity", () -> { Collector<String, ArrayList<String>, ArrayList<String>> cc = Collector.<String, ArrayList<String>>of(ArrayList::new, (l, x) -> l.add(x), (p, q) -> p);
            ArrayList<String> a = cc.supplier().get(); return cc.finisher().apply(a) == a; });

        // arguments are not checked when the collector is built, only when they are used
        nulls("toList", Collectors::toList);
        nulls("toCollection null", () -> Collectors.toCollection(null));
        nulls("joining null delimiter", () -> Collectors.joining(null));
        nulls("joining null prefix", () -> Collectors.joining(",", null, "]"));
        nulls("joining null suffix", () -> Collectors.joining(",", "[", null));
        nulls("mapping null mapper", () -> Collectors.mapping(null, Collectors.toList()));
        nulls("mapping null downstream", () -> Collectors.mapping(String::length, null));
        nulls("collectingAndThen null downstream", () -> Collectors.collectingAndThen(null, x -> x));
        nulls("collectingAndThen null finisher", () -> Collectors.collectingAndThen(Collectors.toList(), null));
        nulls("minBy null", () -> Collectors.minBy(null));
        nulls("maxBy null", () -> Collectors.maxBy(null));
        nulls("summingInt null", () -> Collectors.summingInt(null));
        nulls("summingLong null", () -> Collectors.summingLong(null));
        nulls("summingDouble null", () -> Collectors.summingDouble(null));
        nulls("averagingInt null", () -> Collectors.averagingInt(null));
        nulls("averagingDouble null", () -> Collectors.averagingDouble(null));
        nulls("reducing null op", () -> Collectors.reducing(null));
        nulls("reducing 2 null op", () -> Collectors.reducing("", null));
        nulls("reducing 3 null mapper", () -> Collectors.reducing(0, null, Integer::sum));
        nulls("reducing 3 null op", () -> Collectors.reducing(0, String::length, null));
        nulls("groupingBy null classifier", () -> Collectors.groupingBy(null));
        nulls("groupingBy null downstream", () -> Collectors.groupingBy(String::length, (Collector<String, ?, ?>) null));
        nulls("groupingBy null factory", () -> Collectors.groupingBy(String::length, null, Collectors.toList()));
        nulls("groupingByConcurrent null classifier", () -> Collectors.groupingByConcurrent(null));
        nulls("partitioningBy null predicate", () -> Collectors.partitioningBy(null));
        nulls("partitioningBy null downstream", () -> Collectors.partitioningBy(s -> true, null));
        nulls("toMap null key", () -> Collectors.toMap(null, s -> s));
        nulls("toMap null value", () -> Collectors.toMap(s -> s, null));
        nulls("toMap 3 null merge", () -> Collectors.toMap(s -> s, s -> s, null));
        nulls("toMap 4 null factory", () -> Collectors.toMap(s -> s, s -> s, (a, b) -> a, null));
        nulls("toConcurrentMap null key", () -> Collectors.toConcurrentMap(null, s -> s));
        nulls("summarizingInt null", () -> Collectors.summarizingInt(null));
        nulls("summarizingLong null", () -> Collectors.summarizingLong(null));
        nulls("summarizingDouble null", () -> Collectors.summarizingDouble(null));
        t("collect null collector", () -> Stream.of("a").collect((Collector<String, ?, ?>) null));
        t("collect null collector on empty", () -> Stream.<String>empty().collect((Collector<String, ?, ?>) null));
    }
}
