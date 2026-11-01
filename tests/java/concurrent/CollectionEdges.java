// VMARGS: -Xmx9g
package concurrent;

import java.util.*;
import java.util.concurrent.*;

/** The concurrent collections: what a scratch probe of their unpinned corners decided. The large capacities need the oracle's full default heap. */
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
        show("iterator removes that very element", () -> {
            PriorityBlockingQueue<String> o = new PriorityBlockingQueue<>(2, (x, y) -> 0);
            String one = new String("same"), two = new String("same");
            o.add(one);
            o.add(two);
            Iterator<String> i = o.iterator();
            i.next();
            String held = i.next();
            i.remove();
            return o.size() + " " + (o.peek() == one) + " " + (held == two);
        });
        LinkedBlockingDeque<String> d = new LinkedBlockingDeque<>();
        d.add("x");
        Thread.currentThread().interrupt();
        show("deque timed poll flagged, element ready", () -> d.pollFirst(0, TimeUnit.SECONDS));
        show("left in the deque", () -> Thread.currentThread().isInterrupted() + " " + d);
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

    /** One bin for all, so an iterator's next steps run along a chain. */
    static final class Same {
        final String s;
        Same(String s) { this.s = s; }
        @Override public int hashCode() { return 7; }
        @Override public String toString() { return s; }
    }

    static List<Object> rest(Iterator<?> it) {
        List<Object> l = new ArrayList<>();
        while (it.hasNext()) l.add(it.next());
        return l;
    }

    static void chains() throws Exception {
        Same a = new Same("a"), b = new Same("b"), c = new Same("c"), d = new Same("d");
        ConcurrentHashMap<Same, Integer> m = new ConcurrentHashMap<>();
        for (Same k : new Same[] {a, b, c, d}) m.put(k, 1);
        Iterator<Same> i = m.keySet().iterator();
        i.next();
        m.remove(b);
        m.remove(c);
        show("removed further along one bin", () -> rest(i).size());
        m.put(b, 1);
        m.put(c, 1);
        Iterator<Same> j = m.keySet().iterator();
        j.next();
        m.clear();
        show("cleared under an iterator", () -> rest(j).size());
        ConcurrentHashMap<String, Integer> h = new ConcurrentHashMap<>();
        h.put("k", 1);
        show("entry contains a null key", () -> h.entrySet().contains(new AbstractMap.SimpleEntry<>(null, 1)));
        show("entry contains a null value", () -> h.entrySet().contains(new AbstractMap.SimpleEntry<>("k", null)));
        CountDownLatch in = new CountDownLatch(1), go = new CountDownLatch(1);
        Thread comp = new Thread(() -> h.compute("k", (k, v) -> {
            in.countDown();
            try { go.await(); } catch (InterruptedException e) { return -1; }
            return v + 1;
        }));
        comp.start();
        in.await();
        int[] seen = new int[1];
        Thread late = new Thread(() -> seen[0] = h.computeIfAbsent("k", k -> 9));
        late.start();
        while (late.getState() != Thread.State.BLOCKED) Thread.sleep(1);
        show("computeIfAbsent of a present key waits for its compute", late::getState);
        go.countDown();
        comp.join();
        late.join();
        show("and answers what it computed", () -> seen[0]);
        show("get null with a comparator that allows it", () -> new ConcurrentSkipListMap<Integer, String>(Comparator.nullsFirst(Comparator.<Integer>naturalOrder())).get(null));
        show("set equals a smaller set", () -> new CopyOnWriteArraySet<>(Arrays.asList("a", "b")).equals(new HashSet<>(Arrays.asList("a"))));
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>(Arrays.asList("a", "b"));
        Iterator<String> w = q.iterator();
        w.next();
        q.poll();
        q.poll();
        q.add("c");
        show("linked queue iterator follows past what left", () -> rest(w));
        ConcurrentMap<String, Integer> raced = new ConcurrentHashMap<String, Integer>() {
            @Override public Integer computeIfAbsent(String k, java.util.function.Function<? super String, ? extends Integer> f) {
                return lost.computeIfAbsent(k, f);
            }
        };
        show("default computeIfAbsent that lost answers the winner", () -> raced.computeIfAbsent("k", k -> 1));
        show("default merge rejects a null value on an absent key", () -> lost.merge("new", null, Integer::sum));
    }

    /** Says another thread won every putIfAbsent, and leaves the rest to ConcurrentMap's defaults. */
    static final ConcurrentMap<String, Integer> lost = new LostRaces();

    static final class LostRaces extends AbstractMap<String, Integer> implements ConcurrentMap<String, Integer> {
        @Override public Set<Map.Entry<String, Integer>> entrySet() { return Collections.emptySet(); }
        @Override public Integer putIfAbsent(String k, Integer v) { return 77; }
        @Override public boolean remove(Object k, Object v) { return false; }
        @Override public boolean replace(String k, Integer o, Integer n) { return false; }
        @Override public Integer replace(String k, Integer v) { return null; }
    }

    /** What a sink does to the queue being drained: the bound differs per class, on the oracle. */
    static <T> void drainFeeding(String name, BlockingQueue<T> q, List<T> start, int max) {
        q.addAll(start);
        List<T> sink = new ArrayList<T>() {
            @Override public boolean add(T x) {
                if (size() > 1000) throw new IllegalStateException("sink full");
                q.offer(x);
                return super.add(x);
            }
        };
        show("drain into a sink feeding " + name + " " + max, () -> {
            try {
                return "moved " + q.drainTo(sink, max) + " left " + q.size();
            } catch (IllegalStateException e) {
                return n(e) + " moved " + sink.size() + " left " + q.size();
            }
        });
    }

    static final class Due implements Delayed {
        final int v;
        Due(int v) { this.v = v; }
        @Override public long getDelay(TimeUnit u) { return -1; }
        @Override public int compareTo(Delayed o) { return Integer.compare(v, ((Due) o).v); }
    }

    static void sinks() {
        for (int max : new int[] {2, 3, 5, Integer.MAX_VALUE}) {
            drainFeeding("LinkedBlockingQueue", new LinkedBlockingQueue<>(), Arrays.asList(1, 2, 3), max);
            drainFeeding("ArrayBlockingQueue", new ArrayBlockingQueue<>(6), Arrays.asList(1, 2, 3), max);
            drainFeeding("LinkedBlockingDeque", new LinkedBlockingDeque<>(), Arrays.asList(1, 2, 3), max);
            drainFeeding("PriorityBlockingQueue", new PriorityBlockingQueue<>(), Arrays.asList(1, 2, 3), max);
            drainFeeding("LinkedTransferQueue", new LinkedTransferQueue<>(), Arrays.asList(1, 2, 3), max);
            drainFeeding("DelayQueue", new DelayQueue<Due>(), Arrays.asList(new Due(1), new Due(2), new Due(3)), max);
        }
        LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>(Arrays.asList(1, 2, 3, 4));
        List<Integer> taking = new ArrayList<Integer>() {
            @Override public boolean add(Integer x) { q.poll(); return super.add(x); }
        };
        show("drain into a sink that takes from the queue", () -> q.drainTo(taking) + " " + taking + " left " + q);
    }

    static void copyOnWriteSiblings() {
        CopyOnWriteArrayList<Integer> list = new CopyOnWriteArrayList<>(Arrays.asList(1, 2, 3, 4, 5));
        List<Integer> me = list.subList(0, 3);
        List<Integer> sibling = list.subList(2, 5);
        Integer same = me.get(1);
        show("sub set of the very element", () -> me.set(1, same));
        show("its sibling stays valid", () -> sibling);
        show("as does the sub-list", () -> me);
        Integer equalOther = new Integer(same);
        show("sub set of an equal element", () -> me.set(1, equalOther));
        show("its sibling goes stale", () -> sibling);
        show("the sub-list stays valid", () -> me);
    }

    static void largeCapacities() {
        for (int capacity : new int[] {1 << 28, 1 << 30, Integer.MAX_VALUE}) {
            ConcurrentHashMap<Integer, Integer> m = new ConcurrentHashMap<>(capacity);
            show("capacity " + capacity + " costs nothing yet", () -> m.size() + " " + m.get(1) + " " + m.remove(1) + " " + m.keySet().iterator().hasNext() + " " + m.replace(1, 2) + " " + m.computeIfPresent(1, (a, b) -> b) + " " + m.compute(1, (a, b) -> null) + " " + m);
            m.clear();
            show("cleared", m::isEmpty);
        }
    }

    public static void main(String[] args) throws Exception {
        sinks();
        copyOnWriteSiblings();
        largeCapacities();
        ranges();
        identity();
        bulkEntries();
        iterationThroughGrowth();
        computing();
        copyOnWrite();
        queues();
        chains();
    }
}
