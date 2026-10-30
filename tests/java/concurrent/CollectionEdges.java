package concurrent;

import java.util.*;
import java.util.concurrent.*;

/** The concurrent collections: what a scratch probe of their unpinned corners decided. */
public class CollectionEdges {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static ConcurrentSkipListMap<Integer, String> map() {
        ConcurrentSkipListMap<Integer, String> m = new ConcurrentSkipListMap<>();
        for (int k = 10; k <= 50; k += 10) m.put(k, "v" + k);
        return m;
    }

    static void ranges() {
        ConcurrentNavigableMap<Integer, String> s = map().subMap(20, true, 40, true);
        ConcurrentNavigableMap<Integer, String> x = map().subMap(20, false, 40, false);
        show("head below the low end", () -> s.headMap(10));
        show("tail above the high end", () -> s.tailMap(50));
        show("sub above both ends", () -> s.subMap(50, true, 45, true));
        show("sub below the low end", () -> s.subMap(10, true, 30, true));
        show("sub above the high end", () -> s.subMap(30, true, 50, true));
        show("head on an excluded low end", () -> x.headMap(20, true));
        show("sub ending on an excluded low end", () -> x.subMap(20, false, 20, true));
        show("sub reversed inside", () -> x.subMap(30, true, 20, true));
        show("tail on an excluded high end", () -> x.tailMap(40, true));
        show("head inclusive on an excluded high end", () -> x.headMap(40, true));
        show("head exclusive on an excluded high end", () -> x.headMap(40, false));
        show("tail inclusive on an excluded low end", () -> x.tailMap(20, true));
        show("tail exclusive on an excluded low end", () -> x.tailMap(20, false));
        ConcurrentNavigableMap<Integer, String> d = s.descendingMap();
        show("descending head beyond", () -> d.headMap(50));
        show("descending tail beyond", () -> d.tailMap(10));
        show("descending sub beyond", () -> d.subMap(50, true, 30, true));
        show("descending of a range", () -> d + " " + d.descendingMap());
        show("descending nav", () -> d.lowerKey(30) + " " + d.floorKey(25) + " " + d.ceilingKey(25) + " " + d.higherKey(30) + " " + d.lowerKey(99) + " " + d.higherKey(5));
        show("range nav outside", () -> s.lowerKey(99) + " " + s.floorKey(99) + " " + s.ceilingKey(5) + " " + s.higherKey(5) + " " + s.lowerKey(5) + " " + s.higherKey(99));
        show("range keys", () -> s.keySet().subSet(25, 35) + " " + s.keySet().headSet(30) + " " + d.keySet().headSet(30) + " " + s.descendingKeySet());
        show("range write outside", () -> s.replace(99, "x"));
        show("range conditional outside", () -> s.remove(99, "x"));
        show("range computes outside", () -> s.computeIfAbsent(99, k -> null));
        show("range merge outside", () -> s.merge(99, "x", String::concat));
    }

    static void identity() {
        ConcurrentHashMap<String, Integer> h = new ConcurrentHashMap<>();
        show("hash views are one each", () -> (h.keySet() == h.keySet()) + " " + (h.values() == h.values()) + " " + (h.entrySet() == h.entrySet()));
        show("keySet(v) is new", () -> h.keySet(1) == h.keySet(1));
        ConcurrentSkipListMap<Integer, String> m = map();
        show("skip views are one each", () -> (m.keySet() == m.keySet()) + " " + (m.navigableKeySet() == m.keySet()) + " " + (m.values() == m.values()) + " " + (m.entrySet() == m.entrySet()) + " " + (m.descendingMap() == m.descendingMap()));
        show("double descending is not the map", () -> m.descendingMap().descendingMap() == m);
    }

    static void bulkEntries() {
        ConcurrentHashMap<String, Integer> h = new ConcurrentHashMap<>();
        h.put("a", 1);
        show("bulk entry is read-only", () -> { h.forEachEntry(Long.MAX_VALUE, e -> e.setValue(2)); return "no"; });
        show("reduced entry is read-only", () -> h.reduceEntries(Long.MAX_VALUE, (x, y) -> x).setValue(2));
        show("searched entry", () -> h.searchEntries(Long.MAX_VALUE, e -> e));
        show("view entry writes", () -> { h.entrySet().iterator().next().setValue(3); return h; });
        show("view entry of a removed key writes it back", () -> {
            Map.Entry<String, Integer> e = h.entrySet().iterator().next();
            h.remove("a");
            return e.setValue(4) + " " + h;
        });
    }

