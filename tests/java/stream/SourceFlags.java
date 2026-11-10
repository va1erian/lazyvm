package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Characteristics and sizes of the map and set views, and of ranges and concatenations. */
public class SourceFlags {
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r);
    }
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    static String cmp(Spliterator<?> s) {
        if (!s.hasCharacteristics(Spliterator.SORTED)) return "";
        Comparator<?> c = s.getComparator();
        return c == null ? " natural" : " cmp";
    }
    static String info(Spliterator<?> s) { return names(s.characteristics()) + " est=" + s.estimateSize() + " exact=" + s.getExactSizeIfKnown() + cmp(s); }

    static TreeMap<Integer, String> tree() { TreeMap<Integer, String> m = new TreeMap<>(); for (int i = 1; i <= 5; i++) m.put(i, "v" + i); return m; }
    static TreeMap<Integer, String> treeCmp() { TreeMap<Integer, String> m = new TreeMap<>(Comparator.reverseOrder()); for (int i = 1; i <= 5; i++) m.put(i, "v" + i); return m; }
    static ConcurrentSkipListMap<Integer, String> skip() { ConcurrentSkipListMap<Integer, String> m = new ConcurrentSkipListMap<>(); for (int i = 1; i <= 5; i++) m.put(i, "v" + i); return m; }
    static ConcurrentSkipListMap<Integer, String> skipCmp() { ConcurrentSkipListMap<Integer, String> m = new ConcurrentSkipListMap<>(Comparator.<Integer>reverseOrder()); for (int i = 1; i <= 5; i++) m.put(i, "v" + i); return m; }
    static ConcurrentSkipListSet<Integer> skipSet() { return new ConcurrentSkipListSet<>(Arrays.asList(1, 2, 3, 4, 5)); }

    public static void main(String[] args) {
        // unmodifiable views delegate
        t("unmodifiableMap entrySet HashMap", () -> info(Collections.unmodifiableMap(new HashMap<>(tree())).entrySet().spliterator()));
        t("unmodifiableMap entrySet TreeMap", () -> info(Collections.unmodifiableMap(tree()).entrySet().spliterator()));
        t("unmodifiableMap entrySet stream", () -> info(Collections.unmodifiableMap(tree()).entrySet().stream().spliterator()));
        t("unmodifiableMap keySet TreeMap", () -> info(Collections.unmodifiableMap(tree()).keySet().spliterator()));
        t("unmodifiableMap values TreeMap", () -> info(Collections.unmodifiableMap(tree()).values().spliterator()));
        t("unmodifiableMap entrySet LinkedHashMap", () -> info(Collections.unmodifiableMap(new LinkedHashMap<>(tree())).entrySet().spliterator()));
        t("unmodifiableSortedMap entrySet", () -> info(Collections.unmodifiableSortedMap(tree()).entrySet().spliterator()));
        t("unmodifiableNavigableMap entrySet", () -> info(Collections.unmodifiableNavigableMap(tree()).entrySet().spliterator()));
        t("unmodifiableSet entrySet", () -> info(Collections.unmodifiableSet(tree().entrySet()).spliterator()));
        t("unmodifiableSortedSet", () -> info(Collections.unmodifiableSortedSet(new TreeSet<>(Arrays.asList(1, 2))).spliterator()));
        t("unmodifiableCollection TreeSet", () -> info(Collections.unmodifiableCollection(new TreeSet<>(Arrays.asList(1, 2))).spliterator()));
        t("unmodifiableList", () -> info(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(1, 2))).spliterator()));
        t("synchronizedMap entrySet", () -> info(Collections.synchronizedMap(new HashMap<>(tree())).entrySet().spliterator()));
        t("synchronizedSortedSet", () -> info(Collections.synchronizedSortedSet(new TreeSet<>(Arrays.asList(1, 2))).spliterator()));
        t("checkedSortedSet", () -> info(Collections.checkedSortedSet(new TreeSet<>(Arrays.asList(1, 2)), Integer.class).spliterator()));

        // TreeMap views: whole, descending, ranges
        t("TreeMap entrySet", () -> info(tree().entrySet().spliterator()));
        t("TreeMap keySet", () -> info(tree().keySet().spliterator()));
        t("TreeMap navigableKeySet", () -> info(tree().navigableKeySet().spliterator()));
        t("TreeMap values", () -> info(tree().values().spliterator()));
        t("TreeMap comparator entrySet", () -> info(treeCmp().entrySet().spliterator()));
        t("TreeMap comparator keySet", () -> info(treeCmp().keySet().spliterator()));
        t("TreeMap entrySet stream", () -> info(tree().entrySet().stream().spliterator()));
        t("TreeMap entrySet stream sorted natural", () -> { try { return info(tree().entrySet().stream().sorted().spliterator()); } catch (ClassCastException e) { return "CCE"; } });
        t("TreeMap descendingMap entries", () -> tree().descendingMap().entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toList()));
        t("TreeMap subMap entries", () -> tree().subMap(2, 4).entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toList()));
        t("TreeMap view estimate after change", () -> { TreeMap<Integer, String> m = tree(); Spliterator<Integer> sp = m.keySet().spliterator(); m.put(9, "x"); return sp.estimateSize(); });

        // ConcurrentSkipListMap views
        t("CSLM keySet entries", () -> skip().keySet().stream().collect(Collectors.toList()));
        t("CSLM descendingMap keys", () -> skip().descendingMap().keySet().stream().collect(Collectors.toList()));
        t("CSLM headMap keys", () -> skip().headMap(3).keySet().stream().collect(Collectors.toList()));
        t("CSLM headMap values", () -> skip().headMap(3).values().stream().collect(Collectors.toList()));
        t("CSLM subMap descending keys", () -> skip().subMap(2, 5).descendingMap().keySet().stream().collect(Collectors.toList()));

        // ConcurrentSkipListSet
        t("CSLS headSet elements", () -> skipSet().headSet(3).stream().collect(Collectors.toList()));
        t("CSLS descendingSet elements", () -> skipSet().descendingSet().stream().collect(Collectors.toList()));
        t("CSLS descendingSet headSet elements", () -> skipSet().descendingSet().headSet(3).stream().collect(Collectors.toList()));

        // ranges at the ends of the type
        t("long range MIN MAX", () -> info(LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).spliterator()));
        t("long rangeClosed MIN MAX", () -> info(LongStream.rangeClosed(Long.MIN_VALUE, Long.MAX_VALUE).spliterator()));
        t("long range MIN 0", () -> info(LongStream.range(Long.MIN_VALUE, 0).spliterator()));
        t("long range MIN 1", () -> info(LongStream.range(Long.MIN_VALUE, 1).spliterator()));
        t("long range 0 MAX", () -> info(LongStream.range(0, Long.MAX_VALUE).spliterator()));
        t("long range -1 MAX", () -> info(LongStream.range(-1, Long.MAX_VALUE).spliterator()));
        t("long rangeClosed 0 MAX", () -> info(LongStream.rangeClosed(0, Long.MAX_VALUE).spliterator()));
        t("long rangeClosed 1 MAX", () -> info(LongStream.rangeClosed(1, Long.MAX_VALUE).spliterator()));
        t("long rangeClosed MIN MIN", () -> info(LongStream.rangeClosed(Long.MIN_VALUE, Long.MIN_VALUE).spliterator()));
        t("long rangeClosed MAX MAX", () -> info(LongStream.rangeClosed(Long.MAX_VALUE, Long.MAX_VALUE).spliterator()));
        t("long range MIN MAX peek", () -> info(LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).peek(x -> {}).spliterator()));
        t("long range MIN MAX boxed", () -> info(LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).boxed().spliterator()));
        t("long range MIN MAX limit", () -> info(LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).limit(5).spliterator()));
        t("long range MIN MAX limit sum", () -> LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).limit(3).sum());
        t("long range MIN MAX iterator", () -> { PrimitiveIterator.OfLong it = LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).iterator(); return it.nextLong() + " " + it.nextLong(); });
        t("long range MIN MAX advance", () -> { Spliterator.OfLong sp = LongStream.range(Long.MIN_VALUE, Long.MAX_VALUE).spliterator(); sp.tryAdvance((LongConsumer) x -> {}); return info(sp); });
        t("long rangeClosed MIN MAX advance", () -> { Spliterator.OfLong sp = LongStream.rangeClosed(Long.MIN_VALUE, Long.MAX_VALUE).spliterator(); sp.tryAdvance((LongConsumer) x -> {}); return info(sp); });
        t("long range 0 MAX advance", () -> { Spliterator.OfLong sp = LongStream.range(0, Long.MAX_VALUE).spliterator(); sp.tryAdvance((LongConsumer) x -> {}); return info(sp); });
        t("long rangeClosed MIN MAX count prefix", () -> LongStream.rangeClosed(Long.MIN_VALUE, Long.MAX_VALUE).limit(10).count());
        t("int range MIN MAX", () -> info(IntStream.range(Integer.MIN_VALUE, Integer.MAX_VALUE).spliterator()));
        t("int rangeClosed MIN MAX", () -> info(IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).spliterator()));
        t("int rangeClosed MIN MAX advance", () -> { Spliterator.OfInt sp = IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).spliterator(); sp.tryAdvance((IntConsumer) x -> {}); return info(sp); });
        t("int rangeClosed MAX MAX", () -> info(IntStream.rangeClosed(Integer.MAX_VALUE, Integer.MAX_VALUE).spliterator()));
        t("int rangeClosed MAX MAX advance", () -> { Spliterator.OfInt sp = IntStream.rangeClosed(Integer.MAX_VALUE, Integer.MAX_VALUE).spliterator(); sp.tryAdvance((IntConsumer) x -> {}); return info(sp); });

        // concatenation
        t("concat flags", () -> info(Stream.concat(Stream.of(1, 2, 3), Stream.of(4, 5)).spliterator()));
        t("concat list flags", () -> info(Stream.concat(Arrays.asList(1, 2, 3).stream(), Arrays.asList(4, 5).stream()).spliterator()));
        t("concat set flags", () -> info(Stream.concat(new HashSet<>(Arrays.asList(1, 2)).stream(), new HashSet<>(Arrays.asList(3, 4)).stream()).spliterator()));
        t("concat tree flags", () -> info(Stream.concat(new TreeSet<>(Arrays.asList(1, 2)).stream(), new TreeSet<>(Arrays.asList(3, 4)).stream()).spliterator()));
        t("concat range flags", () -> info(IntStream.concat(IntStream.range(0, 3), IntStream.range(5, 7)).spliterator()));
        t("concat mixed flags", () -> info(Stream.concat(Stream.of(1, 2), Stream.iterate(1, x -> x + 1)).spliterator()));
        t("concat unordered flags", () -> info(Stream.concat(Stream.of(1, 2).unordered(), Stream.of(3)).spliterator()));
        t("concat empty flags", () -> info(Stream.concat(Stream.empty(), Stream.empty()).spliterator()));
        t("concat after advance", () -> {
            Spliterator<Integer> sp = Stream.concat(Stream.of(1, 2, 3), Stream.of(4, 5)).spliterator();
            StringBuilder sb = new StringBuilder(sp.estimateSize() + "");
            while (sp.tryAdvance(x -> {})) sb.append(' ').append(sp.estimateSize()).append('/').append(sp.getExactSizeIfKnown());
            return sb;
        });
        t("concat int after advance", () -> {
            Spliterator.OfInt sp = IntStream.concat(IntStream.of(1, 2), IntStream.of(4, 5, 6)).spliterator();
            StringBuilder sb = new StringBuilder(sp.estimateSize() + "");
            while (sp.tryAdvance((IntConsumer) x -> {})) sb.append(' ').append(sp.estimateSize()).append('/').append(sp.getExactSizeIfKnown());
            return sb;
        });
        t("concat long after advance", () -> {
            Spliterator.OfLong sp = LongStream.concat(LongStream.of(1), LongStream.of(4, 5)).spliterator();
            StringBuilder sb = new StringBuilder(sp.estimateSize() + "");
            while (sp.tryAdvance((LongConsumer) x -> {})) sb.append(' ').append(sp.estimateSize()).append('/').append(sp.getExactSizeIfKnown());
            return sb;
        });
        t("concat double after advance", () -> {
            Spliterator.OfDouble sp = DoubleStream.concat(DoubleStream.of(1), DoubleStream.of(4, 5)).spliterator();
            StringBuilder sb = new StringBuilder(sp.estimateSize() + "");
            while (sp.tryAdvance((DoubleConsumer) x -> {})) sb.append(' ').append(sp.estimateSize()).append('/').append(sp.getExactSizeIfKnown());
            return sb;
        });
        t("concat after forEachRemaining", () -> {
            Spliterator<Integer> sp = Stream.concat(Stream.of(1, 2, 3), Stream.of(4, 5)).spliterator();
            sp.tryAdvance(x -> {}); sp.forEachRemaining(x -> {});
            return info(sp);
        });
        t("concat unsized first", () -> {
            Spliterator<Integer> sp = Stream.concat(Stream.of(1, 2, 3).filter(x -> true), Stream.of(4, 5)).spliterator();
            StringBuilder sb = new StringBuilder(info(sp));
            while (sp.tryAdvance(x -> {})) sb.append(" | ").append(info(sp));
            return sb;
        });
        t("concat total MAX", () -> info(LongStream.concat(LongStream.range(0, Long.MAX_VALUE / 2), LongStream.range(0, Long.MAX_VALUE - Long.MAX_VALUE / 2)).spliterator()));
        t("concat total MAX plus one", () -> info(LongStream.concat(LongStream.range(0, Long.MAX_VALUE / 2 + 1), LongStream.range(0, Long.MAX_VALUE - Long.MAX_VALUE / 2)).spliterator()));
        t("concat total MAX minus one", () -> info(LongStream.concat(LongStream.range(0, Long.MAX_VALUE / 2 - 1), LongStream.range(0, Long.MAX_VALUE - Long.MAX_VALUE / 2)).spliterator()));
        t("concat MAX and one", () -> info(LongStream.concat(LongStream.range(0, Long.MAX_VALUE), LongStream.range(0, 1)).spliterator()));
        t("concat MAX and zero", () -> info(LongStream.concat(LongStream.range(0, Long.MAX_VALUE), LongStream.empty()).spliterator()));
        t("concat zero and MAX", () -> info(LongStream.concat(LongStream.empty(), LongStream.range(0, Long.MAX_VALUE)).spliterator()));
        t("concat MAX and MAX", () -> info(LongStream.concat(LongStream.range(0, Long.MAX_VALUE), LongStream.range(0, Long.MAX_VALUE)).spliterator()));
        t("concat MAX and MAX advance", () -> { Spliterator.OfLong sp = LongStream.concat(LongStream.range(0, Long.MAX_VALUE), LongStream.range(0, Long.MAX_VALUE)).spliterator(); sp.tryAdvance((LongConsumer) x -> {}); return info(sp); });
        t("concat int total large", () -> info(IntStream.concat(IntStream.range(Integer.MIN_VALUE, Integer.MAX_VALUE), IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE)).spliterator()));
        t("concat three", () -> info(Stream.concat(Stream.concat(Stream.of(1), Stream.of(2, 3)), Stream.of(4, 5, 6)).spliterator()));
        t("concat split", () -> { Spliterator<Integer> sp = Stream.concat(Stream.of(1, 2, 3), Stream.of(4, 5)).spliterator(); Spliterator<Integer> p = sp.trySplit(); return p == null ? "null" : info(p) + " | " + info(sp); });
        t("concat split elements", () -> { Spliterator<Integer> sp = Stream.concat(Stream.of(1, 2, 3), Stream.of(4, 5)).spliterator(); Spliterator<Integer> p = sp.trySplit(); StringBuilder sb = new StringBuilder(); if (p != null) p.forEachRemaining(sb::append); sb.append('|'); sp.forEachRemaining(sb::append); return sb; });
    }
}
