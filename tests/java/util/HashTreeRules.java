package util;

import java.util.*;

/**
 * HashMap bins that hold many keys: each script below separates the oracle's rule from a nearby
 * alternative (named in the label), so a different choice changes the printed iteration order.
 * Script ops: n = new HashMap(), n64 = new HashMap(64); P put, CA computeIfAbsent, R remove,
 * IR iterator remove; then an optional key class (A, B, C: not comparable, I: Integer, none: K),
 * the id, and @hash (default 0). In1-n2 puts the Integers n1..n2.
 */
public class HashTreeRules {
    static final class K implements Comparable<K> {
        final int id, h;
        K(int id, int h) { this.id = id; this.h = h; }
        public int hashCode() { return h; }
        public boolean equals(Object o) { return o instanceof K && ((K) o).id == id; }
        public int compareTo(K o) { return Integer.compare(id, o.id); }
        public String toString() { return "K" + id; }
    }

    static class N {
        final int id, h;
        N(int id, int h) { this.id = id; this.h = h; }
        public int hashCode() { return h; }
        public boolean equals(Object o) { return o != null && o.getClass() == getClass() && ((N) o).id == id; }
        public String toString() { return getClass().getSimpleName() + id; }
    }
    static final class TA extends N { TA(int i, int h) { super(i, h); } }
    static final class TB extends N { TB(int i, int h) { super(i, h); } }
    static final class TC extends N { TC(int i, int h) { super(i, h); } }

    static final String[][] SCRIPTS = {
        {"compute adds at the bin's front", "n64 PA0@192 P1 P2 PB3@128 PC4 CAA5 RC4 PA7@128 CA8@192 P9@192"},
        {"compute converts at 8 entries, in 64 buckets", "n64 CA0@128 P1@128 P2@64 P3@128 P4@64 P5@192 R4@64 P7@64 P8 CA9"},
        {"put converts at 9 entries", "n64 P0 CA1 P2@192 P3@128 P4@128 P5@192 CA6 P7 P8@192 IR5@192"},
        {"put does not convert at 8", "n64 P0 P1 R1 CA3 CA4 P5@192 P6 P7@192 CA8 P9"},
        {"class names ascending", "n64 PA0@192 PB1 P2 PC3 PB4@192 P5 P6 CAC7@192 CA8 P9@128"},
        {"iterator removal keeps a tree", "n64 P0 P1 CA2 P3@64 P4@192 P5 P6 CA7@128 IR6 P9 CA10@64"},
        {"iterator removal keeps the head", "n64 PC0@128 PC1 P2 P3 CAB4 CA5 P6@64 CA7 PA8 IR7"},
        {"shrink is not by count 6", "n64 P0 P1@128 P2 P3@192 P4@64 P5 P6@128 CA7 R2 R7"},
        {"shrink needs root.left.left", "n64 P0@64 P1@192 CA2 P3@192 P4@128 P5@192 P6 CA7 CA8@64 R6 P10"},
        {"shrink ignores root.right.right", "n64 P0 P1@192 P2 P3@64 CA4@192 P5@192 P6 CA7@192 R4@192 P9@64"},
        {"32 buckets are too few", "n P0 P1 P2 P3 CA4@128 P5@128 R3 CA7 P8 P9@64 P10@64 P11 R9@64 P13@1"},
        {"compute grows before adding", "n P0 R0 CA2@65 P3 CA4 I1200-1207 P5 CA6@193 IR6@193 R4 P9 IR5 P11@128"},
        {"a split half of 7 stays a tree", "n P0@65 CA1@65 P2@128 I1100-1113 IR0@65 I1150-1150 CA4@64 P5 P6@64 P7 CA8@128 P9@128 CA10@193 P11@1 I1550-1572 R11@1 P13 P14"},
        {"a split half of 6 becomes a list", "n CAA0@64 I1000-1014 PC1@128 PA2 I1100-1106 CA3@64 PB4 PC5@64 CA6@193 I1300-1306 P7 PC8 P9 I1450-1458 PC10@65 P11@64 PB12@129"},
        {"an unsplit tree is kept", "n PC0@1 PB1@129 PC2 CA3 PA4@128 PC5@128 PB6 CAA7 P8 I1400-1409 R3 P10@129 I1500-1512 CA11@128 IRA7 P13@128 IR11@128 PA15@129 I1750-1756 P16 I1800-1812 CA17@129 I1850-1852 RC0@1"},
    };

