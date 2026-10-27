package util;

import java.util.*;

/**
 * Compute-family functions that change the map they run in (spec section 12), in the cases where
 * the oracle's result is itself consistent; tests/java/lazy/util/ReentrantCompute covers the rest.
 */
public class MapReentry {
    /** The map, its size, and whether every entry a walk shows is found again, exactly once. */
    static String state(Map<Integer, Integer> m) {
        int n = 0;
        boolean ok = true;
        Set<Integer> seen = new HashSet<>();
        // A copy first: in access order, get is a change that would end a walk of the map itself.
        for (Map.Entry<Integer, Integer> e : new ArrayList<>(m.entrySet())) {
            n++;
            ok &= seen.add(e.getKey()) && m.containsKey(e.getKey()) && Objects.equals(m.get(e.getKey()), e.getValue());
        }
        return m + " size " + m.size() + (n == m.size() && ok ? " consistent" : " BROKEN");
    }

    public static void main(String[] args) {
        String[] ops = {"computeIfAbsent removes another key", "computeIfAbsent clears", "computeIfAbsent puts another key", "computeIfPresent removes the key",
            "computeIfPresent removes and puts back the key", "computeIfPresent clears", "compute removes the key", "compute clears, returns null",
            "merge removes the key", "merge clears", "compute puts back the key, returns null", "computeIfPresent puts back the key, returns null",
            "merge puts back the key, returns null", "compute clears and puts the key, returns null", "compute puts the absent key, returns null"};
        for (int kind = 0; kind < 4; kind++) {
            for (int op = 0; op < ops.length; op++) {
                if (kind == 2 && op == 7 || kind == 3 && (op >= 3 && op <= 6 || op == 8 || op == 9)) {
                    continue; // the oracle's own map breaks here; see lazy.util.ReentrantCompute
                }
                Map<Integer, Integer> m = kind == 0 ? new HashMap<>() : kind == 1 ? new LinkedHashMap<>() : kind == 2 ? new Hashtable<>()
                    : new LinkedHashMap<>(16, .75f, true);
                for (int i = 1; i <= 4; i++) m.put(i, i * 10);
                String r;
                try {
                    Object x;
                    switch (op) {
                        case 0: x = m.computeIfAbsent(7, q -> { m.remove(2); return 71; }); break;
                        case 1: x = m.computeIfAbsent(7, q -> { m.clear(); return 71; }); break;
                        case 2: x = m.computeIfAbsent(7, q -> { m.put(8, 80); return 71; }); break;
                        case 3: x = m.computeIfPresent(3, (q, v) -> { m.remove(3); return 31; }); break;
                        case 4: x = m.computeIfPresent(3, (q, v) -> { m.remove(3); m.put(3, 32); return 31; }); break;
                        case 5: x = m.computeIfPresent(3, (q, v) -> { m.clear(); return 31; }); break;
                        case 6: x = m.compute(3, (q, v) -> { m.remove(3); return 33; }); break;
                        case 7: x = m.compute(3, (q, v) -> { m.clear(); return null; }); break;
                        case 8: x = m.merge(2, 5, (p, q) -> { m.remove(2); return 25; }); break;
                        case 9: x = m.merge(2, 5, (p, q) -> { m.clear(); return 25; }); break;
                        case 10: x = m.compute(3, (q, v) -> { m.remove(3); m.put(3, 39); return null; }); break;
                        case 11: x = m.computeIfPresent(3, (q, v) -> { m.remove(3); m.put(3, 39); return null; }); break;
                        case 12: x = m.merge(3, 5, (p, q) -> { m.remove(3); m.put(3, 39); return null; }); break;
                        case 13: x = m.compute(3, (q, v) -> { m.clear(); m.put(3, 39); return null; }); break;
                        default: x = m.compute(7, (q, v) -> { m.put(7, 79); return null; }); break;
                    }
                    r = String.valueOf(x);
                } catch (RuntimeException e) { r = e.getClass().getSimpleName(); }
                System.out.println(m.getClass().getSimpleName() + " " + ops[op] + ": " + r + " -> " + state(m));
            }
        }
        System.out.println("done");
    }
}