    static void iterationThroughGrowth() {
        ConcurrentHashMap<Integer, Integer> x = new ConcurrentHashMap<>();
        for (int i = 0; i < 12; i++) x.put(i, i);
        Set<Integer> seen = new HashSet<>();
        int repeats = 0;
        for (int k : x.keySet()) {
            if (!seen.add(k)) repeats++;
            if (x.size() < 1200) for (int j = 0; j < 100; j++) x.put(1000 + x.size(), 0);
        }
        int kept = 0;
        for (int i = 0; i < 12; i++) if (seen.contains(i)) kept++;
        int r = repeats, got = kept;
        show("growing map: no repeat, all old keys seen", () -> r + " " + got);
        ConcurrentHashMap<Integer, Integer> y = new ConcurrentHashMap<>();
        for (int i = 0; i < 10; i++) y.put(i, i);
        Iterator<Integer> it = y.keySet().iterator();
        it.next();
        for (int i = 0; i < 1000; i++) y.put(1000 + i, 0);
        for (int i = 0; i < 10; i++) y.remove(i);
        int old = 0;
        while (it.hasNext()) if (it.next() < 1000) old++;
        int o = old;
        show("removed after growth: at most the one it held", () -> o <= 1);
    }

    /** A computation shuts out writers of its own key, and nobody else. */
    static void computing() throws Exception {
        ConcurrentHashMap<String, Integer> b = new ConcurrentHashMap<>();
        b.put("k", 1);
        CountDownLatch in = new CountDownLatch(1), go = new CountDownLatch(1);
        Thread comp = new Thread(() -> b.compute("k", (k, v) -> {
            in.countDown();
            try { go.await(); } catch (InterruptedException e) { return -1; }
            return v + 1;
        }));
        comp.start();
        in.await();
        show("read during compute", () -> b.get("k"));
        show("other key during compute", () -> b.put("zz", 1));
        show("other compute during compute", () -> b.compute("zz", (k, v) -> v + 1));
        Thread w = new Thread(() -> b.put("k", b.getOrDefault("q", 100)));
        w.start();
        while (w.getState() != Thread.State.BLOCKED) Thread.sleep(1);
        show("writer of that key", w::getState);
        go.countDown();
        comp.join();
        w.join();
        show("writer came second", () -> b.get("k"));
        ConcurrentHashMap<String, Integer> t = new ConcurrentHashMap<>();
        t.put("k", 1);
        show("flag kept by compute", () -> { Thread.currentThread().interrupt(); return t.compute("k", (k, v) -> v + 1) + " " + Thread.interrupted(); });
    }

    static void copyOnWrite() {
        CopyOnWriteArrayList<String> x = new CopyOnWriteArrayList<>(Arrays.asList("a"));
        List<String> s = x.subList(0, 1);
        show("set of the same element is no write", () -> { x.set(0, x.get(0)); return s.size(); });
        show("failed removes are no write", () -> { x.remove("z"); x.removeIf(q -> false); x.removeAll(Arrays.asList("z")); x.retainAll(Arrays.asList("a")); return s.size(); });
        show("failed adds are no write", () -> { x.addIfAbsent("a"); x.addAllAbsent(Arrays.asList("a")); x.addAll(new ArrayList<>()); return s.size(); });
        show("same-order sort is a write", () -> { x.sort(null); return s.size(); });
        CopyOnWriteArrayList<String> y = new CopyOnWriteArrayList<>();
        List<String> e = y.subList(0, 0);
        show("clear of nothing is a write", () -> { y.clear(); return e.size(); });
        CopyOnWriteArrayList<String> z = new CopyOnWriteArrayList<>(Arrays.asList("a"));
        List<String> t = z.subList(0, 1);
        z.add("b");
        show("sub addAll of nothing, stale", () -> t.addAll(new ArrayList<>()));
        show("sub addAll at of nothing, stale", () -> t.addAll(0, new ArrayList<>()));
        show("sub get far, stale: index first", () -> t.get(9));
        show("sub add far, stale: staleness first", () -> { t.add(9, "q"); return "no"; });
        show("sub forEach, stale", () -> { t.forEach(q -> { }); return "ok"; });
        show("array source narrower than the list", () -> { CopyOnWriteArrayList<Object> o = new CopyOnWriteArrayList<Object>(new String[] {"a"}); o.set(0, 1); return o; });
        show("toArray is plain", () -> new CopyOnWriteArrayList<>(new String[] {"a"}).toArray().getClass().getName());
    }

