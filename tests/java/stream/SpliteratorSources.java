package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** The characteristics and sizes of the spliterators that collections, arrays and streams hand out. */
public class SpliteratorSources {
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    static void show(String label, Spliterator<?> s) {
        System.out.println(label + ": " + names(s.characteristics()) + " exact=" + s.getExactSizeIfKnown() + " est=" + s.estimateSize());
    }
    static <T> List<T> filled(List<T> l, T... xs) { l.addAll(Arrays.asList(xs)); return l; }
    @SafeVarargs static <T extends Collection<Integer>> T of(T c, Integer... xs) { c.addAll(Arrays.asList(xs)); return c; }
    static TreeMap<Integer, Integer> tm() { TreeMap<Integer, Integer> t = new TreeMap<>(); m(t); return t; }
    static Map<Integer, Integer> m(Map<Integer, Integer> m) { m.put(1, 1); m.put(2, 2); m.put(3, 3); return m; }

    public static void main(String[] args) {
        System.out.println("constants: " + Spliterator.ORDERED + " " + Spliterator.DISTINCT + " " + Spliterator.SORTED + " " + Spliterator.SIZED + " " + Spliterator.NONNULL + " " + Spliterator.IMMUTABLE + " " + Spliterator.CONCURRENT + " " + Spliterator.SUBSIZED);

        // collections
        show("ArrayList", of(new ArrayList<>(), 1, 2, 3).spliterator());
        show("ArrayList empty", new ArrayList<Integer>().spliterator());
        show("LinkedList", of(new LinkedList<>(), 1, 2, 3).spliterator());
        show("Vector", of(new Vector<>(), 1, 2, 3).spliterator());
        show("Stack", of(new Stack<>(), 1, 2, 3).spliterator());
        show("ArrayDeque", of(new ArrayDeque<>(), 1, 2, 3).spliterator());
        show("PriorityQueue", of(new PriorityQueue<>(), 3, 1, 2).spliterator());
        show("HashSet", of(new HashSet<>(), 1, 2, 3).spliterator());
        show("LinkedHashSet", of(new LinkedHashSet<>(), 1, 2, 3).spliterator());
        show("TreeSet", of(new TreeSet<>(), 1, 2, 3).spliterator());
        show("TreeSet comparator", of(new TreeSet<>(Comparator.<Integer>reverseOrder()), 1, 2, 3).spliterator());
        show("TreeSet headSet", of(new TreeSet<>(), 1, 2, 3).headSet(3).spliterator());
        show("TreeSet descendingSet", of(new TreeSet<>(), 1, 2, 3).descendingSet().spliterator());
        show("EnumSet", EnumSet.of(Thread.State.NEW, Thread.State.BLOCKED).spliterator());
        show("HashMap keySet", m(new HashMap<>()).keySet().spliterator());
        show("HashMap values", m(new HashMap<>()).values().spliterator());
        show("HashMap entrySet", m(new HashMap<>()).entrySet().spliterator());
        show("LinkedHashMap keySet", m(new LinkedHashMap<>()).keySet().spliterator());
        show("LinkedHashMap values", m(new LinkedHashMap<>()).values().spliterator());
        show("LinkedHashMap entrySet", m(new LinkedHashMap<>()).entrySet().spliterator());
        show("TreeMap keySet", m(new TreeMap<>()).keySet().spliterator());
        show("TreeMap descendingKeySet", tm().descendingKeySet().spliterator());
        show("TreeMap values", m(new TreeMap<>()).values().spliterator());
        show("TreeMap entrySet", m(new TreeMap<>()).entrySet().spliterator());
        show("Arrays.asList", Arrays.asList(1, 2, 3).spliterator());
        show("subList", of(new ArrayList<>(), 1, 2, 3, 4).subList(1, 3).spliterator());
        show("Collections.emptyList", Collections.emptyList().spliterator());
        show("Collections.emptySet", Collections.emptySet().spliterator());
        show("Collections.singletonList", Collections.singletonList(1).spliterator());
        show("Collections.singleton", Collections.singleton(1).spliterator());
        show("Collections.nCopies", Collections.nCopies(3, 1).spliterator());
        show("Collections.unmodifiableList", Collections.unmodifiableList(of(new ArrayList<>(), 1, 2)).spliterator());
        show("Collections.unmodifiableSet", Collections.unmodifiableSet(of(new TreeSet<>(), 1, 2)).spliterator());
        show("Collections.unmodifiableCollection", Collections.unmodifiableCollection(of(new ArrayList<>(), 1, 2)).spliterator());
        show("Collections.unmodifiableMap values", Collections.unmodifiableMap(m(new HashMap<>())).values().spliterator());
        show("Collections.unmodifiableMap keySet", Collections.unmodifiableMap(m(new TreeMap<>())).keySet().spliterator());
        show("AbstractList subclass", new AbstractList<Integer>() { public Integer get(int i) { return i; } public int size() { return 3; } }.spliterator());
        show("AbstractSet subclass", new AbstractSet<Integer>() { public Iterator<Integer> iterator() { return Arrays.asList(1, 2).iterator(); } public int size() { return 2; } }.spliterator());
        show("AbstractCollection subclass", new AbstractCollection<Integer>() { public Iterator<Integer> iterator() { return Arrays.asList(1, 2).iterator(); } public int size() { return 2; } }.spliterator());
        show("AbstractQueue subclass", new AbstractQueue<Integer>() { public Iterator<Integer> iterator() { return Arrays.asList(1, 2).iterator(); } public int size() { return 2; }
            public boolean offer(Integer e) { return false; } public Integer poll() { return null; } public Integer peek() { return null; } }.spliterator());
        show("Iterable default", ((Iterable<Integer>) () -> Arrays.asList(1, 2).iterator()).spliterator());

        // java.util.concurrent
        show("ConcurrentHashMap keySet", m(new ConcurrentHashMap<>()).keySet().spliterator());
        show("ConcurrentHashMap values", m(new ConcurrentHashMap<>()).values().spliterator());
        show("ConcurrentHashMap entrySet", m(new ConcurrentHashMap<>()).entrySet().spliterator());
        show("ConcurrentSkipListSet", of(new ConcurrentSkipListSet<>(), 1, 2, 3).spliterator());
        show("CopyOnWriteArrayList", of(new CopyOnWriteArrayList<>(), 1, 2, 3).spliterator());
        show("CopyOnWriteArraySet", of(new CopyOnWriteArraySet<>(), 1, 2, 3).spliterator());
        show("ConcurrentLinkedQueue", of(new ConcurrentLinkedQueue<>(), 1, 2, 3).spliterator());
        show("LinkedBlockingQueue", of(new LinkedBlockingQueue<>(), 1, 2, 3).spliterator());
        show("ArrayBlockingQueue", of(new ArrayBlockingQueue<>(5), 1, 2, 3).spliterator());

        // arrays and the array helpers
        show("Arrays.spliterator", Arrays.spliterator(new String[] {"a", "b", "c"}));
        show("Arrays.spliterator range", Arrays.spliterator(new String[] {"a", "b", "c"}, 1, 3));
        show("Arrays.spliterator int", Arrays.spliterator(new int[] {1, 2, 3}));
        show("Arrays.spliterator int range", Arrays.spliterator(new int[] {1, 2, 3}, 1, 2));
        show("Arrays.spliterator long", Arrays.spliterator(new long[] {1, 2, 3}));
        show("Arrays.spliterator double", Arrays.spliterator(new double[] {1, 2, 3}, 0, 0));
        show("Arrays.stream spliterator", Arrays.stream(new String[] {"a", "b"}).spliterator());
        show("Arrays.stream range spliterator", Arrays.stream(new String[] {"a", "b", "c"}, 1, 2).spliterator());
        show("Arrays.stream int", Arrays.stream(new int[] {1, 2}).spliterator());
        show("Arrays.stream long", Arrays.stream(new long[] {1, 2}).spliterator());
        show("Arrays.stream double", Arrays.stream(new double[] {1, 2}).spliterator());
        show("Arrays.asList stream", Arrays.asList(1, 2).stream().spliterator());

        // stream sources and what each operation does to the flags
        show("Stream.of one", Stream.of(1).spliterator());
        show("Stream.of many", Stream.of(1, 2, 3).spliterator());
        show("Stream.empty", Stream.empty().spliterator());
        show("Stream.builder", Stream.<Integer>builder().add(1).add(2).build().spliterator());
        show("Stream.builder one", Stream.<Integer>builder().add(1).build().spliterator());
        show("Stream.builder empty", Stream.<Integer>builder().build().spliterator());
        show("Stream.iterate", Stream.iterate(1, x -> x).spliterator());
        show("Stream.generate", Stream.generate(() -> 1).spliterator());
        show("Stream.concat", Stream.concat(Stream.of(1), Stream.of(2)).spliterator());
        show("Stream.concat unsized", Stream.concat(Stream.of(1), Stream.generate(() -> 1)).spliterator());
        show("ArrayList stream", of(new ArrayList<>(), 1, 2, 3).stream().spliterator());
        show("HashSet stream", of(new HashSet<>(), 1, 2, 3).stream().spliterator());
        show("TreeSet stream", of(new TreeSet<>(), 1, 2, 3).stream().spliterator());
        show("map", Stream.of(1, 2, 3).map(x -> x).spliterator());
        show("filter", Stream.of(1, 2, 3).filter(x -> true).spliterator());
        show("peek", Stream.of(1, 2, 3).peek(x -> {}).spliterator());
        show("sorted", Stream.of(3, 1, 2).sorted().spliterator());
        show("sorted cmp", Stream.of(3, 1, 2).sorted(Comparator.reverseOrder()).spliterator());
        show("sorted on TreeSet", of(new TreeSet<>(), 1, 2, 3).stream().sorted().spliterator());
        show("distinct", Stream.of(3, 1, 2).distinct().spliterator());
        show("distinct on HashSet", of(new HashSet<>(), 1, 2, 3).stream().distinct().spliterator());
        show("limit", Stream.of(3, 1, 2).limit(2).spliterator());
        show("limit beyond", Stream.of(3, 1, 2).limit(20).spliterator());
        show("skip", Stream.of(3, 1, 2).skip(1).spliterator());
        show("skip beyond", Stream.of(3, 1, 2).skip(20).spliterator());
        show("unordered", Stream.of(3, 1, 2).unordered().spliterator());
        show("unordered HashSet", of(new HashSet<>(), 1, 2, 3).stream().unordered().spliterator());
        show("flatMap", Stream.of(3, 1, 2).flatMap(Stream::of).spliterator());
        show("mapToInt", Stream.of(3, 1, 2).mapToInt(x -> x).spliterator());
        show("mapToInt sorted", Stream.of(3, 1, 2).mapToInt(x -> x).sorted().spliterator());
        show("boxed", IntStream.of(3, 1, 2).boxed().spliterator());
        show("boxed range", IntStream.range(0, 3).boxed().spliterator());
        show("map range", IntStream.range(0, 3).map(x -> x).spliterator());
        show("parallel", Stream.of(3, 1, 2).parallel().spliterator());
        show("sequential", Stream.of(3, 1, 2).sequential().spliterator());
        show("iterate limit", Stream.iterate(1, x -> x).limit(3).spliterator());
        show("generate limit", Stream.generate(() -> 1).limit(3).spliterator());
        show("sorted limit", Stream.of(3, 1, 2).sorted().limit(2).spliterator());
        show("filter sorted", Stream.of(3, 1, 2).filter(x -> true).sorted().spliterator());

        // primitive streams
        show("IntStream.of", IntStream.of(1, 2, 3).spliterator());
        show("IntStream.of one", IntStream.of(1).spliterator());
        show("IntStream.empty", IntStream.empty().spliterator());
        show("IntStream.range", IntStream.range(0, 3).spliterator());
        show("IntStream.range empty", IntStream.range(3, 3).spliterator());
        show("IntStream.rangeClosed", IntStream.rangeClosed(0, 3).spliterator());
        show("IntStream.iterate", IntStream.iterate(0, x -> x).spliterator());
        show("IntStream.generate", IntStream.generate(() -> 0).spliterator());
        show("IntStream.concat", IntStream.concat(IntStream.of(1), IntStream.range(0, 2)).spliterator());
        show("IntStream.builder", IntStream.builder().add(1).add(2).build().spliterator());
        show("IntStream sorted", IntStream.of(2, 1).sorted().spliterator());
        show("IntStream distinct", IntStream.of(2, 1).distinct().spliterator());
        show("IntStream map range", IntStream.range(0, 3).map(x -> x).spliterator());
        show("IntStream filter range", IntStream.range(0, 3).filter(x -> true).spliterator());
        show("IntStream limit range", IntStream.range(0, 3).limit(2).spliterator());
        show("LongStream.of", LongStream.of(1, 2, 3).spliterator());
        show("LongStream.range", LongStream.range(0, 3).spliterator());
        show("LongStream.rangeClosed", LongStream.rangeClosed(0, 3).spliterator());
        show("LongStream.empty", LongStream.empty().spliterator());
        show("LongStream.iterate", LongStream.iterate(0, x -> x).spliterator());
        show("DoubleStream.of", DoubleStream.of(1, 2, 3).spliterator());
        show("DoubleStream.empty", DoubleStream.empty().spliterator());
        show("DoubleStream.iterate", DoubleStream.iterate(0, x -> x).spliterator());
        show("DoubleStream.generate", DoubleStream.generate(() -> 0).spliterator());
        show("DoubleStream sorted", DoubleStream.of(2, 1).sorted().spliterator());
        show("DoubleStream.builder", DoubleStream.builder().add(1).build().spliterator());

        // text and other producers
        show("String chars", "abc".chars().spliterator());
        show("String codePoints", "abc".codePoints().spliterator());
        show("String empty chars", "".chars().spliterator());
        show("StringBuilder chars", new StringBuilder("abc").chars().spliterator());
        show("StringBuffer codePoints", new StringBuffer("abc").codePoints().spliterator());
        show("CharSequence chars", ((CharSequence) java.nio.CharBuffer.wrap("abc")).chars().spliterator());
        show("BitSet stream", BitSet.valueOf(new long[] {0b1011}).stream().spliterator());
        show("BitSet empty stream", new BitSet().stream().spliterator());
        show("Random ints", new Random(1).ints().spliterator());
        show("Random ints size", new Random(1).ints(5).spliterator());
        show("Random ints range", new Random(1).ints(5, 0, 10).spliterator());
        show("Random longs size", new Random(1).longs(5).spliterator());
        show("Random doubles size", new Random(1).doubles(5).spliterator());
        show("Random doubles", new Random(1).doubles().spliterator());
        show("Map entry stream", m(new TreeMap<>()).entrySet().stream().spliterator());

        // sorted spliterators and comparators
        t("comparator natural", () -> of(new TreeSet<>(), 1).spliterator().getComparator());
        t("comparator custom same", () -> { Comparator<Integer> c = Comparator.reverseOrder(); return of(new TreeSet<>(c), 1).spliterator().getComparator() == c; });
        t("comparator unsorted", () -> of(new ArrayList<>(), 1).spliterator().getComparator());
        t("comparator unsorted stream", () -> Stream.of(1, 2).spliterator().getComparator());
        t("comparator sorted stream", () -> Stream.of(1, 2).sorted().spliterator().getComparator());
        t("comparator sorted stream custom", () -> { Comparator<Integer> c = Comparator.reverseOrder(); return Stream.of(1, 2).sorted(c).spliterator().getComparator() == c; });
        t("comparator range", () -> IntStream.range(0, 3).spliterator().getComparator());
        t("comparator IntStream sorted", () -> IntStream.of(2, 1).sorted().spliterator().getComparator());
        t("comparator Arrays.spliterator", () -> Arrays.spliterator(new String[] {"a"}).getComparator());
        t("hasCharacteristics", () -> { Spliterator<Integer> s = of(new ArrayList<>(), 1).spliterator(); return s.hasCharacteristics(Spliterator.SIZED | Spliterator.ORDERED) + " " + s.hasCharacteristics(Spliterator.SORTED) + " " + s.hasCharacteristics(0) + " " + s.hasCharacteristics(Spliterator.SORTED | Spliterator.SIZED); });
    }

    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
}