    static Object key(String cls, int id, int h) {
        switch (cls) {
            case "A": return new TA(id, h);
            case "B": return new TB(id, h);
            case "C": return new TC(id, h);
            case "I": return id;
            default: return new K(id, h);
        }
    }

    static Map<Object, Integer> run(Map<Object, Integer> m, String script) {
        for (String t : script.split(" ")) {
            if (t.equals("n")) { m = new HashMap<>(); continue; }
            if (t.equals("n64")) { m = new HashMap<>(64); continue; }
            if (t.charAt(0) == 'I' && Character.isDigit(t.charAt(1))) {
                int dash = t.indexOf('-');
                for (int i = Integer.parseInt(t.substring(1, dash)); i <= Integer.parseInt(t.substring(dash + 1)); i++) m.put(i, i);
                continue;
            }
            String op = t.startsWith("CA") || t.startsWith("IR") ? t.substring(0, 2) : t.substring(0, 1);
            String rest = t.substring(op.length());
            String cls = "ABCI".indexOf(rest.charAt(0)) >= 0 ? rest.substring(0, 1) : "";
            rest = rest.substring(cls.length());
            int at = rest.indexOf('@');
            int id = Integer.parseInt(at < 0 ? rest : rest.substring(0, at));
            Object k = key(cls, id, at < 0 ? 0 : Integer.parseInt(rest.substring(at + 1)));
            switch (op) {
                case "P": m.put(k, id); break;
                case "CA": m.computeIfAbsent(k, q -> id); break;
                case "R": m.remove(k); break;
                default: for (Iterator<Object> it = m.keySet().iterator(); it.hasNext();) if (it.next().equals(k)) it.remove();
            }
        }
        return m;
    }

    static String order(Map<?, ?> m) {
        StringBuilder sb = new StringBuilder();
        for (Object k : m.keySet()) sb.append(k).append(' ');
        return sb.toString().trim();
    }

    static final String BASE = "n64 P2@64 P8 P5 P1@64 P7@64 P3@128 P0@64 P4@64 P6";

    static void removal(String label, java.util.function.Consumer<Map<Object, Integer>> how) {
        Map<Object, Integer> m = run(null, BASE);
        how.accept(m);
        m.put(new K(50, 0), 50);
        m.put(new K(51, 128), 51);
        System.out.println("removal by " + label + ": " + order(m));
    }

    static List<Object> padded(Object first, int size) {
        List<Object> c = new ArrayList<>();
        c.add(first);
        for (int i = 1; i < size; i++) c.add("pad" + i);
        return c;
    }

    static int calls;

    abstract static class B0 {
        final int id;
        B0(int id) { this.id = id; }
        public int hashCode() { return 0; }
        public boolean equals(Object o) { return o != null && o.getClass() == getClass() && ((B0) o).id == id; }
        int cmp(Object o) { calls++; return Integer.compare(id, ((B0) o).id); }
    }
    static class Direct extends B0 implements Comparable<Direct> { Direct(int i) { super(i); } public int compareTo(Direct o) { return cmp(o); } }
    static class Second extends B0 implements java.io.Serializable, Comparable<Second> { Second(int i) { super(i); } public int compareTo(Second o) { return cmp(o); } }
    static class ObjCmp extends B0 implements Comparable<Object> { ObjCmp(int i) { super(i); } public int compareTo(Object o) { return cmp(o); } }
    interface CI extends Comparable<ViaIface> { }
    static class ViaIface extends B0 implements CI { ViaIface(int i) { super(i); } public int compareTo(ViaIface o) { return cmp(o); } }
    static class Gen<T> extends B0 implements Comparable<Gen<T>> { Gen(int i) { super(i); } public int compareTo(Gen<T> o) { return cmp(o); } }
    static class Sub extends Direct { Sub(int i) { super(i); } }
    static class Redeclare extends Direct implements Comparable<Direct> { Redeclare(int i) { super(i); } }
    @SuppressWarnings("rawtypes")
    static class Raw extends B0 implements Comparable { Raw(int i) { super(i); } public int compareTo(Object o) { return cmp(o); } }

