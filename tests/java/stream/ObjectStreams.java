package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Every method of Stream, with its edge cases. */
public class ObjectStreams {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String a(Object[] x) { return x.getClass().getSimpleName() + Arrays.toString(x); }
    static class Pt { final int k; final String n; Pt(int k, String n) { this.k = k; this.n = n; } public String toString() { return n + k; } }

    public static void main(String[] args) {
        List<String> words = Arrays.asList("pear", "fig", "apple", "kiwi", "date", "plum");
        t("filter", () -> words.stream().filter(w -> w.length() == 4).collect(Collectors.toList()));
        t("map", () -> words.stream().map(String::length).collect(Collectors.toList()));
        t("map null results", () -> Stream.of(1, 2).map(x -> (String) null).collect(Collectors.toList()));
        t("mapToInt", () -> words.stream().mapToInt(String::length).sum());
        t("mapToLong", () -> words.stream().mapToLong(w -> (long) w.length() << 33).sum());
        t("mapToDouble", () -> words.stream().mapToDouble(w -> w.length() / 2.0).sum());
        t("distinct", () -> Stream.of("a", "b", "a", "c", "b").distinct().collect(Collectors.toList()));
        t("distinct uses equals", () -> Stream.of(new String("x"), new String("x"), "x").distinct().count());
        t("distinct unordered keeps one", () -> Stream.of(1, 1, 2, 2).unordered().distinct().count());
        t("sorted", () -> words.stream().sorted().collect(Collectors.toList()));
        t("sorted cmp", () -> words.stream().sorted(Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder())).collect(Collectors.toList()));
        t("sorted stable", () -> Stream.of(new Pt(2, "a"), new Pt(1, "b"), new Pt(2, "c"), new Pt(1, "d"), new Pt(2, "e"), new Pt(0, "f"))
                .sorted(Comparator.comparingInt(p -> p.k)).collect(Collectors.toList()));
        t("sorted stable large", () -> IntStream.range(0, 200).boxed().map(i -> new Pt(i % 3, "n" + i)).sorted(Comparator.comparingInt(p -> p.k))
                .map(p -> p.n).skip(60).limit(8).collect(Collectors.toList()));
        t("sorted not comparable", () -> Stream.of(new Object(), new Object()).sorted().count());
        t("sorted not comparable one", () -> Stream.of(new Pt(1, "a")).sorted().count());
        t("sorted not comparable two", () -> Stream.of(new Pt(1, "a"), new Pt(2, "b")).sorted().count());
        t("sorted null", () -> Stream.of("a", null, "b").sorted().count());
        t("sorted mixed", () -> Stream.of("a", 1).sorted().count());
        t("sorted nullsFirst", () -> Stream.of("b", null, "a").sorted(Comparator.nullsFirst(Comparator.naturalOrder())).collect(Collectors.toList()));
        t("sorted empty", () -> Stream.<String>empty().sorted().collect(Collectors.toList()));
        t("sorted single no compare", () -> Stream.of(new Object()).sorted().count());
        t("peek", () -> Stream.of(1, 2).peek(x -> {}).collect(Collectors.toList()));
        t("limit", () -> words.stream().limit(2).collect(Collectors.toList()));
        t("limit past end", () -> words.stream().limit(100).count());
        t("limit max", () -> words.stream().limit(Long.MAX_VALUE).count());
        t("limit 0", () -> words.stream().limit(0).count());
        t("skip", () -> words.stream().skip(4).collect(Collectors.toList()));
        t("skip 0", () -> words.stream().skip(0).count());
        t("skip past end", () -> words.stream().skip(100).count());
        t("skip max", () -> words.stream().skip(Long.MAX_VALUE).count());
        t("skip limit max", () -> words.stream().skip(2).limit(Long.MAX_VALUE).count());
        t("skip skip", () -> words.stream().skip(1).skip(2).collect(Collectors.toList()));
        t("limit limit", () -> words.stream().limit(4).limit(2).collect(Collectors.toList()));
        t("skip limit window", () -> IntStream.range(0, 100).boxed().skip(10).limit(5).collect(Collectors.toList()));
        t("limit skip window", () -> IntStream.range(0, 100).boxed().limit(15).skip(10).collect(Collectors.toList()));
        t("skip max limit", () -> IntStream.range(0, 100).boxed().skip(Long.MAX_VALUE).limit(Long.MAX_VALUE).count());
        t("skip unsized", () -> Stream.iterate(0, x -> x + 1).skip(5).limit(3).collect(Collectors.toList()));
        t("forEach", () -> { StringBuilder sb = new StringBuilder(); words.stream().forEach(sb::append); return sb; });
        t("forEachOrdered", () -> { StringBuilder sb = new StringBuilder(); words.stream().unordered().forEachOrdered(sb::append); return sb; });
        t("toArray", () -> a(words.stream().toArray()));
        t("toArray typed", () -> a(words.stream().toArray(String[]::new)));
        t("toArray filter", () -> a(words.stream().filter(w -> w.length() == 4).toArray(String[]::new)));
        t("toArray empty", () -> a(Stream.<String>empty().toArray(String[]::new)));
        t("toArray CharSequence", () -> a(words.stream().toArray(CharSequence[]::new)));
        t("toArray bad type", () -> a(words.stream().toArray(Integer[]::new)));
        t("toArray bad type filter", () -> a(words.stream().filter(w -> true).toArray(Integer[]::new)));
        t("toArray bad type empty", () -> a(Stream.<String>empty().toArray(Integer[]::new)));
        t("toArray size asked", () -> { int[] n = {-1}; words.stream().toArray(k -> { n[0] = k; return new String[k]; }); return n[0]; });
        t("toArray size asked filter", () -> { int[] n = {-1}; words.stream().filter(w -> true).toArray(k -> { n[0] = k; return new String[k]; }); return n[0]; });
        t("toArray size asked skip", () -> { int[] n = {-1}; words.stream().skip(1).toArray(k -> { n[0] = k; return new String[k]; }); return n[0]; });
        t("toArray size asked limit", () -> { int[] n = {-1}; words.stream().limit(2).toArray(k -> { n[0] = k; return new String[k]; }); return n[0]; });
        t("toArray size asked sorted", () -> { int[] n = {-1}; words.stream().sorted().toArray(k -> { n[0] = k; return new String[k]; }); return n[0]; });
        t("toArray size asked empty", () -> { int[] n = {-1}; Stream.<String>empty().toArray(k -> { n[0] = k; return new String[k]; }); return n[0]; });
        t("toArray size asked unsized", () -> { int[] n = {-1}; Stream.iterate(1, x -> x + 1).limit(3).toArray(k -> { n[0] = k; return new Integer[k]; }); return n[0]; });
        t("toArray generator too small", () -> a(words.stream().toArray(k -> new String[k - 1])));
        t("toArray generator too small unsized", () -> a(words.stream().filter(w -> true).toArray(k -> new String[k - 1])));
        t("toArray generator too large", () -> a(words.stream().toArray(k -> new String[k + 1])));
        t("toArray generator too large unsized", () -> a(words.stream().filter(w -> true).toArray(k -> new String[k + 1])));
        t("toArray generator null", () -> a(words.stream().toArray(k -> null)));
        t("toArray generator null unsized", () -> a(words.stream().filter(w -> true).toArray(k -> null)));
        t("toArray nulls", () -> a(Stream.of("a", null).toArray()));
        t("reduce", () -> words.stream().reduce((x, y) -> x + y));
        t("reduce empty", () -> Stream.<String>empty().reduce((x, y) -> x + y));
        t("reduce single", () -> Stream.of("a").reduce((x, y) -> { throw new AssertionError(); }));
        t("reduce null result", () -> Stream.of("a", "b").reduce((x, y) -> null));
        t("reduce null result single", () -> Stream.of((String) null).reduce((x, y) -> x));
        t("reduce identity", () -> words.stream().reduce("", (x, y) -> x + y.charAt(0)));
        t("reduce identity empty", () -> Stream.<String>empty().reduce("id", (x, y) -> x + y));
        t("reduce identity null", () -> Stream.<String>empty().reduce(null, (x, y) -> x + y));
        t("reduce 3", () -> words.stream().reduce(0, (n, w) -> n + w.length(), Integer::sum));
        t("reduce 3 sequential ignores combiner", () -> words.stream().reduce(0, (n, w) -> n + w.length(), (x, y) -> { throw new AssertionError(); }));
        t("reduce 3 null combiner", () -> words.stream().reduce(0, (n, w) -> n + w.length(), null));
        t("collect 3", () -> words.stream().collect(StringBuilder::new, StringBuilder::append, StringBuilder::append));
        t("collect 3 null combiner", () -> words.stream().collect(StringBuilder::new, StringBuilder::append, null));
        t("collect 3 null acc", () -> words.stream().collect(StringBuilder::new, null, StringBuilder::append));
        t("collect 3 null supplier", () -> words.stream().<StringBuilder>collect(null, StringBuilder::append, StringBuilder::append));
        t("min", () -> words.stream().min(Comparator.naturalOrder()));
        t("max", () -> words.stream().max(Comparator.comparing(String::length)));
        t("min empty", () -> Stream.<String>empty().min(Comparator.naturalOrder()));
        t("max null element", () -> Stream.of("a", null).max(Comparator.nullsFirst(Comparator.naturalOrder())));
        t("max null result", () -> Stream.of((String) null).max(Comparator.naturalOrder()));
        t("count", () -> words.stream().count());
        t("count empty", () -> Stream.empty().count());
        t("anyMatch", () -> words.stream().anyMatch(w -> w.startsWith("k")));
        t("allMatch", () -> words.stream().allMatch(w -> w.length() > 2));
        t("noneMatch", () -> words.stream().noneMatch(w -> w.isEmpty()));
        t("findFirst", () -> words.stream().findFirst());
        t("findFirst filter", () -> words.stream().filter(w -> w.length() > 4).findFirst());
        t("findFirst none", () -> Stream.empty().findFirst());
        t("findFirst null", () -> Stream.of((String) null, "a").findFirst());
        t("findAny null", () -> Stream.of((String) null, "a").findAny());
        t("findAny", () -> words.stream().findAny());
        t("iterator", () -> { StringBuilder sb = new StringBuilder(); for (Iterator<String> i = words.stream().iterator(); i.hasNext(); ) sb.append(i.next()).append(','); return sb; });
        t("iterator remove", () -> { Iterator<String> i = words.stream().iterator(); i.next(); i.remove(); return "no"; });
        t("iterator exhausted", () -> { Iterator<String> i = Stream.of("a").iterator(); i.next(); return i.next(); });
        t("iterator forEachRemaining", () -> { StringBuilder sb = new StringBuilder(); Iterator<String> i = words.stream().iterator(); i.next(); i.forEachRemaining(sb::append); return sb; });
        t("iterator next without hasNext", () -> { Iterator<String> i = words.stream().filter(w -> w.length() == 3).iterator(); return i.next() + i.hasNext(); });
        t("iterator infinite", () -> { Iterator<Integer> i = Stream.iterate(1, x -> x * 2).iterator(); i.next(); i.next(); return i.next(); });
        t("isParallel", () -> words.stream().isParallel());
        t("parallel", () -> words.stream().parallel().isParallel());
        t("parallel sequential", () -> words.stream().parallel().sequential().isParallel());
        t("parallel in the middle", () -> words.stream().parallel().map(w -> w).isParallel());
        t("sequential in the middle", () -> words.stream().parallel().map(w -> w).sequential().isParallel());
        t("parallel late", () -> words.stream().map(w -> w).parallel().filter(w -> true).isParallel());
        t("parallelStream", () -> words.parallelStream().isParallel());
        t("parallelStream result", () -> words.parallelStream().map(String::length).collect(Collectors.toList()));
        t("parallel reduce", () -> IntStream.rangeClosed(1, 1000).boxed().parallel().reduce(0, Integer::sum));
        t("parallel forEachOrdered", () -> { StringBuilder sb = new StringBuilder(); IntStream.range(0, 20).boxed().parallel().forEachOrdered(sb::append); return sb; });
        t("parallel sorted", () -> Stream.of(3, 1, 2).parallel().sorted().collect(Collectors.toList()));
        t("parallel collect", () -> IntStream.range(0, 10).boxed().parallel().collect(Collectors.groupingBy(i -> i % 2)));
        t("parallel toArray", () -> a(IntStream.range(0, 5).boxed().parallel().toArray()));
        t("unordered", () -> words.stream().unordered().count());
        t("unordered then sorted", () -> words.stream().unordered().sorted().findFirst());
        t("unordered distinct", () -> Stream.of(3, 3, 1).unordered().distinct().sorted().collect(Collectors.toList()));

