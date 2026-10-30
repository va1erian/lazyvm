package concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentMap;

/** ConcurrentHashMap: arguments, nulls, atomic operations, views, equals. Order is never printed. */
public class ConcurrentHashMaps {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static ConcurrentHashMap<String, Integer> abc() {
        ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);
        return m;
    }

    static <T> List<T> sorted(Collection<T> c) {
        List<T> l = new ArrayList<>(c);
        Collections.sort(l, (x, y) -> x.toString().compareTo(y.toString()));
        return l;
    }

    static void arguments() {
        show("capacity negative", () -> new ConcurrentHashMap<String, Integer>(-1));
        show("capacity zero", () -> new ConcurrentHashMap<String, Integer>(0));
        show("factor zero", () -> new ConcurrentHashMap<String, Integer>(1, 0f));
        show("factor negative capacity", () -> new ConcurrentHashMap<String, Integer>(-1, 1f));
        show("concurrency zero", () -> new ConcurrentHashMap<String, Integer>(1, 1f, 0));
        show("null source", () -> new ConcurrentHashMap<String, Integer>(null));
        show("source null value", () -> {
            Map<String, Integer> src = new HashMap<>();
            src.put("x", null);
            return new ConcurrentHashMap<>(src);
        });
        show("copy", () -> new ConcurrentHashMap<>(Collections.singletonMap("k", 1)));
    }

    static void nulls() {
        ConcurrentHashMap<String, Integer> m = abc();
        show("put null key", () -> m.put(null, 1));
        show("put null value", () -> m.put("x", null));
        show("get null", () -> m.get(null));
        show("containsKey null", () -> m.containsKey(null));
        show("containsValue null", () -> m.containsValue(null));
        show("contains null", () -> m.contains(null));
        show("remove null", () -> m.remove(null));
        show("remove null key pair", () -> m.remove(null, 1));
        show("remove null value pair", () -> m.remove("a", null));
        show("putIfAbsent null key", () -> m.putIfAbsent(null, 1));
        show("putIfAbsent null value", () -> m.putIfAbsent("x", null));
        show("replace null key", () -> m.replace(null, 1));
        show("replace null value", () -> m.replace("a", null));
        show("replace3 null old", () -> m.replace("a", null, 1));
        show("replace3 null new", () -> m.replace("a", 1, null));
        show("getOrDefault null key", () -> m.getOrDefault(null, 1));
        show("getOrDefault null default", () -> m.getOrDefault("x", null));
        show("compute null key", () -> m.compute(null, (k, v) -> 1));
        show("compute null fn", () -> m.compute("a", null));
        show("computeIfAbsent null key", () -> m.computeIfAbsent(null, k -> 1));
        show("computeIfAbsent null fn", () -> m.computeIfAbsent("a", null));
        show("computeIfPresent null fn", () -> m.computeIfPresent("a", null));
        show("merge null key", () -> m.merge(null, 1, Integer::sum));
        show("merge null value", () -> m.merge("a", null, Integer::sum));
        show("merge null fn", () -> m.merge("a", 1, null));
        show("forEach null", () -> { m.forEach((java.util.function.BiConsumer<String, Integer>) null); return "no"; });
        show("replaceAll null", () -> { m.replaceAll(null); return "no"; });
        show("replaceAll to null", () -> { m.replaceAll((k, v) -> null); return "no"; });
        show("untouched", () -> sorted(m.entrySet()));
        show("putAll null", () -> { m.putAll(null); return "no"; });
        show("putAll null value", () -> {
            Map<String, Integer> src = new HashMap<>();
            src.put("y", null);
            m.putAll(src);
            return "no";
        });
    }

    static void basics() {
        ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
        show("empty", () -> m);
        show("empty flags", () -> m.isEmpty() + " " + m.size() + " " + m.mappingCount());
        show("put new", () -> m.put("k", 1));
        show("single toString", () -> m);
        show("put old", () -> m.put("k", 2));
        show("get", () -> m.get("k"));
        show("get absent", () -> m.get("q"));
        show("getOrDefault", () -> m.getOrDefault("q", 9));
        show("getOrDefault present", () -> m.getOrDefault("k", 9));
        show("contains value", () -> m.contains(2));
        show("containsValue", () -> m.containsValue(2));
        show("containsKey", () -> m.containsKey("k"));
        show("keys enumeration", () -> Collections.list(m.keys()));
        show("elements enumeration", () -> Collections.list(m.elements()));
        show("putIfAbsent present", () -> m.putIfAbsent("k", 3));
        show("putIfAbsent absent", () -> m.putIfAbsent("j", 3));
        show("remove pair wrong", () -> m.remove("j", 4));
        show("remove pair right", () -> m.remove("j", 3));
        show("replace absent", () -> m.replace("j", 3));
        show("replace present", () -> m.replace("k", 5));
        show("replace3 wrong", () -> m.replace("k", 4, 6));
        show("replace3 right", () -> m.replace("k", 5, 6));
        show("remove", () -> m.remove("k"));
        show("remove absent", () -> m.remove("k"));
        show("after remove", () -> m);
        ConcurrentHashMap<String, Integer> big = new ConcurrentHashMap<>(1);
        for (int i = 0; i < 2000; i++) big.put("key" + i, i);
        show("grown", () -> big.size() + " " + big.mappingCount() + " " + big.get("key1999"));
        int sum = 0;
        for (int v : big.values()) sum += v;
        int total = sum;
        show("values sum", () -> total);
        big.clear();
        show("cleared", big::isEmpty);
    }

    static void computes() {
        ConcurrentHashMap<String, Integer> m = abc();
        show("compute existing", () -> m.compute("a", (k, v) -> v + 10));
        show("compute absent", () -> m.compute("n", (k, v) -> v == null ? 100 : -1));
        show("compute to null removes", () -> m.compute("n", (k, v) -> null));
        show("compute absent null", () -> m.compute("z", (k, v) -> null));
        show("size", m::size);
        show("computeIfAbsent present", () -> m.computeIfAbsent("a", k -> -1));
        show("computeIfAbsent new", () -> m.computeIfAbsent("d", k -> 4));
        show("computeIfAbsent null", () -> m.computeIfAbsent("e", k -> null));
        show("computeIfPresent", () -> m.computeIfPresent("d", (k, v) -> v * 2));
        show("computeIfPresent to null", () -> m.computeIfPresent("d", (k, v) -> null));
        show("computeIfPresent absent", () -> m.computeIfPresent("d", (k, v) -> 1));
        show("merge new", () -> m.merge("m", 1, Integer::sum));
        show("merge old", () -> m.merge("m", 5, Integer::sum));
        show("merge to null", () -> m.merge("m", 5, (x, y) -> null));
        show("present", () -> m.containsKey("m"));
        show("compute throws", () -> m.compute("a", (k, v) -> { throw new IllegalStateException("in fn"); }));
        show("computeIfAbsent throws", () -> m.computeIfAbsent("t", k -> { throw new IllegalStateException("in fn"); }));
        show("merge throws", () -> m.merge("a", 1, (x, y) -> { throw new IllegalStateException("in fn"); }));
        show("unchanged", () -> sorted(m.entrySet()));
        m.replaceAll((k, v) -> v + 1);
        show("replaceAll", () -> sorted(m.entrySet()));
        List<String> seen = new ArrayList<>();
        m.forEach((k, v) -> seen.add(k + v));
        show("forEach", () -> sorted(seen));
    }

    static void views() {
        ConcurrentHashMap<String, Integer> m = abc();
        Set<String> keys = m.keySet();
        Collection<Integer> vals = m.values();
        Set<Map.Entry<String, Integer>> ents = m.entrySet();
        show("views live", () -> { m.put("d", 4); return keys.size() + " " + vals.size() + " " + ents.size(); });
        show("key add", () -> keys.add("x"));
        show("key addAll", () -> keys.addAll(Arrays.asList("x")));
        show("value add", () -> vals.add(5));
        show("value addAll", () -> vals.addAll(Arrays.asList(5)));
        show("entry add", () -> ents.add(new AbstractMap.SimpleEntry<>("e", 5)));
        show("entry add same", () -> ents.add(new AbstractMap.SimpleEntry<>("e", 5)));
        show("entry add changes", () -> ents.add(new AbstractMap.SimpleEntry<>("e", 6)));
        show("entry addAll", () -> ents.addAll(Arrays.asList(new AbstractMap.SimpleEntry<>("f", 7))));
        show("after entry adds", () -> sorted(m.entrySet()));
        show("entry contains", () -> ents.contains(new AbstractMap.SimpleEntry<>("a", 1)));
        show("entry contains wrong", () -> ents.contains(new AbstractMap.SimpleEntry<>("a", 2)));
        show("entry contains non-entry", () -> ((Set<?>) ents).contains("a"));
        show("entry contains null", () -> ents.contains(null));
        show("entry remove", () -> ents.remove(new AbstractMap.SimpleEntry<>("f", 7)));
        show("key contains null", () -> keys.contains(null));
        show("key remove null", () -> keys.remove(null));
        show("value contains null", () -> vals.contains(null));
        show("value remove null", () -> vals.remove(null));
        show("key remove", () -> keys.remove("e"));
        show("value remove", () -> vals.remove(4));
        show("after removes", () -> sorted(m.keySet()));
        show("keys toString", () -> new ConcurrentHashMap<>(Collections.singletonMap("k", 1)).keySet());
        show("values toString", () -> new ConcurrentHashMap<>(Collections.singletonMap("k", 1)).values());
        show("entries toString", () -> new ConcurrentHashMap<>(Collections.singletonMap("k", 1)).entrySet());
        show("toArray", () -> sorted(Arrays.asList(keys.toArray())));
        show("toArray typed", () -> keys.toArray(new String[0]).length);
        show("removeAll", () -> keys.removeAll(Arrays.asList("a", "zz")));
        show("retainAll", () -> keys.retainAll(Arrays.asList("b", "c")));
        show("removeAll null", () -> keys.removeAll(null));
        show("retainAll null", () -> keys.retainAll(null));
        show("containsAll", () -> keys.containsAll(Arrays.asList("b", "c")));
        show("keySet equals", () -> keys.equals(new HashSet<>(Arrays.asList("b", "c"))));
        show("view map", () -> m.keySet().getMap() == m);
        show("view default", () -> m.keySet().getMappedValue());
        keys.clear();
        show("clear via view", m::isEmpty);
    }

    static void iteration() {
        ConcurrentHashMap<String, Integer> m = abc();
        Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        Map.Entry<String, Integer> e = it.next();
        String key = e.getKey();
        show("entry setValue returns", () -> e.setValue(50) == null);
        show("writes through", () -> m.get(key));
        show("entry equals", () -> e.equals(new AbstractMap.SimpleEntry<>(key, 50)));
        show("entry hash", () -> e.hashCode() == (key.hashCode() ^ 50));
        show("entry toString", () -> e.toString().equals(key + "=50"));
        show("setValue null", () -> e.setValue(null));
        it.remove();
        show("iterator remove", () -> m.containsKey(key));
        show("double remove", () -> { it.remove(); return "no"; });
        show("mutate while iterating, no CME", () -> {
            int seen = 0;
            for (String k : m.keySet()) { m.put(k + "x", 1); m.remove(k); seen++; if (seen > 5000) break; }
            return "ok";
        });
        Iterator<Integer> vit = new ConcurrentHashMap<String, Integer>().values().iterator();
        show("hasNext empty", vit::hasNext);
        show("next empty", vit::next);
        Enumeration<String> en = new ConcurrentHashMap<String, Integer>().keys();
        show("enum empty", en::hasMoreElements);
        show("enum next empty", en::nextElement);
    }

    static void equality() {
        ConcurrentHashMap<String, Integer> m = abc();
        HashMap<String, Integer> h = new HashMap<>(m);
        show("equals hashmap", () -> m.equals(h));
        show("hashmap equals", () -> h.equals(m));
        show("hash", () -> m.hashCode() == h.hashCode());
        h.put("z", 1);
        show("not equal", () -> m.equals(h));
        show("equals other type", () -> m.equals("x"));
        show("is ConcurrentMap", () -> (Object) m instanceof ConcurrentMap);
    }

    static void threads() throws Exception {
        ConcurrentHashMap<Integer, Integer> counts = new ConcurrentHashMap<>();
        int workers = 4, each = 2000;
        Thread[] ts = new Thread[workers];
        CountDownLatch start = new CountDownLatch(1);
        for (int w = 0; w < workers; w++) {
            ts[w] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < each; i++) {
                    counts.merge(i % 16, 1, Integer::sum);
                    counts.computeIfAbsent(100 + i % 8, k -> 7);
                    counts.compute(200, (k, v) -> v == null ? 1 : v + 1);
                }
            });
            ts[w].start();
        }
        start.countDown();
        int sum = 0;
        for (Thread t : ts) t.join();
        for (int i = 0; i < 16; i++) sum += counts.get(i);
        int total = sum;
        show("merge total", () -> total);
        show("compute total", () -> counts.get(200));
        show("size", counts::size);
    }

    public static void main(String[] args) throws Exception {
        arguments();
        nulls();
        basics();
        computes();
        views();
        iteration();
        equality();
        threads();
    }
}
