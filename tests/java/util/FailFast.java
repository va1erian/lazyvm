package util;

import java.util.*;
import java.util.function.*;

/**
 * Which operations make an open iterator throw ConcurrentModificationException, whether next()
 * checks for that before or after it checks for the end, and how far forEach-style methods get
 * when their action modifies the collection.
 */
public class FailFast {
    static String after(Iterator<?> it, Runnable change) {
        it.next();
        String r;
        try { change.run(); r = ""; } catch (RuntimeException e) { r = "!" + e.getClass().getSimpleName(); }
        try { it.next(); return r + "ok"; } catch (ConcurrentModificationException e) { return r + "CME"; } catch (NoSuchElementException e) { return r + "end"; }
    }

    static List<Integer> five() { return Arrays.asList(3, 1, 4, 1, 5); }

    static List<Integer> list(String kind) {
        return kind.equals("ArrayList") ? new ArrayList<>(five()) : kind.equals("LinkedList") ? new LinkedList<>(five()) : new Vector<>(five());
    }

    static void sized(List<Integer> l, int how, int n) {
        if (l instanceof ArrayList) { if (how == 0) ((ArrayList<Integer>) l).ensureCapacity(n); else ((ArrayList<Integer>) l).trimToSize(); }
        else if (l instanceof Vector) { if (how == 0) ((Vector<Integer>) l).ensureCapacity(n); else if (how == 1) ((Vector<Integer>) l).trimToSize(); else ((Vector<Integer>) l).setSize(n); }
    }

    static Map<Integer, Integer> map(String kind) {
        Map<Integer, Integer> m = kind.equals("HashMap") ? new HashMap<>() : kind.equals("LinkedHashMap") ? new LinkedHashMap<>()
            : kind.equals("access-order") ? new LinkedHashMap<>(16, .75f, true) : kind.equals("TreeMap") ? new TreeMap<>()
            : kind.equals("Hashtable") ? new Hashtable<>() : new IdentityHashMap<>();
        for (int i = 1; i <= 4; i++) m.put(i, i);
        return m;
    }

    static int calls;

    static String count(Runnable r) {
        calls = 0;
        try { r.run(); return "ok@" + calls; } catch (ConcurrentModificationException e) { return "CME@" + calls; }
    }

    enum St { NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED }

