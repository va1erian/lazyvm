package concurrent;

import java.util.*;
import java.util.concurrent.*;

/** ArrayBlockingQueue and LinkedBlockingQueue: the same contract, run through one script. */
public class BoundedQueues {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static String tag = "";

    static void show(String label, Body body) {
        try { System.out.println(tag + label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(tag + label + " ! " + n(t)); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static void contract(BlockingQueue<String> q) throws Exception {
        show("empty", () -> q);
        show("remaining", q::remainingCapacity);
        show("poll empty", q::poll);
        show("peek empty", q::peek);
        show("timed poll empty", () -> q.poll(1, TimeUnit.MILLISECONDS));
        show("zero poll empty", () -> q.poll(0, TimeUnit.SECONDS));
        show("element empty", q::element);
        show("remove empty", () -> q.remove());
        show("add null", () -> q.add(null));
        show("offer null", () -> q.offer(null));
        show("put null", () -> { q.put(null); return "no"; });
        show("timed offer null", () -> q.offer(null, 1, TimeUnit.SECONDS));
        show("addAll null elem", () -> q.addAll(Arrays.asList("z", null)));
        show("after partial addAll", () -> q);
        q.clear();
        show("addAll self", () -> q.addAll(q));
        show("add", () -> q.add("a"));
        show("offer", () -> q.offer("b"));
        q.put("c");
        show("full", () -> q);
        show("size", q::size);
        show("remaining full", q::remainingCapacity);
        show("add full", () -> q.add("x"));
        show("offer full", () -> q.offer("x"));
        show("timed offer full", () -> q.offer("x", 1, TimeUnit.MILLISECONDS));
        show("zero offer full", () -> q.offer("x", 0, TimeUnit.SECONDS));
        show("addAll full", () -> q.addAll(Arrays.asList("x")));
        show("peek", q::peek);
        show("element", q::element);
        show("contains", () -> q.contains("b"));
        show("contains null", () -> q.contains(null));
        show("remove obj null", () -> q.remove(null));
        show("remove obj absent", () -> q.remove("zz"));
        show("toArray", () -> Arrays.toString(q.toArray()));
        show("toArray big", () -> Arrays.toString(q.toArray(new String[5])));
        show("toArray null", () -> q.toArray((String[]) null));
        show("toArray bad type", () -> { try { return q.toArray(new Integer[0]); } catch (ArrayStoreException e) { return "ArrayStoreException"; } });
        show("iterate", () -> { List<String> l = new ArrayList<>(); for (String s : q) l.add(s); return l; });
        show("remove obj middle", () -> q.remove("b"));
        show("after remove", () -> q);
        show("poll", q::poll);
        show("take", q::take);
        show("drained", q::isEmpty);
    }

    static void drains(BlockingQueue<String> q) {
        q.addAll(Arrays.asList("a", "b", "c"));
        List<String> out = new ArrayList<>();
        show("drain null", () -> q.drainTo(null));
        show("drain self", () -> q.drainTo(q));
        show("drain self max", () -> q.drainTo(q, 1));
        show("drain none", () -> q.drainTo(out, 0));
        show("drain negative", () -> q.drainTo(out, -1));
        show("drain 2", () -> q.drainTo(out, 2));
        show("out", () -> out);
        show("left", () -> q);
        show("drain rest", () -> q.drainTo(out));
        show("out all", () -> out);
        show("drain empty", () -> q.drainTo(out));
        show("drain into failing", () -> {
            q.add("p");
            q.add("q");
            List<String> bad = new ArrayList<String>() {
                @Override public boolean add(String s) { throw new IllegalStateException("sink"); }
            };
            try { return q.drainTo(bad); }
            catch (IllegalStateException e) { return n(e) + " left=" + q; }
        });
        q.clear();
    }

    static void iterators(BlockingQueue<String> q) {
        q.addAll(Arrays.asList("a", "b", "c"));
        Iterator<String> it = q.iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        show("next", it::next);
        it.remove();
        show("iterator removed", () -> q);
        show("remove twice", () -> { it.remove(); return "no"; });
        show("next 2", it::next);
        q.remove("c");
        show("hasNext after concurrent removal (weak)", () -> {
            List<String> l = new ArrayList<>();
            while (it.hasNext()) l.add(it.next());
            return l.size() <= 1;
        });
        show("next past end", it::next);
        q.clear();
        Iterator<String> empty = q.iterator();
        show("empty hasNext", empty::hasNext);
        show("empty next", empty::next);
        q.addAll(Arrays.asList("a", "b"));
        Iterator<String> w = q.iterator();
        q.add("later");
        show("no CME", () -> { while (w.hasNext()) w.next(); return "ok"; });
        q.clear();
        q.add("x");
        List<String> seen = new ArrayList<>();
        q.forEach(seen::add);
        show("forEach", () -> seen);
        show("removeIf", () -> q.removeIf(s -> s.equals("x")));
        q.clear();
    }

    static void blocking(BlockingQueue<String> q) throws Exception {
        q.addAll(Arrays.asList("a", "b", "c"));
        String[] res = new String[2];
        Thread putter = new Thread(() -> {
            try { q.put("d"); res[0] = "put done"; } catch (InterruptedException e) { res[0] = "interrupted"; }
        });
        putter.start();
        until(() -> putter.getState() == Thread.State.WAITING);
        show("put blocks", () -> q.size());
        show("take unblocks", q::take);
        putter.join();
        show("putter", () -> res[0]);
        show("queue", () -> q);
        Thread stuck = new Thread(() -> {
            try { q.put("e"); res[0] = "put done"; }
            catch (InterruptedException e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        stuck.start();
        until(() -> stuck.getState() == Thread.State.WAITING);
        stuck.interrupt();
        stuck.join();
        show("interrupted put", () -> res[0]);
        show("not inserted", () -> q);
        q.clear();
        Thread taker = new Thread(() -> {
            try { res[1] = q.take(); } catch (InterruptedException e) { res[1] = "interrupted"; }
        });
        taker.start();
        until(() -> taker.getState() == Thread.State.WAITING);
        q.add("for taker");
        taker.join();
        show("taker", () -> res[1]);
        Thread stuck2 = new Thread(() -> {
            try { res[1] = q.poll(60, TimeUnit.SECONDS); }
            catch (InterruptedException e) { res[1] = "poll " + n(e); }
        });
        stuck2.start();
        until(() -> stuck2.getState() == Thread.State.TIMED_WAITING);
        stuck2.interrupt();
        stuck2.join();
        show("interrupted poll", () -> res[1]);
        Thread.currentThread().interrupt();
        show("take flagged, empty", q::take);
        q.add("a");
        Thread.currentThread().interrupt();
        show("take flagged, available", q::take);
        q.add("a");
        Thread.currentThread().interrupt();
        show("poll flagged", q::poll);
        Thread.interrupted();
        Thread.currentThread().interrupt();
        show("timed poll flagged", () -> q.poll(0, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        show("put flagged, free", () -> { q.put("b"); return q; });
        Thread.currentThread().interrupt();
        show("timed offer flagged", () -> q.offer("c", 0, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        show("offer flagged", () -> q.offer("c"));
        show("flag stays", Thread::interrupted);
        q.clear();
    }

    static void script(String name, BlockingQueue<String> q) throws Exception {
        tag = name + " ";
        contract(q);
        drains(q);
        iterators(q);
        blocking(q);
    }

    public static void main(String[] args) throws Exception {
        script("array", new ArrayBlockingQueue<>(3));
        script("linked", new LinkedBlockingQueue<>(3));
        tag = "";
        show("array bad capacity", () -> new ArrayBlockingQueue<String>(0));
        show("array negative", () -> new ArrayBlockingQueue<String>(-1, true));
        show("array init", () -> new ArrayBlockingQueue<>(3, false, Arrays.asList("a", "b")));
        show("array init too many", () -> new ArrayBlockingQueue<>(1, false, Arrays.asList("a", "b")));
        show("array init null collection", () -> new ArrayBlockingQueue<String>(1, false, null));
        show("array init null element", () -> new ArrayBlockingQueue<>(2, false, Arrays.asList("a", null)));
        show("linked bad capacity", () -> new LinkedBlockingQueue<String>(0));
        show("linked default capacity", () -> new LinkedBlockingQueue<String>().remainingCapacity());
        show("linked init", () -> new LinkedBlockingQueue<>(Arrays.asList("a", "b")));
        show("linked init capacity", () -> new LinkedBlockingQueue<>(Arrays.asList("a", "b")).remainingCapacity());
        show("linked init null", () -> new LinkedBlockingQueue<String>((Collection<String>) null));
        show("linked init null element", () -> new LinkedBlockingQueue<>(Arrays.asList("a", null)));

        ArrayBlockingQueue<Integer> fair = new ArrayBlockingQueue<>(1, true);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread[] ts = new Thread[3];
        for (int i = 0; i < ts.length; i++) {
            int id = i;
            ts[i] = new Thread(() -> {
                try { order.add("t" + id + " got " + fair.take()); } catch (InterruptedException e) { }
            });
            ts[i].start();
            Thread t = ts[i];
            until(() -> t.getState() == Thread.State.WAITING);
        }
        for (int i = 0; i < ts.length; i++) {
            fair.put(i);
            ts[i].join();
        }
        show("fair takers FIFO", () -> order);
    }
}
