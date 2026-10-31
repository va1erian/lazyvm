package concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

/** ConcurrentHashMap Java 8 bulk operations, on both sides of the parallelism threshold, and key sets. */
public class ConcurrentHashMapBulk {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static ConcurrentHashMap<String, Integer> map() {
        ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
        for (int i = 1; i <= 20; i++) m.put("k" + i, i);
        return m;
    }

    static List<String> collector() { return Collections.synchronizedList(new ArrayList<>()); }

    static List<String> sorted(List<String> l) {
        List<String> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    /**
     * One result per threshold: sequential (Long.MAX_VALUE), then parallel (1). A primitive
     * reduce's basis is folded in once per split, so the parallel run passes an identity basis.
     */
    static void both(String label, java.util.function.LongFunction<Object> op) {
        for (long t : new long[] {Long.MAX_VALUE, 1}) {
            String tag = label + (t == 1 ? " par" : " seq");
            show(tag, () -> op.apply(t));
        }
    }

    static void forEachs(ConcurrentHashMap<String, Integer> m) {
        both("forEach", t -> { List<String> c = collector(); m.forEach(t, (k, v) -> c.add(k + "=" + v)); return sorted(c).size() + " " + sorted(c).get(0); });
        both("forEach transform", t -> { List<String> c = collector(); m.forEach(t, (k, v) -> v > 18 ? k : null, c::add); return sorted(c); });
        both("forEachKey", t -> { List<String> c = collector(); m.forEachKey(t, c::add); return c.size(); });
        both("forEachKey transform", t -> { List<String> c = collector(); m.forEachKey(t, k -> k.equals("k3") ? k + "!" : null, c::add); return c; });
        both("forEachValue", t -> { List<String> c = collector(); m.forEachValue(t, v -> c.add("" + v)); return c.size(); });
        both("forEachValue transform", t -> { List<String> c = collector(); m.forEachValue(t, v -> v == 7 ? "seven" : null, c::add); return c; });
        both("forEachEntry", t -> { List<String> c = collector(); m.forEachEntry(t, e -> c.add(e.getKey())); return c.size(); });
        both("forEachEntry transform", t -> { List<String> c = collector(); m.forEachEntry(t, e -> e.getValue() == 2 ? e.toString() : null, c::add); return c; });
    }

    static void searches(ConcurrentHashMap<String, Integer> m) {
        both("search", t -> m.search(t, (k, v) -> v == 5 ? k : null));
        both("search none", t -> m.search(t, (k, v) -> null));
        both("searchKeys", t -> m.searchKeys(t, k -> k.equals("k9") ? k + "?" : null));
        both("searchValues", t -> m.searchValues(t, v -> v == 9 ? "nine" : null));
        both("searchEntries", t -> m.searchEntries(t, e -> e.getValue() == 11 ? e.getKey() : null));
    }

    static void reduces(ConcurrentHashMap<String, Integer> m) {
        both("reduce", t -> m.reduce(t, (k, v) -> v, Integer::sum));
        both("reduce skip nulls", t -> m.reduce(t, (k, v) -> v > 18 ? v : null, Integer::sum));
        both("reduce all null", t -> m.reduce(t, (k, v) -> null, (Integer a, Integer b) -> a + b));
        both("reduceToDouble", t -> m.reduceToDouble(t, (k, v) -> v * 0.5, (t == 1 ? 0.0 : 1.0), Double::sum));
        both("reduceToLong", t -> m.reduceToLong(t, (k, v) -> v, (t == 1 ? 0L : 100L), Long::sum));
        both("reduceToInt", t -> m.reduceToInt(t, (k, v) -> v, (t == 1 ? 0 : 1000), Integer::sum));
        both("reduceKeys", t -> m.reduceKeys(t, (a, b) -> a.length() >= b.length() ? "long" : "long").length());
        both("reduceKeys transform", t -> m.reduceKeys(t, k -> k.length(), Integer::sum));
        both("reduceKeysToDouble", t -> m.reduceKeysToDouble(t, k -> k.length(), (t == 1 ? 0.0 : 0.5), Double::sum));
        both("reduceKeysToLong", t -> m.reduceKeysToLong(t, k -> k.length(), (t == 1 ? 0L : 1L), Long::sum));
        both("reduceKeysToInt", t -> m.reduceKeysToInt(t, k -> k.length(), (t == 1 ? 0 : 2), Integer::sum));
        both("reduceValues", t -> m.reduceValues(t, Integer::sum));
        both("reduceValues transform", t -> m.reduceValues(t, v -> v * 2, Integer::sum));
        both("reduceValuesToDouble", t -> m.reduceValuesToDouble(t, v -> v, (t == 1 ? 0.0 : 0.25), Double::sum));
        both("reduceValuesToLong", t -> m.reduceValuesToLong(t, v -> v, (t == 1 ? 0L : 3L), Long::sum));
        both("reduceValuesToInt", t -> m.reduceValuesToInt(t, v -> v, (t == 1 ? 0 : 4), Integer::sum));
        both("reduceEntries", t -> m.reduceEntries(t, e -> e.getValue(), Integer::sum));
        both("reduceEntries merge", t -> {
            ConcurrentHashMap<String, Integer> one = new ConcurrentHashMap<>();
            one.put("only", 1);
            return m.getClass() == one.getClass() ? one.reduceEntries(t, (a, b) -> a) : "?";
        });
        both("reduceEntriesToDouble", t -> m.reduceEntriesToDouble(t, e -> e.getValue(), (t == 1 ? 0.0 : 0.5), Double::sum));
        both("reduceEntriesToLong", t -> m.reduceEntriesToLong(t, e -> e.getValue(), (t == 1 ? 0L : 5L), Long::sum));
        both("reduceEntriesToInt", t -> m.reduceEntriesToInt(t, e -> e.getValue(), (t == 1 ? 0 : 6), Integer::sum));
    }

    static void edges() {
        ConcurrentHashMap<String, Integer> e = new ConcurrentHashMap<>();
        both("empty reduce", t -> e.reduce(t, (k, v) -> v, Integer::sum));
        both("empty reduceToInt", t -> e.reduceToInt(t, (k, v) -> v, (t == 1 ? 0 : 42), Integer::sum));
        both("empty search", t -> e.search(t, (k, v) -> k));
        ConcurrentHashMap<String, Integer> m = map();
        both("forEach null", t -> { m.forEach(t, null); return "no"; });
        both("search null", t -> m.search(t, null));
        both("reduce null", t -> m.reduce(t, (k, v) -> v, null));
        both("reduceToInt null", t -> m.reduceToInt(t, null, (t == 1 ? 0 : 0), Integer::sum));
        both("reduceKeys null", t -> m.reduceKeys(t, null));
        both("forEach throws", t -> {
            // Only the class: which worker failed first, and so the message's form, is not fixed.
            try { m.forEach(t, (k, v) -> { throw new IllegalStateException("bulk"); }); return "no"; }
            catch (IllegalStateException ex) { return ex.getClass().getName(); }
        });
        show("threshold zero", () -> m.reduceValues(0, Integer::sum));
        show("threshold negative", () -> m.reduceValues(-1, Integer::sum));
        show("common pool intact", () -> !ForkJoinPool.commonPool().isShutdown());
    }

    static void keySets() {
        Set<String> ns = ConcurrentHashMap.newKeySet();
        show("newKeySet add", () -> ns.add("a"));
        show("newKeySet dup", () -> ns.add("a"));
        show("newKeySet", () -> ns);
        show("newKeySet null", () -> ns.add(null));
        show("newKeySet addAll", () -> ns.addAll(Arrays.asList("a", "b")));
        show("newKeySet size", ns::size);
        show("newKeySet cap", () -> ConcurrentHashMap.<String>newKeySet(32).isEmpty());
        show("newKeySet bad cap", () -> ConcurrentHashMap.<String>newKeySet(-1));
        ConcurrentHashMap.KeySetView<String, Boolean> typed = ConcurrentHashMap.newKeySet();
        show("view mapped true", typed::getMappedValue);
        typed.add("x");
        show("backing value", () -> typed.getMap().get("x"));

        ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
        ConcurrentHashMap.KeySetView<String, Integer> v = m.keySet(7);
        show("keySet default value", v::getMappedValue);
        show("keySet add puts default", () -> v.add("k"));
        show("keySet add again", () -> v.add("k"));
        show("backed", () -> m);
        show("addAll", () -> v.addAll(Arrays.asList("k", "l")));
        show("backing map", () -> v.getMap() == m);
        show("keySet null default", () -> m.keySet(null));
        ConcurrentHashMap.KeySetView<String, Integer> plain = m.keySet();
        show("plain default", plain::getMappedValue);
        show("plain add", () -> plain.add("z"));
        show("plain addAll", () -> plain.addAll(Arrays.asList("z")));
        show("hash vs set", () -> v.hashCode() == new java.util.HashSet<>(v).hashCode());
        show("view toString one", () -> new ConcurrentHashMap<>(Collections.singletonMap("k", 1)).keySet(1));
        for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() != 7) throw new AssertionError();
    }

    public static void main(String[] args) {
        ConcurrentHashMap<String, Integer> m = map();
        forEachs(m);
        searches(m);
        reduces(m);
        edges();
        keySets();
    }
}