    public static void main(String[] args) {
        Map<String, Consumer<List<Integer>>> lops = new LinkedHashMap<>();
        lops.put("set", l -> l.set(0, 7));
        lops.put("sort", l -> l.sort(null));
        lops.put("replaceAll", l -> l.replaceAll(x -> x));
        lops.put("removeIf none", l -> l.removeIf(x -> false));
        lops.put("removeAll none", l -> l.removeAll(Arrays.asList(99)));
        lops.put("retainAll all", l -> l.retainAll(five()));
        lops.put("addAll empty", l -> l.addAll(Collections.<Integer>emptyList()));
        lops.put("addAll(0, empty)", l -> l.addAll(0, Collections.<Integer>emptyList()));
        lops.put("remove missing", l -> l.remove((Object) 99));
        lops.put("subList.set", l -> l.subList(0, 2).set(0, 1));
        lops.put("empty subList.clear", l -> l.subList(1, 1).clear());
        lops.put("Collections.reverse", Collections::reverse);
        lops.put("Collections.swap", l -> Collections.swap(l, 0, 1));
        lops.put("ensureCapacity(0)", l -> sized(l, 0, 0));
        lops.put("ensureCapacity(1)", l -> sized(l, 0, 1));
        lops.put("trimToSize", l -> sized(l, 1, 0));
        lops.put("setSize(size)", l -> sized(l, 2, 5));
        for (String kind : new String[]{"ArrayList", "LinkedList", "Vector"}) {
            StringBuilder sb = new StringBuilder(kind + ":");
            for (Map.Entry<String, Consumer<List<Integer>>> e : lops.entrySet()) {
                List<Integer> l = list(kind);
                sb.append(' ').append(e.getKey()).append('=').append(after(l.iterator(), () -> e.getValue().accept(l)));
            }
            System.out.println(sb);
        }
        Map<String, Consumer<Map<Integer, Integer>>> mops = new LinkedHashMap<>();
        mops.put("put existing", m -> m.put(1, 9));
        mops.put("put existing last", m -> m.put(4, 9));
        mops.put("putIfAbsent existing", m -> m.putIfAbsent(1, 9));
        mops.put("get", m -> m.get(1));
        mops.put("get last", m -> m.get(4));
        mops.put("get missing", m -> m.get(99));
        mops.put("getOrDefault", m -> m.getOrDefault(1, 0));
        mops.put("containsKey", m -> m.containsKey(1));
        mops.put("remove missing", m -> m.remove(99));
        mops.put("replace", m -> m.replace(1, 9));
        mops.put("replace(k, old, new)", m -> m.replace(1, 1, 9));
        mops.put("replaceAll", m -> m.replaceAll((k, v) -> v));
        mops.put("compute existing", m -> m.compute(1, (k, v) -> 9));
        mops.put("compute absent to null", m -> m.compute(99, (k, v) -> null));
        mops.put("computeIfAbsent existing", m -> m.computeIfAbsent(1, k -> 9));
        mops.put("computeIfAbsent to null", m -> m.computeIfAbsent(99, k -> null));
        mops.put("computeIfPresent", m -> m.computeIfPresent(1, (k, v) -> 9));
        mops.put("merge existing", m -> m.merge(1, 9, (x, y) -> y));
        mops.put("putAll empty", m -> m.putAll(new HashMap<Integer, Integer>()));
        mops.put("putAll existing", m -> m.putAll(Collections.singletonMap(1, 5)));
        mops.put("entry.setValue", m -> m.entrySet().iterator().next().setValue(5));
        for (String kind : new String[]{"HashMap", "LinkedHashMap", "access-order", "TreeMap", "Hashtable", "IdentityHashMap"}) {
            StringBuilder sb = new StringBuilder(kind + ":");
            for (Map.Entry<String, Consumer<Map<Integer, Integer>>> e : mops.entrySet()) {
                Map<Integer, Integer> m = map(kind);
                sb.append(' ').append(e.getKey()).append('=').append(after(m.keySet().iterator(), () -> e.getValue().accept(m)));
            }
            System.out.println(sb);
        }
        String[] kinds = {"ArrayList", "LinkedList", "Vector", "ArrayDeque", "PriorityQueue", "HashSet", "TreeSet", "HashMap", "LinkedHashMap", "TreeMap", "Hashtable", "IdentityHashMap"};
        for (String kind : kinds) {
            Collection<Integer> c;
            Map<Integer, Integer> m = null;
            switch (kind) {
                case "ArrayDeque": c = new ArrayDeque<>(five()); break;
                case "PriorityQueue": c = new PriorityQueue<>(five()); break;
                case "HashSet": c = new HashSet<>(five()); break;
                case "TreeSet": c = new TreeSet<>(five()); break;
                case "ArrayList": case "LinkedList": case "Vector": c = list(kind); break;
                default: m = map(kind); c = m.keySet();
            }
            final Map<Integer, Integer> om = m;
            final Collection<Integer> oc = c;
            Runnable grow = () -> { if (om != null) om.put(100 + calls, 0); else oc.add(100 + calls); };
            Iterator<Integer> it = c.iterator();
            while (it.hasNext()) it.next();
            grow.run();
            String end;
            try { it.next(); end = "ok"; } catch (ConcurrentModificationException e) { end = "CME"; } catch (NoSuchElementException e) { end = "NoSuchElement"; }
            StringBuilder sb = new StringBuilder(kind + ": exhausted iterator after an add: hasNext " + it.hasNext() + ", next " + end);
            Collection<Integer> fresh = om != null ? map(kind).keySet() : kind.equals("ArrayDeque") ? new ArrayDeque<>(five()) : kind.equals("PriorityQueue") ? new PriorityQueue<>(five())
                : kind.equals("HashSet") ? new HashSet<>(five()) : kind.equals("TreeSet") ? new TreeSet<>(five()) : list(kind);
            Map<Integer, Integer> fm = om != null ? map(kind) : null;
            Collection<Integer> fc = fm != null ? fm.keySet() : fresh;
            Runnable g2 = () -> { if (fm != null) fm.put(100 + calls, 0); else fc.add(100 + calls); };
            sb.append(" | forEach ").append(count(() -> fc.forEach(x -> { calls++; if (calls <= 5) g2.run(); })));
            if (fm != null) {
                Map<Integer, Integer> f2 = map(kind);
                sb.append(" map.forEach ").append(count(() -> f2.forEach((x, y) -> { calls++; if (calls <= 5) f2.put(100 + calls, 0); })));
                Map<Integer, Integer> f3 = map(kind);
                sb.append(" map.replaceAll ").append(count(() -> f3.replaceAll((x, y) -> { calls++; if (calls <= 5) f3.put(100 + calls, 0); return y; })));
            } else if (fresh instanceof List) {
                List<Integer> l2 = list(kind);
                sb.append(" replaceAll ").append(count(() -> l2.replaceAll(x -> { calls++; if (calls <= 5) l2.add(100 + calls); return x; })));
                List<Integer> l3 = list(kind);
                sb.append(" sort ").append(count(() -> l3.sort((x, y) -> { calls++; if (calls <= 5) l3.add(0); return 0; }))).append(" size ").append(l3.size());
            }
            Collection<Integer> r = om != null ? map(kind).keySet() : kind.equals("ArrayDeque") ? new ArrayDeque<>(five()) : kind.equals("PriorityQueue") ? new PriorityQueue<>(five())
                : kind.equals("HashSet") ? new HashSet<>(five()) : kind.equals("TreeSet") ? new TreeSet<>(five()) : list(kind);
            Iterator<Integer> ri = r.iterator();
            ri.next();
            sb.append(" | forEachRemaining ").append(count(() -> ri.forEachRemaining(x -> { calls++; if (calls == 1) { try { r.add(50); } catch (UnsupportedOperationException e) { r.remove(r.iterator().next()); } } })));
            System.out.println(sb);
        }
        StringBuilder empty = new StringBuilder("clear() of an empty collection, then next() of an older iterator:");
        for (String kind : new String[]{"ArrayList", "LinkedList", "Vector", "ArrayDeque", "PriorityQueue", "HashSet", "TreeSet", "HashMap", "LinkedHashMap", "Hashtable", "IdentityHashMap"}) {
            Collection<Integer> c = kind.equals("ArrayList") ? new ArrayList<>() : kind.equals("LinkedList") ? new LinkedList<>() : kind.equals("Vector") ? new Vector<>()
                : kind.equals("ArrayDeque") ? new ArrayDeque<>() : kind.equals("PriorityQueue") ? new PriorityQueue<>() : kind.equals("HashSet") ? new HashSet<>()
                : kind.equals("TreeSet") ? new TreeSet<>() : map(kind).keySet();
            c.clear();
            Iterator<Integer> it = c.iterator();
            c.clear();
            try { it.next(); empty.append(' ').append(kind).append("=ok"); } catch (ConcurrentModificationException e) { empty.append(' ').append(kind).append("=CME"); } catch (NoSuchElementException e) { empty.append(' ').append(kind).append("=end"); }
        }
        System.out.println(empty);
        EnumMap<St, Integer> em = new EnumMap<>(St.class);
        em.put(St.NEW, 0);
        em.put(St.WAITING, 0);
        StringBuilder seen = new StringBuilder();
        for (St s : em.keySet()) { seen.append(s.ordinal()); em.put(St.TERMINATED, 1); em.remove(St.NEW); }
        System.out.println("EnumMap never fails: " + seen + " " + em.keySet());
        System.out.println("done");
    }
}
