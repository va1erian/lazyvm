package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** toArray when a spliterator's size is wrong, and when the size does not fit an array. */
public class ToArraySizes {
    static StringBuilder log = new StringBuilder();
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r + (log.length() == 0 ? "" : " | " + log.toString().trim()));
        log.setLength(0);
    }
    static List<Integer> ints(int n) { List<Integer> l = new ArrayList<>(); for (int i = 1; i <= n; i++) l.add(i); return l; }
    /** Claims SIZED with size `claim`, delivers `n` elements. */
    static Stream<Integer> liar(int claim, int n) {
        return StreamSupport.stream(Spliterators.spliterator(ints(n).iterator(), claim, Spliterator.ORDERED), false);
    }
    static Spliterator.OfInt intLiar(int claim, int n) {
        return Spliterators.spliterator(IntStream.rangeClosed(1, n).iterator(), claim, Spliterator.ORDERED);
    }
    static IntFunction<Integer[]> gen() { return n -> { log.append("gen" + n + " "); return new Integer[n]; }; }

    public static void main(String[] args) {
        // fewer elements than the claimed size
        t("short toArray", () -> Arrays.toString(liar(5, 2).toArray()));
        t("short toArray gen", () -> Arrays.toString(liar(5, 2).toArray(gen())));
        t("short map toArray", () -> Arrays.toString(liar(5, 2).map(x -> x).toArray()));
        t("short peek toArray", () -> Arrays.toString(liar(5, 2).peek(x -> log.append("p" + x + " ")).toArray()));
        t("short zero toArray", () -> Arrays.toString(liar(5, 0).toArray()));
        t("short one toArray", () -> Arrays.toString(liar(5, 4).toArray()));
        t("short filter toArray", () -> Arrays.toString(liar(5, 2).filter(x -> true).toArray()));
        t("short sorted toArray", () -> Arrays.toString(liar(5, 2).sorted().toArray()));
        t("short limit toArray", () -> Arrays.toString(liar(5, 2).limit(4).toArray()));
        t("short skip toArray", () -> Arrays.toString(liar(5, 2).skip(1).toArray()));
        t("short boxed toArray", () -> Arrays.toString(StreamSupport.intStream(intLiar(5, 2), false).boxed().toArray()));
        t("short int toArray", () -> Arrays.toString(StreamSupport.intStream(intLiar(5, 2), false).toArray()));
        t("short int map toArray", () -> Arrays.toString(StreamSupport.intStream(intLiar(5, 2), false).map(x -> x).toArray()));
        t("short long toArray", () -> Arrays.toString(StreamSupport.longStream(Spliterators.spliterator(LongStream.rangeClosed(1, 2).iterator(), 5, 0), false).toArray()));
        t("short double toArray", () -> Arrays.toString(StreamSupport.doubleStream(Spliterators.spliterator(DoubleStream.of(1, 2).iterator(), 5, 0), false).toArray()));
        t("short collect toList", () -> liar(5, 2).collect(Collectors.toList()));
        t("short count", () -> liar(5, 2).count());
        t("short forEach", () -> { liar(5, 2).forEach(x -> log.append("e" + x + " ")); return "-"; });
        t("short reduce", () -> liar(5, 2).reduce(0, Integer::sum));

        // more elements than the claimed size
        t("long toArray", () -> Arrays.toString(liar(2, 5).toArray()));
        t("long toArray gen", () -> Arrays.toString(liar(2, 5).toArray(gen())));
        t("long map toArray", () -> Arrays.toString(liar(2, 5).map(x -> x).toArray()));
        t("long peek toArray", () -> Arrays.toString(liar(2, 5).peek(x -> log.append("p" + x + " ")).toArray()));
        t("long one toArray", () -> Arrays.toString(liar(4, 5).toArray()));
        t("long zero claim toArray", () -> Arrays.toString(liar(0, 3).toArray()));
        t("long sorted toArray", () -> Arrays.toString(liar(2, 5).sorted().toArray()));
        t("long limit toArray", () -> Arrays.toString(liar(2, 5).limit(4).toArray()));
        t("long skip toArray", () -> Arrays.toString(liar(2, 5).skip(1).toArray()));
        t("long boxed toArray", () -> Arrays.toString(StreamSupport.intStream(intLiar(2, 5), false).boxed().toArray()));
        t("long int toArray", () -> Arrays.toString(StreamSupport.intStream(intLiar(2, 5), false).toArray()));
        t("long int map toArray", () -> Arrays.toString(StreamSupport.intStream(intLiar(2, 5), false).map(x -> x).toArray()));
        t("long long toArray", () -> Arrays.toString(StreamSupport.longStream(Spliterators.spliterator(LongStream.rangeClosed(1, 5).iterator(), 2, 0), false).toArray()));
        t("long double toArray", () -> Arrays.toString(StreamSupport.doubleStream(Spliterators.spliterator(DoubleStream.of(1, 2, 3).iterator(), 1, 0), false).toArray()));
        t("long collect toList", () -> liar(2, 5).collect(Collectors.toList()));
        t("long count", () -> liar(2, 5).count());
        t("long forEach", () -> { liar(2, 5).forEach(x -> log.append("e" + x + " ")); return "-"; });
        t("long iterator", () -> { Iterator<Integer> it = liar(2, 5).iterator(); int n = 0; while (it.hasNext()) { it.next(); n++; } return n; });
        t("exact toArray", () -> Arrays.toString(liar(3, 3).toArray()));

        // a generator that disagrees about the size, with a lying source
        t("short gen too big", () -> Arrays.toString(liar(5, 2).toArray(n -> new Integer[n + 1])));
        t("short gen too small", () -> Arrays.toString(liar(5, 2).toArray(n -> new Integer[1])));
        t("long gen too big", () -> Arrays.toString(liar(2, 5).toArray(n -> new Integer[n + 1])));

        // sizes that do not fit an array
        t("long range limit toArray", () -> LongStream.range(0, Long.MAX_VALUE).limit(3).toArray().length);
        t("long range limit boxed toArray", () -> LongStream.range(0, Long.MAX_VALUE).boxed().limit(3).toArray().length);
        t("long range filter limit toArray", () -> LongStream.range(0, Long.MAX_VALUE).filter(x -> true).limit(3).toArray().length);
        t("long range collect toList limit", () -> LongStream.range(0, Long.MAX_VALUE).boxed().limit(3).collect(Collectors.toList()));
        t("long range exact size", () -> LongStream.range(0, Long.MAX_VALUE).spliterator().getExactSizeIfKnown());
        t("int range 2G exact size", () -> IntStream.range(0, Integer.MAX_VALUE).spliterator().getExactSizeIfKnown());
        t("int range near limit count", () -> IntStream.rangeClosed(Integer.MAX_VALUE - 3, Integer.MAX_VALUE).toArray().length);

        // a supplier that claims SIZED over a spliterator whose size is unknown
        Supplier<Spliterator<Integer>> unsized = () -> { log.append("sup "); return Spliterators.spliteratorUnknownSize(ints(3).iterator(), Spliterator.ORDERED); };
        t("supplier claims SIZED toArray", () -> Arrays.toString(StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).toArray(gen())));
        t("supplier claims SIZED toArray plain", () -> Arrays.toString(StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).toArray()));
        t("supplier claims SIZED map toArray", () -> Arrays.toString(StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).map(x -> x).toArray(gen())));
        t("supplier claims SIZED count", () -> StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).count());
        t("supplier claims SIZED collect", () -> StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).collect(Collectors.toList()));
        t("supplier claims SIZED flags", () -> StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).spliterator().characteristics());
        t("supplier claims SIZED estimate", () -> StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).spliterator().estimateSize());
        t("supplier claims SIZED exact", () -> StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).spliterator().getExactSizeIfKnown());
        t("supplier claims SIZED iterator", () -> { Iterator<Integer> it = StreamSupport.stream(unsized, Spliterator.ORDERED | Spliterator.SIZED, false).iterator(); return it.next() + " " + it.next(); });
        t("supplier no claim toArray", () -> Arrays.toString(StreamSupport.stream(unsized, Spliterator.ORDERED, false).toArray(gen())));
        t("supplier int claims SIZED toArray", () -> Arrays.toString(StreamSupport.intStream(() -> Spliterators.spliteratorUnknownSize(IntStream.of(1, 2, 3).iterator(), Spliterator.ORDERED), Spliterator.ORDERED | Spliterator.SIZED, false).toArray()));
        t("supplier long claims SIZED toArray", () -> Arrays.toString(StreamSupport.longStream(() -> Spliterators.spliteratorUnknownSize(LongStream.of(1, 2, 3).iterator(), Spliterator.ORDERED), Spliterator.ORDERED | Spliterator.SIZED, false).toArray()));
        t("supplier double claims SIZED toArray", () -> Arrays.toString(StreamSupport.doubleStream(() -> Spliterators.spliteratorUnknownSize(DoubleStream.of(1, 2, 3).iterator(), Spliterator.ORDERED), Spliterator.ORDERED | Spliterator.SIZED, false).toArray()));
        t("supplier claims SIZED sized spliterator", () -> Arrays.toString(StreamSupport.stream(() -> ints(3).spliterator(), Spliterator.ORDERED | Spliterator.SIZED, false).toArray(gen())));
        t("supplier claims SIZED fewer", () -> Arrays.toString(StreamSupport.stream(() -> Spliterators.spliterator(ints(2).iterator(), 5, 0), Spliterator.ORDERED | Spliterator.SIZED, false).toArray(gen())));
    }
}
