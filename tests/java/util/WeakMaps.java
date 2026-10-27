// VMARGS: -Xmx64m
// TIMEOUT: 120
package util;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * WeakHashMap with the collector: when entries go, what holds them, and the map, entry, view and
 * iterator contracts (docs/design/weakhashmap.md sections 2 to 7). Lines print only what
 * both VMs must agree on once the collector has run: never how many tries a wait took.
 */
public class WeakMaps {
    static final StringBuilder LOG = new StringBuilder();

    /** A key equal to any key of the same number; hash code is the number. */
    static final class Key {
        final int n;
        Key(int n) { this.n = n; }
        public int hashCode() { return n; }
        public boolean equals(Object o) { return o instanceof Key && ((Key) o).n == n; }
        public String toString() { return "k" + n; }
    }

    /** A key that logs its hashCode and equals calls; equal when hash and id/10 agree. */
    static final class Loud {
        final int h;
        final int id;
        Loud(int h, int id) { this.h = h; this.id = id; }
        public int hashCode() { LOG.append('h').append(id).append(' '); return h; }
        public boolean equals(Object o) {
            LOG.append(id).append(".eq(").append(o instanceof Loud ? String.valueOf(((Loud) o).id) : String.valueOf(o)).append(") ");
            return o instanceof Loud && ((Loud) o).h == h && ((Loud) o).id / 10 == id / 10;
        }
        public String toString() { return "L" + id; }
    }

    static final class Val {
        final int n;
        Object ref;
        Val(int n) { this.n = n; }
        /** Not the identity hash: VMs choose those freely, and the map's hashCode is printed. */
        public int hashCode() { return 1000 * n; }
        public String toString() { return "v" + n; }
    }

