package concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

/** ConcurrentMap's default methods, on a map supplying only the abstract ones. */
public class ConcurrentMapDefaults {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    /** A sorted, single-threaded map; only what ConcurrentMap leaves abstract, nothing default overridden. */
    static class Plain extends AbstractMap<String, Integer> implements ConcurrentMap<String, Integer> {
        final TreeMap<String, Integer> m = new TreeMap<>();
        @Override public Integer put(String k, Integer v) { return m.put(k, v); }
        @Override public Set<Map.Entry<String, Integer>> entrySet() { return m.entrySet(); }
        @Override public Integer putIfAbsent(String k, Integer v) { return m.containsKey(k) ? m.get(k) : m.put(k, v); }
        @Override public boolean remove(Object k, Object v) {
            if (!Objects.equals(m.get(k), v) || !m.containsKey(k)) return false;
            m.remove(k);
            return true;
        }
        @Override public boolean replace(String k, Integer o, Integer n) {
            if (!Objects.equals(m.get(k), o) || !m.containsKey(k)) return false;
            m.put(k, n);
            return true;
        }
        @Override public Integer replace(String k, Integer v) { return m.containsKey(k) ? m.put(k, v) : null; }
    }

    /** Its first conditional write loses a race, as if another thread had got in first. */
    static class Racing extends Plain {
        boolean lose = true;
        @Override public boolean replace(String k, Integer o, Integer n) {
            if (lose) { lose = false; m.put(k, o + 100); return false; }
            return super.replace(k, o, n);
        }
    }

    /** Its entries fail on one key, as for an entry removed by another thread. */
    static class Vanishing extends Plain {
        @Override public Set<Map.Entry<String, Integer>> entrySet() {
            Set<Map.Entry<String, Integer>> out = new LinkedHashSet<>();
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                boolean gone = e.getKey().equals("b");
                String k = e.getKey();
                Integer v = e.getValue();
                out.add(new AbstractMap.SimpleEntry<String, Integer>(k, v) {
                    @Override public String getKey() { if (gone) throw new IllegalStateException("gone"); return k; }
                    @Override public Integer getValue() { if (gone) throw new IllegalStateException("gone"); return v; }
                    @Override public int hashCode() { return k.hashCode(); }
                    @Override public boolean equals(Object o) { return o == this; }
                });
            }
            return out;
        }
    }

    public static void main(String[] args) {
        Plain p = new Plain();
        p.put("a", 1);
        p.put("b", 2);
        show("getOrDefault present", () -> p.getOrDefault("a", 9));
        show("getOrDefault absent", () -> p.getOrDefault("z", 9));
        List<String> seen = new ArrayList<>();
        p.forEach((k, v) -> seen.add(k + v));
        show("forEach", () -> seen);
        show("forEach null", () -> { p.forEach(null); return "no"; });
        p.replaceAll((k, v) -> v * 10);
        show("replaceAll", () -> p);
        show("replaceAll null", () -> { p.replaceAll(null); return "no"; });
        show("replaceAll to null is the map's to judge", () -> {
            Plain q = new Plain();
            q.put("a", 1);
            q.replaceAll((k, v) -> null);
            return q;
        });
        show("computeIfAbsent present", () -> p.computeIfAbsent("a", k -> -1));
        show("computeIfAbsent new", () -> p.computeIfAbsent("c", k -> 3));
        show("computeIfAbsent null result", () -> p.computeIfAbsent("d", k -> null));
        show("computeIfAbsent null fn", () -> p.computeIfAbsent("a", null));
        show("computeIfPresent", () -> p.computeIfPresent("c", (k, v) -> v + 1));
        show("computeIfPresent absent", () -> p.computeIfPresent("z", (k, v) -> 1));
        show("computeIfPresent to null", () -> p.computeIfPresent("c", (k, v) -> null));
        show("computeIfPresent null fn", () -> p.computeIfPresent("a", null));
        show("after", () -> p);
        show("compute new", () -> p.compute("n", (k, v) -> v == null ? 5 : -1));
        show("compute old", () -> p.compute("n", (k, v) -> v + 1));
        show("compute to null", () -> p.compute("n", (k, v) -> null));
        show("compute absent null", () -> p.compute("n", (k, v) -> null));
        show("compute null fn", () -> p.compute("a", null));
        show("merge new", () -> p.merge("m", 1, Integer::sum));
        show("merge old", () -> p.merge("m", 2, Integer::sum));
        show("merge to null", () -> p.merge("m", 2, (x, y) -> null));
        show("merge null value", () -> p.merge("a", null, Integer::sum));
        show("merge null fn", () -> p.merge("a", 1, null));
        show("final", () -> p);

        Racing r = new Racing();
        r.put("k", 1);
        show("merge retries a lost replace", () -> r.merge("k", 1, Integer::sum));
        r.lose = true;
        show("compute retries a lost replace", () -> r.compute("k", (k, v) -> v + 1));
        r.lose = true;
        show("computeIfPresent retries", () -> r.computeIfPresent("k", (k, v) -> v + 1));
        r.lose = true;
        r.replaceAll((k, v) -> v + 1);
        show("replaceAll retries", () -> r);

        Vanishing v = new Vanishing();
        v.put("a", 1);
        v.put("b", 2);
        v.put("c", 3);
        List<String> kept = new ArrayList<>();
        v.forEach((k, x) -> kept.add(k + x));
        show("forEach skips a vanished entry", () -> kept);
        v.replaceAll((k, x) -> x + 1);
        show("replaceAll skips a vanished entry", () -> v.m);
    }
}
