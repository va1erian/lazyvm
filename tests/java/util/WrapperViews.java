package util;

import java.util.*;
import java.util.function.*;

/** Which views of the wrapper and fixed maps are made once (spec 10.X8), and their remaining corners. */
public class WrapperViews {
    static String same(Supplier<Object> s) {
        try { return s.get() == s.get() ? "=" : "x"; } catch (RuntimeException e) { return "!" + e.getClass().getSimpleName(); }
    }

    static String tr(Supplier<Object> s) {
        try { return String.valueOf(s.get()); } catch (RuntimeException e) { return e.getClass().getSimpleName(); }
    }

    public static void main(String[] args) {
        TreeMap<Integer, Integer> tm = new TreeMap<>();
        tm.put(1, 1);
        tm.put(2, 2);
        Map<String, Map<Integer, Integer>> ws = new LinkedHashMap<>();
        ws.put("unmodifiableMap", Collections.unmodifiableMap(new HashMap<>(tm)));
        ws.put("unmodifiableSortedMap", Collections.unmodifiableSortedMap(new TreeMap<>(tm)));
        ws.put("unmodifiableNavigableMap", Collections.unmodifiableNavigableMap(new TreeMap<>(tm)));
        ws.put("synchronizedMap", Collections.synchronizedMap(new HashMap<>(tm)));
        ws.put("synchronizedSortedMap", Collections.synchronizedSortedMap(new TreeMap<>(tm)));
        ws.put("synchronizedNavigableMap", Collections.synchronizedNavigableMap(new TreeMap<>(tm)));
        ws.put("checkedMap", Collections.checkedMap(new HashMap<>(tm), Integer.class, Integer.class));
        ws.put("checkedSortedMap", Collections.checkedSortedMap(new TreeMap<>(tm), Integer.class, Integer.class));
        ws.put("checkedNavigableMap", Collections.checkedNavigableMap(new TreeMap<>(tm), Integer.class, Integer.class));
        ws.put("emptyNavigableMap", Collections.emptyNavigableMap());
        ws.put("singletonMap", Collections.singletonMap(1, 1));
        ws.put("unmodifiable singletonMap", Collections.unmodifiableMap(Collections.singletonMap(1, 1)));
        for (Map.Entry<String, Map<Integer, Integer>> e : ws.entrySet()) {
            Map<Integer, Integer> m = e.getValue();
            StringBuilder sb = new StringBuilder(e.getKey()).append(": keySet").append(same(m::keySet)).append(" values").append(same(m::values))
                .append(" entrySet").append(same(m::entrySet));
            if (m instanceof NavigableMap) {
                NavigableMap<Integer, Integer> n = (NavigableMap<Integer, Integer>) m;
                sb.append(" navigableKeySet").append(same(n::navigableKeySet)).append(" descendingKeySet").append(same(n::descendingKeySet))
                    .append(" descendingMap").append(same(n::descendingMap));
            }
            System.out.println(sb);
        }
        System.out.println("empty sorted and navigable constants: " + (Collections.emptyNavigableMap() == Collections.emptyNavigableMap()) + " "
            + (Collections.emptySortedMap() == (Object) Collections.emptyNavigableMap()) + " " + (Collections.emptyNavigableSet() == Collections.emptyNavigableSet()) + " "
            + (Collections.emptySortedSet() == (Object) Collections.emptyNavigableSet()));
        StringBuilder called = new StringBuilder();
        System.out.println("singletonMap replaceAll: " + tr(() -> { Collections.singletonMap(1, 2).replaceAll((a, b) -> { called.append("f "); return b; }); return "ok"; })
            + " calls [" + called + "] null function " + tr(() -> { Collections.singletonMap(1, 2).replaceAll(null); return "ok"; }));
        Map<Integer, Integer> base = new HashMap<>(tm);
        Map<Integer, Integer> checkedOverUnmod = Collections.checkedMap(Collections.unmodifiableMap(base), Integer.class, Integer.class);
        System.out.println("checked entrySet remove: of 3 " + tr(() -> checkedOverUnmod.entrySet().remove(3)) + ", of null " + tr(() -> checkedOverUnmod.entrySet().remove(null))
            + ", of an entry " + tr(() -> checkedOverUnmod.entrySet().remove(new AbstractMap.SimpleEntry<>(1, 1))));
        StringBuilder walks = new StringBuilder("wrapper view forEach while the map shrinks:");
        String[] names = {"unmodifiable", "synchronized", "checked"};
        for (int w = 0; w < 3; w++) {
            for (int view = 0; view < 3; view++) {
                HashMap<Integer, Integer> m = new HashMap<>();
                for (int i = 0; i < 6; i++) m.put(i, i);
                Map<Integer, Integer> x = w == 0 ? Collections.unmodifiableMap(m) : w == 1 ? Collections.synchronizedMap(m) : Collections.checkedMap(m, Integer.class, Integer.class);
                Collection<?> c = view == 0 ? x.keySet() : view == 1 ? x.values() : x.entrySet();
                StringBuilder seen = new StringBuilder();
                String r = tr(() -> { c.forEach(e -> { seen.append(e).append(','); if (seen.length() < 5) m.remove(5); }); return "ok"; });
                walks.append(' ').append(names[w]).append(view == 0 ? " keys " : view == 1 ? " values " : " entries ").append(r).append(" [").append(seen).append(']');
            }
        }
        System.out.println(walks);
        StringBuilder sized = new StringBuilder("putAll into an empty HashMap through a wrapper, then merge:");
        Map<Integer, Integer> src = new LinkedHashMap<>();
        src.put(1016, 1);
        src.put(1019, 2);
        for (int w = 0; w < 3; w++) {
            HashMap<Integer, Integer> m = new HashMap<>();
            Map<Integer, Integer> x = w == 0 ? Collections.checkedMap(m, Integer.class, Integer.class) : Collections.synchronizedMap(m);
            if (w == 2) m.putAll(src); else x.putAll(src);
            m.merge(1015, 3, (p, q) -> p);
            sized.append(' ').append(w == 0 ? "checked" : w == 1 ? "synchronized" : "the map's own putAll").append(' ').append(m);
        }
        System.out.println(sized);
        System.out.println("done");
    }
}