    public static void main(String[] args) {
        for (String[] s : SCRIPTS) System.out.println(s[0] + ": " + order(run(null, s[1])));
        K k8 = new K(8, 0);
        removal("map.remove", m -> m.remove(k8));
        removal("map.remove(k, v)", m -> m.remove(k8, 8));
        removal("keySet.remove", m -> m.keySet().remove(k8));
        removal("entrySet.remove", m -> m.entrySet().remove(new AbstractMap.SimpleEntry<Object, Integer>(k8, 8)));
        removal("compute to null", m -> m.compute(k8, (k, v) -> null));
        removal("computeIfPresent to null", m -> m.computeIfPresent(k8, (k, v) -> null));
        removal("merge to null", m -> m.merge(k8, 1, (a, b) -> null));
        removal("iterator", m -> { for (Iterator<Object> it = m.keySet().iterator(); it.hasNext();) if (it.next().equals(k8)) it.remove(); });
        removal("keySet.removeIf", m -> m.keySet().removeIf(k8::equals));
        removal("entrySet.removeIf", m -> m.entrySet().removeIf(e -> e.getKey().equals(k8)));
        removal("values.remove", m -> m.values().remove(8));
        removal("keySet.retainAll", m -> { Set<Object> keep = new HashSet<>(m.keySet()); keep.remove(k8); m.keySet().retainAll(keep); });
        for (int size : new int[]{8, 9}) {
            removal("keySet.removeAll of " + size + " (map has 9)", m -> m.keySet().removeAll(padded(k8, size)));
            removal("entrySet.removeAll of " + size, m -> m.entrySet().removeAll(padded(new AbstractMap.SimpleEntry<Object, Integer>(k8, 8), size)));
        }
        int[] ids = {5, 3, 9, 1, 7, 2, 8, 0, 4, 6, 11, 10, 13, 12, 15, 14};
        String[] shapes = {"Direct", "Second", "ObjCmp", "ViaIface", "Gen", "Sub", "Redeclare", "Raw"};
        StringBuilder sb = new StringBuilder("compareTo calls while filling one bin:");
        for (String shape : shapes) {
            Map<Object, Integer> m = new HashMap<>(64);
            calls = 0;
            for (int i : ids) m.put(make(shape, i), i);
            sb.append(' ').append(shape).append('=').append(calls);
        }
        System.out.println(sb);
        Map<String, Integer> strings = new HashMap<>(64);
        String[] parts = {"Aa", "BB"};
        for (int i : ids) {
            StringBuilder w = new StringBuilder();
            for (int bit = 3; bit >= 0; bit--) w.append(parts[(i >> bit) & 1]);
            strings.put(w.toString(), i);
        }
        System.out.println("equal String hashes: " + strings.keySet());
        Map<Object, Integer> tie = new HashMap<>(64);
        for (int i = 0; i < 8; i++) tie.put(new K(i, 0), i);
        N n100 = new N(100, 0), n101 = new N(101, 0);
        tie.put(n100, 0);
        tie.put(n101, 0);
        tie.put(new K(50, 0), 0);
        String low = "K3 K0 K1 K2 K4 K5 K6 K7 N100 N101 K50", high = "K3 K0 K1 K2 K4 K5 K6 K7 N100 K50 N101";
        boolean leftWhenLower = System.identityHashCode(n101) <= System.identityHashCode(n100);
        System.out.println("identity order consistent: " + order(tie).equals(leftWhenLower ? low : high));
        Map<Object, Integer> held = new HashMap<>(64);
        for (int i = 0; i < 8; i++) held.put(new K(i, 0), i);
        Map.Entry<Object, Integer> before = held.entrySet().iterator().next();
        held.put(new K(8, 0), 8);
        before.setValue(99);
        Map.Entry<Object, Integer> inTree = held.entrySet().iterator().next();
        for (int i = 1; i <= 8; i++) held.remove(new K(i, 0));
        inTree.setValue(77);
        StringBuilder walked = new StringBuilder();
        Map<Object, Integer> grows = new HashMap<>(64);
        for (int i = 0; i < 8; i++) grows.put(new K(i, 0), i);
        try { grows.forEach((k, v) -> { walked.append(k).append(' '); if (k.equals(new K(2, 0))) { grows.put(new K(20, 0), 0); grows.put(new K(1, 64), 0); } }); } catch (ConcurrentModificationException e) { walked.append("CME"); }
        System.out.println("entries replaced: held through treeify " + before + " map " + held.get(new K(0, 0)) + "; held through untreeify " + inTree + " map " + held + "; forEach as the bin becomes a tree: " + walked);
        Map<Object, Integer> shrink = new HashMap<>(64);
        for (int i = 0; i < 12; i++) shrink.put(new K(i, 0), i);
        Map.Entry<Object, Integer> keep = null;
        for (Map.Entry<Object, Integer> e : shrink.entrySet()) if (e.getKey().equals(new K(0, 0))) keep = e;
        for (int i = 1; i < 12 && shrink.size() > 2; i++) shrink.remove(new K(i, 0));
        keep.setValue(55);
        Map<Object, Integer> split = new HashMap<>(64);
        for (int i = 0; i < 9; i++) split.put(new K(i, i % 2 == 0 ? 0 : 64), i);
        Map.Entry<Object, Integer> held2 = null;
        for (Map.Entry<Object, Integer> e : split.entrySet()) if (e.getKey().equals(new K(0, 0))) held2 = e;
        for (int i = 0; i < 60; i++) split.put(new K(100 + i, 1 + i), i);
        held2.setValue(66);
        System.out.println("held through untreeify by removal: map " + shrink.get(new K(0, 0)) + " entry " + keep + "; held through a split into lists: map " + split.get(new K(0, 0)) + " entry " + held2);
        for (int side = 0; side < 2; side++) {
            LinkedHashMap<Object, Integer> lm = new LinkedHashMap<>(64);
            lm.put(new K(500, 3), 0);
            for (int i = 0; i < 8; i++) lm.put(new K(i, 0), i);
            lm.put(new K(501, 5), 0);
            lm.put(new K(8, 0), 8);
            lm.put(new K(502, 7), 0);
            if (side == 0) {
                for (Map.Entry<Object, Integer> e : lm.entrySet()) e.setValue(e.getValue() + 1000);
                StringBuilder got = new StringBuilder();
                for (int i = 0; i < 9; i++) got.append(lm.get(new K(i, 0))).append(' ');
                System.out.println("LinkedHashMap after the bin became a tree, values set through iteration: " + lm.values() + ", by get: " + got);
            } else {
                lm.remove(new K(501, 5));
                lm.remove(new K(500, 3));
                lm.remove(new K(502, 7));
                System.out.println("LinkedHashMap after the bin became a tree, neighbours removed: " + lm.keySet());
            }
        }
        System.out.println("done");
    }

    static Object make(String shape, int i) {
        switch (shape) {
            case "Direct": return new Direct(i);
            case "Second": return new Second(i);
            case "ObjCmp": return new ObjCmp(i);
            case "ViaIface": return new ViaIface(i);
            case "Gen": return new Gen<String>(i);
            case "Sub": return new Sub(i);
            case "Redeclare": return new Redeclare(i);
            default: return new Raw(i);
        }
    }
}
