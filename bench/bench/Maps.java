package bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collections the way game code uses them: a registry looked up by name,
 * maps iterated by entry, key and value, lists iterated with for-each, and
 * boxing of the numbers they hold.
 */
public final class Maps {
    private Maps() {
    }

    public static void main(String[] args) {
        int rounds = 40 * Report.scale(args);
        Map<String, Integer> registry = new HashMap<>();
        String[] names = new String[200];
        for (int i = 0; i < names.length; i++) {
            names[i] = "tile_" + i;
            registry.put(names[i], i);
        }
        Map<Integer, Integer> counts = new HashMap<>();
        List<Integer> list = new ArrayList<>();
        long start = System.nanoTime();
        long sum = 0;
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 20; k++) {
                for (String n : names) {
                    sum += registry.get(n);
                }
            }
            for (int i = 0; i < 2000; i++) {
                Integer key = (i * 37 + r) & 511;
                Integer old = counts.get(key);
                counts.put(key, old == null ? 1 : old + 1);
            }
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                sum += e.getKey() * e.getValue();
            }
            for (Integer v : counts.values()) {
                sum += v;
            }
            for (Integer k : counts.keySet()) {
                sum ^= k;
            }
            list.clear();
            for (int i = 0; i < 2000; i++) {
                list.add(i ^ r);
            }
            for (Integer v : list) {
                sum += v;
            }
            sum += registry.containsKey("tile_" + (r % 300)) ? 1 : 0;
        }
        Report.print("maps", sum, start);
    }
}
