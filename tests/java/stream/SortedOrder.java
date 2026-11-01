package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** sorted(): which comparisons it makes, in which argument order, next to Arrays.sort and List.sort. */
public class SortedOrder {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static StringBuilder log = new StringBuilder();
    static final Comparator<Integer> LOGGED = (a, b) -> { log.append(a).append('?').append(b).append(' '); return Integer.compare(a, b); };
    static String take() { String s = log.toString().trim(); log.setLength(0); return s; }
    static List<Integer> data(int... v) { List<Integer> l = new ArrayList<>(); for (int x : v) l.add(x); return l; }
    static List<Integer> noise(int n) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add((i * 37 + 11) % 101); return l; }

    public static void main(String[] args) {
        int[][] cases = {{}, {1}, {2, 1}, {1, 2}, {3, 1, 2}, {5, 4, 3, 2, 1}, {1, 2, 3, 4, 5}, {2, 7, 1, 8, 2, 8}, {4, 4, 4}};
        for (int[] c : cases) {
            String name = Arrays.toString(c);
            t("sorted " + name, () -> { List<Integer> l = data(c); l.stream().sorted(LOGGED).collect(Collectors.toList()); return take(); });
            t("Arrays.sort " + name, () -> { Integer[] a = data(c).toArray(new Integer[0]); Arrays.sort(a, LOGGED); return take(); });
            t("List.sort " + name, () -> { List<Integer> l = data(c); l.sort(LOGGED); return take(); });
            t("same as Arrays.sort " + name, () -> { Integer[] a = data(c).toArray(new Integer[0]); Arrays.sort(a, LOGGED); String x = take(); data(c).stream().sorted(LOGGED).collect(Collectors.toList()); return x.equals(take()); });
            t("unsized same " + name, () -> { Integer[] a = data(c).toArray(new Integer[0]); Arrays.sort(a, LOGGED); String x = take(); data(c).stream().filter(v -> true).sorted(LOGGED).collect(Collectors.toList()); return x.equals(take()); });
            t("natural order same " + name, () -> { data(c).stream().sorted().collect(Collectors.toList()); return "ok"; });
        }
        t("larger same as Arrays.sort", () -> { Integer[] a = noise(80).toArray(new Integer[0]); Arrays.sort(a, LOGGED); String x = take(); noise(80).stream().sorted(LOGGED).collect(Collectors.toList()); return x.equals(take()); });
        t("larger unsized same", () -> { Integer[] a = noise(80).toArray(new Integer[0]); Arrays.sort(a, LOGGED); String x = take(); noise(80).stream().filter(v -> true).sorted(LOGGED).collect(Collectors.toList()); return x.equals(take()); });
        t("larger count", () -> { noise(80).stream().sorted(LOGGED).collect(Collectors.toList()); return take().split(" ").length; });
        t("larger list sort same", () -> { List<Integer> l = noise(80); l.sort(LOGGED); String x = take(); noise(80).stream().sorted(LOGGED).collect(Collectors.toList()); return x.equals(take()); });
        t("sorted result", () -> noise(12).stream().sorted().collect(Collectors.toList()));
        t("sorted reversed result", () -> noise(12).stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
        t("sorted twice", () -> noise(12).stream().sorted(Comparator.reverseOrder()).sorted().collect(Collectors.toList()));
        t("sorted comparator once for a pair", () -> { data(2, 1).stream().sorted(LOGGED).findFirst(); return take(); });
        t("sorted comparator for strings", () -> { StringBuilder sb = new StringBuilder(); Stream.of("b", "a", "c").sorted((x, y) -> { sb.append(x).append(y).append(' '); return x.compareTo(y); }).collect(Collectors.toList()); return sb; });
        t("sorted natural mixed order of cast", () -> Stream.of("a", 1).sorted().count());
        t("sorted comparator that throws", () -> Stream.of(1, 2, 3).sorted((a, b) -> { throw new IllegalStateException("cmp"); }).count());
        t("sorted comparator that throws on one element", () -> Stream.of(1).sorted((a, b) -> { throw new IllegalStateException("cmp"); }).count());
        t("sorted with null comparator result", () -> Stream.of(2, 1).sorted((a, b) -> 0).collect(Collectors.toList()));
        t("sorted inconsistent small", () -> Stream.of(3, 1, 2).sorted((a, b) -> 1).collect(Collectors.toList()));
        t("sorted inconsistent small 2", () -> Stream.of(3, 1, 2).sorted((a, b) -> -1).collect(Collectors.toList()));
        t("sorted pure sizes", () -> IntStream.of(5, 3, 9, 1).sorted().boxed().collect(Collectors.toList()));
        t("sorted longs", () -> LongStream.of(5, 3, 9, 1).sorted().boxed().collect(Collectors.toList()));
        t("sorted doubles", () -> DoubleStream.of(5, 3, 9, 1).sorted().boxed().collect(Collectors.toList()));
        t("min comparator calls", () -> { noise(6).stream().min(LOGGED); return take(); });
        t("max comparator calls", () -> { noise(6).stream().max(LOGGED); return take(); });
        t("distinct then sorted", () -> data(3, 1, 3, 2, 1).stream().distinct().sorted().collect(Collectors.toList()));
        t("sorted then distinct", () -> data(3, 1, 3, 2, 1).stream().sorted().distinct().collect(Collectors.toList()));
        t("sorted nulls with comparator", () -> Stream.of("b", null, "a").sorted(Comparator.nullsLast(Comparator.naturalOrder())).collect(Collectors.toList()));
        t("sorted records stability", () -> Stream.of("b1", "a1", "b2", "a2", "b3", "a3").sorted(Comparator.comparing(s -> s.charAt(0))).collect(Collectors.toList()));
        t("sorted stability 64", () -> IntStream.range(0, 64).boxed().sorted(Comparator.comparingInt(i -> i % 2)).limit(6).collect(Collectors.toList()));
        t("sorted stability 70 odd first", () -> IntStream.range(0, 70).boxed().sorted(Comparator.comparingInt(i -> -(i % 2))).limit(6).collect(Collectors.toList()));
    }
}
