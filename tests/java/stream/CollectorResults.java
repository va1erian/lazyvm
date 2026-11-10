package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** What the factory collectors produce: result types, contents, messages. */
public class CollectorResults {
    static void t(String label, Supplier<Object> s) {
        try { Object o = s.get(); System.out.println(label + ": " + o); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String ty(Object o) { return o.getClass().getName() + " " + o; }
    static final List<String> W = Arrays.asList("pear", "fig", "apple", "kiwi", "date", "plum", "fig");

    public static void main(String[] args) {
        // containers
        t("toList", () -> ty(W.stream().collect(Collectors.toList())));
        t("toList empty", () -> ty(Stream.empty().collect(Collectors.toList())));
        t("toList nulls", () -> Stream.of("a", null).collect(Collectors.toList()));
        t("toList mutable", () -> { List<String> l = W.stream().collect(Collectors.toList()); l.add("x"); return l.size(); });
        t("toSet", () -> W.stream().collect(Collectors.toSet()).getClass().getName() + " " + new TreeSet<>(W.stream().collect(Collectors.toSet())));
        t("toSet nulls", () -> Stream.of("a", null, null).collect(Collectors.toSet()).size());
        t("toCollection", () -> ty(W.stream().collect(Collectors.toCollection(TreeSet::new))));
        t("toCollection LinkedList", () -> ty(W.stream().collect(Collectors.toCollection(LinkedList::new))));
        t("toCollection ArrayDeque", () -> ty(W.stream().collect(Collectors.toCollection(ArrayDeque::new))));
        t("toCollection TreeSet with null", () -> Stream.of("a", null).collect(Collectors.toCollection(TreeSet::new)));
        t("toCollection null supplier result", () -> W.stream().collect(Collectors.toCollection(() -> null)));

        // joining
        t("joining", () -> W.stream().collect(Collectors.joining()));
        t("joining delimiter", () -> W.stream().collect(Collectors.joining(", ")));
        t("joining affixes", () -> W.stream().collect(Collectors.joining(", ", "{", "}")));
        t("joining empty", () -> Stream.<String>empty().collect(Collectors.joining(", ")));
        t("joining empty affixes", () -> Stream.<String>empty().collect(Collectors.joining(", ", "<", ">")));
        t("joining one", () -> Stream.of("x").collect(Collectors.joining(", ", "<", ">")));
        t("joining null element", () -> Stream.of("a", null, "b").collect(Collectors.joining("-")));
        t("joining StringBuilder", () -> Stream.of(new StringBuilder("a"), new StringBuilder("b")).collect(Collectors.joining("+")));
        t("joining empty strings", () -> Stream.of("", "", "").collect(Collectors.joining("|", "[", "]")));
        t("joining empty delimiter", () -> Stream.of("a", "b").collect(Collectors.joining("", "(", ")")));
        t("joining result type", () -> Stream.of("a").collect(Collectors.joining()).getClass().getName());

        // mapping, collectingAndThen, counting, min/max
        t("mapping", () -> W.stream().collect(Collectors.mapping(String::length, Collectors.toList())));
        t("mapping set", () -> W.stream().collect(Collectors.mapping(String::length, Collectors.toCollection(TreeSet::new))));
        t("mapping joining", () -> W.stream().collect(Collectors.mapping(s -> s.substring(0, 1), Collectors.joining())));
        t("collectingAndThen", () -> W.stream().collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList)).getClass().getName());
        t("collectingAndThen size", () -> W.stream().collect(Collectors.collectingAndThen(Collectors.toList(), List::size)));
        t("counting", () -> W.stream().collect(Collectors.counting()));
        t("counting type", () -> W.stream().collect(Collectors.counting()).getClass().getName());
        t("counting empty", () -> Stream.empty().collect(Collectors.counting()));
        t("minBy", () -> W.stream().collect(Collectors.minBy(Comparator.naturalOrder())));
        t("maxBy", () -> W.stream().collect(Collectors.maxBy(Comparator.comparing(String::length))));
        t("maxBy ties", () -> W.stream().collect(Collectors.maxBy(Comparator.comparing(String::length))));
        t("minBy ties", () -> W.stream().collect(Collectors.minBy(Comparator.comparing(String::length))));
        t("minBy empty", () -> Stream.<String>empty().collect(Collectors.minBy(Comparator.naturalOrder())));
        t("maxBy null result", () -> Stream.of((String) null).collect(Collectors.maxBy(Comparator.nullsFirst(Comparator.<String>naturalOrder()))));

        // sums and averages
        t("summingInt", () -> W.stream().collect(Collectors.summingInt(String::length)));
        t("summingInt type", () -> W.stream().collect(Collectors.summingInt(String::length)).getClass().getName());
        t("summingInt wraps", () -> Stream.of(Integer.MAX_VALUE, 1).collect(Collectors.summingInt(x -> x)));
        t("summingInt empty", () -> Stream.<Integer>empty().collect(Collectors.summingInt(x -> x)));
        t("summingLong", () -> Stream.of(Long.MAX_VALUE, 1L).collect(Collectors.summingLong(x -> x)));
        t("summingLong type", () -> Stream.of(1L).collect(Collectors.summingLong(x -> x)).getClass().getName());
        t("summingDouble", () -> Stream.of(0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1).collect(Collectors.summingDouble(x -> x)));
        t("summingDouble type", () -> Stream.of(1.0).collect(Collectors.summingDouble(x -> x)).getClass().getName());
        t("summingDouble empty", () -> Stream.<Double>empty().collect(Collectors.summingDouble(x -> x)));
        t("summingDouble inf", () -> Stream.of(1.0, Double.POSITIVE_INFINITY).collect(Collectors.summingDouble(x -> x)));
        t("summingDouble inf -inf", () -> Stream.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).collect(Collectors.summingDouble(x -> x)));
        t("summingDouble NaN", () -> Stream.of(1.0, Double.NaN).collect(Collectors.summingDouble(x -> x)));
        t("summingDouble -0.0", () -> Stream.of(-0.0).collect(Collectors.summingDouble(x -> x)));
        t("averagingInt", () -> W.stream().collect(Collectors.averagingInt(String::length)));
        t("averagingInt empty", () -> Stream.<String>empty().collect(Collectors.averagingInt(String::length)));
        t("averagingInt type", () -> W.stream().collect(Collectors.averagingInt(String::length)).getClass().getName());
        t("averagingInt large", () -> Stream.of(Integer.MAX_VALUE, Integer.MAX_VALUE).collect(Collectors.averagingInt(x -> x)));
        t("averagingLong", () -> Stream.of(1L, 2L, 4L).collect(Collectors.averagingLong(x -> x)));
        t("averagingLong wraps", () -> Stream.of(Long.MAX_VALUE, Long.MAX_VALUE).collect(Collectors.averagingLong(x -> x)));
        t("averagingDouble", () -> Stream.of(0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1).collect(Collectors.averagingDouble(x -> x)));
        t("averagingDouble empty", () -> Stream.<Double>empty().collect(Collectors.averagingDouble(x -> x)));
        t("averagingDouble inf", () -> Stream.of(1.0, Double.POSITIVE_INFINITY).collect(Collectors.averagingDouble(x -> x)));
        t("averagingDouble NaN", () -> Stream.of(1.0, Double.NaN).collect(Collectors.averagingDouble(x -> x)));

