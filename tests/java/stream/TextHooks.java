package stream;

import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.*;
import java.util.stream.*;

/** CharSequence.chars and codePoints, CharBuffer.chars, BitSet.stream and the Random streams. */
public class TextHooks {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static String a(IntStream s) { return Arrays.toString(s.toArray()); }
    static String a(LongStream s) { return Arrays.toString(s.toArray()); }
    static String a(DoubleStream s) { return Arrays.toString(s.toArray()); }
    static final String SUR = "a😀b";          // a, U+1F600, b
    static final String LONE = "x\uD83Dy\uDE00z";        // unpaired high, then unpaired low

    /** A CharSequence that logs which methods are called. */
    static class Logged implements CharSequence {
        final StringBuilder log; final String s;
        Logged(String s, StringBuilder log) { this.s = s; this.log = log; }
        public int length() { log.append("L"); return s.length(); }
        public char charAt(int i) { log.append("c").append(i); return s.charAt(i); }
        public CharSequence subSequence(int a, int b) { return s.subSequence(a, b); }
        public String toString() { return s; }
    }

    public static void main(String[] args) {
        // chars and codePoints
        t("chars", () -> a("abc".chars()));
        t("chars empty", () -> a("".chars()));
        t("chars surrogates", () -> a(SUR.chars()));
        t("codePoints", () -> a("abc".codePoints()));
        t("codePoints surrogates", () -> a(SUR.codePoints()));
        t("codePoints lone surrogates", () -> a(LONE.codePoints()));
        t("codePoints empty", () -> a("".codePoints()));
        t("codePoints reversed pair", () -> a("\uDE00\uD83D".codePoints()));
        t("codePoints count", () -> SUR.codePoints().count());
        t("chars count", () -> SUR.chars().count());
        t("codePoints to string", () -> SUR.codePoints().mapToObj(c -> new String(Character.toChars(c))).collect(Collectors.joining("|")));
        t("chars to chars", () -> "hello".chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(",")));
        t("chars filter letters", () -> "a1b2".chars().filter(Character::isLetter).count());
        t("chars sum", () -> "abc".chars().sum());
        t("chars max", () -> "abc".chars().max());
        t("chars distinct sorted", () -> "mississippi".chars().distinct().sorted().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining()));
        t("chars grouping", () -> "mississippi".chars().mapToObj(c -> (char) c).collect(Collectors.groupingBy(c -> c, TreeMap::new, Collectors.counting())));
        t("chars boxed to char", () -> "ab".chars().boxed().map(i -> (char) i.intValue()).collect(Collectors.toList()));
        t("chars is single use", () -> { IntStream s = "ab".chars(); s.count(); return s.count(); });
        t("chars parallel", () -> "abcdef".chars().parallel().map(c -> c - 'a').sum());
        t("StringBuilder chars", () -> a(new StringBuilder("xyz").chars()));
        t("StringBuilder codePoints", () -> a(new StringBuilder(SUR).codePoints()));
        t("StringBuffer chars", () -> a(new StringBuffer("xy").chars()));
        t("CharBuffer chars", () -> a(CharBuffer.wrap("abcd").chars()));
        t("CharBuffer chars position", () -> { CharBuffer b = CharBuffer.wrap("abcd"); b.position(1); b.limit(3); return a(b.chars()); });
        t("CharBuffer codePoints", () -> a(CharBuffer.wrap(SUR).codePoints()));
        t("CharBuffer chars does not move", () -> { CharBuffer b = CharBuffer.wrap("abcd"); b.chars().count(); return b.position(); });
        t("CharBuffer chars empty", () -> a(CharBuffer.allocate(0).chars()));
        t("CharBuffer allocate chars", () -> a(CharBuffer.allocate(3).chars()));
        t("CharBuffer late binding", () -> { CharBuffer b = CharBuffer.wrap("abcd"); b.limit(2); IntStream s = b.chars(); b.limit(4); return a(s); });
        t("CharBuffer position late", () -> { CharBuffer b = CharBuffer.wrap("abcd"); IntStream s = b.chars(); b.position(2); return a(s); });
        t("CharBuffer subsequence chars", () -> a(CharBuffer.wrap("abcd").subSequence(1, 3).chars()));
        t("CharBuffer slice chars", () -> { CharBuffer b = CharBuffer.wrap("abcd"); b.position(1); return a(b.slice().chars()); });
        t("CharSequence late binding", () -> { StringBuilder sb = new StringBuilder("ab"); IntStream s = sb.chars(); sb.append("cd"); return a(s); });
        t("CharSequence late binding codePoints", () -> { StringBuilder sb = new StringBuilder("ab"); IntStream s = sb.codePoints(); sb.append("cd"); return a(s); });
        t("CharSequence late binding shrink", () -> { StringBuilder sb = new StringBuilder("abcd"); IntStream s = sb.chars(); sb.setLength(1); return a(s); });
        t("CharSequence modified while streaming", () -> { StringBuilder sb = new StringBuilder("abc"); int[] n = {0}; try { sb.chars().forEach(c -> { n[0]++; sb.setLength(1); }); return "done " + n[0]; } catch (IndexOutOfBoundsException e) { return e.getClass().getName() + " " + n[0]; } });
        t("custom CharSequence calls", () -> { StringBuilder log = new StringBuilder(); IntStream s = new Logged("ab", log).chars(); String before = log.toString(); s.sum(); return "[" + before + "] " + log; });
        t("custom CharSequence codePoints calls", () -> { StringBuilder log = new StringBuilder(); IntStream s = new Logged("ab", log).codePoints(); String before = log.toString(); s.sum(); return "[" + before + "] " + log; });
        t("custom CharSequence surrogate calls", () -> { StringBuilder log = new StringBuilder(); new Logged(SUR, log).codePoints().sum(); return log; });
        t("custom CharSequence count calls", () -> { StringBuilder log = new StringBuilder(); new Logged("abc", log).chars().count(); return log; });
        t("custom CharSequence limit calls", () -> { StringBuilder log = new StringBuilder(); new Logged("abcd", log).chars().limit(2).sum(); return log; });
        t("custom CharSequence iterator calls", () -> { StringBuilder log = new StringBuilder(); PrimitiveIterator.OfInt it = new Logged("abc", log).chars().iterator(); it.nextInt(); return log; });
        t("custom CharSequence empty", () -> { StringBuilder log = new StringBuilder(); new Logged("", log).chars().sum(); return log; });
        t("custom CharSequence is not a String", () -> a(new Logged("hi", new StringBuilder()).chars()));
        t("chars on null", () -> ((CharSequence) null).chars());
        t("chars sized", () -> "abc".chars().spliterator().getExactSizeIfKnown());
        t("codePoints sized", () -> "abc".codePoints().spliterator().getExactSizeIfKnown());
        t("String chars boxed", () -> "ab".chars().boxed().collect(Collectors.toList()));
        t("String join stream", () -> String.join(",", Stream.of("a", "b").collect(Collectors.toList())));
        t("String lines-free split stream", () -> Arrays.stream("a b  c".split(" ")).filter(w -> !w.isEmpty()).count());

        // BitSet.stream
        t("BitSet stream", () -> a(BitSet.valueOf(new long[] {0b101101}).stream()));
        t("BitSet stream empty", () -> a(new BitSet().stream()));
        t("BitSet stream high", () -> { BitSet b = new BitSet(); b.set(0); b.set(63); b.set(64); b.set(1000); return a(b.stream()); });
        t("BitSet stream count", () -> { BitSet b = new BitSet(); b.set(0, 100); return b.stream().count(); });
        t("BitSet stream sum", () -> { BitSet b = new BitSet(); b.set(1); b.set(5); b.set(9); return b.stream().sum(); });
        t("BitSet stream max", () -> { BitSet b = new BitSet(); b.set(3); b.set(77); return b.stream().max(); });
        t("BitSet stream late", () -> { BitSet b = new BitSet(); b.set(1); IntStream s = b.stream(); b.set(2); return a(s); });
        t("BitSet stream late clear", () -> { BitSet b = new BitSet(); b.set(1); b.set(2); IntStream s = b.stream(); b.clear(); return a(s); });
        t("BitSet stream modified during clear", () -> { BitSet b = new BitSet(); b.set(1); b.set(2); b.set(3); StringBuilder sb = new StringBuilder(); try { b.stream().forEach(i -> { sb.append(i); b.clear(i + 1); }); return sb + " done"; } catch (ConcurrentModificationException e) { return sb + " CME"; } });
        t("BitSet stream boxed", () -> { BitSet b = new BitSet(); b.set(4); b.set(2); return b.stream().boxed().collect(Collectors.toList()); });
        t("BitSet stream to BitSet", () -> IntStream.of(5, 1, 3).collect(BitSet::new, BitSet::set, BitSet::or));
        t("BitSet stream iterator", () -> { BitSet b = new BitSet(); b.set(7); b.set(9); PrimitiveIterator.OfInt it = b.stream().iterator(); return it.nextInt() + " " + it.nextInt() + " " + it.hasNext(); });
        t("BitSet stream skip limit", () -> { BitSet b = new BitSet(); b.set(0, 10); return a(b.stream().skip(3).limit(3)); });
        t("BitSet stream sizes", () -> { BitSet b = new BitSet(); b.set(0, 10); Spliterator.OfInt s = b.stream().spliterator(); return s.getExactSizeIfKnown(); });
        t("BitSet stream parallel", () -> { BitSet b = new BitSet(); b.set(0, 100); return b.stream().parallel().sum(); });

        // Random streams: same sequence as the generator's own methods
        t("ints()", () -> a(new Random(42).ints().limit(5)));
        t("nextInt same", () -> { Random r = new Random(42); int[] x = new int[5]; for (int i = 0; i < 5; i++) x[i] = r.nextInt(); return Arrays.toString(x); });
        t("ints(n)", () -> a(new Random(42).ints(5)));
        t("ints(n) consumes generator", () -> { Random r = new Random(42); r.ints(3).sum(); return r.nextInt(); });
        t("ints(n) lazily", () -> { Random r = new Random(42); IntStream s = r.ints(3); int first = r.nextInt(); return first + " " + a(s); });
        t("ints(0)", () -> a(new Random(42).ints(0)));
        t("ints(-1)", () -> new Random(42).ints(-1));
        t("ints(origin, bound)", () -> a(new Random(42).ints(8, 10, 20)));
        t("ints(origin, bound) infinite", () -> a(new Random(42).ints(0, 100).limit(6)));
        t("ints(n, origin, bound)", () -> a(new Random(7).ints(6, -5, 5)));
        t("ints(n, 0, 1)", () -> a(new Random(7).ints(4, 0, 1)));
        t("ints(n, same bound)", () -> new Random(42).ints(3, 5, 5));
        t("ints(n, reversed)", () -> new Random(42).ints(3, 6, 5));
        t("ints(origin, reversed)", () -> new Random(42).ints(6, 5));
        t("ints(n < 0, valid range)", () -> new Random(42).ints(-1, 5, 6));
        t("ints wide range", () -> a(new Random(3).ints(5, Integer.MIN_VALUE, Integer.MAX_VALUE)));
        t("ints wide range 2", () -> a(new Random(3).ints(5, -2000000000, 2000000000)));
        t("ints power of two range", () -> a(new Random(3).ints(6, 0, 16)));
        t("ints non power of two range", () -> a(new Random(3).ints(6, 0, 10)));
        t("ints offset range", () -> a(new Random(3).ints(6, 100, 116)));
        t("nextInt(bound) same as ints(0,bound)", () -> { Random r = new Random(11); int[] x = new int[5]; for (int i = 0; i < 5; i++) x[i] = r.nextInt(10); return Arrays.toString(x) + " " + a(new Random(11).ints(5, 0, 10)); });
        t("longs()", () -> a(new Random(42).longs().limit(3)));
        t("longs(n)", () -> a(new Random(42).longs(3)));
        t("longs(n) is nextLong", () -> { Random r = new Random(42); return r.nextLong() + " " + r.nextLong(); });
        t("longs(-1)", () -> new Random(42).longs(-1));
        t("longs(n, origin, bound)", () -> a(new Random(9).longs(5, 100, 200)));
        t("longs(n, origin, bound) big", () -> a(new Random(9).longs(4, -4000000000L, 4000000000L)));
        t("longs(n, origin, bound) tiny", () -> a(new Random(9).longs(4, 5, 6)));
        t("longs(n, origin, bound) power of two", () -> a(new Random(9).longs(5, 0, 1L << 40)));
        t("longs(n, origin, bound) full range", () -> a(new Random(9).longs(3, Long.MIN_VALUE, Long.MAX_VALUE)));
        t("longs(origin, bound)", () -> a(new Random(9).longs(1000, 2000).limit(3)));
        t("longs(n, reversed)", () -> new Random(9).longs(3, 6, 5));
        t("doubles()", () -> a(new Random(42).doubles().limit(3)));
        t("doubles(n)", () -> a(new Random(42).doubles(3)));
        t("doubles(n) is nextDouble", () -> { Random r = new Random(42); return r.nextDouble() + " " + r.nextDouble(); });
        t("doubles(-1)", () -> new Random(42).doubles(-1));
        t("doubles(n, origin, bound)", () -> a(new Random(5).doubles(3, 10.0, 20.0)));
        t("doubles(n, origin, bound) unit", () -> a(new Random(5).doubles(3, 0.0, 1.0)));
        t("doubles(n, origin, bound) negative", () -> a(new Random(5).doubles(3, -1.0, 1.0)));
        t("doubles(origin, bound)", () -> a(new Random(5).doubles(0.5, 1.5).limit(2)));
        t("doubles(n, same)", () -> new Random(5).doubles(3, 1.0, 1.0));
        t("doubles(n, reversed)", () -> new Random(5).doubles(3, 2.0, 1.0));
        t("doubles(n, NaN)", () -> new Random(5).doubles(3, Double.NaN, 1.0));
        t("doubles(n, NaN bound)", () -> new Random(5).doubles(3, 0.0, Double.NaN));
        t("doubles(n, infinite bound)", () -> new Random(5).doubles(3, 0.0, Double.POSITIVE_INFINITY).count());
        t("doubles(n, huge range)", () -> a(new Random(5).doubles(2, -Double.MAX_VALUE, Double.MAX_VALUE)));
        t("doubles in range", () -> new Random(5).doubles(1000, 2.0, 3.0).allMatch(d -> d >= 2.0 && d < 3.0));
        t("ints in range", () -> new Random(5).ints(1000, -3, 4).allMatch(i -> i >= -3 && i < 4));
        t("ints range covers", () -> new Random(5).ints(1000, -3, 4).distinct().count());
        t("ints sequence continues", () -> { Random r = new Random(1); IntStream a1 = r.ints(2); IntStream a2 = r.ints(2); return a(a1) + a(a2); });
        t("ints equal to nextInt after mixed use", () -> { Random r = new Random(1); int x = r.nextInt(); int[] y = r.ints(2).toArray(); int z = r.nextInt(); return x + " " + Arrays.toString(y) + " " + z; });
        t("ints unseeded size", () -> new Random().ints(7).count());
        t("ints unseeded range", () -> new Random().ints(100, 5, 8).allMatch(i -> i >= 5 && i < 8));
        t("ints boxed sorted", () -> new Random(1).ints(5, 0, 100).boxed().sorted().collect(Collectors.toList()));
        t("ints sum", () -> new Random(1).ints(100, 0, 10).sum());
        t("ints parallel same elements", () -> new Random(1).ints(100, 0, 10).parallel().sum());
        t("ints distinct limit", () -> new Random(1).ints(0, 5).distinct().limit(5).sorted().boxed().collect(Collectors.toList()));
        t("doubles average", () -> new Random(1).doubles(1000).average().getAsDouble() > 0.4);
        t("doubles summary", () -> { DoubleSummaryStatistics s = new Random(1).doubles(500).summaryStatistics(); return s.getCount() + " " + (s.getMin() >= 0) + " " + (s.getMax() < 1); });
        t("ints subclass", () -> { Random r = new Random(1) { protected int next(int bits) { return 7; } }; return a(r.ints(3, 0, 100)); });
        t("ints subclass nextInt unaffected", () -> { Random r = new Random(1) { public int nextInt() { return 7; } }; return a(r.ints(3)); });
        t("ints subclass nextInt(bound)", () -> { Random r = new Random(1) { public int nextInt(int b) { return 7; } }; return a(r.ints(3, 0, 100)); });
        t("longs subclass nextLong", () -> { Random r = new Random(1) { public long nextLong() { return 9; } }; return a(r.longs(2)); });
        t("longs subclass nextLong range", () -> { Random r = new Random(1) { public long nextLong() { return 9; } }; return a(r.longs(2, 0, 100)); });
        t("longs subclass nextLong range wide", () -> { Random r = new Random(1) { public long nextLong() { return -9; } }; return a(r.longs(2, Long.MIN_VALUE, Long.MAX_VALUE)); });
        t("doubles subclass nextDouble range", () -> { Random r = new Random(1) { public double nextDouble() { return 0.25; } }; return a(r.doubles(2, 10, 20)); });
        t("doubles subclass nextDouble", () -> { Random r = new Random(1) { public double nextDouble() { return 0.5; } }; return a(r.doubles(2)); });
        t("ThreadLocalRandom ints", () -> ThreadLocalRandom.current().ints(5, 0, 10).filter(i -> i >= 0 && i < 10).count());
        t("ThreadLocalRandom doubles", () -> ThreadLocalRandom.current().doubles(5).filter(d -> d >= 0 && d < 1).count());
        t("ThreadLocalRandom longs", () -> ThreadLocalRandom.current().longs(5, 10, 20).filter(l -> l >= 10 && l < 20).count());
        t("ThreadLocalRandom ints bad", () -> ThreadLocalRandom.current().ints(5, 3, 3));
        t("ThreadLocalRandom ints negative", () -> ThreadLocalRandom.current().ints(-1));
    }
}
