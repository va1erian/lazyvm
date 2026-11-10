package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** toMap and toConcurrentMap: duplicate keys, nulls, merge functions, map factories. */
public class CollectorMaps {
    static void t(String label, Supplier<Object> s) {
        try { Object o = s.get(); System.out.println(label + ": " + o); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String ty(Object o) { return o.getClass().getName() + " " + o; }
    static class K { final String n; K(String n) { this.n = n; } public String toString() { return "K(" + n + ")"; } }
    static final List<String> W = Arrays.asList("pear", "fig", "apple", "kiwi", "date");

    public static void main(String[] args) {
        t("toMap", () -> ty(W.stream().collect(Collectors.toMap(s -> s.length() * 10 + s.charAt(0) % 10, s -> s))));
        t("toMap identity", () -> ty(W.stream().collect(Collectors.toMap(Function.identity(), String::length)).entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toList())));
        t("toMap empty", () -> ty(Stream.<String>empty().collect(Collectors.toMap(s -> s, s -> s))));
        t("toMap duplicate", () -> W.stream().collect(Collectors.toMap(String::length, s -> s)));
        t("toMap duplicate message names the earlier value", () -> Stream.of(1, 2, 3).collect(Collectors.toMap(i -> i % 2, i -> i)));
        t("toMap distinct key objects", () -> Stream.of("a", "b").collect(Collectors.toMap(s -> new K("x"), s -> s)).values().stream().sorted().collect(Collectors.toList()));
        t("toMap duplicate same string", () -> Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> s)));
        t("toMap duplicate message uses value toString", () -> Stream.of(new K("p"), new K("p")).collect(Collectors.toMap(k -> k.n, k -> k)));
        t("toMap duplicate stops there", () -> { int[] n = {0}; try { Stream.of("a", "a", "b", "c").collect(Collectors.toMap(s -> { n[0]++; return s; }, s -> s)); } catch (IllegalStateException e) { return "after " + n[0]; } return "none"; });
        t("toMap null key", () -> Stream.of("a", "b").collect(Collectors.toMap(s -> s.equals("b") ? null : s, s -> s)));
        t("toMap null key sole", () -> Stream.of("b").collect(Collectors.toMap(s -> (String) null, s -> s)));
        t("toMap null value", () -> Stream.of("a", "b").collect(Collectors.toMap(s -> s, s -> s.equals("b") ? null : s)));
        t("toMap null value sole", () -> Stream.of("a").collect(Collectors.toMap(s -> s, s -> (String) null)));
        t("toMap null value duplicate", () -> Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> (String) null)));
        t("toMap 3", () -> W.stream().collect(Collectors.toMap(String::length, s -> s, (a, b) -> a + "+" + b)));
        t("toMap 3 merge order", () -> Stream.of("a", "b", "c").collect(Collectors.toMap(s -> 1, s -> s, (a, b) -> "(" + a + b + ")")));
        t("toMap 3 merge null removes", () -> Stream.of("a", "b", "c").collect(Collectors.toMap(s -> s.equals("c") ? "b" : s, s -> s, (a, b) -> null)));
        t("toMap 3 merge null then again", () -> Stream.of("a", "a", "a").collect(Collectors.toMap(s -> s, s -> s, (a, b) -> null)));
        t("toMap 3 merge not called for new keys", () -> Stream.of("a", "b").collect(Collectors.toMap(s -> s, s -> s, (a, b) -> { throw new AssertionError(); })));
        t("toMap 3 merge throws", () -> Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> s, (a, b) -> { throw new IllegalArgumentException("mine"); })));
        t("toMap 3 type", () -> W.stream().collect(Collectors.toMap(String::length, s -> s, (a, b) -> a)).getClass().getName());
        t("toMap 4 TreeMap", () -> ty(W.stream().collect(Collectors.toMap(s -> s, String::length, (a, b) -> a, TreeMap::new))));
        t("toMap 4 LinkedHashMap", () -> ty(W.stream().collect(Collectors.toMap(s -> s, String::length, (a, b) -> a, LinkedHashMap::new))));
        t("toMap 4 merge", () -> ty(W.stream().collect(Collectors.toMap(String::length, s -> s, (a, b) -> b + a, TreeMap::new))));
        t("toMap 4 TreeMap null key", () -> Stream.of("a").collect(Collectors.toMap(s -> (String) null, s -> s, (a, b) -> a, TreeMap::new)));
        t("toMap 4 HashMap null key", () -> Stream.of("a").collect(Collectors.toMap(s -> (String) null, s -> s, (a, b) -> a, HashMap::new)));
        t("toMap 4 HashMap null value", () -> Stream.of("a").collect(Collectors.toMap(s -> s, s -> (String) null, (a, b) -> a, HashMap::new)));
        t("toMap 4 HashMap null value merge", () -> Stream.of("a", "a").collect(Collectors.toMap(s -> s, s -> (String) null, (a, b) -> a, HashMap::new)));
        t("toMap 4 factory result reused", () -> { Map<String, Integer> m = new TreeMap<>(); m.put("seed", 0); Map<String, Integer> r = Stream.of("a").collect(Collectors.toMap(s -> s, String::length, (a, b) -> a, () -> m)); return (r == m) + " " + r; });
        t("toMap 4 unmodifiable factory", () -> Stream.of("a").collect(Collectors.toMap(s -> s, String::length, (a, b) -> a, () -> Collections.<String, Integer>emptyMap())));
        t("toMap 4 unmodifiable factory empty stream", () -> Stream.<String>empty().collect(Collectors.toMap(s -> s, String::length, (a, b) -> a, () -> Collections.<String, Integer>emptyMap())));
        t("toMap Hashtable", () -> Stream.of("a").collect(Collectors.toMap(s -> s, String::length, (a, b) -> a, Hashtable::new)));
        t("toMap EnumMap", () -> Stream.of(Thread.State.NEW, Thread.State.BLOCKED).collect(Collectors.toMap(e -> e, Enum::ordinal, (a, b) -> a, () -> new EnumMap<>(Thread.State.class))));
        t("toMap grouped counting", () -> Stream.of("a", "bb", "a").collect(Collectors.toMap(s -> s, s -> 1, Integer::sum, TreeMap::new)));

        t("toConcurrentMap", () -> ty(new TreeMap<>(W.stream().collect(Collectors.toConcurrentMap(s -> s, String::length)))));
        t("toConcurrentMap type", () -> W.stream().collect(Collectors.toConcurrentMap(s -> s, String::length)) instanceof ConcurrentMap);
        t("toConcurrentMap class", () -> W.stream().collect(Collectors.toConcurrentMap(s -> s, String::length)).getClass().getName());
        t("toConcurrentMap duplicate", () -> W.stream().collect(Collectors.toConcurrentMap(String::length, s -> s)));
        t("toConcurrentMap null value", () -> Stream.of("a").collect(Collectors.toConcurrentMap(s -> s, s -> (String) null)));
        t("toConcurrentMap null key", () -> Stream.of("a").collect(Collectors.toConcurrentMap(s -> (String) null, s -> s)));
        t("toConcurrentMap 3", () -> new TreeMap<>(W.stream().collect(Collectors.toConcurrentMap(String::length, s -> s, (a, b) -> a + "+" + b))));
        t("toConcurrentMap 4", () -> ty(W.stream().collect(Collectors.toConcurrentMap(String::length, s -> s, (a, b) -> a + "+" + b, ConcurrentSkipListMap::new))));
        t("toConcurrentMap parallel", () -> new TreeMap<>(W.stream().parallel().collect(Collectors.toConcurrentMap(s -> s, String::length))));
        t("toMap parallel", () -> IntStream.range(0, 10).boxed().parallel().collect(Collectors.toMap(i -> i, i -> i * i)));

        // maps as the target of other operations
        t("word count", () -> Stream.of("b", "a", "b", "c", "a", "b").collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting())));
        t("entries sorted by value", () -> Stream.of("b", "a", "b", "c", "a", "b").collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(e -> e.getKey() + e.getValue()).collect(Collectors.joining(",")));
        t("invert", () -> W.stream().collect(Collectors.toMap(s -> s, String::length)).entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue, TreeMap::new, Collectors.mapping(Map.Entry::getKey, Collectors.toCollection(TreeSet::new)))));
        t("map stream", () -> new TreeMap<>(Collections.singletonMap("k", 1)).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toList()));
        t("map values stream sum", () -> W.stream().collect(Collectors.toMap(s -> s, String::length)).values().stream().mapToInt(Integer::intValue).sum());
        t("keySet stream sorted", () -> W.stream().collect(Collectors.toMap(s -> s, String::length)).keySet().stream().sorted().collect(Collectors.toList()));
        t("entry comparingByKey", () -> new TreeMap<>(Collections.singletonMap("k", 1)).entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.reverseOrder())).findFirst().get());
    }
}
