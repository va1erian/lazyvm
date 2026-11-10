package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * iterator() and spliterator() of pipelines with flatMap, sorted and limit: when each
 * stage runs, and what a mixed tryAdvance / forEachRemaining traversal delivers.
 */
public class PullMode {
    static StringBuilder log = new StringBuilder();
    static <T> Consumer<T> pk(String tag) { return x -> log.append(tag).append(x).append(' '); }
    static <T, R> Function<T, R> mp(String tag, Function<T, R> f) { return x -> { log.append(tag).append(x).append(' '); return f.apply(x); }; }
    static <T> Predicate<T> ft(String tag, Predicate<T> p) { return x -> { log.append(tag).append(x).append(' '); return p.test(x); }; }
    static void step(String label, Object value) {
        System.out.println(label + ": " + value + " | " + log.toString().trim());
        log.setLength(0);
    }
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        step(label, r);
    }
    static Stream<Integer> inner(int x) {
        log.append("i").append(x).append(' ');
        return Stream.of(x * 10, x * 100, x * 1000).peek(pk("in"));
    }
    static String drain(Iterator<?> it) { StringBuilder sb = new StringBuilder(); it.forEachRemaining(x -> sb.append(x).append(',')); return sb.toString(); }
    static String drain(Spliterator<?> sp) { StringBuilder sb = new StringBuilder(); sp.forEachRemaining(x -> sb.append(x).append(',')); return sb.toString(); }

    public static void main(String[] args) {
        // one tryAdvance, then forEachRemaining, over flatMap: nothing may be lost or repeated
        t("flatMap spliterator tryAdvance then rest", () -> {
            Spliterator<Integer> sp = Stream.of(1, 2).peek(pk("o")).flatMap(PullMode::inner).spliterator();
            StringBuilder sb = new StringBuilder();
            sb.append(sp.tryAdvance(x -> sb.append("got").append(x).append(' '))).append(' ');
            step("  after first", sb);
            return sb.append(drain(sp));
        });
        t("flatMap iterator next then rest", () -> {
            Iterator<Integer> it = Stream.of(1, 2).peek(pk("o")).flatMap(PullMode::inner).iterator();
            int first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });
        t("flatMap iterator next hasNext next rest", () -> {
            Iterator<Integer> it = Stream.of(1, 2, 3).flatMap(PullMode::inner).iterator();
            int a = it.next(); boolean h = it.hasNext(); int b = it.next();
            return a + " " + h + " " + b + " then " + drain(it);
        });
        t("flatMap spliterator two tryAdvance then rest", () -> {
            Spliterator<Integer> sp = Stream.of(1, 2).flatMap(PullMode::inner).spliterator();
            List<Integer> got = new ArrayList<>();
            sp.tryAdvance(got::add); sp.tryAdvance(got::add);
            return got + " then " + drain(sp);
        });
        t("flatMap map iterator next then rest", () -> {
            Iterator<String> it = Stream.of(1, 2).flatMap(PullMode::inner).map(mp("m", x -> "s" + x)).iterator();
            String first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });
        t("flatMap filter spliterator tryAdvance then rest", () -> {
            Spliterator<Integer> sp = Stream.of(1, 2).flatMap(PullMode::inner).filter(ft("f", x -> x != 100)).spliterator();
            List<Integer> got = new ArrayList<>();
            sp.tryAdvance(got::add);
            step("  after first", got);
            return got + " then " + drain(sp);
        });
        t("flatMap peek iterator", () -> {
            Iterator<Integer> it = Stream.of(1, 2).flatMap(PullMode::inner).peek(pk("q")).iterator();
            it.hasNext();
            step("  after hasNext", "-");
            it.next(); it.next();
            step("  after two next", "-");
            return drain(it);
        });
        t("concat of flatMaps spliterator", () -> {
            Spliterator<Integer> sp = Stream.concat(Stream.of(1).flatMap(PullMode::inner), Stream.of(2).flatMap(PullMode::inner)).spliterator();
            List<Integer> got = new ArrayList<>();
            sp.tryAdvance(got::add);
            step("  after first", got);
            return got + " then " + drain(sp);
        });
        t("concat of flatMaps iterator", () -> {
            Iterator<Integer> it = Stream.concat(Stream.of(1).flatMap(PullMode::inner), Stream.of(2).flatMap(PullMode::inner)).iterator();
            int first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });
        t("flatMap limit iterator", () -> {
            Iterator<Integer> it = Stream.of(1, 2).peek(pk("o")).flatMap(PullMode::inner).limit(4).iterator();
            int first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });
        t("flatMap skip iterator", () -> {
            Iterator<Integer> it = Stream.of(1, 2).peek(pk("o")).flatMap(PullMode::inner).skip(1).iterator();
            int first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });
        t("flatMap distinct spliterator", () -> {
            Spliterator<Integer> sp = Stream.of(1, 1, 2).flatMap(x -> Stream.of(x, x)).distinct().spliterator();
            List<Integer> got = new ArrayList<>();
            sp.tryAdvance(got::add);
            return got + " then " + drain(sp);
        });
        t("IntStream flatMap spliterator", () -> {
            Spliterator.OfInt sp = IntStream.of(1, 2).peek(x -> log.append("o" + x + " ")).flatMap(x -> IntStream.of(x, x * 10, x * 100).peek(y -> log.append("in" + y + " "))).spliterator();
            StringBuilder sb = new StringBuilder();
            sp.tryAdvance((IntConsumer) x -> sb.append("got").append(x).append(' '));
            step("  after first", sb);
            sp.forEachRemaining((IntConsumer) x -> sb.append(x).append(','));
            return sb;
        });
        t("IntStream flatMap iterator", () -> {
            PrimitiveIterator.OfInt it = IntStream.of(1, 2).flatMap(x -> IntStream.of(x, x * 10, x * 100).peek(y -> log.append("in" + y + " "))).iterator();
            int first = it.nextInt();
            step("  after nextInt", first);
            StringBuilder sb = new StringBuilder().append(first).append(" then ");
            it.forEachRemaining((IntConsumer) x -> sb.append(x).append(','));
            return sb;
        });
        t("LongStream flatMap iterator", () -> {
            PrimitiveIterator.OfLong it = LongStream.of(1, 2).flatMap(x -> LongStream.of(x, x * 10)).iterator();
            long first = it.nextLong();
            StringBuilder sb = new StringBuilder().append(first).append(" then ");
            it.forEachRemaining((LongConsumer) x -> sb.append(x).append(','));
            return sb;
        });
        t("DoubleStream flatMap spliterator", () -> {
            Spliterator.OfDouble sp = DoubleStream.of(1, 2).flatMap(x -> DoubleStream.of(x, x / 2)).spliterator();
            List<Double> got = new ArrayList<>();
            sp.tryAdvance((DoubleConsumer) got::add);
            return got + " then " + drain(sp);
        });
        t("flatMapToInt iterator", () -> {
            PrimitiveIterator.OfInt it = Stream.of("ab", "c").flatMapToInt(String::chars).iterator();
            int first = it.nextInt();
            return first + " then " + drain(it);
        });
        t("flatMap null and empty inner", () -> {
            Iterator<Integer> it = Stream.of(1, 2, 3, 4).flatMap(x -> x == 1 ? null : x == 2 ? Stream.<Integer>empty() : Stream.of(x)).iterator();
            return it.hasNext() + " " + it.next() + " " + drain(it);
        });
        t("sorted flatMap iterator", () -> {
            Iterator<Integer> it = Stream.of(2, 1).peek(pk("o")).sorted().flatMap(PullMode::inner).iterator();
            int first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });
        t("flatMap sorted iterator", () -> {
            Iterator<Integer> it = Stream.of(2, 1).peek(pk("o")).flatMap(x -> Stream.of(x, x + 5)).sorted().iterator();
            int first = it.next();
            step("  after next", first);
            return first + " then " + drain(it);
        });

        // timing of the first hasNext / tryAdvance
        t("sorted peek iterator", () -> {
            Iterator<Integer> it = Stream.of(3, 1, 2).peek(pk("a")).sorted().peek(pk("b")).iterator();
            step("  created", "-");
            it.hasNext();
            step("  hasNext", "-");
            it.next();
            step("  next", "-");
            it.hasNext(); it.next();
            step("  hasNext next", "-");
            return drain(it);
        });
        t("sorted peek spliterator", () -> {
            Spliterator<Integer> sp = Stream.of(3, 1, 2).peek(pk("a")).sorted().peek(pk("b")).spliterator();
            step("  created", "-");
            sp.tryAdvance(x -> {});
            step("  tryAdvance", "-");
            sp.tryAdvance(x -> {});
            step("  tryAdvance", "-");
            return drain(sp);
        });
        t("flatMap map iterator hasNext", () -> {
            Iterator<String> it = Stream.of(1, 2).peek(pk("o")).flatMap(PullMode::inner).map(mp("m", x -> "s" + x)).iterator();
            it.hasNext();
            step("  hasNext", "-");
            it.hasNext();
            step("  hasNext again", "-");
            it.next(); it.next();
            step("  next next", "-");
            it.next();
            step("  next", "-");
            it.next();
            step("  next", "-");
            return drain(it);
        });
        t("flatMap map spliterator tryAdvance", () -> {
            Spliterator<String> sp = Stream.of(1, 2).peek(pk("o")).flatMap(PullMode::inner).map(mp("m", x -> "s" + x)).spliterator();
            sp.tryAdvance(x -> {});
            step("  tryAdvance 1", "-");
            sp.tryAdvance(x -> {});
            step("  tryAdvance 2", "-");
            sp.tryAdvance(x -> {});
            step("  tryAdvance 3", "-");
            sp.tryAdvance(x -> {});
            step("  tryAdvance 4", "-");
            return drain(sp);
        });
        t("flatMap limit 2 iterator", () -> {
            Iterator<Integer> it = Stream.of(1, 2, 3).peek(pk("o")).flatMap(PullMode::inner).limit(2).iterator();
            it.hasNext();
            step("  hasNext", "-");
            it.next(); it.next();
            step("  next next", "-");
            boolean h = it.hasNext();
            step("  hasNext at end", h);
            return "-";
        });
        t("flatMap limit 2 spliterator", () -> {
            Spliterator<Integer> sp = Stream.of(1, 2, 3).peek(pk("o")).flatMap(PullMode::inner).limit(2).spliterator();
            sp.tryAdvance(x -> {});
            step("  tryAdvance 1", "-");
            sp.tryAdvance(x -> {});
            step("  tryAdvance 2", "-");
            boolean h = sp.tryAdvance(x -> {});
            step("  tryAdvance 3", h);
            return "-";
        });
        t("sorted limit 0 iterator", () -> {
            Iterator<Integer> it = Stream.of(3, 1, 2).peek(pk("a")).sorted().limit(0).iterator();
            step("  created", "-");
            boolean h = it.hasNext();
            step("  hasNext", h);
            return "-";
        });
        t("sorted limit 0 spliterator", () -> {
            Spliterator<Integer> sp = Stream.of(3, 1, 2).peek(pk("a")).sorted().limit(0).spliterator();
            boolean h = sp.tryAdvance(x -> {});
            step("  tryAdvance", h);
            return "-";
        });
        t("sorted limit 0 forEach", () -> Stream.of(3, 1, 2).peek(pk("a")).sorted().limit(0).count());
        t("sorted limit 0 findFirst", () -> Stream.of(3, 1, 2).peek(pk("a")).sorted().limit(0).findFirst());
        t("limit 0 sorted count", () -> Stream.of(3, 1, 2).peek(pk("a")).limit(0).sorted().count());
        t("limit 0 count", () -> Stream.of(3, 1, 2).peek(pk("a")).limit(0).count());
        t("limit 0 map sorted iterator", () -> Stream.of(3, 1, 2).peek(pk("a")).limit(0).map(mp("m", x -> x)).sorted().iterator().hasNext());
        t("IntStream sorted limit 0", () -> IntStream.of(3, 1, 2).peek(x -> log.append("a" + x + " ")).sorted().limit(0).sum());
        t("IntStream limit 0", () -> IntStream.of(3, 1, 2).peek(x -> log.append("a" + x + " ")).limit(0).sum());
        t("LongStream sorted limit 0", () -> LongStream.of(3, 1, 2).peek(x -> log.append("a" + x + " ")).sorted().limit(0).sum());
        t("DoubleStream sorted limit 0", () -> DoubleStream.of(3, 1, 2).peek(x -> log.append("a" + x + " ")).sorted().limit(0).sum());

        // flatMap with close handlers, limit, then a barrier
        t("flatMap onClose limit sorted", () -> {
            List<Integer> r = Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x + 10).onClose(() -> log.append("close").append(x).append(' '))).limit(2).sorted().collect(Collectors.toList());
            step("  collected", r);
            return r;
        });
        t("flatMap onClose limit iterator", () -> {
            Iterator<Integer> it = Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x + 10).onClose(() -> log.append("close").append(x).append(' '))).limit(2).iterator();
            it.next();
            step("  next", "-");
            it.next();
            step("  next", "-");
            it.hasNext();
            step("  hasNext", "-");
            return "-";
        });
        t("flatMap onClose findFirst", () -> Stream.of(1, 2, 3).peek(pk("o")).flatMap(x -> Stream.of(x, x + 10).peek(pk("in")).onClose(() -> log.append("close").append(x).append(' '))).findFirst());
        t("nested flatMap findFirst", () -> Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x + 1).peek(pk("m")).flatMap(y -> Stream.of(y * 10, y * 100).peek(pk("n")).onClose(() -> log.append("c").append(y).append(' '))).onClose(() -> log.append("C").append(x).append(' '))).findFirst());
        t("nested flatMap iterator", () -> {
            Iterator<Integer> it = Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x + 1).peek(pk("m")).flatMap(y -> Stream.of(y * 10, y * 100).peek(pk("n")))).iterator();
            int first = it.next();
            step("  next", first);
            return first + " then " + drain(it);
        });
        t("nested flatMap anyMatch", () -> Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x + 1).peek(pk("m")).flatMap(y -> Stream.of(y * 10, y * 100).peek(pk("n")))).anyMatch(x -> x == 20));

        // CME in pull mode: the check comes at the next step
        t("list limit 1 peek add count", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            return l.stream().limit(1).peek(x -> l.add(9)).count();
        });
        t("list peek add limit 1 count", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            return l.stream().peek(x -> l.add(9)).limit(1).count();
        });
        t("list limit 1 peek add forEach", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            l.stream().limit(1).peek(x -> l.add(9)).forEach(x -> {});
            return l;
        });
        t("list limit 1 peek add iterator", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            Iterator<Integer> it = l.stream().limit(1).peek(x -> l.add(9)).iterator();
            return it.hasNext() + " " + it.next() + " " + l;
        });
        t("list limit 2 peek add iterator", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            Iterator<Integer> it = l.stream().limit(2).peek(x -> l.add(9)).iterator();
            return it.next() + " " + it.next() + " " + l;
        });
        t("set limit 1 peek add count", () -> {
            Set<Integer> s = new HashSet<>(Arrays.asList(1, 2, 3));
            return s.stream().limit(1).peek(x -> s.add(9)).count();
        });
        t("set limit 1 peek remove count", () -> {
            Set<Integer> s = new HashSet<>(Arrays.asList(1, 2, 3));
            return s.stream().limit(1).peek(x -> s.remove(3)).count();
        });
        t("list findFirst peek add", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            return l.stream().peek(x -> l.add(9)).findFirst();
        });
        t("list anyMatch add", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            return l.stream().anyMatch(x -> { l.add(9); return x == 1; });
        });
        t("list limit 2 add", () -> {
            List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3));
            return l.stream().peek(x -> { if (x == 1) l.add(9); }).limit(2).count();
        });
        t("linkedlist limit 1 peek add count", () -> {
            List<Integer> l = new LinkedList<>(Arrays.asList(1, 2, 3));
            return l.stream().limit(1).peek(x -> l.add(9)).count();
        });
        t("arraydeque limit 1 peek add count", () -> {
            Deque<Integer> l = new ArrayDeque<>(Arrays.asList(1, 2, 3));
            return l.stream().limit(1).peek(x -> l.add(9)).count();
        });
        t("treeset limit 1 peek add count", () -> {
            Set<Integer> l = new TreeSet<>(Arrays.asList(1, 2, 3));
            return l.stream().limit(1).peek(x -> l.add(9)).count();
        });
    }
}
