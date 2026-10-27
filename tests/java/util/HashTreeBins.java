package util;

import java.util.*;

/**
 * Where HashMap puts the entries of a crowded bin. The order tells when a bin is reorganised (nine
 * entries once the table has 64 buckets), where later entries land, and when it is undone.
 */
public class HashTreeBins {
    static Map<Integer, Integer> table64() {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 1; i <= 30; i++) m.put(i, -i);
        return m;
    }

    static String order(Map<Integer, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (int k : m.keySet()) if (k >= 4096) sb.append(k / 4096).append(' ');
        return sb.toString();
    }

    static void run(String name, int[] insertion) {
        Map<Integer, Integer> m = table64();
        StringBuilder sb = new StringBuilder(name + ":");
        for (int i = 0; i < insertion.length; i++) {
            m.put(insertion[i] * 4096, i);
            if (i >= 6) sb.append("\n  after ").append(i + 1).append(": ").append(order(m));
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        run("ascending", new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});
        run("descending", new int[]{15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1});
        run("shuffled", new int[]{9, 3, 14, 6, 1, 11, 15, 5, 8, 13, 2, 7, 12, 4, 10});
        // The compute family converts a bin one entry earlier than put does.
        for (int how = 0; how < 3; how++) {
            Map<Integer, Integer> m = table64();
            StringBuilder sb = new StringBuilder("compute family " + how + ":");
            for (int i = 1; i <= 11; i++) {
                int k = i * 4096;
                if (how == 0) m.computeIfAbsent(k, x -> 1);
                else if (how == 1) m.compute(k, (x, v) -> 1);
                else m.merge(k, 1, (a, b) -> a);
                if (i >= 6) sb.append("\n  after ").append(i).append(": ").append(order(m));
            }
            System.out.println(sb);
        }
        // Removals: through the map reorganise the bin, through an iterator they leave the order alone.
        for (int mode = 0; mode < 2; mode++) {
            Map<Integer, Integer> m = table64();
            for (int i : new int[]{8, 3, 12, 1, 6, 10, 14, 2, 5, 7, 9, 11, 13, 4, 15}) m.put(i * 4096, i);
            StringBuilder sb = new StringBuilder("removals " + (mode == 0 ? "map" : "iterator") + ": " + order(m));
            for (int r : new int[]{8, 1, 15, 4, 12, 6}) {
                if (mode == 0) {
                    m.remove(r * 4096);
                } else {
                    for (Iterator<Integer> it = m.keySet().iterator(); it.hasNext();) if (it.next() == r * 4096) it.remove();
                }
                sb.append("\n  without ").append(r).append(": ").append(order(m));
            }
            System.out.println(sb);
        }
        // Shrinking by map removals. These cases do not separate the shrink rules; HashTreeRules does.
        for (int keep = 9; keep >= 4; keep--) {
            Map<Integer, Integer> m = table64();
            for (int i = 1; i <= 12; i++) m.put(i * 4096, i);
            int[] gone = {5, 11, 2, 8, 12, 3, 9, 6, 1};
            for (int i = 0; i < 12 - keep; i++) m.remove(gone[i] * 4096);
            StringBuilder sb = new StringBuilder("shrunk to " + m.size() + " (" + keep + " target): " + order(m));
            m.put(20 * 4096, 0);
            m.put(21 * 4096, 0);
            sb.append("\n  plus two: ").append(order(m));
            System.out.println(sb);
        }
        // Growing the table splits a bin; a half of six entries or fewer reverts to a plain chain.
        for (int n : new int[]{9, 12, 14, 17, 20}) {
            Map<Integer, Integer> m = new HashMap<>();
            for (int i = 1; i <= 24; i++) m.put(i, i);
            for (int i = 1; i <= n; i++) m.put(i * 64 + 0, i);
            StringBuilder sb = new StringBuilder("split " + n + ":");
            sb.append(' ').append(m.size());
            for (int k = 25; k <= 60; k++) m.put(k, k);
            for (int k : m.keySet()) if (k >= 64 && k % 64 == 0) sb.append(' ').append(k / 64);
            for (int k = 61; k <= 100; k++) m.put(k, k);
            sb.append(" |");
            for (int k : m.keySet()) if (k >= 64 && k % 64 == 0) sb.append(' ').append(k / 64);
            System.out.println(sb);
        }
        // Keys of different classes with one hash code; the output keeps arrival order, so the class-name rule is pinned in HashTreeRules.
        Map<Object, Integer> mixed = new HashMap<>();
        for (int i = 1; i <= 30; i++) mixed.put(i, i);
        Object[] same = {1L, (short) 1, (byte) 1, '\u0001'};
        for (int i = 1; i <= 4; i++) mixed.put(1 + 64 * i, 0);
        for (Object o : same) mixed.put(o, 0);
        for (int i = 5; i <= 7; i++) mixed.put(1 + 64 * i, 0);
        StringBuilder sb = new StringBuilder("mixed classes:");
        for (Object k : mixed.keySet()) if (k instanceof Number && ((Number) k).intValue() % 64 == 1 || k instanceof Character) sb.append(' ').append(k.getClass().getSimpleName()).append(((k instanceof Character) ? (int) (Character) k : ((Number) k).intValue()));
        System.out.println(sb);
        // A key class that is Comparable of another type is never compared, so no exception arises.
        Map<Object, Integer> odd = new HashMap<>();
        for (int i = 1; i <= 30; i++) odd.put(i, i);
        for (int i = 0; i < 20; i++) odd.put(new Odd(i), i);
        int found = 0;
        for (int i = 0; i < 20; i++) if (odd.containsKey(new Odd(i))) found++;
        System.out.println("odd keys " + odd.size() + " " + found + " " + odd.remove(new Odd(3)) + " " + odd.size());
        System.out.println("done");
    }

    /** Equal hash codes, Comparable of String rather than of itself. */
    static final class Odd implements Comparable<String> {
        final int id;
        Odd(int id) { this.id = id; }
        public int hashCode() { return 7; }
        public boolean equals(Object o) { return o instanceof Odd && ((Odd) o).id == id; }
        public int compareTo(String s) { throw new IllegalStateException("never called"); }
    }
}
