package util;

import java.util.*;

/**
 * Tree bins resist colliding hashes (spec 1.H6, section 12): with keys of one Comparable class,
 * filling and searching one bin of n keys takes about n log n key comparisons, not n squared.
 */
public class HashTreeLookups {
    static long equalsCalls;

    static final class Same implements Comparable<Same> {
        final int v;
        Same(int v) { this.v = v; }
        public int hashCode() { return 42; }
        public boolean equals(Object o) { equalsCalls++; return o instanceof Same && ((Same) o).v == v; }
        public int compareTo(Same o) { return Integer.compare(v, o.v); }
    }

    /** Collides too, but is not Comparable: lookups must still find every key. */
    static final class Plain {
        final int v;
        Plain(int v) { this.v = v; }
        public int hashCode() { return 7; }
        public boolean equals(Object o) { return o instanceof Plain && ((Plain) o).v == v; }
    }

    public static void main(String[] args) {
        int n = 6000;
        HashMap<Same, Integer> m = new HashMap<>();
        for (int i = 0; i < n; i++) m.put(new Same(i), i);
        int found = 0;
        for (int i = 0; i < n; i++) if (m.get(new Same(i)) == i) found++;
        for (int i = 0; i < n; i += 2) m.remove(new Same(i));
        boolean absent = !m.containsKey(new Same(0)) && m.containsKey(new Same(1));
        // A list-searched bin would make about n * n / 2 = 18,000,000 calls here.
        long bound = 100L * n;
        System.out.println(n + " colliding Comparable keys: found " + found + ", size after removing half " + m.size() + ", lookups right " + absent
            + ", equals calls under " + bound + ": " + (equalsCalls < bound));
        HashMap<Plain, Integer> p = new HashMap<>();
        for (int i = 0; i < 400; i++) p.put(new Plain(i), i);
        int hits = 0;
        for (int i = 0; i < 400; i++) if (p.get(new Plain(i)) == i) hits++;
        System.out.println("400 colliding keys that are not Comparable: found " + hits + " of " + p.size() + ", missing key " + p.get(new Plain(400)));
        System.out.println("done");
    }
}
