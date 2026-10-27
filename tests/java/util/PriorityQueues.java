package util;

import java.util.*;
import java.util.function.*;

/** PriorityQueue: heap array order, comparators, iterator removal and constructors. */
public class PriorityQueues {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static String drain(PriorityQueue<?> q) {
        StringBuilder sb = new StringBuilder();
        while (!q.isEmpty()) sb.append(q.poll()).append(' ');
        return sb.toString();
    }

    static final class Logged implements Comparable<Logged> {
        final int v;
        final StringBuilder log;
        Logged(int v, StringBuilder log) { this.v = v; this.log = log; }
        public int compareTo(Logged o) { log.append(v).append('?').append(o.v).append(' '); return Integer.compare(v, o.v); }
        public String toString() { return String.valueOf(v); }
    }

    public static void main(String[] args) {
        PriorityQueue<Integer> q = new PriorityQueue<>();
        int[] input = {15, 3, 9, 1, 12, 7, 20, 5, 5, 18, 2, 11};
        for (int x : input) {
            q.add(x);
            System.out.println("add " + x + " -> " + q + " peek " + q.peek());
        }
        System.out.println(q.size() + " " + q.contains(12) + " " + q.contains(13) + " " + Arrays.toString(q.toArray()) + " " + q.comparator());
        System.out.println(q.remove((Object) 9) + " " + q + " " + q.remove((Object) 99) + " " + q);
        System.out.println(q.poll() + " " + q.poll() + " " + q);
        t("iterator remove", () -> { Iterator<Integer> it = q.iterator(); StringBuilder sb = new StringBuilder(); while (it.hasNext()) { int v = it.next(); sb.append(v).append(' '); if (v % 3 == 0) it.remove(); } return sb + "| " + q; });
        t("iterator remove many", () -> { PriorityQueue<Integer> p = new PriorityQueue<>(); for (int i = 0; i < 20; i++) p.add((i * 7) % 20); Iterator<Integer> it = p.iterator(); StringBuilder sb = new StringBuilder(); while (it.hasNext()) { int v = it.next(); sb.append(v).append(' '); if (v % 2 == 0) it.remove(); } return sb + "| " + p + " " + drain(p); });
        t("iterator remove twice", () -> { Iterator<Integer> it = q.iterator(); it.next(); it.remove(); it.remove(); return 0; });
        t("iterator CME", () -> { for (Integer v : q) q.add(v + 100); return q; });
        t("iterator end", () -> { Iterator<Integer> it = new PriorityQueue<Integer>().iterator(); return it.next(); });
        t("drain", () -> drain(q));
        t("poll empty", () -> q.poll() + " " + q.peek());
        t("remove empty", () -> q.remove());
        t("element empty", () -> q.element());
        t("null add", () -> q.add(null));
        t("null offer", () -> q.offer(null));
        t("not comparable", () -> new PriorityQueue<Object>().add(new Object()));
        t("not comparable 2", () -> { PriorityQueue<Object> p = new PriorityQueue<>(); p.add("a"); p.add(1); return p; });
        PriorityQueue<String> byLen = new PriorityQueue<>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        byLen.addAll(Arrays.asList("pear", "fig", "banana", "kiwi", "apple", "plum", "date", "lime", "a"));
        System.out.println(byLen + " " + byLen.peek() + " " + (byLen.comparator() != null));
        System.out.println(drain(byLen));
        PriorityQueue<Integer> max = new PriorityQueue<>(Collections.reverseOrder());
        max.addAll(Arrays.asList(4, 8, 2, 9, 1, 7));
        System.out.println(max + " " + drain(max));
        PriorityQueue<Integer> sized = new PriorityQueue<>(3, (a, b) -> (a % 10) - (b % 10));
        for (int i : new int[]{19, 21, 34, 12, 55, 8, 40, 1}) sized.offer(i);
        System.out.println(sized + " " + drain(sized));
        t("ctor bad capacity", () -> new PriorityQueue<Integer>(0));
        t("ctor neg capacity", () -> new PriorityQueue<Integer>(-3, null));
        t("ctor null collection", () -> new PriorityQueue<Integer>((Collection<Integer>) null));
        t("ctor collection", () -> new PriorityQueue<Integer>(Arrays.asList(5, 3, 8, 1, 9, 2, 7)));
        t("ctor collection null element", () -> new PriorityQueue<Integer>(Arrays.asList(5, null, 8)));
        t("ctor single null", () -> new PriorityQueue<Integer>(Arrays.asList((Integer) null)));
        t("ctor sorted set", () -> { TreeSet<Integer> s = new TreeSet<>(Collections.reverseOrder()); s.addAll(Arrays.asList(1, 5, 3)); PriorityQueue<Integer> p = new PriorityQueue<Integer>(s); return p + " " + (p.comparator() == s.comparator()); });
        t("ctor priority queue", () -> { PriorityQueue<Integer> a = new PriorityQueue<>(Collections.reverseOrder()); a.addAll(Arrays.asList(1, 5, 3, 4)); PriorityQueue<Integer> b = new PriorityQueue<Integer>(a); PriorityQueue<Integer> c = new PriorityQueue<Integer>((Collection<Integer>) a); return a + " " + b + " " + c; });
        t("addAll self", () -> { PriorityQueue<Integer> a = new PriorityQueue<>(); a.add(1); return a.addAll(a); });
        t("addAll null", () -> new PriorityQueue<Integer>().addAll(null));
        t("equal elements", () -> { PriorityQueue<int[]> p = new PriorityQueue<>((a, b) -> a[0] - b[0]); for (int i = 0; i < 6; i++) p.add(new int[]{i % 2, i}); StringBuilder sb = new StringBuilder(); while (!p.isEmpty()) sb.append(p.poll()[1]); return sb; });
        t("toArray typed", () -> Arrays.toString(new PriorityQueue<Integer>(Arrays.asList(3, 1, 2)).toArray(new Integer[0])) + Arrays.toString(new PriorityQueue<Integer>(Arrays.asList(3, 1, 2)).toArray(new Integer[5])));
        t("toArray bad type", () -> new PriorityQueue<Integer>(Arrays.asList(3, 1, 2)).toArray(new String[0]));
        t("clear", () -> { PriorityQueue<Integer> p = new PriorityQueue<>(Arrays.asList(3, 1, 2)); p.clear(); return p + " " + p.size() + " " + p.peek(); });
        t("removeIf / forEach", () -> { PriorityQueue<Integer> p = new PriorityQueue<>(Arrays.asList(8, 3, 6, 1, 9, 2)); StringBuilder sb = new StringBuilder(); p.forEach(x -> sb.append(x).append(' ')); boolean r = p.removeIf(x -> x % 3 == 0); return sb + "| " + r + " " + p; });
        t("removeAll / retainAll", () -> { PriorityQueue<Integer> p = new PriorityQueue<>(Arrays.asList(8, 3, 6, 1, 9, 2)); boolean a = p.removeAll(Arrays.asList(3, 9)); String s = p.toString(); boolean b = p.retainAll(Arrays.asList(8, 2, 100)); return a + " " + s + " " + b + " " + p; });
        t("compare calls", () -> {
            StringBuilder log = new StringBuilder();
            Comparator<Integer> c = (x, y) -> { log.append(x).append('?').append(y).append(' '); return Integer.compare(x, y); };
            PriorityQueue<Integer> p = new PriorityQueue<>(c);
            for (int x : new int[]{5, 3, 8, 1, 9, 2, 7}) p.offer(x);
            log.append("| "); p.poll(); log.append("| "); p.remove(8); log.append("| ");
            for (Iterator<Integer> it = p.iterator(); it.hasNext();) { int v = it.next(); log.append('<').append(v).append("> "); if (v == 3) it.remove(); }
            return log + "=> " + p;
        });
        t("building calls", () -> {
            StringBuilder log = new StringBuilder();
            List<Logged> in = new ArrayList<>();
            for (int x : new int[]{9, 4, 7, 1, 8, 2, 6, 3}) in.add(new Logged(x, log));
            PriorityQueue<Logged> p = new PriorityQueue<>(in);
            log.append("| ");
            PriorityQueue<Logged> q2 = new PriorityQueue<>(p);
            TreeSet<Logged> ts = new TreeSet<>(in);
            log.append("| ");
            PriorityQueue<Logged> r = new PriorityQueue<>(ts);
            return log + "=> " + p + " " + q2 + " " + r;
        });
        t("large", () -> { PriorityQueue<Integer> p = new PriorityQueue<>(); for (int i = 0; i < 1000; i++) p.add((i * 7919) % 1009); long s = 0; int prev = -1; boolean ok = true; while (!p.isEmpty()) { int v = p.poll(); ok &= v >= prev; prev = v; s += v; } return s + " " + ok; });
        t("comparator adding to its own queue", () -> { List<PriorityQueue<Integer>> box = new ArrayList<>(); int[] n = {0};
            PriorityQueue<Integer> p = new PriorityQueue<>((x, y) -> { if (n[0]++ < 3) box.get(0).add(n[0] + 100); return Integer.compare(x, y); });
            box.add(p); p.add(1); p.add(2); p.add(3); return p + " " + n[0]; });
        t("size seen by the first comparison", () -> { List<PriorityQueue<Integer>> box = new ArrayList<>(); StringBuilder seen = new StringBuilder();
            PriorityQueue<Integer> p = new PriorityQueue<>((x, y) -> { seen.append(box.get(0).size()).append(' '); return Integer.compare(x, y); });
            box.add(p); p.add(5); p.add(3); p.add(8); p.add(1); return seen; });
        System.out.println("done");
    }
}
