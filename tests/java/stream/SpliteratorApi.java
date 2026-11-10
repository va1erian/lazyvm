package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Spliterator, Spliterators, PrimitiveIterator and StreamSupport used directly. */
public class SpliteratorApi {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    static <T> String drain(Spliterator<T> s) { StringBuilder sb = new StringBuilder(); s.forEachRemaining(x -> sb.append(x).append(' ')); return sb.toString().trim(); }
    static <T> String info(Spliterator<T> s) { return names(s.characteristics()) + " est=" + s.estimateSize() + " exact=" + s.getExactSizeIfKnown(); }
    static List<Integer> ints(int n) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); return l; }
    static Iterator<Integer> iter(int n) { return ints(n).iterator(); }

    public static void main(String[] args) {
        // tryAdvance and forEachRemaining
        t("tryAdvance", () -> { Spliterator<Integer> s = Arrays.asList(1, 2, 3).spliterator(); StringBuilder sb = new StringBuilder(); while (s.tryAdvance(x -> sb.append(x))) sb.append(','); return sb + " " + s.estimateSize(); });
        t("tryAdvance null", () -> Arrays.asList(1).spliterator().tryAdvance(null));
        t("tryAdvance null on empty", () -> new ArrayList<Integer>().spliterator().tryAdvance(null));
        t("forEachRemaining null", () -> { Arrays.asList(1).spliterator().forEachRemaining(null); return "no"; });
        t("forEachRemaining twice", () -> { Spliterator<Integer> s = Arrays.asList(1, 2).spliterator(); String a = drain(s); return a + "|" + drain(s); });
        t("size shrinks", () -> { Spliterator<Integer> s = ints(5).spliterator(); s.tryAdvance(x -> {}); s.tryAdvance(x -> {}); return s.estimateSize() + " " + s.getExactSizeIfKnown(); });
        t("array tryAdvance", () -> { Spliterator<String> s = Arrays.spliterator(new String[] {"a", "b"}); StringBuilder sb = new StringBuilder(); while (s.tryAdvance(sb::append)) sb.append('.'); return sb; });
        t("int array", () -> drain(Arrays.spliterator(new int[] {4, 5, 6}, 1, 3)));
        t("array range check", () -> Arrays.spliterator(new String[3], 2, 5));
        t("array range check order", () -> Arrays.spliterator(new String[3], 3, 2));
        t("array range check negative", () -> Arrays.spliterator(new int[3], -1, 2));
        t("array null", () -> Arrays.spliterator((String[]) null));
        t("array range null", () -> Arrays.spliterator((int[]) null, 0, 0));
        t("Spliterators array range", () -> Spliterators.spliterator(new Object[3], 1, 9, 0));
        t("Spliterators array range ok", () -> info(Spliterators.spliterator(new Object[] {1, 2, 3}, 1, 3, Spliterator.DISTINCT)));
        t("Spliterators array null", () -> Spliterators.spliterator((Object[]) null, 0));
        t("Spliterators array chars", () -> info(Spliterators.spliterator(new Object[] {1, 2, 3}, 0)));
        t("Spliterators array chars strip", () -> info(Spliterators.spliterator(new Object[] {1, 2, 3}, Spliterator.SORTED | Spliterator.CONCURRENT)));
        t("Spliterators int array", () -> info(Spliterators.spliterator(new int[] {1, 2, 3}, Spliterator.NONNULL)));
        t("Spliterators long array range", () -> info(Spliterators.spliterator(new long[] {1, 2, 3}, 1, 2, 0)));
        t("Spliterators double array", () -> info(Spliterators.spliterator(new double[] {1, 2, 3}, 0)));

        // splitting
        t("trySplit array", () -> { Spliterator<Integer> s = Arrays.asList(1, 2, 3, 4, 5).spliterator(); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit array info", () -> { Spliterator<Integer> s = Arrays.asList(1, 2, 3, 4, 5).spliterator(); Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + info(s); });
        t("trySplit array single", () -> Arrays.asList(1).spliterator().trySplit());
        t("trySplit array empty", () -> Arrays.asList().spliterator().trySplit());
        t("trySplit array twice", () -> { Spliterator<Integer> s = ints(8).spliterator(); Spliterator<Integer> a = s.trySplit(); Spliterator<Integer> b = s.trySplit(); return drain(a) + " | " + drain(b) + " | " + drain(s); });
        t("trySplit ArrayList", () -> { Spliterator<Integer> s = ints(7).spliterator(); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit ArrayList after advance", () -> { Spliterator<Integer> s = ints(7).spliterator(); s.tryAdvance(x -> {}); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit ArrayList info", () -> { Spliterator<Integer> s = ints(7).spliterator(); Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + info(s); });
        t("trySplit ArrayList tiny", () -> { Spliterator<Integer> s = ints(1).spliterator(); return s.trySplit(); });
        t("trySplit ArrayList pair", () -> { Spliterator<Integer> s = ints(2).spliterator(); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit int range", () -> { Spliterator.OfInt s = IntStream.range(0, 10).spliterator(); Spliterator.OfInt p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit int range info", () -> { Spliterator.OfInt s = IntStream.range(0, 10).spliterator(); Spliterator.OfInt p = s.trySplit(); return info(p) + " | " + info(s); });
        t("trySplit int range odd", () -> { Spliterator.OfInt s = IntStream.range(0, 7).spliterator(); Spliterator.OfInt p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit int range one", () -> IntStream.range(0, 1).spliterator().trySplit());
        t("trySplit rangeClosed", () -> { Spliterator.OfInt s = IntStream.rangeClosed(0, 9).spliterator(); Spliterator.OfInt p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit long range", () -> { Spliterator.OfLong s = LongStream.range(0, 10).spliterator(); Spliterator.OfLong p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit stream of", () -> { Spliterator<Integer> s = Stream.of(1, 2, 3, 4).spliterator(); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit after wrap", () -> Stream.of(1, 2, 3, 4).filter(x -> true).spliterator().trySplit() == null);
        t("trySplit unknown size", () -> { Spliterator<Integer> s = Spliterators.spliteratorUnknownSize(iter(3000), 0); Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + info(s); });
        t("trySplit unknown size drain", () -> { Spliterator<Integer> s = Spliterators.spliteratorUnknownSize(iter(3000), 0); Spliterator<Integer> p = s.trySplit(); Spliterator<Integer> q = s.trySplit(); return info(p) + " | " + info(q) + " | " + info(s); });
        t("trySplit unknown size small", () -> { Spliterator<Integer> s = Spliterators.spliteratorUnknownSize(iter(10), 0); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit unknown size empty", () -> Spliterators.spliteratorUnknownSize(iter(0), 0).trySplit());
        t("trySplit unknown size order", () -> { Spliterator<Integer> s = Spliterators.spliteratorUnknownSize(iter(5), Spliterator.ORDERED); Spliterator<Integer> p = s.trySplit(); return drain(p) + " | " + drain(s); });
        t("trySplit sized iterator", () -> { Spliterator<Integer> s = Spliterators.spliterator(iter(3000), 3000, 0); Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + info(s); });
        t("trySplit HashSet", () -> { Spliterator<Integer> s = new HashSet<>(ints(16)).spliterator(); Spliterator<Integer> p = s.trySplit(); return p != null && p.estimateSize() > 0 && drain(p).length() > 0; });
        t("trySplit collection default", () -> { Spliterator<Integer> s = new AbstractCollection<Integer>() { public Iterator<Integer> iterator() { return iter(5); } public int size() { return 5; } }.spliterator(); Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + info(s) + " | " + drain(p) + " | " + drain(s); });

        // Spliterators over iterators and collections
        t("iterator sized", () -> info(Spliterators.spliterator(iter(4), 4, 0)));
        t("iterator sized chars", () -> info(Spliterators.spliterator(iter(4), 4, Spliterator.ORDERED | Spliterator.DISTINCT)));
        t("iterator sized chars sorted", () -> info(Spliterators.spliterator(iter(4), 4, Spliterator.SORTED)));
        t("iterator sized mismatched", () -> { Spliterator<Integer> s = Spliterators.spliterator(iter(4), 10, 0); return drain(s) + " " + s.estimateSize(); });
        t("iterator sized null", () -> Spliterators.spliterator((Iterator<Integer>) null, 4, 0));
        t("iterator unknown", () -> info(Spliterators.spliteratorUnknownSize(iter(4), 0)));
        t("iterator unknown sized flag", () -> info(Spliterators.spliteratorUnknownSize(iter(4), Spliterator.SIZED | Spliterator.ORDERED)));
        t("iterator unknown null", () -> Spliterators.spliteratorUnknownSize((Iterator<Integer>) null, 0));
        t("iterator advance", () -> { Spliterator<Integer> s = Spliterators.spliteratorUnknownSize(iter(3), 0); StringBuilder sb = new StringBuilder(); while (s.tryAdvance(x -> sb.append(x))) sb.append(','); return sb; });
        t("iterator advance null", () -> Spliterators.spliteratorUnknownSize(iter(3), 0).tryAdvance(null));
        t("collection", () -> info(Spliterators.spliterator(ints(4), 0)));
        t("collection chars", () -> info(Spliterators.spliterator(ints(4), Spliterator.ORDERED | Spliterator.SORTED)));
        t("collection null", () -> Spliterators.spliterator((Collection<Integer>) null, 0));
        t("collection late binding", () -> { List<Integer> l = ints(2); Spliterator<Integer> s = Spliterators.spliterator(l, 0); l.add(99); return info(s) + " " + drain(s); });
        t("collection late binding after first use", () -> { List<Integer> l = ints(3); Spliterator<Integer> s = Spliterators.spliterator(l, 0); s.tryAdvance(x -> {}); l.add(99); return info(s) + " " + drain(s); });
        t("collection modification detected", () -> { List<Integer> l = ints(3); Spliterator<Integer> s = Spliterators.spliterator(l, 0); s.tryAdvance(x -> {}); l.add(99); StringBuilder sb = new StringBuilder(); try { s.forEachRemaining(x -> sb.append(x)); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
        t("collection modification tryAdvance", () -> { List<Integer> l = ints(3); Spliterator<Integer> s = Spliterators.spliterator(l, 0); s.tryAdvance(x -> {}); l.add(99); try { s.tryAdvance(x -> {}); return "ok"; } catch (ConcurrentModificationException e) { return "CME"; } });
        t("collection set", () -> info(Spliterators.spliterator(new TreeSet<>(ints(4)), Spliterator.DISTINCT)));
        t("empty", () -> info(Spliterators.emptySpliterator()));
        t("empty advance", () -> Spliterators.emptySpliterator().tryAdvance(x -> { throw new AssertionError(); }));
        t("empty advance null", () -> Spliterators.emptySpliterator().tryAdvance(null));
        t("empty forEachRemaining null", () -> { Spliterators.emptySpliterator().forEachRemaining(null); return "no"; });
        t("empty split", () -> Spliterators.emptySpliterator().trySplit());
        t("empty int", () -> info(Spliterators.emptyIntSpliterator()));
        t("empty long", () -> info(Spliterators.emptyLongSpliterator()));
        t("empty double", () -> info(Spliterators.emptyDoubleSpliterator()));
        t("empty int advance null", () -> Spliterators.emptyIntSpliterator().tryAdvance((IntConsumer) null));
        t("empty stream", () -> StreamSupport.stream(Spliterators.emptySpliterator(), false).count());
        t("empty is shared", () -> Spliterators.emptySpliterator() == Spliterators.emptySpliterator());
        t("OfInt from iterator", () -> info(Spliterators.spliterator(IntStream.range(0, 3).iterator(), 3, 0)));
        t("OfInt from iterator unknown", () -> info(Spliterators.spliteratorUnknownSize(IntStream.range(0, 3).iterator(), 0)));
        t("OfLong from iterator", () -> drain(Spliterators.spliterator(LongStream.range(0, 3).iterator(), 3, 0)));
        t("OfDouble from iterator", () -> drain(Spliterators.spliteratorUnknownSize(DoubleStream.of(1, 2).iterator(), 0)));
        t("OfInt from iterator null", () -> Spliterators.spliterator((PrimitiveIterator.OfInt) null, 3, 0));
        t("OfInt array", () -> drain(Spliterators.spliterator(new int[] {7, 8}, 0)));

        // Spliterators.iterator
        t("iterator of spliterator", () -> { Iterator<Integer> i = Spliterators.iterator(ints(3).spliterator()); StringBuilder sb = new StringBuilder(); while (i.hasNext()) sb.append(i.next()); return sb; });
        t("iterator of spliterator next on empty", () -> Spliterators.iterator(ints(0).spliterator()).next());
        t("iterator of spliterator hasNext twice", () -> { Iterator<Integer> i = Spliterators.iterator(ints(2).spliterator()); return i.hasNext() + " " + i.hasNext() + " " + i.next() + " " + i.next() + " " + i.hasNext(); });
        t("iterator of spliterator remove", () -> { Iterator<Integer> i = Spliterators.iterator(ints(2).spliterator()); i.next(); i.remove(); return "no"; });
        t("iterator of spliterator forEachRemaining", () -> { Iterator<Integer> i = Spliterators.iterator(ints(3).spliterator()); i.next(); StringBuilder sb = new StringBuilder(); i.forEachRemaining(sb::append); return sb; });
        t("iterator of spliterator null", () -> Spliterators.iterator((Spliterator<Integer>) null));
        t("iterator of int spliterator", () -> { PrimitiveIterator.OfInt i = Spliterators.iterator(IntStream.range(0, 3).spliterator()); return i.nextInt() + " " + i.next() + " " + i.nextInt() + " " + i.hasNext(); });
        t("iterator of int spliterator null", () -> Spliterators.iterator((Spliterator.OfInt) null));
        t("iterator of long spliterator", () -> { PrimitiveIterator.OfLong i = Spliterators.iterator(LongStream.range(0, 2).spliterator()); return i.nextLong() + " " + i.next(); });
        t("iterator of double spliterator", () -> { PrimitiveIterator.OfDouble i = Spliterators.iterator(DoubleStream.of(1.5).spliterator()); return i.nextDouble() + " " + i.hasNext(); });

        // subclassing the abstract spliterators
        t("AbstractSpliterator", () -> { Spliterator<Integer> s = new Spliterators.AbstractSpliterator<Integer>(3, Spliterator.ORDERED) { int n = 0;
            public boolean tryAdvance(Consumer<? super Integer> a) { if (n >= 3) return false; a.accept(n++); return true; } }; return info(s) + " " + drain(s); });
        t("AbstractSpliterator sized adds subsized", () -> info(new Spliterators.AbstractSpliterator<Integer>(3, Spliterator.SIZED) { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } }));
        t("AbstractSpliterator unsized", () -> info(new Spliterators.AbstractSpliterator<Integer>(Long.MAX_VALUE, 0) { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } }));
        t("AbstractSpliterator sized no subsized for sized only", () -> names(new Spliterators.AbstractSpliterator<Integer>(3, Spliterator.SIZED | Spliterator.NONNULL) { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } }.characteristics()));
        t("AbstractSpliterator split", () -> { Spliterator<Integer> s = new Spliterators.AbstractSpliterator<Integer>(Long.MAX_VALUE, Spliterator.ORDERED) { int n = 0;
            public boolean tryAdvance(Consumer<? super Integer> a) { if (n >= 5) return false; a.accept(n++); return true; } }; Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + drain(p) + " | " + drain(s); });
        t("AbstractSpliterator split sized", () -> { Spliterator<Integer> s = new Spliterators.AbstractSpliterator<Integer>(5, Spliterator.ORDERED | Spliterator.SIZED) { int n = 0;
            public boolean tryAdvance(Consumer<? super Integer> a) { if (n >= 5) return false; a.accept(n++); return true; } }; Spliterator<Integer> p = s.trySplit(); return info(p) + " | " + info(s) + " | " + drain(p) + " | " + drain(s); });
        t("AbstractSpliterator forEachRemaining", () -> { Spliterator<Integer> s = new Spliterators.AbstractSpliterator<Integer>(3, 0) { int n = 0;
            public boolean tryAdvance(Consumer<? super Integer> a) { if (n >= 3) return false; a.accept(n++); return true; } }; return drain(s); });
        t("AbstractIntSpliterator", () -> { Spliterator.OfInt s = new Spliterators.AbstractIntSpliterator(3, Spliterator.ORDERED | Spliterator.SIZED) { int n = 0;
            public boolean tryAdvance(IntConsumer a) { if (n >= 3) return false; a.accept(n++); return true; } }; return info(s) + " " + drain(s); });
        t("AbstractLongSpliterator", () -> { Spliterator.OfLong s = new Spliterators.AbstractLongSpliterator(3, 0) { long n = 0;
            public boolean tryAdvance(LongConsumer a) { if (n >= 3) return false; a.accept(n++); return true; } }; return info(s) + " " + drain(s); });
        t("AbstractDoubleSpliterator", () -> { Spliterator.OfDouble s = new Spliterators.AbstractDoubleSpliterator(3, 0) { double n = 0;
            public boolean tryAdvance(DoubleConsumer a) { if (n >= 3) return false; a.accept(n++); return true; } }; return info(s) + " " + drain(s); });
        t("AbstractIntSpliterator boxed advance", () -> { Spliterator.OfInt s = new Spliterators.AbstractIntSpliterator(2, 0) { int n = 0;
            public boolean tryAdvance(IntConsumer a) { if (n >= 2) return false; a.accept(n++); return true; } }; StringBuilder sb = new StringBuilder(); Consumer<Integer> c = sb::append; s.tryAdvance(c); s.forEachRemaining(c); return sb; });
        t("AbstractSpliterator stream", () -> StreamSupport.stream(new Spliterators.AbstractSpliterator<Integer>(Long.MAX_VALUE, Spliterator.ORDERED) { int n = 0;
            public boolean tryAdvance(Consumer<? super Integer> a) { a.accept(n++); return true; } }, false).limit(4).collect(Collectors.toList()));
        t("Spliterator default getExactSizeIfKnown", () -> new Spliterator<Integer>() { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } public Spliterator<Integer> trySplit() { return null; }
            public long estimateSize() { return 7; } public int characteristics() { return 0; } }.getExactSizeIfKnown());
        t("Spliterator default getExactSizeIfKnown sized", () -> new Spliterator<Integer>() { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } public Spliterator<Integer> trySplit() { return null; }
            public long estimateSize() { return 7; } public int characteristics() { return SIZED; } }.getExactSizeIfKnown());
        t("Spliterator default forEachRemaining", () -> { int[] n = {0}; Spliterator<Integer> s = new Spliterator<Integer>() { public boolean tryAdvance(Consumer<? super Integer> a) { if (n[0] >= 3) return false; a.accept(n[0]++); return true; } public Spliterator<Integer> trySplit() { return null; }
            public long estimateSize() { return 3; } public int characteristics() { return 0; } }; return drain(s); });
        t("Spliterator default getComparator", () -> new Spliterator<Integer>() { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } public Spliterator<Integer> trySplit() { return null; }
            public long estimateSize() { return 0; } public int characteristics() { return SORTED; } }.getComparator());
        t("Spliterator default hasCharacteristics", () -> new Spliterator<Integer>() { public boolean tryAdvance(Consumer<? super Integer> a) { return false; } public Spliterator<Integer> trySplit() { return null; }
            public long estimateSize() { return 0; } public int characteristics() { return ORDERED | SIZED; } }.hasCharacteristics(Spliterator.ORDERED));

        // PrimitiveIterator defaults
        t("PrimitiveIterator.OfInt custom", () -> { PrimitiveIterator.OfInt i = new PrimitiveIterator.OfInt() { int n = 0; public boolean hasNext() { return n < 3; } public int nextInt() { return n++; } };
            StringBuilder sb = new StringBuilder(); sb.append(i.next()).append(i.nextInt()); i.forEachRemaining((IntConsumer) sb::append); return sb; });
        t("PrimitiveIterator.OfInt custom remove", () -> { PrimitiveIterator.OfInt i = new PrimitiveIterator.OfInt() { public boolean hasNext() { return true; } public int nextInt() { return 1; } }; i.remove(); return "no"; });
        t("PrimitiveIterator.OfInt custom forEachRemaining Consumer", () -> { PrimitiveIterator.OfInt i = new PrimitiveIterator.OfInt() { int n = 0; public boolean hasNext() { return n < 2; } public int nextInt() { return n++; } };
            StringBuilder sb = new StringBuilder(); i.forEachRemaining((Consumer<Integer>) sb::append); return sb; });
        t("PrimitiveIterator.OfLong custom", () -> { PrimitiveIterator.OfLong i = new PrimitiveIterator.OfLong() { long n = 5; public boolean hasNext() { return n < 7; } public long nextLong() { return n++; } };
            return i.next() + " " + i.nextLong() + " " + i.hasNext(); });
        t("PrimitiveIterator.OfDouble custom", () -> { PrimitiveIterator.OfDouble i = new PrimitiveIterator.OfDouble() { double n = 0.5; public boolean hasNext() { return n < 2; } public double nextDouble() { return n++; } };
            StringBuilder sb = new StringBuilder(); i.forEachRemaining((DoubleConsumer) sb::append); return sb; });

        // StreamSupport
        t("StreamSupport.stream", () -> StreamSupport.stream(ints(3).spliterator(), false).collect(Collectors.toList()));
        t("StreamSupport.stream parallel", () -> StreamSupport.stream(ints(3).spliterator(), true).isParallel());
        t("StreamSupport.stream sequential", () -> StreamSupport.stream(ints(3).spliterator(), false).isParallel());
        t("StreamSupport.stream consumed spliterator", () -> { Spliterator<Integer> s = ints(3).spliterator(); s.tryAdvance(x -> {}); return StreamSupport.stream(s, false).collect(Collectors.toList()); });
        t("StreamSupport.stream spliterator reuse", () -> { Spliterator<Integer> s = ints(3).spliterator(); StreamSupport.stream(s, false).count(); return StreamSupport.stream(s, false).count(); });
        t("StreamSupport.stream supplier", () -> StreamSupport.stream(() -> ints(3).spliterator(), Spliterator.ORDERED | Spliterator.SIZED, false).collect(Collectors.toList()));
        t("StreamSupport.stream supplier late", () -> { int[] calls = {0}; Stream<Integer> s = StreamSupport.stream(() -> { calls[0]++; return ints(3).spliterator(); }, Spliterator.ORDERED, false); int before = calls[0]; s.count(); return before + " " + calls[0]; });
        t("StreamSupport.stream supplier map", () -> { int[] calls = {0}; Stream<Integer> s = StreamSupport.stream(() -> { calls[0]++; return ints(3).spliterator(); }, Spliterator.ORDERED, false).map(x -> x); return calls[0]; });
        t("StreamSupport.stream supplier iterator", () -> { int[] calls = {0}; Iterator<Integer> i = StreamSupport.stream(() -> { calls[0]++; return ints(3).spliterator(); }, Spliterator.ORDERED, false).iterator(); int before = calls[0]; i.hasNext(); return before + " " + calls[0]; });
        t("StreamSupport.stream supplier characteristics", () -> names(StreamSupport.stream(() -> ints(3).spliterator(), Spliterator.ORDERED | Spliterator.SIZED, false).spliterator().characteristics()));
        t("StreamSupport.stream supplier characteristics mask", () -> names(StreamSupport.stream(() -> ints(3).spliterator(), 0, false).spliterator().characteristics()));
        t("StreamSupport.stream supplier null result", () -> StreamSupport.stream(() -> (Spliterator<Integer>) null, 0, false).count());
        t("StreamSupport.stream supplier called once", () -> { int[] calls = {0}; Stream<Integer> s = StreamSupport.stream(() -> { calls[0]++; return ints(3).spliterator(); }, 0, false); s.filter(x -> true).count(); return calls[0]; });
        t("StreamSupport.intStream", () -> Arrays.toString(StreamSupport.intStream(IntStream.range(0, 3).spliterator(), false).toArray()));
        t("StreamSupport.intStream supplier", () -> Arrays.toString(StreamSupport.intStream(() -> IntStream.range(0, 3).spliterator(), Spliterator.ORDERED | Spliterator.SIZED, false).toArray()));
        t("StreamSupport.longStream", () -> Arrays.toString(StreamSupport.longStream(LongStream.range(0, 3).spliterator(), true).toArray()));
        t("StreamSupport.longStream supplier", () -> Arrays.toString(StreamSupport.longStream(() -> LongStream.range(0, 3).spliterator(), Spliterator.ORDERED, false).toArray()));
        t("StreamSupport.doubleStream", () -> Arrays.toString(StreamSupport.doubleStream(DoubleStream.of(1, 2).spliterator(), false).toArray()));
        t("StreamSupport.doubleStream supplier", () -> Arrays.toString(StreamSupport.doubleStream(() -> DoubleStream.of(1, 2).spliterator(), Spliterator.ORDERED, false).toArray()));
        t("StreamSupport.intStream null", () -> StreamSupport.intStream((Spliterator.OfInt) null, false));
        t("StreamSupport.longStream null", () -> StreamSupport.longStream((Spliterator.OfLong) null, false));
        t("StreamSupport.doubleStream null", () -> StreamSupport.doubleStream((Spliterator.OfDouble) null, false));
        t("StreamSupport.intStream supplier null", () -> StreamSupport.intStream((Supplier<Spliterator.OfInt>) null, 0, false) != null);
        t("StreamSupport iterator round trip", () -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(iter(4), Spliterator.ORDERED), false).map(x -> x * x).collect(Collectors.toList()));
        t("StreamSupport.stream late binding", () -> { List<Integer> l = ints(2); Stream<Integer> s = StreamSupport.stream(l.spliterator(), false); l.add(5); return s.collect(Collectors.toList()); });
        t("Iterable to stream", () -> { Iterable<Integer> it = () -> iter(3); return StreamSupport.stream(it.spliterator(), false).collect(Collectors.toList()); });
        t("Iterable default spliterator late", () -> { int[] calls = {0}; Iterable<Integer> it = () -> { calls[0]++; return iter(3); }; Spliterator<Integer> s = it.spliterator(); int before = calls[0]; s.tryAdvance(x -> {}); return before + " " + calls[0]; });
        t("Iterable default forEach", () -> { Iterable<Integer> it = () -> iter(3); StringBuilder sb = new StringBuilder(); it.forEach(sb::append); return sb; });
        t("Iterable default forEach null", () -> { Iterable<Integer> it = () -> iter(3); it.forEach(null); return "no"; });
        t("Iterable default spliterator split", () -> { Iterable<Integer> it = () -> iter(5); Spliterator<Integer> s = it.spliterator(); Spliterator<Integer> p = s.trySplit(); return info(s) + " " + (p == null ? "null" : drain(p) + "|" + drain(s)); });
    }
}
