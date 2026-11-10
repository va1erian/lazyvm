package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Every method of IntStream, with its edge cases. */
public class IntStreams {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String l(IntStream s) { return Arrays.toString(s.toArray()); }

    public static void main(String[] args) {
        // range and rangeClosed, including the ends of int
        t("range", () -> l(IntStream.range(3, 7)));
        t("range empty", () -> l(IntStream.range(5, 5)));
        t("range reversed", () -> l(IntStream.range(7, 3)));
        t("range negative", () -> l(IntStream.range(-2, 2)));
        t("rangeClosed", () -> l(IntStream.rangeClosed(3, 7)));
        t("rangeClosed one", () -> l(IntStream.rangeClosed(5, 5)));
        t("rangeClosed reversed", () -> l(IntStream.rangeClosed(7, 3)));
        t("rangeClosed to MAX", () -> l(IntStream.rangeClosed(Integer.MAX_VALUE - 2, Integer.MAX_VALUE)));
        t("range to MAX", () -> l(IntStream.range(Integer.MAX_VALUE - 2, Integer.MAX_VALUE)));
        t("rangeClosed from MIN", () -> l(IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MIN_VALUE + 2)));
        t("rangeClosed MIN MIN", () -> l(IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MIN_VALUE)));
        t("rangeClosed MAX MAX", () -> l(IntStream.rangeClosed(Integer.MAX_VALUE, Integer.MAX_VALUE)));
        t("range MIN MIN", () -> l(IntStream.range(Integer.MIN_VALUE, Integer.MIN_VALUE)));
        t("range MIN..MIN+2", () -> l(IntStream.range(Integer.MIN_VALUE, Integer.MIN_VALUE + 2)));
        t("range full size", () -> IntStream.range(Integer.MIN_VALUE, Integer.MAX_VALUE).spliterator().getExactSizeIfKnown());
        t("rangeClosed full size", () -> IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).spliterator().getExactSizeIfKnown());
        t("range full limit", () -> l(IntStream.range(Integer.MIN_VALUE, Integer.MAX_VALUE).limit(3)));
        t("rangeClosed full limit", () -> l(IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).limit(3)));
        t("rangeClosed full skip", () -> l(IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).skip(4294967293L)));
        t("rangeClosed full skip past", () -> l(IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).skip(4294967296L)));
        t("rangeClosed full iterator end", () -> { PrimitiveIterator.OfInt it = IntStream.rangeClosed(Integer.MAX_VALUE - 1, Integer.MAX_VALUE).iterator(); it.nextInt(); it.nextInt(); return it.hasNext(); });
        t("rangeClosed anyMatch at MAX", () -> IntStream.rangeClosed(Integer.MAX_VALUE - 5, Integer.MAX_VALUE).anyMatch(x -> x == Integer.MAX_VALUE));
        t("range sized skip limit", () -> l(IntStream.range(0, 1000).skip(990).limit(3)));
        t("range count", () -> IntStream.range(0, 1000000).count());
        t("rangeClosed sum", () -> IntStream.rangeClosed(1, 100).sum());

        // terminal operations
        t("sum", () -> IntStream.of(1, 2, 3).sum());
        t("sum empty", () -> IntStream.empty().sum());
        t("sum wraps", () -> IntStream.of(Integer.MAX_VALUE, 1).sum());
        t("average", () -> IntStream.of(1, 2, 4).average());
        t("average empty", () -> IntStream.empty().average());
        t("average large exact", () -> IntStream.of(Integer.MAX_VALUE, Integer.MAX_VALUE).average());
        t("average of one", () -> IntStream.of(-7).average());
        t("min", () -> IntStream.of(3, -1, 2).min());
        t("max", () -> IntStream.of(3, -1, 2).max());
        t("min empty", () -> IntStream.empty().min());
        t("max empty", () -> IntStream.empty().max());
        t("count", () -> IntStream.of(3, 4).count());
        t("summaryStatistics", () -> IntStream.of(4, -2, 10).summaryStatistics());
        t("summaryStatistics empty", () -> IntStream.empty().summaryStatistics());
        t("reduce op", () -> IntStream.of(2, 3, 4).reduce((a, b) -> a * b));
        t("reduce op empty", () -> IntStream.empty().reduce((a, b) -> a * b));
        t("reduce identity", () -> IntStream.of(2, 3, 4).reduce(10, (a, b) -> a - b));
        t("reduce identity empty", () -> IntStream.empty().reduce(10, (a, b) -> a - b));
        t("collect", () -> IntStream.of(1, 2).collect(StringBuilder::new, StringBuilder::append, StringBuilder::append));
        t("collect ArrayList", () -> IntStream.of(1, 2).collect(ArrayList<Integer>::new, ArrayList::add, ArrayList::addAll));
        t("anyMatch", () -> IntStream.of(1, 2).anyMatch(x -> x > 1));
        t("allMatch", () -> IntStream.of(1, 2).allMatch(x -> x > 1));
        t("noneMatch", () -> IntStream.of(1, 2).noneMatch(x -> x > 5));
        t("anyMatch empty", () -> IntStream.empty().anyMatch(x -> true));
        t("allMatch empty", () -> IntStream.empty().allMatch(x -> false));
        t("noneMatch empty", () -> IntStream.empty().noneMatch(x -> true));
        t("findFirst", () -> IntStream.of(9, 8).findFirst());
        t("findAny", () -> IntStream.of(9, 8).findAny());
        t("findFirst empty", () -> IntStream.empty().findFirst());
        t("forEach", () -> { StringBuilder sb = new StringBuilder(); IntStream.of(1, 2, 3).forEach(sb::append); return sb; });
        t("forEachOrdered", () -> { StringBuilder sb = new StringBuilder(); IntStream.of(1, 2, 3).forEachOrdered(sb::append); return sb; });
        t("toArray empty", () -> IntStream.empty().toArray().length);
        t("toArray filter", () -> l(IntStream.range(0, 10).filter(x -> x % 3 == 0)));

        // intermediate operations
        t("map", () -> l(IntStream.of(1, 2, 3).map(x -> x * x)));
        t("map overflow", () -> l(IntStream.of(Integer.MAX_VALUE).map(x -> x + 1)));
        t("mapToObj", () -> IntStream.of(65, 66).mapToObj(x -> "" + (char) x).collect(Collectors.toList()));
        t("mapToLong", () -> IntStream.of(Integer.MAX_VALUE).mapToLong(x -> x + 1L).sum());
        t("mapToDouble", () -> IntStream.of(1, 2).mapToDouble(x -> x / 4.0).sum());
        t("asLongStream", () -> IntStream.of(-1, 2).asLongStream().boxed().collect(Collectors.toList()));
        t("asDoubleStream", () -> IntStream.of(-1, 2).asDoubleStream().boxed().collect(Collectors.toList()));
        t("boxed", () -> IntStream.of(1, 2).boxed().collect(Collectors.toList()));
        t("boxed identity", () -> IntStream.of(127).boxed().findFirst().get() == (Integer) 127);
        t("flatMap", () -> l(IntStream.of(1, 2).flatMap(x -> IntStream.range(0, x))));
        t("filter", () -> l(IntStream.range(0, 10).filter(x -> x % 2 == 1)));
        t("distinct", () -> l(IntStream.of(3, 1, 3, 2, 1).distinct()));
        t("sorted", () -> l(IntStream.of(3, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE).sorted()));
        t("sorted empty", () -> l(IntStream.empty().sorted()));
        t("peek", () -> { StringBuilder sb = new StringBuilder(); IntStream.of(1, 2).peek(sb::append).sum(); return sb; });
        t("limit", () -> l(IntStream.iterate(1, x -> x * 2).limit(6)));
        t("skip", () -> l(IntStream.range(0, 6).skip(4)));
        t("limit 0", () -> l(IntStream.range(0, 6).limit(0)));
        t("skip past", () -> l(IntStream.range(0, 6).skip(10)));
        t("sequential", () -> IntStream.of(1).sequential().isParallel());
        t("parallel", () -> IntStream.of(1).parallel().isParallel());
        t("parallel sum", () -> IntStream.rangeClosed(1, 2000).parallel().sum());
        t("unordered", () -> l(IntStream.of(2, 1).unordered()));

        // factories
        t("empty", () -> l(IntStream.empty()));
        t("of one", () -> l(IntStream.of(5)));
        t("of none", () -> l(IntStream.of()));
        t("of many", () -> l(IntStream.of(5, 6, 7)));
        t("of array is not copied", () -> { int[] a = {1, 2}; IntStream s = IntStream.of(a); a[0] = 9; return l(s); });
        t("iterate", () -> l(IntStream.iterate(10, x -> x - 3).limit(4)));
        t("generate", () -> { int[] n = {0}; return l(IntStream.generate(() -> n[0] += 2).limit(3)); });
        t("concat", () -> l(IntStream.concat(IntStream.of(1, 2), IntStream.of(3))));
        t("concat empty", () -> l(IntStream.concat(IntStream.empty(), IntStream.empty())));
        t("builder", () -> { IntStream.Builder b = IntStream.builder(); IntStream.Builder r = b.add(1).add(2); b.accept(3); return (r == b) + " " + l(b.build()); });
        t("builder empty", () -> l(IntStream.builder().build()));
        t("builder twice", () -> { IntStream.Builder b = IntStream.builder(); b.build(); return b.build(); });
        t("builder add after", () -> { IntStream.Builder b = IntStream.builder(); b.build(); return b.add(1); });
        t("builder as IntConsumer", () -> { IntStream.Builder b = IntStream.builder(); IntStream.of(4, 5).forEach(b); return l(b.build()); });

        // iterator and spliterator
        t("iterator", () -> { PrimitiveIterator.OfInt i = IntStream.of(5, 6).iterator(); return i.nextInt() + " " + i.next() + " " + i.hasNext(); });
        t("iterator exhausted", () -> { PrimitiveIterator.OfInt i = IntStream.of(5).iterator(); i.nextInt(); return i.nextInt(); });
        t("iterator next exhausted", () -> { PrimitiveIterator.OfInt i = IntStream.of(5).iterator(); i.next(); return i.next(); });
        t("iterator forEachRemaining IntConsumer", () -> { StringBuilder sb = new StringBuilder(); IntStream.of(1, 2, 3).iterator().forEachRemaining((IntConsumer) sb::append); return sb; });
        t("iterator forEachRemaining Consumer", () -> { StringBuilder sb = new StringBuilder(); IntStream.of(1, 2, 3).iterator().forEachRemaining((Consumer<Integer>) sb::append); return sb; });
        t("iterator forEachRemaining null", () -> { IntStream.of(1).iterator().forEachRemaining((IntConsumer) null); return "no"; });
        t("iterator forEachRemaining Consumer null", () -> { IntStream.of(1).iterator().forEachRemaining((Consumer<Integer>) null); return "no"; });
        t("iterator remove", () -> { PrimitiveIterator.OfInt i = IntStream.of(1).iterator(); i.nextInt(); i.remove(); return "no"; });
        t("spliterator", () -> { Spliterator.OfInt s = IntStream.of(5, 6).spliterator(); StringBuilder sb = new StringBuilder(); while (s.tryAdvance((IntConsumer) sb::append)) sb.append(','); return sb; });
        t("spliterator boxed tryAdvance", () -> { Spliterator.OfInt s = IntStream.of(5, 6).spliterator(); StringBuilder sb = new StringBuilder(); s.tryAdvance((Consumer<Integer>) sb::append); s.forEachRemaining((Consumer<Integer>) sb::append); return sb; });
    }
}
