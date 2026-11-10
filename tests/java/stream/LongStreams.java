package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Every method of LongStream, with its edge cases. */
public class LongStreams {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String l(LongStream s) { return Arrays.toString(s.toArray()); }
    static final long MAX = Long.MAX_VALUE, MIN = Long.MIN_VALUE;

    public static void main(String[] args) {
        t("range", () -> l(LongStream.range(3, 7)));
        t("range empty", () -> l(LongStream.range(5, 5)));
        t("range reversed", () -> l(LongStream.range(7, 3)));
        t("rangeClosed", () -> l(LongStream.rangeClosed(3, 7)));
        t("rangeClosed one", () -> l(LongStream.rangeClosed(-1, -1)));
        t("rangeClosed reversed", () -> l(LongStream.rangeClosed(7, 3)));
        t("rangeClosed to MAX", () -> l(LongStream.rangeClosed(MAX - 2, MAX)));
        t("range to MAX", () -> l(LongStream.range(MAX - 2, MAX)));
        t("rangeClosed from MIN", () -> l(LongStream.rangeClosed(MIN, MIN + 2)));
        t("rangeClosed MIN MIN", () -> l(LongStream.rangeClosed(MIN, MIN)));
        t("rangeClosed MAX MAX", () -> l(LongStream.rangeClosed(MAX, MAX)));
        t("range MIN MIN", () -> l(LongStream.range(MIN, MIN)));
        t("range huge size", () -> LongStream.range(MIN, MAX).spliterator().estimateSize());
        t("range huge exact size", () -> LongStream.range(MIN, MAX).spliterator().getExactSizeIfKnown());
        t("range small exact size", () -> LongStream.range(10, 15).spliterator().getExactSizeIfKnown());
        t("rangeClosed huge size", () -> LongStream.rangeClosed(MIN, MAX).spliterator().estimateSize());
        t("rangeClosed huge limit", () -> l(LongStream.rangeClosed(MIN, MAX).limit(2)));
        t("range huge limit", () -> l(LongStream.range(MIN, MAX).limit(2)));
        t("rangeClosed anyMatch at MAX", () -> LongStream.rangeClosed(MAX - 3, MAX).anyMatch(x -> x == MAX));
        t("rangeClosed iterator end", () -> { PrimitiveIterator.OfLong it = LongStream.rangeClosed(MAX - 1, MAX).iterator(); it.nextLong(); it.nextLong(); return it.hasNext(); });
        t("rangeClosed sum", () -> LongStream.rangeClosed(1, 100000).sum());
        t("range count", () -> LongStream.range(0, 123456).count());

        t("sum", () -> LongStream.of(1, 2, 3).sum());
        t("sum empty", () -> LongStream.empty().sum());
        t("sum wraps", () -> LongStream.of(MAX, 1).sum());
        t("average", () -> LongStream.of(1, 2, 4).average());
        t("average empty", () -> LongStream.empty().average());
        t("average wraps", () -> LongStream.of(MAX, MAX).average());
        t("average of one", () -> LongStream.of(MAX).average());
        t("min", () -> LongStream.of(3, -1, 2).min());
        t("max", () -> LongStream.of(3, -1, 2).max());
        t("min empty", () -> LongStream.empty().min());
        t("count", () -> LongStream.of(3, 4).count());
        t("summaryStatistics", () -> LongStream.of(4, -2, 10_000_000_000L).summaryStatistics());
        t("summaryStatistics empty", () -> LongStream.empty().summaryStatistics());
        t("summaryStatistics wraps", () -> LongStream.of(MAX, MAX).summaryStatistics());
        t("reduce op", () -> LongStream.of(2, 3, 4).reduce((a, b) -> a * b));
        t("reduce op empty", () -> LongStream.empty().reduce((a, b) -> a * b));
        t("reduce identity", () -> LongStream.of(2, 3, 4).reduce(100, (a, b) -> a - b));
        t("collect", () -> LongStream.of(1, 2).collect(StringBuilder::new, StringBuilder::append, StringBuilder::append));
        t("anyMatch", () -> LongStream.of(1, 2).anyMatch(x -> x > 1));
        t("allMatch", () -> LongStream.of(1, 2).allMatch(x -> x > 1));
        t("noneMatch", () -> LongStream.of(1, 2).noneMatch(x -> x > 5));
        t("anyMatch empty", () -> LongStream.empty().anyMatch(x -> true));
        t("allMatch empty", () -> LongStream.empty().allMatch(x -> false));
        t("findFirst", () -> LongStream.of(9, 8).findFirst());
        t("findAny", () -> LongStream.of(9, 8).findAny());
        t("findFirst empty", () -> LongStream.empty().findFirst());
        t("forEach", () -> { StringBuilder sb = new StringBuilder(); LongStream.of(1, 2, 3).forEach(sb::append); return sb; });
        t("forEachOrdered", () -> { StringBuilder sb = new StringBuilder(); LongStream.of(1, 2, 3).forEachOrdered(sb::append); return sb; });

        t("map", () -> l(LongStream.of(1, 2, 3).map(x -> x << 40)));
        t("map overflow", () -> l(LongStream.of(MAX).map(x -> x + 1)));
        t("mapToObj", () -> LongStream.of(65, 66).mapToObj(Long::toHexString).collect(Collectors.toList()));
        t("mapToInt truncates", () -> Arrays.toString(LongStream.of(1L << 32 | 5, -1).mapToInt(x -> (int) x).toArray()));
        t("mapToDouble", () -> LongStream.of(1, 2).mapToDouble(x -> x / 4.0).sum());
        t("asDoubleStream", () -> LongStream.of(-1, 2, MAX, (1L << 53) + 1).asDoubleStream().boxed().collect(Collectors.toList()));
        t("boxed", () -> LongStream.of(1, 2).boxed().collect(Collectors.toList()));
        t("boxed type", () -> LongStream.of(1).boxed().findFirst().get().getClass().getName());
        t("flatMap", () -> l(LongStream.of(1, 2).flatMap(x -> LongStream.range(0, x))));
        t("filter", () -> l(LongStream.range(0, 10).filter(x -> x % 2 == 1)));
        t("distinct", () -> l(LongStream.of(3, 1, 3, 2, 1, MAX, MAX).distinct()));
        t("sorted", () -> l(LongStream.of(3, -1, 2, MIN, MAX).sorted()));
        t("peek", () -> { StringBuilder sb = new StringBuilder(); LongStream.of(1, 2).peek(sb::append).sum(); return sb; });
        t("limit", () -> l(LongStream.iterate(1, x -> x * 10).limit(5)));
        t("skip", () -> l(LongStream.range(0, 6).skip(4)));
        t("limit/skip max", () -> l(LongStream.range(0, 6).skip(MAX).limit(MAX)));
        t("parallel", () -> LongStream.of(1).parallel().isParallel());
        t("sequential", () -> LongStream.of(1).parallel().sequential().isParallel());
        t("parallel sum", () -> LongStream.rangeClosed(1, 5000).parallel().sum());
        t("unordered", () -> l(LongStream.of(2, 1).unordered()));

        t("empty", () -> l(LongStream.empty()));
        t("of one", () -> l(LongStream.of(5)));
        t("of none", () -> l(LongStream.of()));
        t("of many", () -> l(LongStream.of(5, 6, 7)));
        t("iterate", () -> l(LongStream.iterate(10, x -> x - 3).limit(4)));
        t("generate", () -> { long[] n = {0}; return l(LongStream.generate(() -> n[0] += 2).limit(3)); });
        t("concat", () -> l(LongStream.concat(LongStream.of(1, 2), LongStream.of(3))));
        t("builder", () -> { LongStream.Builder b = LongStream.builder(); LongStream.Builder r = b.add(1).add(2); b.accept(3); return (r == b) + " " + l(b.build()); });
        t("builder empty", () -> l(LongStream.builder().build()));
        t("builder twice", () -> { LongStream.Builder b = LongStream.builder(); b.build(); return b.build(); });
        t("builder add after", () -> { LongStream.Builder b = LongStream.builder(); b.build(); return b.add(1); });
        t("builder as LongConsumer", () -> { LongStream.Builder b = LongStream.builder(); LongStream.of(4, 5).forEach(b); return l(b.build()); });

        t("iterator", () -> { PrimitiveIterator.OfLong i = LongStream.of(5, 6).iterator(); return i.nextLong() + " " + i.next() + " " + i.hasNext(); });
        t("iterator exhausted", () -> { PrimitiveIterator.OfLong i = LongStream.of(5).iterator(); i.nextLong(); return i.nextLong(); });
        t("iterator forEachRemaining LongConsumer", () -> { StringBuilder sb = new StringBuilder(); LongStream.of(1, 2, 3).iterator().forEachRemaining((LongConsumer) sb::append); return sb; });
        t("iterator forEachRemaining Consumer", () -> { StringBuilder sb = new StringBuilder(); LongStream.of(1, 2, 3).iterator().forEachRemaining((Consumer<Long>) sb::append); return sb; });
        t("iterator forEachRemaining null", () -> { LongStream.of(1).iterator().forEachRemaining((LongConsumer) null); return "no"; });
        t("iterator remove", () -> { PrimitiveIterator.OfLong i = LongStream.of(1).iterator(); i.nextLong(); i.remove(); return "no"; });
        t("spliterator", () -> { Spliterator.OfLong s = LongStream.of(5, 6).spliterator(); StringBuilder sb = new StringBuilder(); while (s.tryAdvance((LongConsumer) sb::append)) sb.append(','); return sb; });
        t("spliterator boxed", () -> { Spliterator.OfLong s = LongStream.of(5, 6).spliterator(); StringBuilder sb = new StringBuilder(); s.tryAdvance((Consumer<Long>) sb::append); s.forEachRemaining((Consumer<Long>) sb::append); return sb; });
    }
}