        // reducing
        t("reducing op", () -> W.stream().collect(Collectors.reducing((a, b) -> a.length() >= b.length() ? a : b)));
        t("reducing op empty", () -> Stream.<String>empty().collect(Collectors.reducing((a, b) -> a)));
        t("reducing identity", () -> W.stream().collect(Collectors.reducing("", (a, b) -> a + b.charAt(0))));
        t("reducing identity empty", () -> Stream.<String>empty().collect(Collectors.reducing("none", (a, b) -> a + b)));
        t("reducing mapper", () -> W.stream().collect(Collectors.reducing(0, String::length, Integer::sum)));
        t("reducing mapper empty", () -> Stream.<String>empty().collect(Collectors.reducing(7, String::length, Integer::sum)));
        t("reducing identity object is reused", () -> { Collector<String, ?, StringBuilder> c = Collectors.reducing(new StringBuilder(), StringBuilder::new, (a, b) -> a.append(b));
            return W.stream().collect(c) + " " + W.stream().collect(c); });
        t("reducing null result", () -> Stream.of("a", "b").collect(Collectors.reducing(null, (a, b) -> null)));
        t("reducing op null result", () -> Stream.of("a", "b").collect(Collectors.reducing((a, b) -> null)));
        t("reducing op null element", () -> Stream.of((String) null).collect(Collectors.reducing((a, b) -> a)));

