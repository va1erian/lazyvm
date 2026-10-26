package util;

import java.util.Comparator;

/** Comparator combinators, exercised with an insertion sort. */
public class Comparators {
    static class P {
        final String name; final int age; final long id; final double score;
        P(String name, int age, long id, double score) { this.name = name; this.age = age; this.id = id; this.score = score; }
        String name() { return name; }
        int age() { return age; }
        public String toString() { return name + age; }
    }

    static <T> String sorted(T[] in, Comparator<? super T> c) {
        T[] a = in.clone();
        for (int i = 1; i < a.length; i++) {
            T k = a[i];
            int j = i - 1;
            while (j >= 0 && c.compare(a[j], k) > 0) { a[j + 1] = a[j]; j--; }
            a[j + 1] = k;
        }
        StringBuilder sb = new StringBuilder();
        for (T t : a) sb.append(t).append(' ');
        return sb.toString().trim();
    }

    static void expectNpe(String what, Runnable r) {
        try { r.run(); System.out.println(what + ": none"); }
        catch (NullPointerException e) { System.out.println(what + ": NullPointerException"); }
    }

    public static void main(String[] args) {
        P[] ps = { new P("bob", 30, 3, 1.5), new P("amy", 25, 2, 9.5), new P("cat", 30, 1, 1.5), new P("amy", 20, 4, 4.0) };
        System.out.println(sorted(ps, Comparator.comparing(P::name)));
        System.out.println(sorted(ps, Comparator.comparing(P::name).reversed()));
        System.out.println(sorted(ps, Comparator.comparing(P::name).thenComparing(P::age)));
        System.out.println(sorted(ps, Comparator.comparingInt(P::age).thenComparing(P::name, Comparator.reverseOrder())));
        System.out.println(sorted(ps, Comparator.comparingInt(P::age).thenComparingLong(p -> p.id)));
        System.out.println(sorted(ps, Comparator.comparingDouble((P p) -> p.score).thenComparingInt(P::age)));
        System.out.println(sorted(ps, Comparator.comparingLong((P p) -> p.id).reversed()));
        System.out.println(sorted(ps, Comparator.comparingDouble((P p) -> p.score).thenComparingDouble(p -> -p.age)));
        System.out.println(sorted(ps, Comparator.comparing(P::name, (a, b) -> b.length() - a.length()).thenComparing(Comparator.comparingInt(P::age))));
        Integer[] is = { 5, 3, 9, 1 };
        System.out.println(sorted(is, Comparator.naturalOrder()) + " | " + sorted(is, Comparator.reverseOrder()));
        System.out.println(sorted(is, Comparator.<Integer>naturalOrder().reversed()) + " | " + sorted(is, Comparator.<Integer>reverseOrder().reversed()));
        String[] ss = { "b", null, "a", null, "c" };
        System.out.println(sorted(ss, Comparator.nullsFirst(Comparator.<String>naturalOrder())));
        System.out.println(sorted(ss, Comparator.nullsLast(Comparator.<String>naturalOrder())));
        System.out.println(sorted(ss, Comparator.nullsFirst(Comparator.<String>reverseOrder())));
        System.out.println(sorted(ss, Comparator.nullsLast(null)));
        System.out.println(sorted(ss, Comparator.nullsFirst(Comparator.<String>naturalOrder()).reversed()));
        System.out.println(Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(null, null));
        expectNpe("natural null", () -> Comparator.<String>naturalOrder().compare(null, "a"));
        expectNpe("natural null 2", () -> Comparator.<String>naturalOrder().compare("a", null));
        expectNpe("reverse null", () -> Comparator.<String>reverseOrder().compare("a", null));
        expectNpe("comparing null", () -> Comparator.comparing((java.util.function.Function<String, String>) null));
        expectNpe("comparing 2 null", () -> Comparator.comparing(P::name, null));
        expectNpe("comparingInt null", () -> Comparator.comparingInt(null));
        expectNpe("comparingLong null", () -> Comparator.comparingLong(null));
        expectNpe("comparingDouble null", () -> Comparator.comparingDouble(null));
        expectNpe("thenComparing null", () -> Comparator.<String>naturalOrder().thenComparing((Comparator<String>) null));
        System.out.println("done");
    }
}
