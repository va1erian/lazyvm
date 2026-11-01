package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** skip(0) hands back the stream it was called on: identity, linking, flags and what runs. */
public class SkipZero {
    static StringBuilder log = new StringBuilder();
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

    public static void main(String[] args) {
        t("Stream same", () -> { Stream<Integer> s = Stream.of(1, 2, 3); return s.skip(0) == s; });
        t("IntStream same", () -> { IntStream s = IntStream.of(1, 2, 3); return s.skip(0) == s; });
        t("LongStream same", () -> { LongStream s = LongStream.of(1, 2, 3); return s.skip(0) == s; });
        t("DoubleStream same", () -> { DoubleStream s = DoubleStream.of(1, 2, 3); return s.skip(0) == s; });
        t("skip 1 not same", () -> { Stream<Integer> s = Stream.of(1, 2, 3); return s.skip(1) == s; });
        t("limit max not same", () -> { Stream<Integer> s = Stream.of(1, 2, 3); return s.limit(Long.MAX_VALUE) == s; });
        t("collection same", () -> { Stream<Integer> s = Arrays.asList(1, 2).stream(); return s.skip(0) == s; });
        t("mapped same", () -> { Stream<Integer> s = Stream.of(1, 2).map(x -> x); return s.skip(0) == s; });
        t("sorted same", () -> { Stream<Integer> s = Stream.of(2, 1).sorted(); return s.skip(0) == s; });
        t("range same", () -> { IntStream s = IntStream.range(0, 3); return s.skip(0) == s; });
        t("parallel same", () -> { Stream<Integer> s = Stream.of(1, 2).parallel(); return s.skip(0) == s; });
        t("skip 0 twice same", () -> { Stream<Integer> s = Stream.of(1, 2); return s.skip(0).skip(0) == s; });

        // a stream that was only skip(0)'d is not linked
        t("Stream still usable", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.skip(0); return s.count(); });
        t("IntStream still usable", () -> { IntStream s = IntStream.of(1, 2, 3); s.skip(0); return s.sum(); });
        t("LongStream still usable", () -> { LongStream s = LongStream.of(1, 2, 3); s.skip(0); return s.sum(); });
        t("DoubleStream still usable", () -> { DoubleStream s = DoubleStream.of(1, 2, 3); s.skip(0); return s.sum(); });
        t("skip 0 then count", () -> Stream.of(1, 2, 3).peek(x -> log.append("p" + x + " ")).skip(0).count());
        t("skip 0 then int count", () -> IntStream.of(1, 2, 3).peek(x -> log.append("p" + x + " ")).skip(0).count());
        t("skip 0 then long count", () -> LongStream.of(1, 2, 3).peek(x -> log.append("p" + x + " ")).skip(0).count());
        t("skip 0 then double count", () -> DoubleStream.of(1, 2, 3).peek(x -> log.append("p" + x + " ")).skip(0).count());
        t("skip 0 then count and again", () -> { Stream<Integer> s = Stream.of(1, 2, 3); Stream<Integer> k = s.skip(0); k.count(); return k.count(); });
        t("skip 0 count then original", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.skip(0).count(); return s.count(); });
        t("skip 0 then map then original", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.skip(0).map(x -> x); return s.count(); });

        // skip(0) on a stream that is linked or consumed
        t("consumed skip 0", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.count(); return s.skip(0) == s; });
        t("consumed skip 0 then count", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.count(); return s.skip(0).count(); });
        t("consumed int skip 0", () -> { IntStream s = IntStream.of(1, 2, 3); s.sum(); return s.skip(0) == s; });
        t("consumed long skip 0", () -> { LongStream s = LongStream.of(1, 2, 3); s.sum(); return s.skip(0) == s; });
        t("consumed double skip 0", () -> { DoubleStream s = DoubleStream.of(1, 2, 3); s.sum(); return s.skip(0) == s; });
        t("linked skip 0", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.map(x -> x); return s.skip(0) == s; });
        t("linked skip 0 then count", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.map(x -> x); return s.skip(0).count(); });
        t("linked int skip 0", () -> { IntStream s = IntStream.of(1, 2, 3); s.map(x -> x); return s.skip(0) == s; });
        t("linked long skip 0", () -> { LongStream s = LongStream.of(1, 2, 3); s.map(x -> x); return s.skip(0) == s; });
        t("linked double skip 0", () -> { DoubleStream s = DoubleStream.of(1, 2, 3); s.map(x -> x); return s.skip(0) == s; });
        t("closed skip 0", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.close(); return s.skip(0) == s; });
        t("iterated skip 0", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.iterator(); return s.skip(0) == s; });
        t("skip 1 on consumed", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.count(); return s.skip(1); });
        t("skip -1 on consumed", () -> { Stream<Integer> s = Stream.of(1, 2, 3); s.count(); return s.skip(-1); });
        t("skip -1 fresh", () -> Stream.of(1, 2, 3).skip(-1));
        t("skip 0 handler", () -> { Stream<Integer> s = Stream.of(1).onClose(() -> log.append("closed ")); Stream<Integer> k = s.skip(0); k.close(); return k == s; });
        t("skip 0 handlers on result", () -> { Stream<Integer> s = Stream.of(1); s.skip(0).onClose(() -> log.append("closed ")); s.close(); return "-"; });

        // flags are those of the stream itself
        t("list flags", () -> info(Arrays.asList(1, 2, 3).stream().skip(0).spliterator()));
        t("list flags skip 1", () -> info(Arrays.asList(1, 2, 3).stream().skip(1).spliterator()));
        t("list flags limit", () -> info(Arrays.asList(1, 2, 3).stream().skip(0).limit(2).spliterator()));
        t("array flags", () -> info(Stream.of(1, 2, 3).skip(0).spliterator()));
        t("set flags", () -> info(new HashSet<>(Arrays.asList(1, 2, 3)).stream().skip(0).spliterator()));
        t("TreeSet flags", () -> info(new TreeSet<>(Arrays.asList(1, 2, 3)).stream().skip(0).spliterator()));
        t("range flags", () -> info(IntStream.range(0, 4).skip(0).spliterator()));
        t("long range flags", () -> info(LongStream.range(0, 4).skip(0).spliterator()));
        t("sorted flags", () -> info(Stream.of(3, 1, 2).sorted().skip(0).spliterator()));
        t("filter flags", () -> info(Stream.of(3, 1, 2).filter(x -> true).skip(0).spliterator()));
        t("double array flags", () -> info(DoubleStream.of(1, 2).skip(0).spliterator()));
        t("same spliterator source", () -> { List<Integer> l = Arrays.asList(1, 2, 3); Spliterator<Integer> sp = l.stream().skip(0).spliterator(); return sp.getClass() == l.stream().spliterator().getClass(); });
        t("empty skip 0", () -> info(Stream.empty().skip(0).spliterator()));
        t("count after skip 0 sized", () -> Arrays.asList(1, 2, 3).stream().peek(x -> log.append("p" + x + " ")).skip(0).count());
        t("iterator after skip 0", () -> { Iterator<Integer> it = Stream.of(1, 2).skip(0).iterator(); return it.next() + " " + it.next() + " " + it.hasNext(); });
        t("limit 0 skip 0", () -> Stream.of(1, 2).limit(0).skip(0).count());
        t("skip huge then skip 0", () -> Stream.of(1, 2).skip(Long.MAX_VALUE).skip(0).count());
        t("sequential same", () -> { Stream<Integer> s = Stream.of(1, 2); return s.sequential() == s && s.parallel() == s && s.unordered() != s; });
        t("onClose same", () -> { Stream<Integer> s = Stream.of(1, 2); return s.onClose(() -> {}) == s; });
    }
}
