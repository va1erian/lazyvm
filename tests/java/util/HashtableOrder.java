package util;

import java.util.*;

/**
 * Hashtable order: bucket = (hash & 0x7FFFFFFF) % capacity, newest first in a bucket, iterators
 * from the last bucket down, forEach and replaceAll from the first bucket up; growth to 2n+1.
 */
public class HashtableOrder {
    static final class K {
        final int id, h;
        K(int id, int h) { this.id = id; this.h = h; }
        public int hashCode() { return h; }
        public boolean equals(Object o) { return o instanceof K && ((K) o).id == id; }
        public String toString() { return "k" + id; }
    }

    static String all(Hashtable<K, Integer> t) {
        StringBuilder a = new StringBuilder(), b = new StringBuilder(), c = new StringBuilder(), d = new StringBuilder();
        for (Enumeration<K> e = t.keys(); e.hasMoreElements();) a.append(e.nextElement()).append(' ');
        for (Map.Entry<K, Integer> e : t.entrySet()) b.append(e.getKey()).append(' ');
        t.forEach((k, v) -> c.append(k).append(' '));
        t.replaceAll((k, v) -> { d.append(k).append(' '); return v; });
        String it = a.toString().trim();
        return it + (b.toString().trim().equals(it) ? "" : " ENTRYSET DIFFERS") + " | forEach " + c.toString().trim()
            + (c.toString().equals(d.toString()) ? "" : " | replaceAll " + d.toString().trim());
    }

    public static void main(String[] args) {
        Hashtable<K, Integer> t = new Hashtable<>();
        int[] hashes = {5, 16, 27, 3, 14, -6, 40, 2, 51, 62, 7, 18, Integer.MIN_VALUE + 5, -1, 0, 11};
        for (int i = 0; i < hashes.length; i++) {
            t.put(new K(i, hashes[i]), i);
            if (i >= 6 && i <= 9 || i == 15) System.out.println("after " + (i + 1) + ": " + all(t));
        }
        t.remove(new K(3, 3));
        t.computeIfAbsent(new K(20, 3), k -> 0);
        t.merge(new K(21, 26), 0, Integer::sum);
        t.compute(new K(22, 49), (k, v) -> 0);
        t.putIfAbsent(new K(23, 72), 0);
        System.out.println("compute family adds like put: " + all(t));
        @SuppressWarnings("unchecked") Hashtable<K, Integer> cl = (Hashtable<K, Integer>) t.clone();
        System.out.println("clone keeps the order: " + all(cl).equals(all(t)));
        Map<K, Integer> src = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) src.put(new K(100 + i, i * 7), i);
        System.out.println("copy of 12 (24 buckets): " + all(new Hashtable<K, Integer>(src)));
        Map<K, Integer> few = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) few.put(new K(200 + i, i * 11), i);
        System.out.println("copy of 3 (11 buckets): " + all(new Hashtable<K, Integer>(few)));
        Hashtable<K, Integer> pa = new Hashtable<>(3);
        pa.putAll(src);
        System.out.println("putAll into 3 buckets: " + all(pa));
        for (int cap : new int[]{0, 1, 2, 4}) {
            Hashtable<K, Integer> z = new Hashtable<>(cap);
            for (int i = 0; i < 6; i++) z.put(new K(i, i), i);
            System.out.println("Hashtable(" + cap + ") with 6: " + all(z));
        }
        for (float lf : new float[]{0.25f, 1f, 3f}) {
            Hashtable<K, Integer> z = new Hashtable<>(5, lf);
            for (int i = 0; i < 14; i++) z.put(new K(i, i * 3), i);
            System.out.println("Hashtable(5, " + lf + ") with 14: " + all(z));
        }
        Hashtable<String, Object> self = new Hashtable<>();
        self.put("me", self);
        self.put("x", "y");
        System.out.println("self: " + self + " " + self.hashCode() + " " + self.values().contains(self));
        Hashtable<String, Object> a = new Hashtable<>(), b = new Hashtable<>();
        a.put("k", "v");
        a.put("b", b);
        b.put("a", a);
        System.out.println("cycle hash codes: " + a.hashCode() + " " + b.hashCode());
        try { System.out.println(a.toString()); } catch (StackOverflowError e) { System.out.println("cycle toString: StackOverflowError"); }
        Hashtable<Integer, Integer> e = new Hashtable<>();
        Iterator<Integer> ei = e.keySet().iterator();
        Enumeration<Integer> ee = e.keys();
        e.put(1, 1);
        String r;
        try { ei.next(); r = "ok"; } catch (ConcurrentModificationException x) { r = "CME"; } catch (NoSuchElementException x) { r = "end"; }
        System.out.println("iterator of an empty table after a put: " + r + " " + ei.hasNext() + " " + ee.hasMoreElements());
        System.out.println("empty hashCode " + new Hashtable<String, String>().hashCode());
        Hashtable<Integer, Integer> h2 = new Hashtable<>();
        h2.put(1, 10); h2.put(2, 20); h2.put(3, 30);
        Enumeration<Integer> en = h2.keys();
        while (en.hasMoreElements()) en.nextElement();
        String m1, m2, m3;
        try { en.nextElement(); m1 = "none"; } catch (NoSuchElementException x) { m1 = x.getMessage(); }
        try { h2.keySet().iterator().remove(); m2 = "none"; } catch (IllegalStateException x) { m2 = x.getMessage(); }
        try { ((Iterator<?>) h2.keys()).remove(); m3 = "none"; } catch (UnsupportedOperationException x) { m3 = "UnsupportedOperationException"; }
        Map.Entry<Integer, Integer> held1 = null, held2 = null;
        for (Map.Entry<Integer, Integer> x : h2.entrySet()) { if (x.getKey() == 1) held1 = x; if (x.getKey() == 2) held2 = x; }
        h2.remove(1);
        for (Iterator<Integer> it2 = h2.keySet().iterator(); it2.hasNext();) if (it2.next() == 2) it2.remove();
        System.out.println("enumerator: " + m1 + " | " + m2 + " | " + m3 + " | held after remove(k) " + held1.getKey() + "=" + held1.getValue() + ", after iterator remove " + held2);
        Hashtable<String, Integer> h3 = new Hashtable<>();
        h3.put("a", 1); h3.put("b", 2);
        Map.Entry<String, Integer> gone = h3.entrySet().iterator().next();
        h3.remove(gone.getKey());
        String text;
        try { text = gone.toString(); } catch (NullPointerException x) { text = "NullPointerException"; }
        String added;
        try { ((Set) h3.entrySet()).add("x"); added = "added"; } catch (ClassCastException x) { added = "ClassCastException"; } catch (UnsupportedOperationException x) { added = "UnsupportedOperationException"; }
        System.out.println("removed entry: " + gone.getKey() + " hashCode " + gone.hashCode() + " toString " + text + " | entrySet().add(String) " + added);
        System.out.println("done");
    }
}