    static void queues() throws Exception {
        LinkedTransferQueue<String> q = new LinkedTransferQueue<>();
        show("tryTransfer null unit leaves nothing", () -> q.tryTransfer("a", 1, null));
        show("nothing left", q::size);
        show("timed offer never reads its unit", () -> q.offer("a", 1, null) + " " + q.poll());
        Thread.currentThread().interrupt();
        show("zero tryTransfer flagged", () -> q.tryTransfer("a", 0, TimeUnit.SECONDS));
        show("flag cleared, not left", () -> Thread.currentThread().isInterrupted() + " " + q.size());
        PriorityBlockingQueue<Integer> p = new PriorityBlockingQueue<>(1, Comparator.reverseOrder());
        p.add(1);
        show("blocking queue source keeps its comparator", () -> new PriorityBlockingQueue<>(p).comparator() != null);
        Comparator<Integer> rev = Comparator.reverseOrder();
        PriorityQueue<Integer> plain = new PriorityQueue<>(1, rev);
        plain.add(1);
        show("java.util.PriorityQueue source: comparator dropped", () -> new PriorityBlockingQueue<>(plain).comparator());
        show("one non-comparable source", () -> new PriorityBlockingQueue<>(Arrays.asList(new Object())).size());
        show("comparator that throws, second insert", () -> {
            PriorityBlockingQueue<String> o = new PriorityBlockingQueue<>(1, (x, y) -> { throw new IllegalStateException("cmp"); });
            o.add("a");
            try { o.add("b"); } catch (IllegalStateException e) { return n(e) + " " + o.size() + " " + o.peek(); }
            return "no";
        });
        show("heap layout", () -> {
            PriorityBlockingQueue<Integer> o = new PriorityBlockingQueue<>();
            for (int v : new int[] {5, 3, 8, 1, 9, 2}) o.add(v);
            o.poll();
            o.remove(8);
            return o + " " + new PriorityBlockingQueue<>(Arrays.asList(5, 3, 8, 1, 9, 2));
        });
        SynchronousQueue<String> s = new SynchronousQueue<>();
        String[] got = new String[1];
        Thread taker = new Thread(() -> { try { got[0] = s.take(); } catch (InterruptedException e) { got[0] = "interrupted"; } });
        taker.start();
        while (taker.getState() != Thread.State.WAITING) Thread.sleep(1);
        Thread.currentThread().interrupt();
        show("put flagged, a taker waits", () -> { s.put("v"); return Thread.interrupted(); });
        taker.join();
        show("taker", () -> got[0]);
        Thread giver = new Thread(() -> { try { s.put("g"); } catch (InterruptedException e) { } });
        giver.start();
        while (giver.getState() != Thread.State.WAITING) Thread.sleep(1);
        Thread.currentThread().interrupt();
        show("timed poll flagged, a giver waits", () -> s.poll(1, TimeUnit.SECONDS) + " " + Thread.interrupted());
        giver.join();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread[] ts = new Thread[3];
        for (int i = 0; i < 3; i++) {
            int id = i;
            ts[i] = new Thread(() -> { try { order.add(id + ":" + s.take()); } catch (InterruptedException e) { } });
            ts[i].start();
            while (ts[i].getState() != Thread.State.WAITING) Thread.sleep(1);
        }
        for (int i = 0; i < 3; i++) { s.put("x" + i); ts[2 - i].join(); }
        show("non-fair: the newest taker first", () -> order);
        LinkedTransferQueue<String> c = new LinkedTransferQueue<>();
        Thread c1 = new Thread(() -> { try { c.take(); } catch (InterruptedException e) { } });
        Thread c2 = new Thread(() -> { try { c.take(); } catch (InterruptedException e) { } });
        c1.start();
        while (c.getWaitingConsumerCount() < 1) Thread.sleep(1);
        c2.start();
        while (c.getWaitingConsumerCount() < 2) Thread.sleep(1);
        show("one hand-over per consumer", () -> c.tryTransfer("a") + " " + c.tryTransfer("b") + " " + c.tryTransfer("c"));
        c1.join();
        c2.join();
        show("after", () -> c.size() + " " + c.getWaitingConsumerCount());
        ArrayBlockingQueue<Integer> fp = new ArrayBlockingQueue<>(1, true);
        fp.put(-1);
        Thread[] ps = new Thread[3];
        for (int i = 0; i < 3; i++) {
            int id = i;
            ps[i] = new Thread(() -> { try { fp.put(id); } catch (InterruptedException e) { } });
            ps[i].start();
            while (ps[i].getState() != Thread.State.WAITING) Thread.sleep(1);
        }
        List<Integer> took = new ArrayList<>();
        for (int i = 0; i < 4; i++) { took.add(fp.take()); if (i < 3) ps[i].join(); }
        show("fair putters FIFO", () -> took);
    }

    public static void main(String[] args) throws Exception {
        ranges();
        identity();
        bulkEntries();
        iterationThroughGrowth();
        computing();
        copyOnWrite();
        queues();
    }
}