        // summarizing
        t("summarizingInt", () -> W.stream().collect(Collectors.summarizingInt(String::length)));
        t("summarizingLong", () -> W.stream().collect(Collectors.summarizingLong(String::length)));
        t("summarizingDouble", () -> W.stream().collect(Collectors.summarizingDouble(String::length)));
        t("summarizingInt empty", () -> Stream.<String>empty().collect(Collectors.summarizingInt(String::length)));
        t("summarizingDouble empty", () -> Stream.<String>empty().collect(Collectors.summarizingDouble(String::length)));
        t("summarizingInt type", () -> W.stream().collect(Collectors.summarizingInt(String::length)).getClass().getName());

        // grouping
        t("groupingBy", () -> ty(W.stream().collect(Collectors.groupingBy(String::length))));
        t("groupingBy keys", () -> W.stream().collect(Collectors.groupingBy(s -> s.charAt(0))));
        t("groupingBy value type", () -> W.stream().collect(Collectors.groupingBy(String::length)).get(3).getClass().getName());
        t("groupingBy empty", () -> ty(Stream.<String>empty().collect(Collectors.groupingBy(String::length))));
        t("groupingBy counting", () -> W.stream().collect(Collectors.groupingBy(String::length, Collectors.counting())));
        t("groupingBy set", () -> W.stream().collect(Collectors.groupingBy(String::length, Collectors.toCollection(TreeSet::new))));
        t("groupingBy mapping", () -> W.stream().collect(Collectors.groupingBy(String::length, Collectors.mapping(s -> s.substring(0, 1), Collectors.joining()))));
        t("groupingBy TreeMap", () -> ty(W.stream().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.toList()))));
        t("groupingBy TreeMap reversed", () -> W.stream().collect(Collectors.groupingBy(String::length, () -> new TreeMap<>(Comparator.reverseOrder()), Collectors.counting())));
        t("groupingBy LinkedHashMap", () -> ty(W.stream().collect(Collectors.groupingBy(s -> s.substring(0, 1), LinkedHashMap::new, Collectors.toList()))));
        t("groupingBy nested", () -> W.stream().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.groupingBy(s -> s.charAt(0), TreeMap::new, Collectors.counting()))));
        t("groupingBy finisher", () -> W.stream().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.collectingAndThen(Collectors.toList(), List::size))));
        t("groupingBy finisher keeps map", () -> ty(W.stream().collect(Collectors.groupingBy(String::length, LinkedHashMap::new, Collectors.counting()))));
        t("groupingBy summing", () -> W.stream().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.summingInt(String::length))));
        t("groupingBy maxBy", () -> W.stream().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.maxBy(Comparator.<String>naturalOrder()))));
        t("groupingBy null key", () -> W.stream().collect(Collectors.groupingBy(s -> (String) null)));
        t("groupingBy null key later", () -> W.stream().collect(Collectors.groupingBy(s -> s.equals("kiwi") ? null : s)));
        t("groupingBy null key empty", () -> ty(Stream.<String>empty().collect(Collectors.groupingBy(s -> (String) null))));
        t("groupingByConcurrent", () -> ty(new TreeMap<>(W.stream().collect(Collectors.groupingByConcurrent(String::length)))));
        t("groupingByConcurrent counting", () -> ty(new TreeMap<>(W.stream().collect(Collectors.groupingByConcurrent(String::length, Collectors.counting())))));
        t("groupingByConcurrent factory", () -> ty(W.stream().collect(Collectors.groupingByConcurrent(String::length, ConcurrentSkipListMap::new, Collectors.toList()))));
        t("groupingByConcurrent null key", () -> W.stream().collect(Collectors.groupingByConcurrent(s -> (String) null)));
        t("groupingByConcurrent type", () -> W.stream().collect(Collectors.groupingByConcurrent(String::length)) instanceof ConcurrentMap);
        t("groupingBy parallel", () -> new TreeMap<>(W.stream().parallel().collect(Collectors.groupingBy(String::length))));
        t("groupingByConcurrent parallel", () -> new TreeMap<>(W.stream().parallel().collect(Collectors.groupingByConcurrent(String::length, Collectors.counting()))));

        // partitioning
        t("partitioningBy", () -> W.stream().collect(Collectors.partitioningBy(s -> s.length() > 3)));
        t("partitioningBy empty", () -> Stream.<String>empty().collect(Collectors.partitioningBy(s -> true)));
        t("partitioningBy counting", () -> W.stream().collect(Collectors.partitioningBy(s -> s.length() > 3, Collectors.counting())));
        t("partitioningBy empty counting", () -> Stream.<String>empty().collect(Collectors.partitioningBy(s -> true, Collectors.counting())));
        t("partitioningBy all one side", () -> W.stream().collect(Collectors.partitioningBy(s -> true, Collectors.toCollection(TreeSet::new))));
        t("partitioningBy mapping", () -> W.stream().collect(Collectors.partitioningBy(s -> s.contains("i"), Collectors.mapping(String::length, Collectors.toList()))));
        t("partitioningBy nested", () -> W.stream().collect(Collectors.partitioningBy(s -> s.length() > 3, Collectors.partitioningBy(s -> s.contains("e")))));
        t("partitioningBy get", () -> { Map<Boolean, List<String>> m = W.stream().collect(Collectors.partitioningBy(s -> s.length() > 3)); return m.get(true) + " " + m.get(false) + " " + m.get(null) + " " + m.get("x"); });
        t("partitioningBy containsKey", () -> { Map<Boolean, Long> m = W.stream().collect(Collectors.partitioningBy(s -> true, Collectors.counting())); return m.containsKey(true) + " " + m.containsKey(false) + " " + m.containsKey(null) + " " + m.containsKey(1); });
        t("partitioningBy size", () -> Stream.<String>empty().collect(Collectors.partitioningBy(s -> true)).size());
        t("partitioningBy keys", () -> Stream.<String>empty().collect(Collectors.partitioningBy(s -> true)).keySet());
        t("partitioningBy values", () -> W.stream().collect(Collectors.partitioningBy(s -> s.length() > 3, Collectors.counting())).values());
        t("partitioningBy entries", () -> { StringBuilder sb = new StringBuilder(); for (Map.Entry<Boolean, Long> e : W.stream().collect(Collectors.partitioningBy(s -> s.length() > 3, Collectors.counting())).entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(';'); return sb; });
        t("partitioningBy put", () -> W.stream().collect(Collectors.partitioningBy(s -> true)).put(true, null));
        t("partitioningBy remove", () -> W.stream().collect(Collectors.partitioningBy(s -> true)).remove(true));
        t("partitioningBy clear", () -> { W.stream().collect(Collectors.partitioningBy(s -> true)).clear(); return "no"; });
        t("partitioningBy entry setValue", () -> { Map<Boolean, List<String>> m = W.stream().collect(Collectors.partitioningBy(s -> true)); m.entrySet().iterator().next().setValue(null); return "no"; });
        t("partitioningBy equals", () -> { Map<Boolean, List<String>> m = Stream.of("a").collect(Collectors.partitioningBy(s -> true)); Map<Boolean, List<String>> h = new HashMap<>(); h.put(false, new ArrayList<>()); h.put(true, Arrays.asList("a")); return m.equals(h) + " " + h.equals(m) + " " + (m.hashCode() == h.hashCode()); });
        t("partitioningBy forEach", () -> { StringBuilder sb = new StringBuilder(); Stream.of("a", "bb").collect(Collectors.partitioningBy(s -> s.length() > 1)).forEach((k, v) -> sb.append(k).append(v)); return sb; });
        t("partitioningBy mutable lists", () -> { Map<Boolean, List<String>> m = Stream.of("a").collect(Collectors.partitioningBy(s -> true)); m.get(false).add("z"); return m; });
        t("partitioningBy get twice same", () -> { Map<Boolean, List<String>> m = Stream.of("a").collect(Collectors.partitioningBy(s -> true)); return m.get(true) == m.get(true); });
        t("partitioningBy parallel", () -> IntStream.range(0, 10).boxed().parallel().collect(Collectors.partitioningBy(i -> i % 3 == 0)));
    }
}
