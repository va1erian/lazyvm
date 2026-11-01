package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Whole pipelines of the kind real programs write. Only deterministic orders are printed. */
public class Pipelines {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static class Emp {
        final String name, dept; final int age; final double pay;
        Emp(String name, String dept, int age, double pay) { this.name = name; this.dept = dept; this.age = age; this.pay = pay; }
        String name() { return name; } String dept() { return dept; } int age() { return age; } double pay() { return pay; }
        public String toString() { return name; }
    }
    static final List<Emp> EMPS = Arrays.asList(
        new Emp("ann", "eng", 31, 120.5), new Emp("bob", "ops", 45, 80.25), new Emp("cy", "eng", 28, 99.0),
        new Emp("di", "hr", 39, 70.0), new Emp("ed", "ops", 51, 85.5), new Emp("flo", "eng", 35, 130.0), new Emp("gus", "hr", 23, 55.75));
    static final String TEXT = "the quick brown fox jumps over the lazy dog the end";

    public static void main(String[] args) {
        // grouping and partitioning
        t("by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.toList())));
        t("count by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.counting())));
        t("avg pay by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.averagingDouble(Emp::pay))));
        t("sum pay by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.summingDouble(Emp::pay))));
        t("oldest by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.maxBy(Comparator.comparingInt(Emp::age)))));
        t("names by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.mapping(Emp::name, Collectors.joining("/")))));
        t("stats by dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.summarizingInt(Emp::age))));
        t("dept then decade", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.groupingBy(e -> e.age / 10 * 10, TreeMap::new, Collectors.mapping(Emp::name, Collectors.toList())))));
        t("partition by age", () -> EMPS.stream().collect(Collectors.partitioningBy(e -> e.age >= 35, Collectors.mapping(Emp::name, Collectors.toList()))));
        t("partition then count", () -> EMPS.stream().collect(Collectors.partitioningBy(e -> e.pay > 90, Collectors.counting())));
        t("partition by dept then avg", () -> EMPS.stream().collect(Collectors.partitioningBy(e -> e.dept.equals("eng"), Collectors.averagingInt(Emp::age))));
        t("top paid per dept", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, TreeMap::new, Collectors.collectingAndThen(Collectors.maxBy(Comparator.comparingDouble(Emp::pay)), o -> o.get().name))));
        t("dept names sorted by size", () -> EMPS.stream().collect(Collectors.groupingBy(Emp::dept, Collectors.counting())).entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).collect(Collectors.toList()));
        t("sorted by two keys", () -> EMPS.stream().sorted(Comparator.comparing(Emp::dept).thenComparing(Emp::age, Comparator.reverseOrder())).map(Emp::name).collect(Collectors.toList()));
        t("sorted by pay desc limit", () -> EMPS.stream().sorted(Comparator.comparingDouble(Emp::pay).reversed()).limit(3).map(Emp::name).collect(Collectors.joining(",")));
        t("total pay", () -> EMPS.stream().mapToDouble(Emp::pay).sum());
        t("pay stats", () -> EMPS.stream().mapToDouble(Emp::pay).summaryStatistics());
        t("average age", () -> EMPS.stream().mapToInt(Emp::age).average().getAsDouble());
        t("youngest", () -> EMPS.stream().min(Comparator.comparingInt(Emp::age)).get());
        t("any over 50", () -> EMPS.stream().anyMatch(e -> e.age > 50));
        t("all paid", () -> EMPS.stream().allMatch(e -> e.pay > 50));
        t("name lengths", () -> EMPS.stream().map(Emp::name).mapToInt(String::length).boxed().collect(Collectors.toList()));
        t("name index map", () -> IntStream.range(0, EMPS.size()).boxed().collect(Collectors.toMap(i -> EMPS.get(i).name, i -> i, (a, b) -> a, TreeMap::new)));

        // text
        t("words", () -> Arrays.stream(TEXT.split(" ")).collect(Collectors.toList()));
        t("word counts", () -> Arrays.stream(TEXT.split(" ")).collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting())));
        t("distinct sorted words", () -> Arrays.stream(TEXT.split(" ")).distinct().sorted().collect(Collectors.joining(" ")));
        t("longest word", () -> Arrays.stream(TEXT.split(" ")).max(Comparator.comparingInt(String::length)).get());
        t("longest word ties first", () -> Arrays.stream(TEXT.split(" ")).max(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).get());
        t("words by length", () -> Arrays.stream(TEXT.split(" ")).distinct().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.toCollection(TreeSet::new))));
        t("capitalized", () -> Arrays.stream(TEXT.split(" ")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" ")));
        t("letters", () -> TEXT.chars().filter(Character::isLetter).mapToObj(c -> (char) c).collect(Collectors.groupingBy(c -> c, TreeMap::new, Collectors.counting())).entrySet().stream().filter(e -> e.getValue() >= 4).map(Object::toString).collect(Collectors.joining(",")));
        t("reverse words", () -> { List<String> w = Arrays.asList(TEXT.split(" ")); return IntStream.range(0, w.size()).mapToObj(i -> w.get(w.size() - 1 - i)).collect(Collectors.joining(" ")); });
        t("char frequency top", () -> TEXT.replace(" ", "").chars().boxed().collect(Collectors.groupingBy(c -> c, Collectors.counting())).entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(3).map(e -> (char) e.getKey().intValue() + "" + e.getValue()).collect(Collectors.joining(",")));
        t("csv", () -> Stream.of("a,b,c", "d,e", "f").map(l -> l.split(",")).flatMap(Arrays::stream).collect(Collectors.joining("")));
        t("palindromes", () -> Stream.of("level", "stream", "noon", "java").filter(s -> new StringBuilder(s).reverse().toString().equals(s)).collect(Collectors.toList()));
        t("anagram groups", () -> Stream.of("listen", "silent", "enlist", "google", "gogole", "cat", "act").collect(Collectors.groupingBy(s -> { char[] c = s.toCharArray(); Arrays.sort(c); return new String(c); }, TreeMap::new, Collectors.toList())).values());

        // numbers
        t("squares", () -> IntStream.rangeClosed(1, 6).map(i -> i * i).boxed().collect(Collectors.toList()));
        t("sum of squares", () -> IntStream.rangeClosed(1, 100).map(i -> i * i).sum());
        t("factorial long", () -> LongStream.rangeClosed(1, 20).reduce(1, (a, b) -> a * b));
        t("factorial overflow", () -> LongStream.rangeClosed(1, 21).reduce(1, (a, b) -> a * b));
        t("primes", () -> IntStream.rangeClosed(2, 50).filter(n -> IntStream.rangeClosed(2, (int) Math.sqrt(n)).noneMatch(d -> n % d == 0)).boxed().collect(Collectors.toList()));
        t("fibonacci", () -> Stream.iterate(new long[] {0, 1}, f -> new long[] {f[1], f[0] + f[1]}).limit(12).map(f -> f[0]).collect(Collectors.toList()));
        t("fibonacci big", () -> Stream.iterate(new long[] {0, 1}, f -> new long[] {f[1], f[0] + f[1]}).skip(90).findFirst().get()[0]);
        t("powers of two", () -> Stream.iterate(1L, x -> x * 2).limit(10).map(String::valueOf).collect(Collectors.joining(",")));
        t("collatz", () -> Stream.iterate(27L, n -> n % 2 == 0 ? n / 2 : 3 * n + 1).limit(12).collect(Collectors.toList()));
        t("collatz until one", () -> Stream.iterate(6, n -> n % 2 == 0 ? n / 2 : 3 * n + 1).limit(10).filter(n -> n == 1).findFirst());
        t("multiplication table", () -> IntStream.rangeClosed(1, 3).mapToObj(i -> IntStream.rangeClosed(1, 4).mapToObj(j -> String.valueOf(i * j)).collect(Collectors.joining(" "))).collect(Collectors.joining("; ")));
        t("transpose", () -> { int[][] m = {{1, 2, 3}, {4, 5, 6}}; return IntStream.range(0, 3).mapToObj(c -> Arrays.stream(m).mapToInt(r -> r[c]).boxed().collect(Collectors.toList())).collect(Collectors.toList()); });
        t("pairs", () -> IntStream.rangeClosed(1, 3).boxed().flatMap(a -> IntStream.rangeClosed(a, 3).mapToObj(b -> a + "-" + b)).collect(Collectors.toList()));
        t("pythagorean", () -> IntStream.rangeClosed(1, 20).boxed().flatMap(a -> IntStream.rangeClosed(a, 20).boxed().flatMap(b -> IntStream.rangeClosed(b, 20).filter(c -> a * a + b * b == c * c).mapToObj(c -> a + "," + b + "," + c))).collect(Collectors.toList()));
        t("cartesian", () -> Stream.of("a", "b").flatMap(x -> Stream.of(1, 2).map(y -> x + y)).collect(Collectors.toList()));
        t("nested flatMap depth 3", () -> Stream.of(1, 2).flatMap(a -> Stream.of(a * 10, a * 100).flatMap(b -> Stream.of(b, -b))).collect(Collectors.toList()));
        t("flatMap of lists", () -> Stream.of(Arrays.asList(1, 2), Collections.<Integer>emptyList(), Arrays.asList(3)).flatMap(List::stream).map(x -> x * x).collect(Collectors.toList()));
        t("flatMap optional", () -> Stream.of(Optional.of(1), Optional.<Integer>empty(), Optional.of(3)).flatMap(o -> o.isPresent() ? Stream.of(o.get()) : Stream.<Integer>empty()).collect(Collectors.toList()));
        t("boxing round trip int", () -> IntStream.of(3, 1, 2).boxed().sorted(Comparator.reverseOrder()).mapToInt(Integer::intValue).boxed().collect(Collectors.toList()));
        t("boxing round trip long", () -> LongStream.of(3, 1, 2).boxed().mapToLong(Long::longValue).sum());
        t("boxing round trip double", () -> DoubleStream.of(1.5, 2.5).boxed().mapToDouble(Double::doubleValue).sum());
        t("boxing mixed", () -> IntStream.range(0, 4).mapToLong(i -> i * 1000000000L).mapToDouble(l -> l / 1e9).boxed().collect(Collectors.toList()));
        t("int to double average", () -> IntStream.of(1, 2).asDoubleStream().map(Math::sqrt).sum());
        t("map to object and back", () -> IntStream.range(0, 5).mapToObj(Integer::toString).mapToInt(Integer::parseInt).sum());
        t("empty int stats", () -> IntStream.empty().boxed().collect(Collectors.summarizingInt(x -> x)));
        t("empty pipeline", () -> Stream.empty().map(x -> x).filter(x -> true).sorted().distinct().skip(1).limit(1).collect(Collectors.toList()));
        t("empty reductions", () -> Stream.<Integer>empty().reduce(Integer::sum) + " " + Stream.<Integer>empty().reduce(0, Integer::sum) + " " + Stream.<Integer>empty().max(Integer::compare) + " " + IntStream.empty().max() + " " + IntStream.empty().average());
        t("single element pipeline", () -> Stream.of(5).map(x -> x + 1).filter(x -> x > 3).sorted().distinct().skip(0).limit(5).reduce(0, Integer::sum));
        t("generate with state", () -> { int[] n = {0}; return Stream.generate(() -> n[0]++).limit(5).collect(Collectors.toList()); });
        t("iterate while-like", () -> Stream.iterate(1, x -> x * 2).filter(x -> x > 100).findFirst().get());
        t("iterate with limit and sum", () -> Stream.iterate(1, x -> x + 1).limit(100).mapToInt(Integer::intValue).sum());
        t("zip", () -> { List<String> a = Arrays.asList("x", "y", "z"); List<Integer> b = Arrays.asList(1, 2, 3); return IntStream.range(0, a.size()).mapToObj(i -> a.get(i) + b.get(i)).collect(Collectors.toList()); });
        t("sliding windows", () -> { int[] d = {1, 2, 3, 4, 5}; return IntStream.rangeClosed(0, d.length - 3).mapToObj(i -> Arrays.stream(d, i, i + 3).sum()).collect(Collectors.toList()); });
        t("running total", () -> { int[] acc = {0}; return IntStream.rangeClosed(1, 5).map(i -> acc[0] += i).boxed().collect(Collectors.toList()); });
        t("chunks", () -> { List<Integer> l = IntStream.range(0, 7).boxed().collect(Collectors.toList()); return IntStream.range(0, (l.size() + 2) / 3).mapToObj(i -> l.subList(i * 3, Math.min(l.size(), i * 3 + 3))).collect(Collectors.toList()); });
        t("histogram", () -> IntStream.of(1, 5, 2, 8, 5, 5, 1).boxed().collect(Collectors.groupingBy(i -> i / 3, TreeMap::new, Collectors.counting())));
        t("median", () -> { double[] d = DoubleStream.of(5, 1, 4, 2, 3).sorted().toArray(); return d[d.length / 2]; });
        t("reduce to string", () -> Stream.of("a", "b", "c").reduce("", (x, y) -> y + x));
        t("reduce to map", () -> Stream.of("a", "bb", "cc").reduce(new TreeMap<Integer, Integer>(), (m, s) -> { m.merge(s.length(), 1, Integer::sum); return m; }, (m1, m2) -> m1));
        t("collect to array list and shuffle-free sort", () -> Stream.of(5, 3, 9, 1).sorted().collect(Collectors.toCollection(ArrayList::new)));
        t("toArray sorted strings", () -> Arrays.toString(Stream.of("pear", "apple", "fig").sorted().toArray(String[]::new)));
        t("sorted stability by key", () -> Stream.of("bb", "a", "cc", "d", "ee", "f").sorted(Comparator.comparingInt(String::length)).collect(Collectors.toList()));
        t("sorted stability large", () -> IntStream.range(0, 100).boxed().sorted(Comparator.comparingInt(i -> i % 4)).skip(20).limit(10).collect(Collectors.toList()));
        t("sorted reverse stability", () -> Stream.of("bb", "a", "cc", "d", "ee", "f").sorted(Comparator.comparingInt(String::length).reversed()).collect(Collectors.toList()));
        t("max by with ties keeps first or last", () -> Stream.of("bb", "a", "cc").collect(Collectors.maxBy(Comparator.comparingInt(String::length))).get());
        t("limit then sorted", () -> Stream.of(5, 3, 9, 1).limit(3).sorted().collect(Collectors.toList()));
        t("sorted then limit", () -> Stream.of(5, 3, 9, 1).sorted().limit(3).collect(Collectors.toList()));
        t("distinct preserves first", () -> Stream.of("b", "a", "b", "c", "a").distinct().collect(Collectors.toList()));
        t("distinct custom equals", () -> Stream.of(new int[] {1}, new int[] {1}).distinct().count());
        t("iterator for loop", () -> { StringBuilder sb = new StringBuilder(); for (Iterator<Integer> i = Stream.of(1, 2, 3).map(x -> x * 2).iterator(); i.hasNext(); ) sb.append(i.next()); return sb; });
        t("Iterable from stream", () -> { Iterable<Integer> it = Stream.of(1, 2, 3)::iterator; StringBuilder sb = new StringBuilder(); for (int x : it) sb.append(x); return sb; });
        t("stream of map entries", () -> { Map<String, Integer> m = new TreeMap<>(); m.put("b", 2); m.put("a", 1); return m.entrySet().stream().map(e -> e.getKey() + e.getValue()).collect(Collectors.joining(",")); });
        t("map values transform", () -> { Map<String, Integer> m = new TreeMap<>(); m.put("b", 2); m.put("a", 1); return m.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() * 10, (x, y) -> x, TreeMap::new)); });
        t("optional chain", () -> Stream.of("a", "bb").filter(s -> s.length() > 1).findFirst().map(String::toUpperCase).orElse("none"));
        t("optional empty chain", () -> Stream.of("a", "bb").filter(s -> s.length() > 5).findFirst().map(String::toUpperCase).orElse("none"));
        t("optional int chain", () -> IntStream.of(3, 8).filter(x -> x > 5).findFirst().orElse(-1));
        t("optional int none", () -> IntStream.of(3, 8).filter(x -> x > 9).findFirst().orElse(-1));
        t("optional double", () -> DoubleStream.of(3.5).max().orElseThrow(IllegalStateException::new));
        t("optional long", () -> LongStream.empty().max().orElseGet(() -> 77L));
        t("big pipeline", () -> IntStream.range(0, 100000).filter(i -> i % 3 == 0).mapToLong(i -> (long) i * i).sum());
        t("big boxed pipeline", () -> IntStream.range(0, 20000).boxed().map(i -> i * 2).filter(i -> i % 3 == 0).collect(Collectors.counting()));
        t("big sort", () -> IntStream.range(0, 5000).map(i -> (i * 7919) % 5003).boxed().sorted().skip(2500).findFirst());
        t("big distinct", () -> IntStream.range(0, 10000).map(i -> i % 137).boxed().distinct().count());
        t("big group", () -> IntStream.range(0, 10000).boxed().collect(Collectors.groupingBy(i -> i % 7, TreeMap::new, Collectors.counting())));
        t("big flatMap", () -> IntStream.range(0, 300).boxed().flatMap(i -> IntStream.range(0, i % 5).boxed()).count());
        t("big concat", () -> Stream.concat(IntStream.range(0, 5000).boxed(), IntStream.range(5000, 10000).boxed()).mapToInt(i -> i).sum());
        t("big iterate limit", () -> Stream.iterate(1, x -> x + 1).limit(20000).mapToLong(x -> x).sum());
        t("big string join", () -> IntStream.range(0, 2000).mapToObj(Integer::toString).collect(Collectors.joining()).length());
    }
}
