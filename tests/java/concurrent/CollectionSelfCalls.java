package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
/**
 * Which public methods of ConcurrentHashMap and CopyOnWriteArrayList call which, as a subclass overriding
 * them sees it. Each line is one call on the subclass and the overridden methods it reached.
 */
public class CollectionSelfCalls {
    static final List<String> log = new ArrayList<>();
    static class M extends ConcurrentHashMap<String,Integer> {
        public Integer put(String k, Integer v) { log.add("put"); return super.put(k, v); }
        public Integer get(Object k) { log.add("get"); return super.get(k); }
        public boolean containsKey(Object k) { log.add("containsKey"); return super.containsKey(k); }
        public boolean containsValue(Object k) { log.add("containsValue"); return super.containsValue(k); }
        public Integer remove(Object k) { log.add("remove"); return super.remove(k); }
        public boolean remove(Object k, Object v) { log.add("remove2"); return super.remove(k, v); }
        public Integer putIfAbsent(String k, Integer v) { log.add("putIfAbsent"); return super.putIfAbsent(k, v); }
        public void clear() { log.add("clear"); super.clear(); }
        public int size() { log.add("size"); return super.size(); }
        public boolean isEmpty() { log.add("isEmpty"); return super.isEmpty(); }
        public Integer replace(String k, Integer v) { log.add("replace"); return super.replace(k, v); }
        public boolean replace(String k, Integer o, Integer v) { log.add("replace3"); return super.replace(k, o, v); }
        public void forEach(BiConsumer<? super String, ? super Integer> f) { log.add("forEach"); super.forEach(f); }
        public void forEach(long t, BiConsumer<? super String, ? super Integer> f) { log.add("forEachT"); super.forEach(t, f); }
        public void forEachKey(long t, Consumer<? super String> f) { log.add("forEachKey"); super.forEachKey(t, f); }
        public Set<Map.Entry<String,Integer>> entrySet() { log.add("entrySet"); return super.entrySet(); }
        public KeySetView<String,Integer> keySet() { log.add("keySet"); return super.keySet(); }
        public Collection<Integer> values() { log.add("values"); return super.values(); }
        public long mappingCount() { log.add("mappingCount"); return super.mappingCount(); }
    }
    static class L extends CopyOnWriteArrayList<String> {
        public boolean add(String e) { log.add("add"); return super.add(e); }
        public void add(int i, String e) { log.add("addAt"); super.add(i, e); }
        public boolean remove(Object o) { log.add("removeO"); return super.remove(o); }
        public String remove(int i) { log.add("removeI"); return super.remove(i); }
        public boolean addAll(Collection<? extends String> c) { log.add("addAll"); return super.addAll(c); }
        public boolean contains(Object o) { log.add("contains"); return super.contains(o); }
        public int indexOf(Object o) { log.add("indexOf"); return super.indexOf(o); }
        public int size() { log.add("size"); return super.size(); }
        public String get(int i) { log.add("get"); return super.get(i); }
        public String set(int i, String e) { log.add("set"); return super.set(i, e); }
        public Iterator<String> iterator() { log.add("iterator"); return super.iterator(); }
        public boolean addIfAbsent(String e) { log.add("addIfAbsent"); return super.addIfAbsent(e); }
        public Object[] toArray() { log.add("toArray"); return super.toArray(); }
    }
    static void show(String label, Supplier<Object> s) {
        log.clear(); Object r; try { r = s.get(); } catch (Throwable t) { r = t.getClass().getName(); }
        System.out.println(label + " = " + r + " calls " + log);
    }
    /** A map built from another calls the overridable putAll, and that no overridable put. */
    static class Built extends ConcurrentHashMap<String,Integer> {
        Built(Map<String,Integer> m) { super(m); }
        @Override public void putAll(Map<? extends String, ? extends Integer> m) { System.out.println("built: putAll"); super.putAll(m); }
        @Override public Integer put(String k, Integer v) { System.out.println("built: put"); return super.put(k, v); }
    }
    public static void main(String[] a) {
        new Built(Collections.singletonMap("a", 1));
        ConcurrentHashMap<Object,Object> own = new ConcurrentHashMap<>();
        own.put("a", own);
        System.out.println("toString of a map holding itself = " + own);
        own.clear();
        own.put(own, 1);
        System.out.println("toString of a map keyed by itself = " + own);
        M m = new M(); m.put("a", 1);
        show("map putAll", () -> { m.putAll(Collections.singletonMap("b", 2)); return m.containsKey("b"); });
        show("new-ish get", () -> m.get("a"));
        show("contains", () -> m.contains(1));
        show("containsValue", () -> m.containsValue(1));
        show("containsKey", () -> m.containsKey("a"));
        show("getOrDefault", () -> m.getOrDefault("a", 5));
        show("isEmpty", () -> m.isEmpty()); show("size", () -> m.size()); show("mappingCount", () -> m.mappingCount());
        show("hashCode", () -> m.hashCode()); show("toString", () -> m.toString().length()); show("equals", () -> m.equals(new HashMap<>(m)));
        show("merge", () -> m.merge("a", 1, Integer::sum)); show("compute", () -> m.compute("a", (k, v) -> v)); show("computeIfAbsent", () -> m.computeIfAbsent("q", k -> 1)); show("computeIfPresent", () -> m.computeIfPresent("q", (k, v) -> v));
        show("replaceAll", () -> { m.replaceAll((k, v) -> v); return 0; });
        show("forEach", () -> { m.forEach((k, v) -> { }); return 0; });
        show("forEachT", () -> { m.forEach(1L, (k, v) -> { }); return 0; });
        show("forEachKey", () -> { m.forEachKey(1L, k -> { }); return 0; });
        show("forEachValue", () -> { m.forEachValue(1L, k -> { }); return 0; });
        show("forEachEntry", () -> { m.forEachEntry(1L, k -> { }); return 0; });
        show("keys", () -> m.keys().hasMoreElements()); show("elements", () -> m.elements().hasMoreElements());
        ConcurrentHashMap.KeySetView<String,Integer> ks = m.keySet(9); show("keySet(v) add", () -> ks.add("z")); show("keySet(v) addAll", () -> ks.addAll(Arrays.asList("y")));
        Set<String> k0 = m.keySet(); Collection<Integer> v0 = m.values(); Set<Map.Entry<String,Integer>> e0 = m.entrySet();
        show("keys contains", () -> k0.contains("a")); show("keys remove", () -> k0.remove("y")); show("keys size", () -> k0.size()); show("keys isEmpty", () -> k0.isEmpty()); show("keys toString", () -> k0.toString().length());
        show("keys forEach", () -> { k0.forEach(k -> { }); return 0; });
        show("keys clear of copy", () -> { M c = new M(); c.keySet().clear(); return 0; });
        show("keys iterator remove", () -> { Iterator<String> i = k0.iterator(); i.next(); i.remove(); return 0; });
        m.put("a", 1); m.put("b", 2);
        show("values contains", () -> v0.contains(1)); show("values remove", () -> v0.remove(77)); show("values size", () -> v0.size());
        show("values iterator remove", () -> { Iterator<Integer> i = v0.iterator(); i.next(); i.remove(); return 0; });
        m.put("a", 1); m.put("b", 2);
        show("entries contains", () -> e0.contains(new AbstractMap.SimpleEntry<>("a", 1))); show("entries remove", () -> e0.remove(new AbstractMap.SimpleEntry<>("a", 1))); show("entries add", () -> e0.add(new AbstractMap.SimpleEntry<>("a", 1)));
        show("entries addAll", () -> e0.addAll(Arrays.asList(new AbstractMap.SimpleEntry<>("a", 1))));
        show("entry setValue", () -> { Map.Entry<String,Integer> e = e0.iterator().next(); return e.setValue(3); });
        show("entries iterator remove", () -> { Iterator<Map.Entry<String,Integer>> i = e0.iterator(); i.next(); i.remove(); return 0; });
        show("removeAll keys", () -> k0.removeAll(Arrays.asList("nothing"))); show("retainAll keys", () -> k0.retainAll(Arrays.asList(m.keys().nextElement())));
        show("clear", () -> { m.clear(); return 0; });
        L l = new L(); l.add("a"); l.add("b");
        show("list addIfAbsent new", () -> l.addIfAbsent("c")); show("list addIfAbsent present", () -> l.addIfAbsent("a"));
        show("list addAllAbsent", () -> l.addAllAbsent(Arrays.asList("x")));
        show("list addAll", () -> l.addAll(Arrays.asList("y"))); show("list addAll at", () -> l.addAll(0, Arrays.asList("y")));
        show("list contains", () -> l.contains("a")); show("list containsAll", () -> l.containsAll(Arrays.asList("a"))); show("list indexOf", () -> l.indexOf("a")); show("list lastIndexOf", () -> l.lastIndexOf("a"));
        show("list isEmpty", () -> l.isEmpty()); show("list hashCode", () -> l.hashCode()); show("list equals", () -> l.equals(new ArrayList<>(l))); show("list toString", () -> l.toString().length());
        show("list removeAll", () -> l.removeAll(Arrays.asList("nothing"))); show("list retainAll", () -> l.retainAll(new ArrayList<>(l))); show("list removeIf", () -> l.removeIf(x -> false));
        show("list sort", () -> { l.sort(null); return 0; }); show("list replaceAll", () -> { l.replaceAll(x -> x); return 0; }); show("list forEach", () -> { l.forEach(x -> { }); return 0; });
        show("list iterator", () -> l.iterator().hasNext()); show("list listIterator", () -> l.listIterator().hasNext()); show("list subList", () -> l.subList(0, 1).size()); show("list toArrayT", () -> l.toArray(new String[0]).length); show("list clone", () -> ((List<?>) l.clone()).size());
        show("list clear", () -> { l.clear(); return 0; });
    }
}
