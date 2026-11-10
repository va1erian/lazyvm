package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * sorted() and distinct() on sources that already are sorted or distinct (they do nothing),
 * and the flags of comparator-sorted sources.
 */
public class SortedNoop {
    static StringBuilder log = new StringBuilder();
    static <T> Consumer<T> pk(String tag) { return x -> log.append(tag).append(x).append(' '); }
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r + (log.length() == 0 ? "" : " | " + log.toString().trim()));
        log.setLength(0);
    }
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    static String info(Spliterator<?> s) { return names(s.characteristics()) + " est=" + s.estimateSize(); }
    static String cmp(Spliterator<?> s) { try { Comparator<?> c = s.getComparator(); return c == null ? "null" : "cmp"; } catch (IllegalStateException e) { return "ISE"; } }

    /** Ordered by group only; equal by id only. */
    static final class Item implements Comparable<Item> {
        final int group, id;
        Item(int group, int id) { this.group = group; this.id = id; }
        public int compareTo(Item o) { log.append("cmp "); return Integer.compare(group, o.group); }
        public boolean equals(Object o) { return o instanceof Item && ((Item) o).id == id; }
        public int hashCode() { return id; }
        public String toString() { return group + ":" + id; }
    }
    static Spliterator<Integer> claim(int ch, Integer... a) { return Spliterators.spliterator(a, ch); }

    public static void main(String[] args) {
        // a sorted primitive range is not sorted again
        t("long range sorted findFirst", () -> LongStream.range(0, Long.MAX_VALUE).sorted().findFirst());
        t("long range sorted limit", () -> LongStream.range(0, Long.MAX_VALUE).sorted().limit(3).sum());
        t("long rangeClosed sorted skip", () -> LongStream.rangeClosed(1, Long.MAX_VALUE).sorted().skip(5).findFirst());
        t("int range sorted findFirst", () -> IntStream.range(0, Integer.MAX_VALUE).sorted().findFirst());
        t("int range sorted boxed", () -> IntStream.range(0, Integer.MAX_VALUE).sorted().boxed().limit(2).collect(Collectors.toList()));
        t("long range distinct findFirst", () -> LongStream.range(0, Long.MAX_VALUE).distinct().findFirst());
        t("long range sorted distinct limit", () -> LongStream.range(0, Long.MAX_VALUE).sorted().distinct().limit(2).sum());
        t("long range boxed findFirst", () -> LongStream.range(0, Long.MAX_VALUE).boxed().findFirst());
        t("long range boxed sorted", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().findFirst());
        t("long range mapToObj sorted", () -> LongStream.range(0, Long.MAX_VALUE).mapToObj(x -> x).sorted().findFirst());
        t("int range boxed sorted huge", () -> IntStream.range(0, Integer.MAX_VALUE).boxed().sorted().findFirst());
        t("long range map sorted", () -> LongStream.range(0, Long.MAX_VALUE).map(x -> x).sorted().findFirst());
        t("long range sorted peek findFirst", () -> LongStream.range(0, Long.MAX_VALUE).peek(x -> log.append("p" + x + " ")).sorted().findFirst());
        t("long range sorted iterator", () -> LongStream.range(0, Long.MAX_VALUE).sorted().iterator().nextLong());
        t("long range sorted spliterator", () -> { LongStream.range(0, Long.MAX_VALUE).sorted().spliterator().tryAdvance((LongConsumer) x -> log.append("got" + x)); return "-"; });
        t("array int sorted flags", () -> info(IntStream.of(3, 1, 2).sorted().spliterator()));
        t("int range sorted flags", () -> info(IntStream.range(0, 5).sorted().spliterator()));
        t("int range sorted peek flags", () -> info(IntStream.range(0, 5).sorted().peek(x -> {}).spliterator()));
        t("int range sorted distinct flags", () -> info(IntStream.range(0, 5).sorted().distinct().spliterator()));

        // sorted().distinct() compares neighbours with equals only when the stream is sorted
        Item a1 = new Item(1, 1), a2 = new Item(1, 2), a1b = new Item(1, 1), b3 = new Item(2, 3);
        t("sorted distinct inconsistent", () -> Stream.of(a1, a2, a1b, b3).sorted().distinct().collect(Collectors.toList()));
        t("distinct sorted inconsistent", () -> Stream.of(a1, a2, a1b, b3).distinct().sorted().collect(Collectors.toList()));
        t("sorted distinct inconsistent reversed", () -> Stream.of(b3, a1b, a2, a1).sorted().distinct().collect(Collectors.toList()));
        t("sorted distinct unsorted input", () -> Stream.of(a1, b3, a1b, a2).sorted().distinct().collect(Collectors.toList()));
        t("sorted distinct findFirst", () -> Stream.of(a1, a2, a1b).sorted().distinct().findFirst());
        t("sorted distinct count", () -> Stream.of(a1, a2, a1b, b3).sorted().distinct().count());
        t("sorted distinct with nulls", () -> Stream.of(1, null, 1).distinct().collect(Collectors.toList()));
        t("TreeSet sorted distinct inconsistent", () -> new TreeSet<>(Arrays.asList(a1, a2, b3)).stream().distinct().collect(Collectors.toList()));

        // a spliterator may claim to be sorted or distinct; the pipeline believes it
        t("claims SORTED natural", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).sorted().collect(Collectors.toList()));
        t("claims SORTED natural, no ORDERED", () -> StreamSupport.stream(claim(Spliterator.SORTED, 3, 1, 2), false).sorted().collect(Collectors.toList()));
        t("claims DISTINCT", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.DISTINCT, 1, 1, 2, 2), false).distinct().collect(Collectors.toList()));
        t("claims DISTINCT sorted", () -> StreamSupport.stream(claim(Spliterator.DISTINCT, 2, 2, 1), false).sorted().collect(Collectors.toList()));
        t("claims SORTED sorted(cmp)", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
        t("claims SORTED sorted(natural order cmp)", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).sorted(Comparator.naturalOrder()).collect(Collectors.toList()));
        t("claims SORTED peek sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).peek(pk("a")).sorted().peek(pk("b")).collect(Collectors.toList()));
        t("claims SORTED map sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).map(x -> x).sorted().collect(Collectors.toList()));
        t("claims SORTED filter sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).filter(x -> true).sorted().collect(Collectors.toList()));
        t("claims SORTED limit sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).limit(2).sorted().collect(Collectors.toList()));
        t("claims SORTED skip sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).skip(1).sorted().collect(Collectors.toList()));
        t("claims SORTED distinct sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 1), false).distinct().sorted().collect(Collectors.toList()));
        t("claims SORTED unordered sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).unordered().sorted().collect(Collectors.toList()));
        t("claims SORTED parallel sorted", () -> StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), true).sorted().collect(Collectors.toList()));
        t("claims SORTED comparator", () -> {
            Spliterator<Integer> s = new Spliterators.AbstractSpliterator<Integer>(3, Spliterator.ORDERED | Spliterator.SORTED) {
                int i = 3;
                public boolean tryAdvance(Consumer<? super Integer> c) { if (i == 0) return false; c.accept(i--); return true; }
                public Comparator<? super Integer> getComparator() { return Comparator.reverseOrder(); }
            };
            return StreamSupport.stream(s, false).sorted().collect(Collectors.toList());
        });
        t("claims SORTED comparator sorted(same)", () -> {
            Comparator<Integer> rev = Comparator.reverseOrder();
            Spliterator<Integer> s = new Spliterators.AbstractSpliterator<Integer>(3, Spliterator.ORDERED | Spliterator.SORTED) {
                int i = 1;
                public boolean tryAdvance(Consumer<? super Integer> c) { if (i == 4) return false; c.accept(i++); return true; }
                public Comparator<? super Integer> getComparator() { return rev; }
            };
            return StreamSupport.stream(s, false).sorted(rev).collect(Collectors.toList());
        });
        t("claims SORTED long", () -> StreamSupport.longStream(Spliterators.spliterator(new long[] {3, 1, 2}, Spliterator.ORDERED | Spliterator.SORTED), false).sorted().boxed().collect(Collectors.toList()));
        t("claims SORTED int", () -> StreamSupport.intStream(Spliterators.spliterator(new int[] {3, 1, 2}, Spliterator.ORDERED | Spliterator.SORTED), false).sorted().boxed().collect(Collectors.toList()));
        t("claims SORTED double", () -> StreamSupport.doubleStream(Spliterators.spliterator(new double[] {3, 1, 2}, Spliterator.ORDERED | Spliterator.SORTED), false).sorted().boxed().collect(Collectors.toList()));
        t("claims DISTINCT int", () -> StreamSupport.intStream(Spliterators.spliterator(new int[] {1, 1}, Spliterator.ORDERED | Spliterator.DISTINCT), false).distinct().boxed().collect(Collectors.toList()));

        // TreeSet and friends: how often the elements are compared
        t("TreeSet sorted calls", () -> { TreeSet<Item> s = new TreeSet<>(Arrays.asList(a1, b3)); log.setLength(0); return s.stream().sorted().collect(Collectors.toList()); });
        t("TreeSet sorted peek order", () -> new TreeSet<>(Arrays.asList(3, 1, 2)).stream().peek(pk("a")).sorted().peek(pk("b")).collect(Collectors.toList()));
        t("TreeSet sorted findFirst", () -> new TreeSet<>(Arrays.asList(3, 1, 2)).stream().peek(pk("a")).sorted().peek(pk("b")).findFirst());
        t("TreeSet comparator sorted order", () -> { TreeSet<Integer> s = new TreeSet<>(Comparator.reverseOrder()); s.addAll(Arrays.asList(1, 2, 3)); return s.stream().peek(pk("a")).sorted().peek(pk("b")).collect(Collectors.toList()); });
        t("TreeSet comparator sorted(cmp)", () -> { TreeSet<Integer> s = new TreeSet<>(Comparator.reverseOrder()); s.addAll(Arrays.asList(1, 2, 3)); return s.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList()); });
        t("TreeSet comparator sorted counts", () -> {
            int[] n = {0};
            TreeSet<Integer> s = new TreeSet<>((x, y) -> { n[0]++; return Integer.compare(y, x); });
            s.addAll(Arrays.asList(1, 2, 3, 4)); n[0] = 0;
            Object r = s.stream().sorted().collect(Collectors.toList());
            return r + " cmp calls " + n[0];
        });
        t("TreeSet comparator sorted(cmp) counts", () -> {
            int[] n = {0};
            Comparator<Integer> c = (x, y) -> { n[0]++; return Integer.compare(y, x); };
            TreeSet<Integer> s = new TreeSet<>(c);
            s.addAll(Arrays.asList(1, 2, 3, 4)); n[0] = 0;
            Object r = s.stream().sorted(c).collect(Collectors.toList());
            return r + " cmp calls " + n[0];
        });
        t("TreeSet natural sorted counts", () -> {
            TreeSet<Item> s = new TreeSet<>(Arrays.asList(new Item(1, 1), new Item(2, 2), new Item(3, 3)));
            log.setLength(0);
            Object r = s.stream().sorted().count();
            return r + " compareTo calls " + log.length() / 4;
        });
        t("sorted peek sorted", () -> Stream.of(3, 1, 2).peek(pk("a")).sorted().peek(pk("b")).sorted().peek(pk("c")).collect(Collectors.toList()));
        t("sorted sorted compare calls", () -> {
            int[] n = {0};
            Comparator<Integer> c = (x, y) -> { n[0]++; return Integer.compare(x, y); };
            Object r = Stream.of(3, 1, 2).sorted(c).sorted(c).collect(Collectors.toList());
            return r + " cmp calls " + n[0];
        });
        t("natural sorted sorted calls", () -> {
            Object r = Stream.of(new Item(3, 3), new Item(1, 1), new Item(2, 2)).sorted().sorted().count();
            return r + " compareTo calls " + log.length() / 4;
        });
        t("TreeMap keySet sorted peek order", () -> new TreeMap<>(Collections.singletonMap(2, "b")).keySet().stream().peek(pk("a")).sorted().peek(pk("b")).findFirst());
        t("range sorted peek order", () -> IntStream.range(0, 3).peek(x -> log.append("a" + x + " ")).sorted().peek(x -> log.append("b" + x + " ")).findFirst());
        t("BitSet sorted peek order", () -> { BitSet b = new BitSet(); b.set(3); b.set(1); return b.stream().peek(x -> log.append("a" + x + " ")).sorted().peek(x -> log.append("b" + x + " ")).findFirst(); });
        t("sorted distinct short circuit", () -> new TreeSet<>(Arrays.asList(3, 1, 2)).stream().peek(pk("a")).sorted().distinct().peek(pk("b")).findFirst());
        t("sorted distinct short circuit list", () -> Stream.of(3, 1, 1, 2).peek(pk("a")).sorted().distinct().peek(pk("b")).findFirst());
        t("sorted distinct limit", () -> Stream.of(3, 1, 1, 2).peek(pk("a")).sorted().distinct().peek(pk("b")).limit(2).collect(Collectors.toList()));
        t("sorted distinct iterator", () -> { Iterator<Integer> it = Stream.of(3, 1, 1, 2).peek(pk("a")).sorted().distinct().peek(pk("b")).iterator(); it.next(); return it.next(); });
        t("distinct sorted limit", () -> Stream.of(3, 1, 1, 2).peek(pk("a")).distinct().sorted().peek(pk("b")).limit(2).collect(Collectors.toList()));
        t("TreeSet distinct peek order", () -> new TreeSet<>(Arrays.asList(3, 1, 2)).stream().peek(pk("a")).distinct().peek(pk("b")).limit(2).collect(Collectors.toList()));
        t("LinkedHashSet distinct peek order", () -> new LinkedHashSet<>(Arrays.asList(3, 1, 2)).stream().peek(pk("a")).distinct().peek(pk("b")).limit(2).collect(Collectors.toList()));
        t("distinct equals calls", () -> {
            Stream.of(a1, a2, a1b).peek(pk("a")).distinct().peek(pk("b")).count();
            return "-";
        });

        // comparator-sorted sources
        TreeSet<Integer> rev = new TreeSet<>(Comparator.reverseOrder()); rev.addAll(Arrays.asList(1, 2, 3));
        TreeSet<Integer> nat = new TreeSet<>(Arrays.asList(1, 2, 3));
        t("TreeSet comparator source", () -> info(rev.spliterator()) + " " + cmp(rev.spliterator()));
        t("TreeSet comparator stream", () -> info(rev.stream().spliterator()) + " " + cmp(rev.stream().spliterator()));
        t("TreeSet comparator map", () -> info(rev.stream().map(x -> x).spliterator()));
        t("TreeSet comparator peek", () -> info(rev.stream().peek(x -> {}).spliterator()));
        t("TreeSet comparator filter", () -> info(rev.stream().filter(x -> true).spliterator()));
        t("TreeSet comparator sorted", () -> info(rev.stream().sorted().spliterator()) + " " + cmp(rev.stream().sorted().spliterator()));
        t("TreeSet comparator sorted(cmp)", () -> info(rev.stream().sorted(Comparator.reverseOrder()).spliterator()));
        t("TreeSet comparator distinct", () -> info(rev.stream().distinct().spliterator()));
        t("TreeSet comparator limit", () -> info(rev.stream().limit(2).spliterator()));
        t("TreeSet comparator unordered", () -> info(rev.stream().unordered().spliterator()));
        t("TreeSet comparator boxed ints", () -> info(rev.stream().mapToInt(x -> x).spliterator()));
        t("TreeSet natural peek", () -> info(nat.stream().peek(x -> {}).spliterator()) + " " + cmp(nat.stream().peek(x -> {}).spliterator()));
        t("TreeSet natural map", () -> info(nat.stream().map(x -> x).spliterator()));
        t("TreeSet natural sorted", () -> info(nat.stream().sorted().spliterator()) + " " + cmp(nat.stream().sorted().spliterator()));
        t("TreeSet natural sorted(cmp)", () -> info(nat.stream().sorted(Comparator.reverseOrder()).spliterator()));
        t("TreeSet natural distinct", () -> info(nat.stream().distinct().spliterator()));
        t("list sorted peek", () -> info(Arrays.asList(2, 1).stream().sorted().peek(x -> {}).spliterator()) + " " + cmp(Arrays.asList(2, 1).stream().sorted().peek(x -> {}).spliterator()));
        t("list sorted(cmp) peek", () -> info(Arrays.asList(2, 1).stream().sorted(Comparator.reverseOrder()).peek(x -> {}).spliterator()) + " " + cmp(Arrays.asList(2, 1).stream().sorted(Comparator.reverseOrder()).peek(x -> {}).spliterator()));
        t("list sorted spliterator", () -> cmp(Arrays.asList(2, 1).stream().sorted().spliterator()));
        t("list sorted map spliterator", () -> cmp(Arrays.asList(2, 1).stream().sorted().map(x -> x).spliterator()));
        t("TreeSet comparator sorted getComparator", () -> { Comparator<? super Integer> c = rev.stream().sorted().spliterator().getComparator(); return c == null; });
        t("sorted(cmp) spliterator getComparator", () -> cmp(Arrays.asList(2, 1).stream().sorted(Comparator.reverseOrder()).spliterator()));
        t("TreeSet comparator sorted(cmp) peek getComparator", () -> cmp(rev.stream().sorted(Comparator.reverseOrder()).peek(x -> {}).spliterator()));
        t("TreeSet comparator getComparator same", () -> rev.spliterator().getComparator() == rev.comparator());
        t("TreeSet comparator stream getComparator same", () -> rev.stream().spliterator().getComparator() == rev.comparator());
        t("natural TreeSet sorted(cmp) getComparator", () -> cmp(nat.stream().sorted(Comparator.reverseOrder()).spliterator()));
        t("natural TreeSet sorted(cmp) peek getComparator", () -> cmp(nat.stream().sorted(Comparator.reverseOrder()).peek(x -> {}).spliterator()));
        t("natural TreeSet sorted peek getComparator", () -> cmp(nat.stream().sorted().peek(x -> {}).spliterator()));
        t("natural TreeSet sorted(cmp) sorted getComparator", () -> cmp(nat.stream().sorted(Comparator.reverseOrder()).sorted().spliterator()));
        t("natural TreeSet sorted(cmp) sorted flags", () -> info(nat.stream().sorted(Comparator.reverseOrder()).sorted().spliterator()));
        t("natural TreeSet sorted(cmp) sorted order", () -> nat.stream().sorted(Comparator.reverseOrder()).sorted().collect(Collectors.toList()));
        t("list sorted(cmp) sorted getComparator", () -> cmp(Arrays.asList(2, 1).stream().sorted(Comparator.reverseOrder()).sorted().spliterator()));
        t("list sorted sorted(cmp) getComparator", () -> cmp(Arrays.asList(2, 1).stream().sorted().sorted(Comparator.reverseOrder()).spliterator()));
        t("list sorted sorted(cmp) order", () -> Arrays.asList(2, 1, 3).stream().sorted().sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
        t("list sorted peek skip getComparator", () -> cmp(Arrays.asList(2, 1).stream().sorted().peek(x -> {}).skip(1).spliterator()));
        t("list sorted peek getComparator twice", () -> { Spliterator<Integer> sp = Arrays.asList(2, 1).stream().sorted().peek(x -> {}).spliterator(); return cmp(sp) + " " + cmp(sp); });
        t("claims SORTED sorted peek getComparator", () -> cmp(StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).sorted().peek(x -> {}).spliterator()));
        t("claims SORTED sorted(cmp) peek getComparator", () -> cmp(StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).sorted(Comparator.reverseOrder()).peek(x -> {}).spliterator()));
        t("claims SORTED peek getComparator", () -> cmp(StreamSupport.stream(claim(Spliterator.ORDERED | Spliterator.SORTED, 3, 1, 2), false).peek(x -> {}).spliterator()));
        t("natural TreeSet sorted(cmp) order", () -> nat.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
        t("comparator TreeSet sorted natural order", () -> rev.stream().sorted().collect(Collectors.toList()));
        t("comparator TreeSet sorted distinct", () -> rev.stream().sorted().distinct().collect(Collectors.toList()));
        t("comparator TreeSet sorted peek order", () -> rev.stream().peek(pk("a")).sorted().peek(pk("b")).findFirst());
    }
}
