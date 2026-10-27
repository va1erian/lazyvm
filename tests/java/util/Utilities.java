package util;

import java.util.*;
import java.util.function.*;

/** Optional, OptionalInt, OptionalLong, OptionalDouble and Objects: docs/design/util.md sections 2 and 3. */
public class Utilities {
    static int calls;

    static String t(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (Throwable e) {
            return e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    static void p(String label, Object v) { System.out.println(label + ": " + v); }

    /** A key with a chosen hashCode that counts equals calls. */
    static final class K {
        final int h;
        K(int h) { this.h = h; }
        public int hashCode() { return h; }
        public boolean equals(Object o) { calls++; return o instanceof K && ((K) o).h == h; }
        public String toString() { return "K" + h; }
    }

    public static void main(String[] args) {
        singletons();
        strings();
        hashes();
        equality();
        nullChecks();
        operations();
        primitives();
        objects();
        fuzz();
        System.out.println("done");
    }

    static void singletons() {
        // O1: the empty Optionals are one object each; of() is a new object each time.
        Object e = Optional.empty();
        p("empty same", (e == Optional.empty()) + " " + (e == Optional.ofNullable(null))
                + " " + (OptionalInt.empty() == OptionalInt.empty()) + " " + (OptionalLong.empty() == OptionalLong.empty())
                + " " + (OptionalDouble.empty() == OptionalDouble.empty()));
        p("of fresh", (Optional.of("a") == Optional.of("a")) + " " + (OptionalInt.of(3) == OptionalInt.of(3))
                + " " + (OptionalLong.of(0) == OptionalLong.of(0)) + " " + (OptionalDouble.of(0) == OptionalDouble.of(0))
                + " " + (OptionalInt.of(0) == OptionalInt.empty()));
        // O1: operations that give "nothing" return that same empty object.
        Optional<String> x = Optional.of("x");
        p("empty results", (x.filter(s -> false) == e) + " " + (x.map(s -> null) == e)
                + " " + (Optional.<String>empty().map(s -> s) == e) + " " + (Optional.<String>empty().filter(s -> true) == e)
                + " " + (Optional.<String>empty().flatMap(s -> Optional.of(s)) == e));
        // O7: filter that passes and flatMap give back the very Optional they had.
        Optional<String> y = Optional.of("y");
        p("identity", (x.filter(s -> true) == x) + " " + (x.flatMap(s -> y) == y) + " " + (x.map(s -> s) == x)
                + " " + (x.map(s -> s).get() == x.get()));
    }

    static void strings() {
        // O2: toString formats.
        p("toString", Optional.empty() + " " + Optional.of("s") + " " + Optional.of(Arrays.asList(1, 2))
                + " " + Optional.of(new int[0]).toString().startsWith("Optional[[I@") + " " + Optional.of(""));
        p("toString int", OptionalInt.empty() + " " + OptionalInt.of(0) + " " + OptionalInt.of(Integer.MIN_VALUE));
        p("toString long", OptionalLong.empty() + " " + OptionalLong.of(-1) + " " + OptionalLong.of(Long.MAX_VALUE));
        p("toString double", OptionalDouble.empty() + " " + OptionalDouble.of(1e10) + " " + OptionalDouble.of(-0.0)
                + " " + OptionalDouble.of(Double.NaN) + " " + OptionalDouble.of(Double.NEGATIVE_INFINITY)
                + " " + OptionalDouble.of(0.1) + " " + OptionalDouble.of(1e-5) + " " + OptionalDouble.of(Double.MIN_VALUE));
        // O2: a value whose toString is null prints as "null".
        Object nullString = new Object() { public String toString() { return null; } };
        p("toString null text", Optional.of(nullString));
    }

    static void hashes() {
        // O3: hashCode of the value, 0 when empty.
        p("hash", Optional.empty().hashCode() + " " + Optional.of(1).hashCode() + " " + Optional.of("ab").hashCode()
                + " " + Optional.of(new K(-9)).hashCode() + " " + Optional.of(Arrays.asList(1, 2)).hashCode());
        p("hash int", OptionalInt.empty().hashCode() + " " + OptionalInt.of(-7).hashCode() + " " + OptionalInt.of(0).hashCode());
        p("hash long", OptionalLong.empty().hashCode() + " " + OptionalLong.of(1L << 40).hashCode() + " " + OptionalLong.of(-1).hashCode()
                + " " + OptionalLong.of(0x123456789ABCDEFL).hashCode());
        p("hash double", OptionalDouble.empty().hashCode() + " " + OptionalDouble.of(Double.NaN).hashCode() + " "
                + OptionalDouble.of(-0.0).hashCode() + " " + OptionalDouble.of(0.0).hashCode() + " " + OptionalDouble.of(1.5).hashCode()
                + " " + OptionalDouble.of(Double.longBitsToDouble(0x7ff8000000000001L)).hashCode());
    }

    static void equality() {
        // O4: equal only to the same class with an equal value.
        Optional<Integer> one = Optional.of(1);
        p("equals", one.equals(Optional.of(1)) + " " + one.equals(Optional.of(2)) + " " + one.equals(1)
                + " " + one.equals(null) + " " + Optional.empty().equals(Optional.empty()) + " " + one.equals(Optional.empty())
                + " " + Optional.empty().equals(one) + " " + one.equals(OptionalInt.of(1)) + " " + OptionalInt.of(1).equals(OptionalLong.of(1)));
        p("equals int", OptionalInt.of(5).equals(OptionalInt.of(5)) + " " + OptionalInt.of(5).equals(OptionalInt.empty())
                + " " + OptionalInt.empty().equals(OptionalInt.empty()) + " " + OptionalInt.of(0).equals(OptionalInt.empty())
                + " " + OptionalLong.of(0).equals(OptionalLong.empty()) + " " + OptionalLong.of(7).equals(OptionalLong.of(7)));
        // O4: OptionalDouble compares like Double.compare: NaN equals NaN, 0.0 differs from -0.0.
        p("equals double", OptionalDouble.of(Double.NaN).equals(OptionalDouble.of(Double.NaN)) + " "
                + OptionalDouble.of(0.0).equals(OptionalDouble.of(-0.0)) + " " + OptionalDouble.of(0.0).equals(OptionalDouble.empty())
                + " " + OptionalDouble.of(Double.NaN).equals(OptionalDouble.of(Double.longBitsToDouble(0x7ff8000000000001L))));
        // O4: the value's equals is called with the other value.
        calls = 0;
        boolean eq = Optional.of(new K(3)).equals(Optional.of(new K(3)));
        p("equals calls", eq + " " + calls);
    }

    static void nullChecks() {
        Optional<String> e = Optional.empty();
        Optional<String> x = Optional.of("x");
        // O5: which null arguments throw NullPointerException.
        p("of null", t(() -> Optional.of(null)));
        p("ifPresent null", t(() -> e.ifPresent(null)) + " " + t(() -> x.ifPresent(null)));
        p("filter null", t(() -> e.filter(null)) + " " + t(() -> x.filter(null)));
        p("map null", t(() -> e.map(null)) + " " + t(() -> x.map(null)));
        p("flatMap null", t(() -> e.flatMap(null)) + " " + t(() -> x.flatMap(null)) + " " + t(() -> x.flatMap(s -> null)));
        p("orElseGet null", t(() -> e.orElseGet(null)) + " " + t(() -> x.orElseGet(null)));
        p("orElseThrow null", t(() -> e.orElseThrow((Supplier<RuntimeException>) null))
                + " " + t(() -> x.orElseThrow((Supplier<RuntimeException>) null))
                + " " + t(() -> e.orElseThrow((Supplier<RuntimeException>) () -> null)));
        p("orElse null", e.orElse(null) + " " + x.orElse(null));
        p("prim nulls", t(() -> OptionalInt.empty().ifPresent(null)) + " " + t(() -> OptionalInt.of(1).ifPresent(null))
                + " " + t(() -> OptionalInt.of(1).orElseGet(null)) + " " + t(() -> OptionalInt.empty().orElseGet(null))
                + " " + t(() -> OptionalInt.of(1).orElseThrow(null)) + " " + t(() -> OptionalInt.empty().orElseThrow(null)));
        p("prim nulls long", t(() -> OptionalLong.empty().ifPresent(null)) + " " + t(() -> OptionalLong.of(1).ifPresent(null))
                + " " + t(() -> OptionalLong.of(1).orElseGet(null)) + " " + t(() -> OptionalLong.empty().orElseGet(null))
                + " " + t(() -> OptionalLong.of(1).orElseThrow(null)) + " " + t(() -> OptionalLong.empty().orElseThrow(null)));
        p("prim nulls double", t(() -> OptionalDouble.empty().ifPresent(null)) + " " + t(() -> OptionalDouble.of(1).ifPresent(null))
                + " " + t(() -> OptionalDouble.of(1).orElseGet(null)) + " " + t(() -> OptionalDouble.empty().orElseGet(null))
                + " " + t(() -> OptionalDouble.of(1).orElseThrow(null)) + " " + t(() -> OptionalDouble.empty().orElseThrow(null))
                + " " + t(() -> OptionalDouble.empty().orElseThrow(() -> null)));
        // O6: get on an empty Optional.
        p("get empty", t(() -> e.get()) + " | " + t(() -> OptionalInt.empty().getAsInt()) + " | "
                + t(() -> OptionalLong.empty().getAsLong()) + " | " + t(() -> OptionalDouble.empty().getAsDouble()));
    }

    static void operations() {
        Optional<String> e = Optional.empty();
        Optional<String> x = Optional.of("x");
        StringBuilder log = new StringBuilder();
        // O8: suppliers and functions run only when they are needed, once.
        p("orElseGet", x.orElseGet(() -> { log.append("g"); return "z"; }) + " " + e.orElseGet(() -> { log.append("G"); return "z"; }));
        x.ifPresent(s -> log.append("i").append(s));
        e.ifPresent(s -> log.append("I"));
        x.filter(s -> { log.append("f"); return true; });
        e.filter(s -> { log.append("F"); return true; });
        x.map(s -> { log.append("m"); return s; });
        e.map(s -> { log.append("M"); return s; });
        x.flatMap(s -> { log.append("l"); return e; });
        e.flatMap(s -> { log.append("L"); return e; });
        x.orElseThrow(() -> { log.append("t"); return new IllegalStateException(); });
        p("call log", log);
        // O8: orElseThrow throws exactly what the supplier made.
        IllegalStateException made = new IllegalStateException("made");
        try {
            e.orElseThrow(() -> made);
        } catch (IllegalStateException got) {
            p("orElseThrow same", got == made);
        }
        p("orElseThrow", t(() -> OptionalInt.empty().orElseThrow(() -> new IllegalArgumentException("ia")))
                + " | " + t(() -> OptionalLong.empty().orElseThrow(UnsupportedOperationException::new)));
        p("orElse", e.orElse("d") + " " + x.orElse("d") + " " + OptionalInt.empty().orElse(4) + " " + OptionalLong.of(9).orElse(4)
                + " " + OptionalDouble.empty().orElse(0.5) + " " + OptionalDouble.of(2).orElseGet(() -> 3));
        p("isPresent", e.isPresent() + " " + x.isPresent() + " " + OptionalInt.of(0).isPresent() + " " + OptionalLong.empty().isPresent()
                + " " + OptionalDouble.of(Double.NaN).isPresent());
        // O7: map gives a new Optional of the result; flatMap gives the function's Optional.
        p("map flatMap", x.map(s -> s + "!") + " " + x.map(String::length) + " " + x.flatMap(s -> Optional.of(s + s))
                + " " + Optional.of(5).map(i -> i * 2).filter(i -> i > 9).map(Object::toString));
    }

    static void primitives() {
        StringBuilder log = new StringBuilder();
        OptionalInt.of(4).ifPresent(i -> log.append("i").append(i));
        OptionalInt.empty().ifPresent(i -> log.append("I"));
        OptionalLong.of(5).ifPresent(i -> log.append("l").append(i));
        OptionalDouble.of(6).ifPresent(d -> log.append("d").append(d));
        OptionalInt.of(1).orElseGet(() -> { log.append("g"); return 0; });
        p("prim log", log);
        p("prim get", OptionalInt.of(-1).getAsInt() + " " + OptionalLong.of(Long.MIN_VALUE).getAsLong() + " "
                + OptionalDouble.of(-0.0).getAsDouble() + " " + (1 / OptionalDouble.of(-0.0).getAsDouble()));
    }

    static void objects() {
        // J1: equals and deepEquals.
        int[] a = {1, 2};
        p("equals", Objects.equals(null, null) + " " + Objects.equals(null, 1) + " " + Objects.equals(1, null) + " "
                + Objects.equals(1, 1) + " " + Objects.equals(a, new int[]{1, 2}) + " " + Objects.equals(a, a)
                + " " + Objects.equals(Double.NaN, Double.NaN) + " " + Objects.equals(0.0, -0.0));
        p("deepEquals", Objects.deepEquals(a, new int[]{1, 2}) + " " + Objects.deepEquals(new int[]{1}, new long[]{1})
                + " " + Objects.deepEquals(new Object[]{new int[]{1}}, new Object[]{new int[]{1}}) + " " + Objects.deepEquals(null, null)
                + " " + Objects.deepEquals(new Object[0], null) + " " + Objects.deepEquals(null, new Object[0])
                + " " + Objects.deepEquals(new Object[0], new String[0]) + " " + Objects.deepEquals(new double[]{Double.NaN}, new double[]{Double.NaN})
                + " " + Objects.deepEquals(new double[]{0.0}, new double[]{-0.0}) + " " + Objects.deepEquals("a", "a")
                + " " + Objects.deepEquals(new Object[]{null}, new Object[]{null}) + " " + Objects.deepEquals(new int[0], new Object[0]));
        // J1: Objects.equals calls a.equals(b) once, and not at all for the same reference or a null.
        calls = 0;
        K k = new K(1);
        Objects.equals(k, k);
        Objects.equals(k, null);
        Objects.equals(null, k);
        int before = calls;
        Objects.equals(k, new K(1));
        p("equals calls", before + " " + calls);
        // J2: hashCode and hash.
        p("hashCode", Objects.hashCode(null) + " " + Objects.hashCode("ab") + " " + Objects.hashCode(new K(-5)));
        p("hash", Objects.hash() + " " + Objects.hash((Object[]) null) + " " + Objects.hash((Object) null) + " "
                + Objects.hash(1, "a", null) + " " + Objects.hash(new K(7), new K(8)) + " " + (Objects.hash((Object) a) == 31 + a.hashCode())
                + " " + Objects.hash(Long.MAX_VALUE, -0.0, 'c', true, (byte) -1));
        // J3: toString.
        p("toString", Objects.toString(null) + " " + Objects.toString(null, null) + " " + Objects.toString(null, "d")
                + " " + Objects.toString(1, "d") + " " + Objects.toString(Arrays.asList(1), null));
        // J4: compare skips the comparator for the same reference.
        calls = 0;
        Comparator<String> counting = (s1, s2) -> { calls++; return s1.length() - s2.length(); };
        String s = "s";
        p("compare", Objects.compare(null, null, null) + " " + Objects.compare(s, s, null) + " " + Objects.compare(s, s, counting)
                + " " + calls + " " + Objects.compare("aa", "b", counting) + " " + calls + " " + t(() -> Objects.compare("a", "b", null))
                + " " + Objects.compare(null, "b", (u, v) -> 7));
        // J5: requireNonNull.
        p("requireNonNull", t(() -> Objects.requireNonNull(null)) + " | " + t(() -> Objects.requireNonNull(null, "msg"))
                + " | " + t(() -> Objects.requireNonNull(null, (String) null)) + " | " + t(() -> Objects.requireNonNull(null, () -> "sup"))
                + " | " + t(() -> Objects.requireNonNull(null, () -> null)) + " | " + t(() -> Objects.requireNonNull(null, (Supplier<String>) null)));
        StringBuilder log = new StringBuilder();
        Object back = Objects.requireNonNull(s, () -> { log.append("called"); return "x"; });
        p("requireNonNull pass", (back == s) + " " + (Objects.requireNonNull(s, (Supplier<String>) null) == s)
                + " " + (Objects.requireNonNull(s, (String) null) == s) + " [" + log + "]");
        // J6: isNull, nonNull.
        p("isNull", Objects.isNull(null) + " " + Objects.isNull(s) + " " + Objects.nonNull(null) + " " + Objects.nonNull(s));
    }

    /** O3, O4, J2: hashes and equality on many values from a fixed generator, folded into checksums. */
    static void fuzz() {
        long g = 0x9E3779B97F4A7C15L;
        int sumI = 0, sumL = 0, sumD = 0, sumH = 0, eqs = 0;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            g ^= g << 13;
            g ^= g >>> 7;
            g ^= g << 17;
            int iv = (int) g;
            long lv = g;
            double dv = (i % 3 == 0) ? Double.longBitsToDouble(g) : (double) (g >> 20) / (1 + (i & 15));
            sumI = 31 * sumI + OptionalInt.of(iv).hashCode();
            sumL = 31 * sumL + OptionalLong.of(lv).hashCode();
            sumD = 31 * sumD + OptionalDouble.of(dv).hashCode();
            sumH = 31 * sumH + Objects.hash(iv, lv, dv, i % 5 == 0 ? null : "s" + (iv & 255));
            if (OptionalDouble.of(dv).equals(OptionalDouble.of(Double.longBitsToDouble(Double.doubleToRawLongBits(dv))))) eqs++;
            if (i % 997 == 0) sample.append(OptionalDouble.of(dv)).append(' ').append(OptionalLong.of(lv)).append(' ');
        }
        p("fuzz", sumI + " " + sumL + " " + sumD + " " + sumH + " " + eqs);
        p("fuzz sample", sample.toString().trim());
    }
}