        // static factories
        t("empty", () -> Stream.empty().count());
        t("of one", () -> Stream.of("x").collect(Collectors.toList()));
        t("of none", () -> Stream.of().count());
        t("of array spread", () -> Stream.of("a", "b", "c").count());
        t("of array", () -> Stream.of(new String[] {"a", "b"}).count());
        t("of int array is one", () -> Stream.of(new int[] {1, 2}).count());
        t("of two arrays", () -> Stream.of(new String[] {"a", "b"}, new String[] {"c"}).count());
        t("of cast Object", () -> Stream.of((Object) new String[] {"a", "b"}).count());
        t("of null spread", () -> Stream.of("a", null, "b").collect(Collectors.toList()));
        t("iterate", () -> Stream.iterate(1, x -> x * 3).limit(5).collect(Collectors.toList()));
        t("iterate null seed", () -> Stream.iterate((String) null, x -> "n").limit(2).collect(Collectors.toList()));
        t("iterate fn null result", () -> Stream.iterate(1, x -> null).limit(3).collect(Collectors.toList()));
        t("iterate string", () -> Stream.iterate("a", x -> x + "b").limit(3).collect(Collectors.toList()));
        t("generate", () -> { int[] n = {0}; return Stream.generate(() -> ++n[0]).limit(4).collect(Collectors.toList()); });
        t("generate unordered", () -> Stream.generate(() -> "z").limit(3).collect(Collectors.joining()));
        t("concat", () -> Stream.concat(Stream.of(1, 2), Stream.of(3)).collect(Collectors.toList()));
        t("concat empty", () -> Stream.concat(Stream.empty(), Stream.empty()).count());
        t("concat covariant", () -> Stream.<Object>concat(Stream.of("a"), Stream.of(1)).collect(Collectors.toList()));
        t("concat nested", () -> Stream.concat(Stream.concat(Stream.of(1), Stream.of(2)), Stream.concat(Stream.of(3), Stream.of(4))).collect(Collectors.toList()));
        t("concat same stream", () -> { Stream<Integer> s = Stream.of(1); return Stream.concat(s, s).count(); });
        t("concat deep", () -> { Stream<Integer> s = Stream.of(0); for (int i = 1; i < 300; i++) s = Stream.concat(s, Stream.of(i)); return s.mapToInt(x -> x).sum(); });
        t("concat sorted unsized", () -> Stream.concat(Stream.of(3, 1), Stream.iterate(2, x -> x).limit(1)).sorted().collect(Collectors.toList()));
        t("concat limit infinite", () -> Stream.concat(Stream.of(1), Stream.iterate(2, x -> x + 1)).limit(4).collect(Collectors.toList()));