    /** Collects until the map reports {@code want} entries, or gives up after a bounded wait. */
    static boolean settle(Map<?, ?> m, int want) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (m.size() == want) return true;
            System.gc();
            Thread.sleep(5);
        }
        return m.size() == want;
    }

    /** Collects until {@code r} is cleared, touching no map. */
    static boolean cleared(WeakReference<?> r) throws InterruptedException {
        for (int i = 0; i < 400 && r.get() != null; i++) {
            System.gc();
            Thread.sleep(5);
        }
        return r.get() == null;
    }

    static String log() {
        String s = LOG.toString();
        LOG.setLength(0);
        return s;
    }

    static void t(String label, Runnable r) {
        try {
            r.run();
            System.out.println(label + " ok");
        } catch (RuntimeException e) {
            System.out.println(label + " " + e);
        }
    }

    public static void main(String[] args) throws Exception {
        disappearing();
        holders();
        iteratorsAndGc();
        walkAcrossGc();
        equality();
        entries();
        views();
        failFast();
        callbacks();
        errors();
        System.out.println("done");
    }

    /** W10, W11: a dropped key's entry is gone from every query once the collector ran. */
    static void disappearing() throws Exception {
        WeakHashMap<Key, Val> m = new WeakHashMap<>();
        Key[] keep = new Key[10];
        // No local holds an odd key: a dead local slot may stay a root on a VM without liveness
        // maps (JLS 12.6.1 allows both), and the test must not depend on which kind it runs on.
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) keep[i] = new Key(i);
            m.put(i % 2 == 0 ? keep[i] : new Key(i), new Val(i));
        }
        Val v3 = m.get(new Key(3));
        System.out.println("W10 before " + m.size() + " " + m);
        System.out.println("W10 settled " + settle(m, 5) + " " + m + " empty " + m.isEmpty());
        System.out.println("W10 queries get " + m.get(new Key(3)) + " containsKey " + m.containsKey(new Key(3))
                + " containsValue " + m.containsValue(v3) + " remove " + m.remove(new Key(3)));
        System.out.println("W10 views " + m.keySet().size() + " " + m.values().size() + " " + m.entrySet().size()
                + " " + m.keySet() + " " + m.values() + " " + m.entrySet());
        int walked = 0;
        for (Map.Entry<Key, Val> e : m.entrySet()) walked++;
        System.out.println("W10 walked " + walked + " hash " + m.hashCode() + " kept " + Arrays.toString(keep));
        Arrays.fill(keep, null);
        System.out.println("W10 all dropped " + settle(m, 0) + " " + m + " " + m.isEmpty());
        m.put(new Key(1), new Val(1));
        Key k2 = new Key(2);
        m.put(k2, new Val(2));
        m.put(new Key(2), new Val(22));
        System.out.println("W11 an equal key keeps the first key object " + settle(m, 1) + " " + m + " " + (m.keySet().iterator().next() == k2));
        m.clear();
        Key[] many = new Key[300];
        for (int i = 0; i < 300; i++) m.put(many[i] = new Key(i), new Val(i));
        for (int i = 0; i < 300; i += 3) many[i] = null;
        System.out.println("W10 a third of 300 dropped " + settle(m, 200) + " " + m.get(new Key(3)) + " " + m.get(new Key(4)));
        StringBuilder sb = new StringBuilder();
        for (Key k : m.keySet()) if (k.n < 40) sb.append(k.n).append(' ');
        System.out.println("W10 order of survivors " + sb);
        for (int i = 300; i < 340; i++) m.put(new Key(i), new Val(i));
        System.out.println("W10 refill then drop " + settle(m, 200) + " " + m.containsKey(new Key(301)) + " " + many[299]);
    }

    /** W12: the null key, cached boxes, interned literals and values that hold their key all stay. */
    static void holders() throws Exception {
        WeakHashMap<Object, Object> m = new WeakHashMap<>();
        m.put(5, "boxed 5");
        m.put("lit", "literal");
        m.put(null, "null key");
        m.put(new String("heap"), "copy");
        m.put(100000, "big box");
        Key kk = new Key(9);
        Val vv = new Val(9);
        vv.ref = kk;
        m.put(kk, vv);
        kk = null;
        vv = null;
        System.out.println("W12 settled " + settle(m, 4) + " 5 " + m.containsKey(5) + " lit " + m.containsKey("lit")
                + " null " + m.containsKey(null) + " heap " + m.containsKey("heap") + " big " + m.containsKey(100000)
                + " self-held " + m.containsKey(new Key(9)));
        System.out.println("W12 null key get " + m.get(null) + " put " + m.put(null, "again") + " remove " + m.remove(null)
                + " gone " + m.containsKey(null) + " " + m.size());
        m.remove(new Key(9));
        WeakHashMap<Key, Object> chain = new WeakHashMap<>();
        Key a = new Key(1);
        Key b = new Key(2);
        chain.put(a, b);
        chain.put(b, new Key(3));
        a = null;
        b = null;
        System.out.println("W12 a value that is another key keeps it " + settle(chain, 1) + " " + chain);
    }

    /** W13: iterators hold the key they announced or returned; expunging is not a modification. */
    static void iteratorsAndGc() throws Exception {
        WeakHashMap<Key, Integer> m = new WeakHashMap<>();
        for (int i = 0; i < 6; i++) m.put(new Key(i), i);
        Iterator<Key> it = m.keySet().iterator();
        System.out.println("W13 hasNext " + it.hasNext() + " then only the announced key stays " + settle(m, 1));
        t("W13 next after expunge", () -> System.out.print(it.next() + " " + it.hasNext() + " | "));

        WeakHashMap<Key, Integer> m3 = new WeakHashMap<>();
        for (int i = 0; i < 3; i++) m3.put(new Key(i), i);
        Iterator<Key> it3 = m3.keySet().iterator();
        it3.next();
        System.out.println("W13 the returned key stays " + settle(m3, 1));
        t("W13 remove it", () -> { it3.remove(); System.out.print(m3.size() + " | "); });

        WeakHashMap<Key, Integer> m4 = new WeakHashMap<>();
        Key h0 = new Key(100);
        Key h1 = new Key(101);
        m4.put(h0, 0);
        m4.put(h1, 1);
        for (int i = 0; i < 4; i++) m4.put(new Key(i), i);
        Iterator<Key> it4 = m4.keySet().iterator();
        Key first = it4.next();
        System.out.println("W13 first " + first + " settled " + settle(m4, 3));
        StringBuilder sb = new StringBuilder();
        t("W13 rest after expunge", () -> { while (it4.hasNext()) sb.append(it4.next()).append(' '); System.out.print(sb + "| "); });

        WeakHashMap<Key, Integer> m5 = new WeakHashMap<>();
        Key g0 = new Key(50);
        m5.put(g0, 0);
        Object marker = new Object();
        WeakReference<Object> probe = new WeakReference<>(marker);
        for (int i = 0; i < 4; i++) m5.put(new Key(i), i);
        marker = null;
        System.out.println("W13 cleared " + cleared(probe));
        Iterator<Map.Entry<Key, Integer>> it5 = m5.entrySet().iterator();
        sb.setLength(0);
        while (it5.hasNext()) sb.append(it5.next()).append(' ');
        System.out.println("W13 a walk skips cleared keys " + sb + "size " + m5.size());

        WeakHashMap<Key, String> m6 = new WeakHashMap<>();
        m6.put(new Key(7), "seven");
        Map.Entry<Key, String> e = m6.entrySet().iterator().next();
        System.out.println("W14 held entry " + e + " settled " + settle(m6, 0) + " key " + e.getKey() + " value " + e.getValue()
                + " " + e + " hash " + e.hashCode());
        System.out.println("W14 setValue on it " + e.setValue("x") + " " + e + " map " + m6);
    }

    /** Keys that all share one hash code, so they sit in one chain; equal by number. */
    static final class Same {
        final int n;
        Same(int n) { this.n = n; }
        public int hashCode() { return 7; }
        public boolean equals(Object o) { return o instanceof Same && ((Same) o).n == n; }
        public String toString() { return "k" + n; }
    }

    /** W22, W26: a walk keeps every survivor when the collector clears keys in the middle of it. */
    static void walkAcrossGc() throws Exception {
        WeakHashMap<Same, String> m = new WeakHashMap<>();
        Same[] keep = new Same[10];
        // From 9 down, so the chain (newest first) starts with k0, which stays.
        for (int i = 9; i >= 0; i--) {
            if (i % 2 == 0) keep[i] = new Same(i);
            m.put(i % 2 == 0 ? keep[i] : new Same(i), "v" + i);
        }
        Iterator<Map.Entry<Same, String>> it = m.entrySet().iterator();
        System.out.print("W22 one chain first " + it.next());
        List<String> rest = new ArrayList<>();
        System.out.print(" settled " + settle(m, 5));
        while (it.hasNext()) rest.add(String.valueOf(it.next()));
        System.out.println(" rest " + rest + " kept " + Arrays.toString(keep));

        WeakHashMap<Key, String> spread = new WeakHashMap<>();
        Key[] kept = new Key[20];
        for (int i = 0; i < 20; i++) {
            if (i % 3 == 0) kept[i] = new Key(i);
            spread.put(i % 3 == 0 ? kept[i] : new Key(i), "v" + i);
        }
        Iterator<Key> ki = spread.keySet().iterator();
        StringBuilder sb = new StringBuilder();
        int last = -1;
        // Only numbers go in locals: a dropped key must not sit in a frame slot.
        for (int i = 0; i < 3; i++) sb.append('k').append(last = ki.next().n).append(' ');
        // The iterator holds the key it returned last, kept or not (W13).
        int want = 7 + (last % 3 == 0 ? 0 : 1);
        System.out.print("W22 many buckets first " + sb + "settled " + settle(spread, want));
        sb.setLength(0);
        while (ki.hasNext()) sb.append(ki.next()).append(' ');
        System.out.println(" rest " + sb + "kept " + Arrays.toString(kept));

        WeakHashMap<Key, Integer> end = new WeakHashMap<>();
        end.put(new Key(1), 1);
        Iterator<Key> ei = end.keySet().iterator();
        ei.next();
        System.out.println("W26 the end of a walk lets go of the last key " + ei.hasNext() + " " + settle(end, 0));
        t("W26 remove then", () -> { ei.remove(); System.out.print(end.size() + " | "); });
    }

    /** W15: lookups hash once and call the argument's equals on stored keys of the same hash. */
    static void equality() {
        WeakHashMap<Loud, Integer> m = new WeakHashMap<>();
        Loud a1 = new Loud(1, 10);
        Loud a2 = new Loud(1, 20);
        Loud a3 = new Loud(17, 30);
        Loud b = new Loud(2, 40);
        Loud c = new Loud(0x10011, 50);
        m.put(a1, 1);
        m.put(a2, 2);
        m.put(a3, 3);
        m.put(b, 4);
        m.put(c, 5);
        System.out.println("W15 puts " + log());
        System.out.println("W15 get " + m.get(new Loud(1, 11)) + " " + log());
        System.out.println("W15 get same object " + m.get(a2) + " " + log());
        System.out.println("W15 containsKey miss " + m.containsKey(new Loud(1, 99)) + " " + log());
        System.out.println("W15 put equal " + m.put(new Loud(1, 12), 6) + " " + log() + m.keySet());
        System.out.println("W15 remove " + m.remove(new Loud(1, 13)) + " " + log() + m.keySet());
        System.out.println("W15 containsValue " + m.containsValue(2) + " " + m.containsValue(null) + " " + log());
        System.out.println("W15 hashCode " + m.hashCode() + " " + log());
        System.out.println("W15 toString " + m + " " + log());
        m.put(a1, 1);
        log();
        System.out.println("W15 equals HashMap " + m.equals(new HashMap<>(m)) + " " + log());
        System.out.println("W15 HashMap equals it " + new HashMap<>(m).equals(m) + " " + log());
        System.out.println("W15 self equals " + m.equals(m) + " " + log());
    }

    /** W16: entries compare and hash like Map.Entry; setValue writes through. */
    static void entries() {
        WeakHashMap<Loud, String> m = new WeakHashMap<>();
        Loud k = new Loud(3, 30);
        m.put(k, "x");
        Map.Entry<Loud, String> e = m.entrySet().iterator().next();
        log();
        System.out.println("W16 equals same key " + e.equals(new AbstractMap.SimpleEntry<>(k, "x")) + " " + log());
        System.out.println("W16 equals equal key " + e.equals(new AbstractMap.SimpleEntry<>(new Loud(3, 31), "x")) + " " + log());
        System.out.println("W16 equals other value " + e.equals(new AbstractMap.SimpleEntry<>(k, "y")) + " " + log());
        System.out.println("W16 equals non-entry " + e.equals("k") + " " + e.equals(null) + " " + log());
        System.out.println("W16 hashCode " + e.hashCode() + " " + log() + " toString " + e + " " + log());
        System.out.println("W16 setValue " + e.setValue("y") + " " + m.get(k) + " " + log());
        WeakHashMap<Object, Object> n = new WeakHashMap<>();
        n.put(null, null);
        Map.Entry<Object, Object> ne = n.entrySet().iterator().next();
        System.out.println("W16 null entry " + ne + " " + ne.getKey() + " " + ne.hashCode() + " "
                + ne.equals(new AbstractMap.SimpleEntry<>(null, null)) + " " + new AbstractMap.SimpleEntry<>(null, null).equals(ne));
        System.out.println("W16 entrySet contains " + n.entrySet().contains(new AbstractMap.SimpleEntry<>(null, null))
                + " " + n.entrySet().contains(new AbstractMap.SimpleEntry<>(null, 1)) + " " + n.entrySet().contains("x"));
        n.put("v", 7);
        System.out.println("W16 entrySet remove " + n.entrySet().remove(new AbstractMap.SimpleEntry<>("v", 8))
                + " " + n.entrySet().remove(new AbstractMap.SimpleEntry<>("v", 7)) + " " + n + " " + n.entrySet().remove(3));
        n.put("self", n);
        System.out.println("W16 self value " + n.size() + " " + n.containsKey("self"));
    }

    /** W17: views read and remove through the map. */
    static void views() {
        WeakHashMap<Integer, String> m = new WeakHashMap<>();
        for (int i = 0; i < 8; i++) m.put(i, "s" + (i % 3));
        Set<Integer> ks = m.keySet();
        Collection<String> vs = m.values();
        Set<Map.Entry<Integer, String>> es = m.entrySet();
        System.out.println("W17 same views " + (ks == m.keySet()) + " " + (vs == m.values()) + " " + (es == m.entrySet()));
        System.out.println("W17 keySet " + ks.contains(3) + " " + ks.remove(3) + " " + ks.remove(3) + " " + m);
        System.out.println("W17 values remove first match " + vs.remove("s1") + " " + vs.contains("s1") + " " + m);
        System.out.println("W17 removeAll " + ks.removeAll(Arrays.asList(0, 99)) + " retainAll " + vs.retainAll(Arrays.asList("s2", "s0")) + " " + m);
        System.out.println("W17 removeIf " + es.removeIf(x -> x.getKey() > 5) + " " + m);
        System.out.println("W17 toArray " + Arrays.toString(ks.toArray()) + " " + Arrays.toString(vs.toArray(new String[0]))
                + " " + Arrays.toString(es.toArray()));
        Iterator<Integer> it = ks.iterator();
        it.next();
        it.remove();
        System.out.println("W17 iterator remove " + m);
        t("W17 remove again", it::remove);
        Iterator<Integer> fresh = ks.iterator();
        t("W17 remove before next", fresh::remove);
        while (fresh.hasNext()) fresh.next();
        t("W17 next past end", fresh::next);
        ks.clear();
        System.out.println("W17 keySet clear " + m + " " + vs.isEmpty() + " " + es.size());
        t("W17 keySet add", () -> ks.add(1));
        t("W17 values add", () -> vs.add("x"));
        t("W17 entrySet add", () -> es.add(new AbstractMap.SimpleEntry<>(1, "x")));
        Iterator<String> empty = vs.iterator();
        t("W17 empty next", empty::next);
    }

    /** W18: which changes a live iterator notices, and when. */
    static void failFast() {
        WeakHashMap<Integer, Integer> m = new WeakHashMap<>();
        for (int i = 0; i < 5; i++) m.put(i, i);
        Iterator<Integer> a = m.keySet().iterator();
        a.next();
        m.put(1, 9);
        t("W18 value replaced, next", a::next);
        m.remove(99);
        t("W18 absent removed, next", a::next);
        m.put(7, 7);
        t("W18 key added, hasNext " + a.hasNext() + ", next", a::next);
        Iterator<Integer> b = m.keySet().iterator();
        while (b.hasNext()) b.next();
        m.remove(7);
        t("W18 at end after a remove, hasNext " + b.hasNext() + ", next", b::next);
        Iterator<Map.Entry<Integer, Integer>> c = m.entrySet().iterator();
        c.next();
        m.clear();
        t("W18 cleared, hasNext " + c.hasNext() + ", next", c::next);
        Iterator<Integer> d = m.values().iterator();
        m.clear();
        t("W18 clear of an empty map, next", d::next);
        m.put(1, 1);
        Iterator<Integer> e = m.keySet().iterator();
        m.putAll(new HashMap<Integer, Integer>());
        m.putAll(Collections.singletonMap(1, 5));
        t("W18 putAll of nothing new, next", () -> System.out.print(e.next() + " "));
        Iterator<Integer> e2 = m.keySet().iterator();
        m.putAll(Collections.singletonMap(99, 1));
        t("W18 putAll that adds, next", e2::next);
        Iterator<Integer> e3 = m.values().iterator();
        m.keySet().remove(99);
        t("W18 removed through another view, next", e3::next);
        Iterator<Integer> f = m.keySet().iterator();
        f.next();
        f.remove();
        m.put(2, 2);
        t("W18 own remove then put, hasNext " + f.hasNext() + ", remove", f::remove);
        Iterator<Integer> g = m.keySet().iterator();
        m.remove(2);
        t("W18 removed before first next, next", g::next);
    }

    /** W8, W19: forEach and replaceAll check for changes after every call of the action. */
    static void callbacks() {
        WeakHashMap<Integer, Integer> m = new WeakHashMap<>();
        for (int i = 0; i < 5; i++) m.put(i, i);
        StringBuilder sb = new StringBuilder();
        t("W19 forEach adds", () -> m.forEach((k, v) -> { sb.append(k).append(' '); if (k == 3) m.put(103, 0); }));
        System.out.println("W19 visited " + sb + m);
        sb.setLength(0);
        t("W19 replaceAll adds", () -> m.replaceAll((k, v) -> { sb.append(k).append(' '); if (k == 2) m.put(200, 0); return v + 1; }));
        System.out.println("W19 visited " + sb + m);
        sb.setLength(0);
        t("W19 forEach removes at the last key", () -> m.forEach((k, v) -> { sb.append(k).append(' '); if (k == 4) m.remove(0); }));
        System.out.println("W19 visited " + sb + m);
        sb.setLength(0);
        t("W19 forEach replaces a value", () -> m.forEach((k, v) -> { sb.append(k).append(' '); m.put(k, -1); }));
        System.out.println("W19 visited " + sb + m);
        t("W19 forEach null", () -> m.forEach(null));
        t("W19 replaceAll null", () -> m.replaceAll(null));
        t("W19 forEach null on empty", () -> new WeakHashMap<Integer, Integer>().forEach(null));
        t("W19 replaceAll null on empty", () -> new WeakHashMap<Integer, Integer>().replaceAll(null));
        sb.setLength(0);
        t("W19 keySet forEach adds at the last key", () -> m.keySet().forEach(k -> { sb.append(k).append(' '); if (k == 1) m.put(300, 0); }));
        System.out.println("W19 visited " + sb + m);
        sb.setLength(0);
        t("W19 values forEach adds", () -> m.values().forEach(v -> { sb.append(v).append(' '); m.put(400 + v, 0); }));
        System.out.println("W19 visited " + sb);
        WeakHashMap<Integer, Integer> d = new WeakHashMap<>();
        System.out.println("W20 computeIfAbsent " + d.computeIfAbsent(1, k -> 10) + " " + d.computeIfAbsent(1, k -> 11)
                + " computeIfPresent " + d.computeIfPresent(1, (k, v) -> v + 1) + " compute " + d.compute(2, (k, v) -> v == null ? 20 : v)
                + " merge " + d.merge(2, 5, Integer::sum) + " putIfAbsent " + d.putIfAbsent(3, 30) + " " + d.putIfAbsent(3, 31)
                + " getOrDefault " + d.getOrDefault(9, -9) + " replace " + d.replace(3, 33) + " " + d.replace(3, 0, 1)
                + " remove(k,v) " + d.remove(3, 34) + " " + d.remove(3, 33) + " " + d);
        System.out.println("W20 computeIfAbsent that puts " + d.computeIfAbsent(55, k -> { d.put(56, 1); return 2; }) + " " + d);
        System.out.println("W20 compute to null removes " + d.compute(55, (k, v) -> null) + " " + d.merge(56, 1, (x, y) -> null) + " " + d);
        d.put(8, null);
        System.out.println("W20 null value " + d.containsKey(8) + " " + d.get(8) + " " + d.getOrDefault(8, 5) + " " + d.putIfAbsent(8, 6) + " " + d);
    }

    /** W21: constructor arguments and null maps. */
    static void errors() {
        t("W21 capacity -1", () -> new WeakHashMap<Object, Object>(-1));
        t("W21 capacity MIN", () -> new WeakHashMap<Object, Object>(Integer.MIN_VALUE, 1f));
        t("W21 load factor 0", () -> new WeakHashMap<Object, Object>(4, 0f));
        t("W21 load factor -1", () -> new WeakHashMap<Object, Object>(4, -1f));
        t("W21 load factor NaN", () -> new WeakHashMap<Object, Object>(4, Float.NaN));
        t("W21 capacity -1 and load factor 0", () -> new WeakHashMap<Object, Object>(-1, 0f));
        t("W21 load factor infinite", () -> new WeakHashMap<Object, Object>(4, Float.POSITIVE_INFINITY).put(1, 1));
        t("W21 copy of null", () -> new WeakHashMap<Object, Object>(null));
        t("W21 putAll null", () -> new WeakHashMap<Object, Object>().putAll(null));
        t("W21 putAll empty into null-free", () -> new WeakHashMap<Object, Object>().putAll(Collections.emptyMap()));
    }
}
