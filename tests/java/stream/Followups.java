package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Cases found by the second review: sizes across stages that change the count, stepping after a first step,
 * closing a source under way, changes during a walk, and a few traces and flags that were not pinned.
 */
public class Followups {
    static StringBuilder log = new StringBuilder();
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        String l = log.toString().trim();
        log.setLength(0);
        System.out.println(label + ": " + r + (l.isEmpty() ? "" : " | " + l));
    }
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    static <T> Consumer<T> pk(String tag) { return x -> log.append(tag).append(x).append(' '); }
    @SuppressWarnings("unchecked")
    static <T> List<Object> distinctSorted(Object... a) {
        return StreamSupport.stream(Spliterators.spliterator(a, Spliterator.ORDERED | Spliterator.SORTED), false).distinct().collect(Collectors.toList());
    }

    public static void main(String[] args) {
        // F1: a count-changing stage hides the size a sort announced
        t("sorted flatMap sorted", () -> Stream.of(2, 1).sorted().flatMap(x -> Stream.of(x, x + 10)).sorted().collect(Collectors.toList()));
        t("sorted flatMap sorted cmp", () -> Stream.of(2, 1).sorted().flatMap(x -> Stream.of(x, x + 10)).sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
        t("sorted flatMapToInt sorted", () -> Stream.of(2, 1).sorted().flatMapToInt(x -> IntStream.of(x, x + 10)).sorted().boxed().collect(Collectors.toList()));
        t("int sorted flatMap sorted", () -> Arrays.toString(IntStream.of(2, 1).sorted().flatMap(x -> IntStream.of(x, x + 10)).sorted().toArray()));
        t("long sorted flatMap sorted", () -> Arrays.toString(LongStream.of(2, 1).sorted().flatMap(x -> LongStream.of(x, x + 10)).sorted().toArray()));
        t("double sorted flatMap sorted", () -> Arrays.toString(DoubleStream.of(2, 1).sorted().flatMap(x -> DoubleStream.of(x, x + 10)).sorted().toArray()));
        t("sorted flatMap sorted iterator", () -> { Iterator<Integer> it = Stream.of(2, 1).sorted().flatMap(x -> Stream.of(x, x + 10)).sorted().iterator(); List<Integer> r = new ArrayList<>(); while (it.hasNext()) r.add(it.next()); return r; });
        t("sorted filter sorted", () -> Stream.of(3, 2, 1).sorted().filter(x -> x > 1).sorted().collect(Collectors.toList()));
        t("sorted limit sorted", () -> Stream.of(3, 2, 1).sorted().limit(2).sorted().collect(Collectors.toList()));
        t("sorted skip sorted", () -> Stream.of(3, 2, 1).sorted().skip(1).sorted().collect(Collectors.toList()));
        t("sorted distinct sorted", () -> Stream.of(3, 3, 1).sorted().distinct().sorted().collect(Collectors.toList()));
        t("sorted flatMap sorted toArray", () -> Arrays.toString(Stream.of(2, 1).sorted().flatMap(x -> Stream.of(x, x + 10)).sorted().toArray()));
        t("flatMap sorted several outer", () -> Stream.of(5, 3, 4, 1, 2).flatMap(x -> Stream.of(x, x * 10)).sorted().collect(Collectors.toList()));
        t("flatMap sorted peek", () -> Stream.of(2, 1).flatMap(x -> Stream.of(x, x + 10)).sorted().peek(pk("p")).collect(Collectors.toList()));

        // F2: after one step, forEachRemaining goes on stepping
        t("step then forEachRemaining", () -> {
            Spliterator<Integer> sp = Stream.of(1, 2).flatMap(x -> Stream.of(x, x * 10).onClose(() -> log.append("c" + x + " "))).peek(pk("p")).spliterator();
            sp.tryAdvance(x -> log.append("t" + x + " "));
            sp.forEachRemaining(x -> log.append("r" + x + " "));
            return "-";
        });
        t("no step then forEachRemaining", () -> {
            Spliterator<Integer> sp = Stream.of(1, 2).flatMap(x -> Stream.of(x, x * 10).onClose(() -> log.append("c" + x + " "))).peek(pk("p")).spliterator();
            sp.forEachRemaining(x -> log.append("r" + x + " "));
            return "-";
        });
        t("iterator next then forEachRemaining", () -> {
            Iterator<Integer> it = Stream.of(1, 2).peek(pk("o")).flatMap(x -> Stream.of(x, x * 10)).peek(pk("p")).iterator();
            it.next();
            it.forEachRemaining(x -> log.append("r" + x + " "));
            return "-";
        });

        // F3: sorted(null) links first, then fails
        t("sorted null empty", () -> Stream.empty().sorted(null));
        t("sorted null used", () -> { Stream<Integer> s = Stream.of(1); s.count(); return s.sorted(null); });
        t("sorted null then reuse", () -> { Stream<Integer> s = Stream.of(1); try { s.sorted(null); } catch (NullPointerException e) { log.append("npe "); } return s.count(); });

        // F4: the size known at the sort stage decides
        t("long range boxed sorted limit", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().limit(3).count());
        t("long range boxed filter sorted", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().filter(x -> x > 5).count());
        t("long range boxed sorted skip", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().skip(3).count());
        t("long range boxed skip sorted", () -> LongStream.range(0, Long.MAX_VALUE).boxed().skip(1).sorted().findFirst());
        t("long range boxed sorted limit toArray", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().limit(3).toArray().length);

        // F5: closing the source stage does not break work under way
        t("close source under way", () -> { Stream<Integer> s = Stream.of(1, 2, 3); Iterator<Integer> i = s.map(x -> x).iterator(); int a = i.next(); s.close(); return a + " " + i.next() + " " + i.next() + " " + i.hasNext(); });
        t("close source under way spliterator", () -> { Stream<Integer> s = Stream.of(1, 2, 3); Spliterator<Integer> sp = s.map(x -> x).spliterator(); sp.tryAdvance(x -> {}); s.close(); StringBuilder b = new StringBuilder(); sp.forEachRemaining(b::append); return b; });

        // F6: an array spliterator moves on before it delivers
        t("array tryAdvance throws", () -> { Spliterator<Integer> sp = Arrays.asList(1, 2, 3).spliterator(); try { sp.tryAdvance(x -> { throw new RuntimeException("x"); }); } catch (RuntimeException e) { log.append("caught "); } StringBuilder b = new StringBuilder(); sp.forEachRemaining(b::append); return b; });
        t("array forEachRemaining throws", () -> { Spliterator<Integer> sp = Arrays.asList(1, 2, 3).spliterator(); try { sp.forEachRemaining(x -> { throw new RuntimeException("x"); }); } catch (RuntimeException e) { log.append("caught "); } StringBuilder b = new StringBuilder(); sp.forEachRemaining(b::append); return b.length() + " " + sp.estimateSize(); });
        t("array nested tryAdvance", () -> { Spliterator<Integer> sp = Arrays.asList(1, 2, 3).spliterator(); StringBuilder b = new StringBuilder(); sp.tryAdvance(x -> { b.append(x); sp.tryAdvance(y -> b.append(y)); }); sp.forEachRemaining(b::append); return b; });
        t("int array tryAdvance throws", () -> { Spliterator.OfInt sp = Arrays.spliterator(new int[] {1, 2, 3}); try { sp.tryAdvance((IntConsumer) x -> { throw new RuntimeException("x"); }); } catch (RuntimeException e) { log.append("caught "); } StringBuilder b = new StringBuilder(); sp.forEachRemaining((IntConsumer) b::append); return b; });

        // F7: a change during a walk does not make elements vanish
        t("treeset remove during iterator", () -> { Set<Integer> s = new TreeSet<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = s.stream().iterator(); StringBuilder b = new StringBuilder(); b.append(i.next()).append(' '); s.remove(1); try { while (i.hasNext()) b.append(i.next()).append(' '); b.append("end"); } catch (ConcurrentModificationException e) { b.append("CME"); } return b; });
        t("treeset remove during spliterator", () -> { Set<Integer> s = new TreeSet<>(Arrays.asList(1, 2, 3)); Spliterator<Integer> sp = s.spliterator(); StringBuilder b = new StringBuilder(); sp.tryAdvance(x -> b.append(x).append(' ')); s.remove(1); try { sp.forEachRemaining(x -> b.append(x).append(' ')); b.append("end"); } catch (ConcurrentModificationException e) { b.append("CME"); } return b; });
        t("linkedlist clear during walk", () -> { List<Integer> l = new LinkedList<>(Arrays.asList(1, 2, 3)); Spliterator<Integer> sp = l.spliterator(); StringBuilder b = new StringBuilder(); sp.tryAdvance(x -> b.append(x).append(' ')); l.clear(); try { sp.forEachRemaining(x -> b.append(x).append(' ')); b.append("end"); } catch (ConcurrentModificationException e) { b.append("CME"); } return b; });
        t("linkedlist clear then tryAdvance", () -> { List<Integer> l = new LinkedList<>(Arrays.asList(1, 2, 3)); Spliterator<Integer> sp = l.spliterator(); sp.tryAdvance(x -> {}); l.clear(); try { return sp.tryAdvance(x -> {}); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("priorityqueue clear then tryAdvance", () -> { PriorityQueue<Integer> q = new PriorityQueue<>(Arrays.asList(1, 2, 3)); Spliterator<Integer> sp = q.spliterator(); sp.tryAdvance(x -> {}); q.clear(); try { return sp.tryAdvance(x -> {}); } catch (ConcurrentModificationException e) { return "CME"; } });

        // F9: concat flags are fixed when it is made
        t("deep concat", () -> { Stream<Integer> s = Stream.of(0); for (int i = 1; i < 600; i++) s = Stream.concat(s, Stream.of(i)); return s.count(); });
        t("deep concat flags", () -> { Stream<Integer> s = Stream.of(0); for (int i = 1; i < 600; i++) s = Stream.concat(s, Stream.of(i)); Spliterator<Integer> sp = s.spliterator(); return names(sp.characteristics()) + " " + sp.estimateSize(); });
        t("concat unsized by overflow after advance", () -> { Spliterator.OfLong sp = LongStream.concat(LongStream.range(0, Long.MAX_VALUE), LongStream.of(1, 2)).spliterator(); sp.tryAdvance((LongConsumer) x -> {}); return names(sp.characteristics()) + " " + sp.getExactSizeIfKnown(); });

        // F10: the same exception from the body and from an inner close
        t("flatMap same exception body and close", () -> { RuntimeException e = new RuntimeException("same"); return Stream.of(1).flatMap(x -> Stream.of(x).onClose(() -> { throw e; }).map(y -> { throw e; })).count(); });
        t("flatMap different exception body and close", () -> Stream.of(1).flatMap(x -> Stream.of(x).onClose(() -> { throw new IllegalStateException("c"); }).map(y -> { throw new RuntimeException("b"); })).count());

        // F11
        t("toArray generator null", () -> Stream.of(1, 2).peek(pk("p")).toArray(n -> null));
        t("retried hasNext after comparator throws", () -> { Iterator<Integer> it = Stream.of(3, 1, 2).sorted((a, b) -> { throw new IllegalStateException("cmp"); }).iterator(); StringBuilder b = new StringBuilder(); for (int i = 0; i < 2; i++) { try { b.append(it.hasNext()); } catch (IllegalStateException e) { b.append("ISE "); } } return b; });
        t("spliterator Collection concurrent", () -> names(Spliterators.spliterator(new ArrayList<>(Arrays.asList(1, 2)), Spliterator.CONCURRENT).characteristics()));
        t("spliterator Iterator concurrent", () -> names(Spliterators.spliterator(Arrays.asList(1, 2).iterator(), 2, Spliterator.CONCURRENT).characteristics()));
        t("spliterator Iterator not concurrent", () -> names(Spliterators.spliterator(Arrays.asList(1, 2).iterator(), 2, Spliterator.ORDERED).characteristics()));

        // distinct on a sorted stream: a null passes once and makes the line forget the previous value
        t("sorted distinct null a", () -> distinctSorted(null, 1, null, 1, 1, null));
        t("sorted distinct null b", () -> distinctSorted(1, null, 1, null));
        t("sorted distinct null c", () -> distinctSorted(1, 1, null, null, 1));
        t("sorted distinct null d", () -> distinctSorted(null, null, 2, 2, null));
        t("sorted distinct null e", () -> distinctSorted(1, 2, null, 2, 1));
        t("sorted distinct plain", () -> distinctSorted(1, 2, 1, 2, 2));

        // traces that were not pinned
        t("int iterate trace", () -> IntStream.iterate(1, x -> { log.append("f" + x + " "); return x * 2; }).peek(x -> log.append("p" + x + " ")).limit(3).sum());
        t("long iterate trace", () -> LongStream.iterate(1, x -> { log.append("f" + x + " "); return x * 2; }).peek(x -> log.append("p" + x + " ")).limit(3).sum());
        t("double iterate trace", () -> DoubleStream.iterate(1, x -> { log.append("f" + x + " "); return x * 2; }).peek(x -> log.append("p" + x + " ")).limit(3).sum());
        t("object iterate trace", () -> Stream.iterate(1, x -> { log.append("f" + x + " "); return x * 2; }).peek(x -> log.append("p" + x + " ")).limit(3).count());
        t("concat sequential parallel", () -> Stream.concat(Stream.of(1), Stream.of(2).parallel()).isParallel());
        t("concat parallel sequential", () -> Stream.concat(Stream.of(1).parallel(), Stream.of(2)).isParallel());
        t("concat sequential sequential", () -> Stream.concat(Stream.of(1), Stream.of(2)).isParallel());
        t("unordered twice", () -> { Stream<Integer> u = Stream.of(1).unordered(); return u.unordered() == u; });
        t("unordered of unordered source", () -> { Stream<Integer> u = new HashSet<>(Arrays.asList(1)).stream().unordered(); return u.unordered() == u; });
        t("inner flatMap read in bulk", () -> { List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3)); StringBuilder b = new StringBuilder(); try { Stream.of(0).flatMap(x -> l.stream()).peek(y -> { if (y == 1) l.add(9); }).forEach(b::append); } catch (ConcurrentModificationException e) { b.append("CME"); } return b; });
        t("inner flatMap read stepwise", () -> { List<Integer> l = new ArrayList<>(Arrays.asList(1, 2, 3)); StringBuilder b = new StringBuilder(); try { Stream.of(0).flatMap(x -> l.stream()).peek(y -> { if (y == 1) l.add(9); }).findFirst().get(); b.append("done"); } catch (ConcurrentModificationException e) { b.append("CME"); } return b; });
        t("supplier claims SORTED sorted", () -> StreamSupport.stream(() -> Spliterators.spliterator(new Object[] {3, 1, 2}, Spliterator.ORDERED | Spliterator.SORTED), Spliterator.ORDERED | Spliterator.SORTED, false).sorted().collect(Collectors.toList()));
        t("supplier claims DISTINCT distinct", () -> StreamSupport.stream(() -> Spliterators.spliterator(new Object[] {1, 1, 2}, Spliterator.ORDERED), Spliterator.ORDERED | Spliterator.DISTINCT, false).distinct().collect(Collectors.toList()));
        t("supplier no claim sorted", () -> StreamSupport.stream(() -> Spliterators.spliterator(new Object[] {3, 1, 2}, Spliterator.ORDERED | Spliterator.SORTED), Spliterator.ORDERED, false).sorted().collect(Collectors.toList()));
        t("supplier claims SORTED int", () -> Arrays.toString(StreamSupport.intStream(() -> Spliterators.spliterator(new int[] {3, 1, 2}, Spliterator.ORDERED | Spliterator.SORTED), Spliterator.ORDERED | Spliterator.SORTED, false).sorted().toArray()));

        // third review: bulk run keeps inner pipelines in bulk; a sort after limit or skip may be fed more or less
        t("flatMap sorted peek bulk spliterator", () -> { Stream.of(1).flatMap(x -> Stream.of(3, 1, 2).sorted().peek(pk("i"))).spliterator().forEachRemaining(pk("r")); return "-"; });
        t("concat flatMap sorted peek bulk", () -> { Stream.concat(Stream.of(1).flatMap(x -> Stream.of(3, 1, 2).sorted().peek(pk("i"))), Stream.of(9)).forEach(pk("r")); return "-"; });
        t("flatMap sorted peek step then rest", () -> { Spliterator<Integer> sp = Stream.of(1).flatMap(x -> Stream.of(3, 1, 2).sorted().peek(pk("i"))).spliterator(); sp.tryAdvance(pk("t")); sp.forEachRemaining(pk("r")); return "-"; });
        t("lying chars limit sorted", () -> { StringBuilder sb = new StringBuilder("abcde"); return sb.chars().peek(x -> sb.append('c')).limit(10).sorted().count(); });
        t("lying chars sorted", () -> { StringBuilder sb = new StringBuilder("abcde"); return sb.chars().peek(x -> { if (sb.length() < 8) sb.append('c'); }).sorted().count(); });
        t("huge boxed limit sorted", () -> LongStream.range(0, Long.MAX_VALUE).boxed().limit(3).sorted().count());
        t("huge boxed sorted iterator", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().iterator().hasNext());
        t("huge boxed sorted spliterator", () -> LongStream.range(0, Long.MAX_VALUE).boxed().sorted().spliterator().tryAdvance(x -> {}));
        t("huge boxed limit sorted iterator", () -> LongStream.range(0, Long.MAX_VALUE).boxed().limit(3).sorted().iterator().next());
        t("int array forEachRemaining throws", () -> { Spliterator.OfInt sp = Arrays.spliterator(new int[] {1, 2, 3}); try { sp.forEachRemaining((IntConsumer) x -> { throw new RuntimeException("x"); }); } catch (RuntimeException e) { log.append("caught "); } StringBuilder b = new StringBuilder(); sp.forEachRemaining((IntConsumer) b::append); return b.length() + " " + sp.estimateSize(); });
        t("double array forEachRemaining throws", () -> { Spliterator.OfDouble sp = Arrays.spliterator(new double[] {1, 2, 3}); try { sp.forEachRemaining((DoubleConsumer) x -> { throw new RuntimeException("x"); }); } catch (RuntimeException e) { log.append("caught "); } StringBuilder b = new StringBuilder(); sp.forEachRemaining((DoubleConsumer) b::append); return b.length() + " " + sp.estimateSize(); });
        t("long array forEachRemaining throws", () -> { Spliterator.OfLong sp = Arrays.spliterator(new long[] {1, 2, 3}); try { sp.forEachRemaining((LongConsumer) x -> { throw new RuntimeException("x"); }); } catch (RuntimeException e) { log.append("caught "); } StringBuilder b = new StringBuilder(); sp.forEachRemaining((LongConsumer) b::append); return b.length() + " " + sp.estimateSize(); });
        t("long rangeClosed skip past end", () -> LongStream.rangeClosed(0, 5).skip(6).count());
        t("long rangeClosed skip all", () -> LongStream.rangeClosed(0, 5).skip(5).sum());
        t("long rangeClosed skip over", () -> LongStream.rangeClosed(0, 5).skip(100).count());
        t("int range skip past end", () -> IntStream.range(0, 5).skip(9).count());
        t("int range skip exactly end", () -> IntStream.range(0, 5).skip(5).count());
        t("int rangeClosed skip one less", () -> IntStream.rangeClosed(0, 5).skip(5).sum());
        t("sorted distinct equals side", () -> { class E { final String n; E(String n) { this.n = n; } public boolean equals(Object o) { log.append(n + "=" + ((E) o).n + " "); return n.equals(((E) o).n); } public int hashCode() { return 1; } public String toString() { return n; } } return StreamSupport.stream(Spliterators.spliterator(new Object[] {new E("p"), new E("q"), new E("q"), new E("r")}, Spliterator.ORDERED | Spliterator.SORTED), false).distinct().collect(Collectors.toList()); });
        t("linkedlist shrink during walk", () -> { LinkedList<Integer> l = new LinkedList<>(Arrays.asList(1, 2, 3, 4)); Spliterator<Integer> sp = l.spliterator(); StringBuilder b = new StringBuilder(); sp.tryAdvance(x -> b.append(x).append(' ')); l.removeLast(); try { sp.forEachRemaining(x -> b.append(x).append(' ')); b.append("end"); } catch (ConcurrentModificationException e) { b.append("CME"); } return b; });
        t("linkedlist shrink before first use", () -> { LinkedList<Integer> l = new LinkedList<>(Arrays.asList(1, 2, 3, 4)); Spliterator<Integer> sp = l.spliterator(); l.removeFirst(); StringBuilder b = new StringBuilder(); sp.forEachRemaining(x -> b.append(x).append(' ')); return b; });
    }
}
