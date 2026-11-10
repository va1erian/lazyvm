package stream;

import java.nio.CharBuffer;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * The exact calls chars() and codePoints() make on a user-written CharSequence, also when it
 * shrinks or grows while the stream runs; and sources that bind at their first use.
 * L is length(), cN is charAt(N).
 */
public class TextTrace {
    /** A CharSequence that logs every call and can change its content after the Nth charAt. */
    static class Seq implements CharSequence {
        String s; final StringBuilder tr = new StringBuilder();
        int charAts, changeAfter = -1; String changeTo;
        Seq(String s) { this.s = s; }
        Seq change(int afterCharAts, String to) { changeAfter = afterCharAts; changeTo = to; return this; }
        public int length() { tr.append("L "); return s.length(); }
        public char charAt(int i) {
            tr.append("c").append(i).append(' ');
            char c = s.charAt(i);
            if (++charAts == changeAfter) { tr.append("(change) "); s = changeTo; }
            return c;
        }
        public CharSequence subSequence(int a, int b) { return s.subSequence(a, b); }
        public String toString() { return s; }
    }
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r);
    }
    static final String PAIR = "a😀b";
    static String mark(Seq q) { String r = "[" + q.tr.toString().trim() + "]"; q.tr.setLength(0); return r; }
    static String cps(String s) { StringBuilder sb = new StringBuilder(); s.codePoints().forEach(c -> sb.append(Integer.toHexString(c)).append(' ')); return sb.toString().trim(); }

    public static void main(String[] args) {
        // codePoints().iterator(): one hasNext / nextInt at a time
        t("codePoints iterator steps", () -> {
            Seq q = new Seq("abc"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            sb.append("created ").append(mark(q)).append(' ');
            sb.append(it.hasNext()).append(mark(q)).append(' ');
            sb.append(it.hasNext()).append(mark(q)).append(' ');
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            sb.append(it.hasNext()).append(mark(q)).append(' ');
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            sb.append(it.hasNext()).append(mark(q));
            return sb;
        });
        t("codePoints iterator pair steps", () -> {
            Seq q = new Seq(PAIR); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            while (it.hasNext()) { sb.append(Integer.toHexString(it.nextInt())).append(mark(q)).append(' '); }
            sb.append("end").append(mark(q));
            return sb;
        });
        t("codePoints iterator next without hasNext", () -> {
            Seq q = new Seq("ab"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            sb.append(it.nextInt()).append(mark(q)).append(' ').append(it.nextInt()).append(mark(q)).append(' ');
            try { it.nextInt(); } catch (NoSuchElementException e) { sb.append("NSEE"); }
            return sb.append(mark(q));
        });
        t("codePoints iterator boxed next", () -> {
            Seq q = new Seq("ab");
            Iterator<Integer> it = q.codePoints().iterator();
            return it.next() + " " + it.next() + " " + it.hasNext() + " " + mark(q);
        });
        t("codePoints iterator forEachRemaining", () -> {
            Seq q = new Seq(PAIR); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            it.forEachRemaining((IntConsumer) c -> sb.append(Integer.toHexString(c)).append(' '));
            return sb.append(mark(q));
        });
        t("chars iterator steps", () -> {
            Seq q = new Seq("abc"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.chars().iterator();
            sb.append(it.hasNext()).append(mark(q)).append(' ');
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            sb.append(it.nextInt()).append(mark(q)).append(' ');
            sb.append(it.hasNext()).append(mark(q));
            return sb;
        });

        // the sequence shrinks while it is being read
        t("codePoints iterator shrinks to 1", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            while (it.hasNext()) sb.append(it.nextInt()).append(' ');
            return sb.append(mark(q));
        });
        t("codePoints iterator shrinks to empty", () -> {
            Seq q = new Seq("abcd").change(1, ""); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            while (it.hasNext()) sb.append(it.nextInt()).append(' ');
            return sb.append(mark(q));
        });
        t("codePoints iterator shrinks inside pair", () -> {
            Seq q = new Seq(PAIR).change(2, "a\uD83D"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            while (it.hasNext()) sb.append(Integer.toHexString(it.nextInt())).append(' ');
            return sb.append(mark(q));
        });
        t("codePoints iterator shrinks before pair", () -> {
            Seq q = new Seq(PAIR).change(1, "a\uD83D"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            while (it.hasNext()) sb.append(Integer.toHexString(it.nextInt())).append(' ');
            return sb.append(mark(q));
        });
        t("codePoints iterator shrinks then grows", () -> {
            Seq q = new Seq("abcd").change(1, "ab"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            sb.append(it.nextInt()).append(' ');
            q.s = "abcdef";
            while (it.hasNext()) sb.append(it.nextInt()).append(' ');
            return sb.append(mark(q));
        });
        t("codePoints iterator grows", () -> {
            Seq q = new Seq("ab").change(1, "abcd"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.codePoints().iterator();
            while (it.hasNext()) sb.append(it.nextInt()).append(' ');
            return sb.append(mark(q));
        });
        t("chars iterator shrinks", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.chars().iterator();
            while (it.hasNext()) sb.append(it.nextInt()).append(' ');
            return sb.append(mark(q));
        });
        t("chars iterator grows", () -> {
            Seq q = new Seq("ab").change(1, "abcd"); StringBuilder sb = new StringBuilder();
            PrimitiveIterator.OfInt it = q.chars().iterator();
            while (it.hasNext()) sb.append(it.nextInt()).append(' ');
            return sb.append(mark(q));
        });
        t("chars forEach shrinks", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            q.chars().forEach(c -> sb.append(c).append(' '));
            return sb.append(mark(q));
        });
        t("chars forEach shrinks to empty", () -> {
            Seq q = new Seq("abcd").change(1, ""); StringBuilder sb = new StringBuilder();
            q.chars().forEach(c -> sb.append(c).append(' '));
            return sb.append(mark(q));
        });
        t("chars forEach grows", () -> {
            Seq q = new Seq("ab").change(1, "abcd"); StringBuilder sb = new StringBuilder();
            q.chars().forEach(c -> sb.append(c).append(' '));
            return sb.append(mark(q));
        });
        t("chars spliterator tryAdvance shrinks", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            Spliterator.OfInt sp = q.chars().spliterator();
            while (sp.tryAdvance((IntConsumer) c -> sb.append(c).append(' '))) { }
            return sb.append(mark(q));
        });
        t("chars spliterator forEachRemaining shrinks", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            Spliterator.OfInt sp = q.chars().spliterator();
            sp.tryAdvance((IntConsumer) c -> sb.append(c).append(' '));
            sp.forEachRemaining((IntConsumer) c -> sb.append(c).append(' '));
            return sb.append(mark(q));
        });
        t("codePoints forEach shrinks", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            q.codePoints().forEach(c -> sb.append(c).append(' '));
            return sb.append(mark(q));
        });
        t("codePoints forEach shrinks inside pair", () -> {
            Seq q = new Seq(PAIR).change(2, "a\uD83D"); StringBuilder sb = new StringBuilder();
            q.codePoints().forEach(c -> sb.append(Integer.toHexString(c)).append(' '));
            return sb.append(mark(q));
        });
        t("codePoints forEach grows", () -> {
            Seq q = new Seq("ab").change(1, "abcd"); StringBuilder sb = new StringBuilder();
            q.codePoints().forEach(c -> sb.append(c).append(' '));
            return sb.append(mark(q));
        });
        t("codePoints spliterator tryAdvance shrinks", () -> {
            Seq q = new Seq("abcd").change(1, "a"); StringBuilder sb = new StringBuilder();
            Spliterator.OfInt sp = q.codePoints().spliterator();
            while (sp.tryAdvance((IntConsumer) c -> sb.append(c).append(' '))) { }
            return sb.append(mark(q));
        });
        t("codePoints sum shrinks", () -> { Seq q = new Seq("abcd").change(1, "a"); int s = q.codePoints().sum(); return s + " " + mark(q); });
        t("codePoints count shrinks", () -> { Seq q = new Seq("abcd").change(1, "a"); long s = q.codePoints().count(); return s + " " + mark(q); });
        t("codePoints limit shrinks", () -> { Seq q = new Seq("abcd").change(1, "a"); long s = q.codePoints().limit(3).count(); return s + " " + mark(q); });
        t("chars limit shrinks", () -> { Seq q = new Seq("abcd").change(1, "a"); long s = q.chars().limit(3).count(); return s + " " + mark(q); });
        t("chars findFirst shrinks", () -> { Seq q = new Seq("abcd").change(1, "a"); return q.chars().findFirst() + " " + mark(q); });
        t("chars anyMatch shrinks", () -> { Seq q = new Seq("abcd").change(1, "a"); return q.chars().anyMatch(c -> c == 'b') + " " + mark(q); });

        // traces of the other terminal operations
        t("codePoints count trace", () -> { Seq q = new Seq("abc"); long n = q.codePoints().count(); return n + " " + mark(q); });
        t("codePoints pair count trace", () -> { Seq q = new Seq(PAIR); long n = q.codePoints().count(); return n + " " + mark(q); });
        t("codePoints limit trace", () -> { Seq q = new Seq(PAIR); int n = q.codePoints().limit(2).sum(); return n + " " + mark(q); });
        t("codePoints findFirst trace", () -> { Seq q = new Seq(PAIR); return q.codePoints().findFirst() + " " + mark(q); });
        t("codePoints map iterator trace", () -> { Seq q = new Seq(PAIR); PrimitiveIterator.OfInt it = q.codePoints().map(c -> c + 1).iterator(); it.nextInt(); return mark(q); });
        t("codePoints spliterator trace", () -> {
            Seq q = new Seq(PAIR); StringBuilder sb = new StringBuilder();
            Spliterator.OfInt sp = q.codePoints().spliterator();
            sb.append("created ").append(mark(q)).append(' ');
            sb.append(sp.estimateSize()).append(mark(q)).append(' ');
            sb.append(sp.characteristics()).append(mark(q)).append(' ');
            while (sp.tryAdvance((IntConsumer) c -> {})) sb.append("step").append(mark(q)).append(' ');
            return sb.append("end").append(mark(q));
        });
        t("chars spliterator trace", () -> {
            Seq q = new Seq("ab"); StringBuilder sb = new StringBuilder();
            Spliterator.OfInt sp = q.chars().spliterator();
            sb.append("created ").append(mark(q)).append(' ');
            sb.append(sp.estimateSize()).append(mark(q)).append(' ');
            while (sp.tryAdvance((IntConsumer) c -> {})) sb.append("step").append(mark(q)).append(' ');
            return sb.append("end").append(mark(q));
        });
        t("chars spliterator split trace", () -> {
            Seq q = new Seq("abcd");
            Spliterator.OfInt sp = q.chars().spliterator();
            Spliterator.OfInt prefix = sp.trySplit();
            return (prefix == null ? "null" : prefix.estimateSize()) + " " + sp.estimateSize() + " " + mark(q);
        });
        t("chars asDoubleStream trace", () -> { Seq q = new Seq("ab"); double d = q.chars().asDoubleStream().sum(); return d + " " + mark(q); });
        t("chars boxed sorted trace", () -> { Seq q = new Seq("ba"); List<Integer> l = q.chars().boxed().sorted().collect(Collectors.toList()); return l + " " + mark(q); });
        t("chars empty iterator trace", () -> { Seq q = new Seq(""); boolean h = q.chars().iterator().hasNext(); return h + " " + mark(q); });
        t("codePoints empty iterator trace", () -> { Seq q = new Seq(""); boolean h = q.codePoints().iterator().hasNext(); return h + " " + mark(q); });
        t("codePoints trailing high surrogate", () -> cps("a\uD83D"));
        t("codePoints trailing high surrogate iterator", () -> { Seq q = new Seq("a\uD83D"); PrimitiveIterator.OfInt it = q.codePoints().iterator(); StringBuilder sb = new StringBuilder(); while (it.hasNext()) sb.append(Integer.toHexString(it.nextInt())).append(' '); return sb.append(mark(q)); });
        t("StringBuilder codePoints iterator shrinks", () -> {
            StringBuilder sb = new StringBuilder("abcd"); PrimitiveIterator.OfInt it = sb.codePoints().iterator();
            StringBuilder r = new StringBuilder(); r.append(it.nextInt()).append(' '); sb.setLength(1);
            while (it.hasNext()) r.append(it.nextInt()).append(' ');
            return r;
        });
        t("StringBuilder chars iterator shrinks", () -> {
            StringBuilder sb = new StringBuilder("abcd"); PrimitiveIterator.OfInt it = sb.chars().iterator();
            StringBuilder r = new StringBuilder(); r.append(it.nextInt()).append(' '); sb.setLength(1);
            while (it.hasNext()) r.append(it.nextInt()).append(' ');
            return r;
        });
        t("StringBuilder codePoints iterator grows", () -> {
            StringBuilder sb = new StringBuilder("ab"); PrimitiveIterator.OfInt it = sb.codePoints().iterator();
            StringBuilder r = new StringBuilder(); r.append(it.nextInt()).append(' '); sb.append("cd");
            while (it.hasNext()) r.append(it.nextInt()).append(' ');
            return r;
        });
        t("CharBuffer codePoints iterator position", () -> {
            CharBuffer cb = CharBuffer.wrap("abcd"); PrimitiveIterator.OfInt it = cb.codePoints().iterator();
            StringBuilder r = new StringBuilder(); r.append(it.nextInt()).append(' '); cb.position(2);
            while (it.hasNext()) r.append(it.nextInt()).append(' ');
            return r.append(cb.position());
        });
        t("CharBuffer chars iterator limit", () -> {
            CharBuffer cb = CharBuffer.wrap("abcd"); PrimitiveIterator.OfInt it = cb.chars().iterator();
            StringBuilder r = new StringBuilder(); r.append(it.nextInt()).append(' '); cb.limit(2);
            while (it.hasNext()) r.append(it.nextInt()).append(' ');
            return r.append(cb.position());
        });

        // a source that binds at its first use: length at spliterator() or at the first look
        t("chars spliterator binds at first use", () -> {
            StringBuilder sb = new StringBuilder("ab"); Spliterator.OfInt sp = sb.chars().spliterator(); sb.append("cd");
            return sp.estimateSize() + " " + sp.getExactSizeIfKnown() + " " + sp.characteristics();
        });
        t("chars spliterator binds at characteristics", () -> {
            StringBuilder sb = new StringBuilder("ab"); Spliterator.OfInt sp = sb.chars().spliterator();
            int ch = sp.characteristics(); sb.append("cd");
            return ch + " " + sp.estimateSize();
        });
        t("chars spliterator binds at estimate", () -> {
            StringBuilder sb = new StringBuilder("ab"); Spliterator.OfInt sp = sb.chars().spliterator();
            long e = sp.estimateSize(); sb.append("cd");
            StringBuilder r = new StringBuilder(); sp.forEachRemaining((IntConsumer) c -> r.append((char) c));
            return e + " " + sp.estimateSize() + " " + r;
        });
        t("chars spliterator binds at tryAdvance", () -> {
            StringBuilder sb = new StringBuilder("ab"); Spliterator.OfInt sp = sb.chars().spliterator();
            sp.tryAdvance((IntConsumer) c -> {}); sb.append("cd");
            StringBuilder r = new StringBuilder(); sp.forEachRemaining((IntConsumer) c -> r.append((char) c));
            return sp.estimateSize() + " " + r;
        });
        t("chars spliterator bound before spliterator()", () -> {
            StringBuilder sb = new StringBuilder("ab"); IntStream st = sb.chars(); sb.append("c"); Spliterator.OfInt sp = st.spliterator(); sb.append("d");
            return sp.estimateSize();
        });
        t("chars iterator binds at hasNext", () -> {
            StringBuilder sb = new StringBuilder("ab"); PrimitiveIterator.OfInt it = sb.chars().iterator(); sb.append("cd");
            int n = 0; while (it.hasNext()) { it.nextInt(); n++; } return n;
        });
        t("codePoints spliterator binds at first use", () -> {
            StringBuilder sb = new StringBuilder("ab"); Spliterator.OfInt sp = sb.codePoints().spliterator(); sb.append("cd");
            StringBuilder r = new StringBuilder(); sp.forEachRemaining((IntConsumer) c -> r.append((char) c));
            return r + " " + sp.characteristics();
        });
        t("BitSet spliterator binds at first use", () -> {
            BitSet b = new BitSet(); b.set(1); Spliterator.OfInt sp = b.stream().spliterator(); b.set(5);
            return sp.estimateSize() + " " + sp.getExactSizeIfKnown() + " " + sp.characteristics();
        });
        t("BitSet spliterator binds at characteristics", () -> {
            BitSet b = new BitSet(); b.set(1); Spliterator.OfInt sp = b.stream().spliterator();
            int ch = sp.characteristics(); b.set(5);
            StringBuilder r = new StringBuilder(); sp.forEachRemaining((IntConsumer) c -> r.append(c).append(' '));
            return ch + " " + sp.estimateSize() + " " + r;
        });
        t("BitSet spliterator binds at tryAdvance", () -> {
            BitSet b = new BitSet(); b.set(1); b.set(2); Spliterator.OfInt sp = b.stream().spliterator();
            sp.tryAdvance((IntConsumer) c -> {}); b.set(5);
            StringBuilder r = new StringBuilder(); sp.forEachRemaining((IntConsumer) c -> r.append(c).append(' '));
            return sp.estimateSize() + " " + r;
        });
        t("BitSet spliterator after clear", () -> {
            BitSet b = new BitSet(); b.set(1); b.set(2); Spliterator.OfInt sp = b.stream().spliterator(); b.clear();
            return sp.estimateSize() + " " + sp.tryAdvance((IntConsumer) c -> {});
        });
        t("BitSet iterator binds at hasNext", () -> {
            BitSet b = new BitSet(); b.set(1); PrimitiveIterator.OfInt it = b.stream().iterator(); b.set(5);
            StringBuilder r = new StringBuilder(); while (it.hasNext()) r.append(it.nextInt()).append(' '); return r;
        });
        t("BitSet stream created then set", () -> { BitSet b = new BitSet(); IntStream st = b.stream(); b.set(7); return st.boxed().collect(Collectors.toList()); });
        t("BitSet spliterator split", () -> {
            BitSet b = new BitSet(); for (int i = 0; i < 8; i++) b.set(i);
            Spliterator.OfInt sp = b.stream().spliterator(); Spliterator.OfInt p = sp.trySplit();
            return (p == null ? "null" : p.estimateSize()) + " " + sp.estimateSize();
        });
        t("BitSet spliterator empty", () -> { Spliterator.OfInt sp = new BitSet().stream().spliterator(); return sp.estimateSize() + " " + sp.characteristics() + " " + sp.tryAdvance((IntConsumer) c -> {}); });
    }
}
