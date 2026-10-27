package util;

import java.util.*;
import java.util.function.*;

/** IdentityHashMap compares by reference; its iteration order is unspecified, so output is sorted. */
public class IdentityMaps {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static List<String> sorted(Collection<?> c) {
        List<String> l = new ArrayList<>();
        for (Object o : c) l.add(String.valueOf(o));
        Collections.sort(l);
        return l;
    }

    public static void main(String[] args) {
        String a1 = new String("key"), a2 = new String("key"), b = "other";
        IdentityHashMap<String, Integer> m = new IdentityHashMap<>();
        System.out.println(m.put(a1, 1) + " " + m.put(a2, 2) + " " + m.put(a1, 3) + " " + m.put(b, 4) + " " + m.size());
        System.out.println(m.get(a1) + " " + m.get(a2) + " " + m.get(new String("key")) + " " + m.get("other") + " " + m.containsKey(a2) + " " + m.containsKey(new String("key")) + " " + m.containsValue(3) + " " + m.containsValue(new Integer(3)));
        System.out.println(sorted(m.keySet()) + " " + sorted(m.values()) + " " + sorted(m.entrySet()));
        System.out.println(m.remove(new String("key")) + " " + m.remove(a2) + " " + m.size() + " " + m.remove(a2) + " " + sorted(m.values()));
        m.put(null, 9); m.put(c("x"), null);
        System.out.println(m.size() + " " + m.get(null) + " " + m.containsKey(null) + " " + m.containsValue(null) + " " + m.remove(null) + " " + m.size());
        Integer big1 = 1000, big2 = 1000, small1 = 5, small2 = 5;
        IdentityHashMap<Integer, String> im = new IdentityHashMap<>();
        im.put(big1, "big1"); im.put(big2, "big2"); im.put(small1, "small1"); im.put(small2, "small2");
        System.out.println(im.size() + " " + (big1 == big2) + " " + (small1 == small2));
        t("iterator remove", () -> { Iterator<String> it = m.keySet().iterator(); int n = 0; while (it.hasNext()) { it.next(); it.remove(); n++; } return n + " " + m.size() + " " + m.isEmpty(); });
        for (int i = 0; i < 40; i++) m.put(c("k" + i), i);
        System.out.println(m.size() + " " + sorted(m.values()).size());
        t("many", () -> { long sum = 0; for (int v : m.values()) sum += v; return sum; });
        t("entry setValue", () -> { for (Map.Entry<String, Integer> e : m.entrySet()) e.setValue(e.getValue() * 2); long sum = 0; for (int v : m.values()) sum += v; return sum; });
        t("values remove", () -> { Iterator<Integer> it = m.values().iterator(); while (it.hasNext()) if (it.next() % 4 == 0) it.remove(); return m.size(); });
        t("entrySet contains/remove", () -> { Map.Entry<String, Integer> any = m.entrySet().iterator().next(); String k = any.getKey(); Integer v = any.getValue(); return m.entrySet().contains(new AbstractMap.SimpleEntry<String, Integer>(k, v)) + " " + m.entrySet().contains(new AbstractMap.SimpleEntry<String, Integer>(new String(k), v)) + " " + m.entrySet().remove(new AbstractMap.SimpleEntry<String, Integer>(k, v)) + " " + m.size(); });
        t("keySet remove", () -> { String k = m.keySet().iterator().next(); int before = m.size(); return m.keySet().remove(new String(k)) + " " + m.keySet().remove(k) + " " + (before - m.size()); });
        t("putAll/clone", () -> { IdentityHashMap<String, Integer> c = new IdentityHashMap<>(m); IdentityHashMap<String, Integer> d = (IdentityHashMap<String, Integer>) m.clone(); d.clear(); return c.size() + " " + m.size() + " " + d.size() + " " + c.equals(m) + " " + c.equals(d); });
        t("equals other maps", () -> { String k = "shared"; IdentityHashMap<String, Integer> p = new IdentityHashMap<>(); p.put(k, 1); Map<String, Integer> h = new HashMap<>(); h.put(k, 1); return p.equals(h) + " " + h.equals(p) + " " + p.equals(new IdentityHashMap<String, Integer>(p)); });
        t("forEach/replaceAll", () -> { IdentityHashMap<String, Integer> p = new IdentityHashMap<>(); p.put(c("a"), 1); p.put(c("b"), 2); int[] sum = {0}; p.forEach((k, v) -> sum[0] += v); p.replaceAll((k, v) -> v * 10); long total = 0; for (int v : p.values()) total += v; return sum[0] + " " + total; });
        t("forEach CME", () -> { IdentityHashMap<String, Integer> p = new IdentityHashMap<>(); p.put(c("a"), 1); p.put(c("b"), 2); int[] calls = {0}; try { p.forEach((k, v) -> { calls[0]++; p.put(c("late"), 0); }); } catch (ConcurrentModificationException e) { return "CME after " + calls[0]; } return "no CME " + calls[0]; });
        t("iterator CME", () -> { IdentityHashMap<String, Integer> p = new IdentityHashMap<>(); p.put(c("a"), 1); p.put(c("b"), 2); Iterator<String> it = p.keySet().iterator(); it.next(); p.put(c("c"), 3); return it.next(); });
        t("iterator twice remove", () -> { IdentityHashMap<String, Integer> p = new IdentityHashMap<>(); p.put(c("a"), 1); Iterator<String> it = p.keySet().iterator(); it.next(); it.remove(); it.remove(); return 0; });
        t("iterator end", () -> new IdentityHashMap<String, Integer>().keySet().iterator().next());
        t("ctor negative", () -> new IdentityHashMap<String, Integer>(-1));
        t("ctor zero", () -> { IdentityHashMap<String, Integer> p = new IdentityHashMap<>(0); p.put("a", 1); return p; });
        t("ctor null", () -> new IdentityHashMap<String, Integer>((Map<String, Integer>) null));
        t("default Map methods", () -> { IdentityHashMap<String, Integer> p = new IdentityHashMap<>(); String k = c("k"); return p.putIfAbsent(k, 1) + " " + p.putIfAbsent(new String("k"), 2) + " " + p.computeIfAbsent(k, x -> 5) + " " + p.merge(k, 10, Integer::sum) + " " + p.getOrDefault(new String("k"), -1) + " " + p.replace(k, 7) + " " + p.remove(k, 7) + " " + p.size(); });
        System.out.println("done");
    }

    static String c(String s) {
        return new String(s);
    }
}
