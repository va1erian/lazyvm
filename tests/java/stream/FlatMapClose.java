package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Close handlers of the inner streams of flatMap, a body exception together with a close
 * exception, and using a stage after its source was closed.
 */
public class FlatMapClose {
    static StringBuilder log = new StringBuilder();
    static Runnable h(String tag) { return () -> log.append(tag).append(' '); }
    static Runnable boom(String tag) { return () -> { log.append(tag).append(' '); throw new IllegalStateException("close " + tag); }; }
    static String desc(Throwable e) {
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "(" + e.getMessage() + ")"));
        for (Throwable s : e.getSuppressed()) sb.append(" +").append(desc(s));
        if (e.getCause() != null) sb.append(" <-").append(desc(e.getCause()));
        return sb.toString();
    }
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + desc(e); }
        System.out.println(label + ": " + r + " | " + log.toString().trim());
        log.setLength(0);
    }
    static RuntimeException body(String tag) { return new RuntimeException("body " + tag); }
    static <T> Consumer<T> pk(String tag) { return x -> log.append(tag).append(x).append(' '); }

    public static void main(String[] args) {
        // inner close handlers, nested flatMap and limit
        t("flatMap run", () -> Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x * 10).onClose(h("c" + x))).collect(Collectors.toList()));
        t("flatMap limit", () -> Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x * 10).onClose(h("c" + x))).limit(3).collect(Collectors.toList()));
        t("flatMap limit 1", () -> Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x * 10).onClose(h("c" + x))).limit(1).collect(Collectors.toList()));
        t("flatMap limit 2 peek", () -> Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x * 10).peek(pk("in")).onClose(h("c" + x))).peek(pk("out")).limit(2).collect(Collectors.toList()));
        t("nested flatMap", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).flatMap(y -> Stream.of(y, y * 10).onClose(h("c" + y))).onClose(h("C" + x))).collect(Collectors.toList()));
        t("nested flatMap limit", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).flatMap(y -> Stream.of(y, y * 10).onClose(h("c" + y))).onClose(h("C" + x))).limit(3).collect(Collectors.toList()));
        t("nested flatMap limit 1", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).flatMap(y -> Stream.of(y, y * 10).onClose(h("c" + y))).onClose(h("C" + x))).limit(1).collect(Collectors.toList()));
        t("nested flatMap findFirst", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).flatMap(y -> Stream.of(y, y * 10).onClose(h("c" + y))).onClose(h("C" + x))).findFirst());
        t("nested flatMap limit inside", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).flatMap(y -> Stream.of(y, y * 10).onClose(h("c" + y))).limit(3).onClose(h("C" + x))).collect(Collectors.toList()));
        t("flatMap empty inner", () -> Stream.of(1, 2).flatMap(x -> Stream.<Integer>empty().onClose(h("c" + x))).count());
        t("flatMap null inner", () -> Stream.of(1, 2).flatMap(x -> (Stream<Integer>) null).count());
        t("flatMap inner handler order", () -> Stream.of(1).flatMap(x -> Stream.of(x).onClose(h("a")).onClose(h("b"))).count());
        t("flatMap inner mapped", () -> Stream.of(1).flatMap(x -> Stream.of(x).onClose(h("a")).map(y -> y).onClose(h("b"))).count());
        t("IntStream flatMap close", () -> IntStream.of(1, 2).flatMap(x -> IntStream.of(x, x).onClose(h("c" + x))).sum());
        t("IntStream flatMap close limit", () -> IntStream.of(1, 2).flatMap(x -> IntStream.of(x, x).onClose(h("c" + x))).limit(1).sum());
        t("LongStream flatMap close", () -> LongStream.of(1, 2).flatMap(x -> LongStream.of(x, x).onClose(h("c" + x))).sum());
        t("DoubleStream flatMap close", () -> DoubleStream.of(1, 2).flatMap(x -> DoubleStream.of(x, x).onClose(h("c" + x))).sum());
        t("flatMapToInt close", () -> Stream.of(1, 2).flatMapToInt(x -> IntStream.of(x).onClose(h("c" + x))).sum());
        t("flatMapToLong close", () -> Stream.of(1, 2).flatMapToLong(x -> LongStream.of(x).onClose(h("c" + x))).sum());
        t("flatMapToDouble close", () -> Stream.of(1, 2).flatMapToDouble(x -> DoubleStream.of(x).onClose(h("c" + x))).sum());
        t("outer handler not run by flatMap", () -> Stream.of(1, 2).onClose(h("outer")).flatMap(x -> Stream.of(x).onClose(h("c" + x))).count());

        // the inner stream is closed when its own pipeline throws
        t("inner pipeline throws", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(h("c" + x)).map(y -> { throw body("m"); })).count());
        t("inner pipeline throws later", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).onClose(h("c" + x)).peek(y -> { if (y == 2) throw body("p"); })).count());
        t("downstream throws", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 1).onClose(h("c" + x))).peek(y -> { throw body("d"); }).count());
        t("IntStream inner pipeline throws", () -> IntStream.of(1, 2).flatMap(x -> IntStream.of(x).onClose(h("c" + x)).map(y -> { throw body("m"); })).sum());
        t("nested inner throws", () -> Stream.of(1).flatMap(x -> Stream.of(x).flatMap(y -> Stream.of(y).onClose(h("c" + y)).map(z -> { throw body("n"); })).onClose(h("C" + x))).count());

        // an inner stream that was used already
        t("inner already consumed", () -> { Stream<Integer> in = Stream.of(1, 2).onClose(h("used")); in.count(); return Stream.of(1).flatMap(x -> in).count(); });
        t("inner already consumed then close", () -> { Stream<Integer> in = Stream.of(1, 2).onClose(h("used")); in.count(); try { Stream.of(1).flatMap(x -> in).count(); } catch (IllegalStateException e) { log.append("caught "); } in.close(); return "-"; });
        t("inner already linked", () -> { Stream<Integer> in = Stream.of(1, 2).onClose(h("used")); in.map(y -> y); return Stream.of(1).flatMap(x -> in).count(); });
        t("same inner returned twice", () -> { Stream<Integer> in = Stream.of(1, 2).onClose(h("shared")); return Stream.of(1, 2).flatMap(x -> in).count(); });
        t("inner handler registered late", () -> Stream.of(1).flatMap(x -> { Stream<Integer> in = Stream.of(x); in.count(); return in.onClose(h("late")); }).count());

        // a body exception stays primary; close exceptions are suppressed into it
        t("flatMap body and close", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(boom("c" + x)).map(y -> { throw body("m" + y); })).count());
        t("flatMap body and two closes", () -> Stream.of(1).flatMap(x -> Stream.of(x).onClose(boom("a")).onClose(boom("b")).map(y -> { throw body("m"); })).count());
        t("flatMap body and close through peek", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(boom("c" + x))).peek(y -> { throw body("p" + y); }).count());
        t("flatMap body and close through limit", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x).onClose(boom("c" + x))).limit(5).peek(y -> { throw body("l" + y); }).count());
        t("flatMap body and close through peek limit", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x).onClose(boom("c" + x))).peek(pk("p")).limit(1).map(y -> { throw body("q" + y); }).count());
        t("flatMap close only", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(boom("c" + x))).count());
        t("flatMap close only limit", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x, x).onClose(boom("c" + x))).limit(1).count());
        t("flatMap close only peek", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(boom("c" + x))).peek(pk("p")).count());
        t("flatMap close only second", () -> Stream.of(1, 2).flatMap(x -> Stream.of(x).onClose(x == 2 ? boom("c2") : h("c1"))).count());
        t("flatMap close error", () -> Stream.of(1).flatMap(x -> Stream.of(x).onClose(() -> { throw new AssertionError("err"); })).count());
        t("flatMap body error and close", () -> Stream.of(1).flatMap(x -> Stream.of(x).onClose(boom("c")).map(y -> { throw new AssertionError("body err"); })).count());
        t("nested flatMap body and close", () -> Stream.of(1).flatMap(x -> Stream.of(x).flatMap(y -> Stream.of(y).onClose(boom("c" + y)).map(z -> { throw body("n"); })).onClose(boom("C" + x))).count());
        t("IntStream flatMap body and close", () -> IntStream.of(1).flatMap(x -> IntStream.of(x).onClose(boom("c")).map(y -> { throw body("m"); })).sum());
        t("LongStream flatMap body and close", () -> LongStream.of(1).flatMap(x -> LongStream.of(x).onClose(boom("c")).map(y -> { throw body("m"); })).sum());
        t("DoubleStream flatMap body and close", () -> DoubleStream.of(1).flatMap(x -> DoubleStream.of(x).onClose(boom("c")).map(y -> { throw body("m"); })).sum());
        t("flatMap iterator body and close", () -> Stream.of(1).flatMap(x -> Stream.of(x, x).onClose(boom("c")).map(y -> { throw body("m"); })).iterator().hasNext());
        t("flatMap spliterator close only", () -> Stream.of(1).flatMap(x -> Stream.of(x, x).onClose(boom("c"))).spliterator().tryAdvance(y -> {}));

        // try-with-resources and Stream.close
        t("twr body and close", () -> { try (Stream<Integer> s = Stream.of(1).onClose(boom("c"))) { throw body("t"); } });
        t("twr body and two closes", () -> { try (Stream<Integer> s = Stream.of(1).onClose(boom("a")).onClose(boom("b"))) { throw body("t"); } });
        t("twr terminal throws and close", () -> { try (Stream<Integer> s = Stream.of(1).onClose(boom("c"))) { return s.map(y -> { throw body("m"); }).count(); } });
        t("twr close only", () -> { try (Stream<Integer> s = Stream.of(1).onClose(boom("a")).onClose(boom("b"))) { return s.count(); } });
        t("twr concat body and close", () -> { try (Stream<Integer> s = Stream.concat(Stream.of(1).onClose(boom("a")), Stream.of(2).onClose(boom("b"))).onClose(boom("c"))) { return s.map(y -> { throw body("m"); }).count(); } });
        t("twr flatMap in twr", () -> { try (Stream<Integer> s = Stream.of(1).onClose(boom("outer")).flatMap(x -> Stream.of(x).onClose(boom("inner")).map(y -> { throw body("m"); }))) { return s.count(); } });
        t("twr IntStream body and close", () -> { try (IntStream s = IntStream.of(1).onClose(boom("c"))) { return s.map(y -> { throw body("m"); }).sum(); } });
        t("close same exception object", () -> { RuntimeException e = body("same"); try (Stream<Integer> s = Stream.of(1).onClose(() -> { throw e; })) { throw e; } });
        t("close suppressed already present", () -> {
            RuntimeException e = body("pre"); e.addSuppressed(new RuntimeException("old"));
            Stream<Integer> s = Stream.of(1).onClose(boom("a")).onClose(() -> { throw e; }).onClose(boom("b"));
            s.close(); return "-";
        });

        // close the source, then use the next stage
        t("close source then count", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.map(x -> x); s.close(); return m.count(); });
        t("close source then iterator", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.map(x -> x); s.close(); return m.iterator().hasNext(); });
        t("close source then spliterator", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.map(x -> x); s.close(); return m.spliterator().estimateSize(); });
        t("close source then map", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.map(x -> x); s.close(); return m.map(x -> x).count(); });
        t("close source then sorted", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.filter(x -> true); s.close(); return m.sorted().count(); });
        t("close source then collect", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.peek(x -> {}); s.close(); return m.collect(Collectors.toList()); });
        t("close source then close again", () -> { Stream<Integer> s = Stream.of(1, 2).onClose(h("a")); Stream<Integer> m = s.map(x -> x).onClose(h("b")); s.close(); m.close(); return "-"; });
        t("close middle then count", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> m = s.map(x -> x); Stream<Integer> l = m.filter(x -> true); m.close(); return l.count(); });
        t("close last then count", () -> { Stream<Integer> s = Stream.of(1, 2); Stream<Integer> l = s.map(x -> x); l.close(); return l.count(); });
        t("close then count", () -> { Stream<Integer> s = Stream.of(1, 2); s.close(); return s.count(); });
        t("close then map", () -> { Stream<Integer> s = Stream.of(1, 2); s.close(); return s.map(x -> x).count(); });
        t("close then iterator", () -> { Stream<Integer> s = Stream.of(1, 2); s.close(); return s.iterator().hasNext(); });
        t("close then onClose", () -> { Stream<Integer> s = Stream.of(1, 2); s.close(); s.onClose(h("late")); s.close(); return "-"; });
        t("close then parallel", () -> { Stream<Integer> s = Stream.of(1, 2); s.close(); return s.parallel().isParallel(); });
        t("close source then count after op", () -> { Stream<Integer> s = Stream.of(1, 2); s.count(); Stream<Integer> m = s.onClose(h("x")); m.close(); return "-"; });
        t("IntStream close source then sum", () -> { IntStream s = IntStream.of(1, 2); IntStream m = s.map(x -> x); s.close(); return m.sum(); });
        t("LongStream close source then sum", () -> { LongStream s = LongStream.of(1, 2); LongStream m = s.map(x -> x); s.close(); return m.sum(); });
        t("DoubleStream close source then sum", () -> { DoubleStream s = DoubleStream.of(1, 2); DoubleStream m = s.map(x -> x); s.close(); return m.sum(); });
        t("IntStream close then boxed", () -> { IntStream s = IntStream.of(1, 2); s.close(); return s.boxed().count(); });
        t("collection close source then count", () -> { Stream<Integer> s = Arrays.asList(1, 2).stream(); Stream<Integer> m = s.map(x -> x); s.close(); return m.count(); });
        t("range close source then count", () -> { IntStream s = IntStream.range(0, 3); IntStream m = s.map(x -> x); s.close(); return m.count(); });
        t("concat close source then count", () -> { Stream<Integer> s = Stream.concat(Stream.of(1), Stream.of(2)); Stream<Integer> m = s.map(x -> x); s.close(); return m.count(); });
        t("close source of sorted then count", () -> { Stream<Integer> s = Stream.of(2, 1); Stream<Integer> m = s.sorted(); Stream<Integer> n = m.map(x -> x); s.close(); return n.count(); });
    }
}
