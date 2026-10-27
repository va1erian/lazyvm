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

    static long compareCalls;

    /** Comparable, and counts each compareTo; mixed with other classes in one bin. */
    static final class Counted implements Comparable<Counted> {
        final int v;
        Counted(int v) { this.v = v; }
        public int hashCode() { return 5; }
        public boolean equals(Object o) { return o instanceof Counted && ((Counted) o).v == v; }
        public int compareTo(Counted o) { compareCalls++; return Integer.compare(v, o.v); }
        public String toString() { return "C" + v; }
    }

    /**
     * Not Comparable. O2 hashes like Counted, so it ties with them (class names decide); the
     * others hash apart within the same bin, so no two keys ever fall back on identity hashes.
     */
    static final class Other {
        final int v;
        Other(int v) { this.v = v; }
        public int hashCode() { return v == 2 ? 5 : 5 + 64 * v; }
        public boolean equals(Object o) { return o instanceof Other && ((Other) o).v == v; }
        public String toString() { return "O" + v; }
    }

    /** Two Comparable classes with one hash: between them H6 falls to class names, never identity. */
    static final class TieC implements Comparable<TieC> {
        final int v;
        TieC(int v) { this.v = v; }
        public int hashCode() { return 7; }
        public boolean equals(Object o) { return o instanceof TieC && ((TieC) o).v == v; }
        public int compareTo(TieC o) { compareCalls++; return Integer.compare(v, o.v); }
    }

    static final class TieD implements Comparable<TieD> {
        final int v;
        TieD(int v) { this.v = v; }
        public int hashCode() { return 7; }
        public boolean equals(Object o) { return o instanceof TieD && ((TieD) o).v == v; }
        public int compareTo(TieD o) { compareCalls++; return Integer.compare(v, o.v); }
    }

    static long counted(Runnable r) {
        compareCalls = 0;
        r.run();
        return compareCalls;
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
        // Spec 1.H6, H8: a put makes exactly H6's compareTo calls, in a bin of mixed classes too.
        HashMap<Object, Integer> mixed = new HashMap<>(64);
        StringBuilder calls = new StringBuilder("compareTo calls per put in a mixed bin:");
        for (int i = 0; i < 40; i++) {
            Object k = i % 3 == 2 ? new Other(i) : new Counted((i * 17) % 41);
            calls.append(' ').append(counted(() -> mixed.put(k, 0)));
        }
        calls.append(" | existing:");
        for (int i = 0; i < 40; i++) {
            Object k = i % 3 == 2 ? new Other(i) : new Counted((i * 17) % 41);
            calls.append(' ').append(counted(() -> mixed.put(k, 1)));
        }
        System.out.println(calls + " | size " + mixed.size());
        // Spec 1.H8, H10: after iterator removals the first entry of the sequence need not be the
        // root; a put tries it first.
        HashMap<Object, Integer> thinned = new HashMap<>(64);
        for (int i = 0; i < 12; i++) thinned.put(new Counted(i), i);
        Iterator<Object> it = thinned.keySet().iterator();
        for (int i = 0, left = 6; i < 12; i++) {
            it.next();
            if (i % 2 == 0 && left-- > 0) it.remove();
        }
        StringBuilder again = new StringBuilder("after iterator removals, compareTo calls per existing key:");
        for (Object k : new ArrayList<>(thinned.keySet())) again.append(' ').append(counted(() -> thinned.put(k, 0)));
        again.append(" | per get:");
        for (Object k : new ArrayList<>(thinned.keySet())) again.append(' ').append(counted(() -> thinned.get(k)));
        System.out.println(again + " " + thinned.keySet());
        // Spec 1.H8: keys that never order (no compareTo) are still found when put again, and a new
        // key counts as a change.
        HashMap<Object, Integer> ties = new HashMap<>();
        for (int i = 0; i < 50; i++) ties.put(new Plain(i), i);
        for (int i = 0; i < 50; i++) ties.put(new Plain(i), -i);
        int overwritten = 0;
        for (int v : ties.values()) if (v <= 0) overwritten++;
        Iterator<Object> walk = ties.keySet().iterator();
        walk.next();
        ties.put(new Plain(1000), 1);
        String change;
        try { walk.next(); change = "no CME"; } catch (ConcurrentModificationException e) { change = "CME"; }
        System.out.println("re-put of 50 keys that never order: size " + ties.size() + ", overwritten " + overwritten + ", a new key then " + change);
        // Spec 1.H8: a put searches both sides for its key once, at the first node where compareTo
        // cannot decide, and no more at later such nodes; per-seed counts, folded into a digest.
        long digest = 0, total = 0;
        StringBuilder firstSeeds = new StringBuilder();
        for (int seed = 0; seed < 600; seed++) {
            Random r = new Random(seed);
            HashMap<Object, Integer> tm = new HashMap<>(64);
            List<Object> keys = new ArrayList<>();
            for (int i = 9 + r.nextInt(40); i > 0; i--) {
                Object k = r.nextInt(3) == 0 ? new TieD(r.nextInt(100)) : new TieC(r.nextInt(100));
                if (!tm.containsKey(k)) keys.add(k);
                tm.put(k, i);
            }
            StringBuilder row = new StringBuilder();
            for (Object k : keys) row.append(counted(() -> tm.put(k, 1))).append(' ');
            for (int i = 0; i < 5; i++) {
                Object k = new TieC(100 + r.nextInt(50));
                row.append('n').append(counted(() -> tm.put(k, 2))).append(' ');
            }
            for (char ch : row.toString().toCharArray()) digest = digest * 31 + ch;
            for (String t : row.toString().trim().split(" ")) total += Long.parseLong(t.replace("n", ""));
            if (seed < 3) firstSeeds.append(" seed ").append(seed).append(": ").append(row.toString().trim()).append(';');
        }
        System.out.println("puts past two classes of tie nodes, 600 seeds: total compareTo calls " + total + ", digest " + digest + ";" + firstSeeds);
        // Spec 1.H7: building the tree (the 9th put) makes only H6's calls, with no search for the key.
        StringBuilder build = new StringBuilder("compareTo calls of the put that makes the tree, mixed classes:");
        for (int pattern = 0; pattern < 8; pattern++) {
            HashMap<Object, Integer> tm = new HashMap<>(64);
            Random r = new Random(100 + pattern);
            long last = 0;
            for (int i = 0; i < 9; i++) {
                Object k = r.nextBoolean() ? new TieD(i * 7 % 23) : new TieC(i * 11 % 29);
                last = counted(() -> tm.put(k, 0));
            }
            build.append(' ').append(last);
        }
        System.out.println(build);
        System.out.println("done");
    }
}
