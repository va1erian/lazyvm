package lazy.util;

import java.util.*;

/**
 * LazyVM's own rule (spec section 12) for a compute-family function that changes the map it runs
 * in, in the cases where the oracle's map ends up broken (a key twice, or a key lost): after the
 * function, the key is looked up again, so the map stays consistent. The golden is hand-written.
 */
public class ReentrantCompute {
    static String state(Map<Integer, Integer> m) {
        int n = 0;
        boolean ok = true;
        Set<Integer> seen = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            n++;
            ok &= seen.add(e.getKey()) && m.containsKey(e.getKey()) && Objects.equals(m.get(e.getKey()), e.getValue());
        }
        return "size " + m.size() + " walked " + n + (n == m.size() && ok ? " consistent" : " BROKEN") + " get " + m.get(7) + " " + m.get(8);
    }

    public static void main(String[] args) {
        String[] ops = {"computeIfAbsent puts the key", "compute puts the key", "computeIfAbsent puts many keys", "computeIfPresent puts many keys",
            "merge clears and puts the key", "compute clears, returns null"};
        for (int kind = 0; kind < 3; kind++) {
            for (int op = 0; op < ops.length; op++) {
                Map<Integer, Integer> m = kind == 0 ? new HashMap<>() : kind == 1 ? new LinkedHashMap<>() : new Hashtable<>();
                for (int i = 1; i <= 4; i++) m.put(i, i * 10);
                m.put(8, 80);
                Object x;
                switch (op) {
                    case 0: x = m.computeIfAbsent(7, q -> { m.put(7, 70); return 71; }); break;
                    case 1: x = m.compute(7, (q, v) -> { m.put(7, 70); return 71; }); break;
                    case 2: x = m.computeIfAbsent(7, q -> { for (int i = 20; i < 60; i++) m.put(i, i); return 71; }); break;
                    case 3: x = m.computeIfPresent(8, (q, v) -> { for (int i = 20; i < 60; i++) m.put(i, i); return 81; }); break;
                    case 4: x = m.merge(8, 1, (p, q) -> { m.clear(); m.put(8, 82); return 81; }); break;
                    default: x = m.compute(8, (q, v) -> { m.clear(); return null; }); break;
                }
                System.out.println(m.getClass().getSimpleName() + " " + ops[op] + ": " + x + " -> " + state(m));
            }
        }
        System.out.println("done");
    }
}