        // builder
        t("builder", () -> { Stream.Builder<String> b = Stream.builder(); Stream.Builder<String> r = b.add("x").add("y"); b.accept("z"); return (r == b) + " " + b.build().collect(Collectors.toList()); });
        t("builder empty", () -> Stream.<String>builder().build().count());
        t("builder null element", () -> Stream.<String>builder().add(null).add("a").build().collect(Collectors.toList()));
        t("builder build twice", () -> { Stream.Builder<String> b = Stream.builder(); b.add("x"); b.build(); return b.build(); });
        t("builder add after build", () -> { Stream.Builder<String> b = Stream.builder(); b.build(); return b.add("x"); });
        t("builder accept after build", () -> { Stream.Builder<String> b = Stream.builder(); b.build(); b.accept("x"); return "no"; });
        t("builder is a Consumer", () -> { Stream.Builder<Integer> b = Stream.builder(); Stream.of(1, 2, 3).forEach(b); return b.build().count(); });
        t("builder many", () -> { Stream.Builder<Integer> b = Stream.builder(); for (int i = 0; i < 1000; i++) b.add(i); return b.build().mapToInt(x -> x).sum(); });
        t("builder one", () -> { Stream.Builder<Integer> b = Stream.builder(); b.add(7); return b.build().findFirst(); });
        t("builder stream is single use", () -> { Stream.Builder<Integer> b = Stream.builder(); b.add(7); Stream<Integer> s = b.build(); s.count(); return s.count(); });
    }
}
