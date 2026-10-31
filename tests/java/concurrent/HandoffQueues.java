package concurrent;

import java.util.*;
import java.util.concurrent.*;

/** SynchronousQueue and LinkedTransferQueue: hand-offs, waiting consumers, an unbounded FIFO. */
public class HandoffQueues {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    /** Takes one element, then refuses the rest: which elements survive the failure? */
    static <T> String drainFailing(BlockingQueue<T> q) {
        List<T> sink = new ArrayList<T>() {
            @Override public boolean add(T t) {
                if (!isEmpty()) throw new IllegalStateException("sink");
                return super.add(t);
            }
        };
        try { return "returned " + q.drainTo(sink); }
        catch (IllegalStateException e) { return n(e) + " left=" + q + " got=" + new ArrayList<>(sink); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static boolean parked(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
    }

    static void syncShape(SynchronousQueue<String> q) {
        show("toString", () -> q);
        show("size", q::size);
        show("isEmpty", q::isEmpty);
        show("remaining", q::remainingCapacity);
        show("peek", q::peek);
        show("poll", q::poll);
        show("element", q::element);
        show("remove()", () -> q.remove());
        show("offer alone", () -> q.offer("x"));
        show("add alone", () -> q.add("x"));
        show("timed offer alone", () -> q.offer("x", 1, TimeUnit.MILLISECONDS));
        show("timed poll alone", () -> q.poll(1, TimeUnit.MILLISECONDS));
        show("zero poll", () -> q.poll(0, TimeUnit.SECONDS));
        show("offer null", () -> q.offer(null));
        show("put null", () -> { q.put(null); return "no"; });
        show("timed offer null", () -> q.offer(null, 1, TimeUnit.SECONDS));
        show("add null", () -> q.add(null));
        show("contains", () -> q.contains("x"));
        show("contains null", () -> q.contains(null));
        show("remove obj", () -> q.remove("x"));
        show("removeAll", () -> q.removeAll(Arrays.asList("x")));
        show("retainAll", () -> q.retainAll(Arrays.asList("x")));
        show("containsAll", () -> q.containsAll(Arrays.asList("x")));
        show("containsAll empty", () -> q.containsAll(new ArrayList<>()));
        show("toArray", () -> Arrays.toString(q.toArray()));
        show("toArray typed sets null", () -> {
            String[] a = {"keep", "keep2"};
            q.toArray(a);
            return Arrays.toString(a);
        });
        show("toArray null", () -> q.toArray((String[]) null));
        show("iterator", () -> q.iterator().hasNext());
        show("iterator next", () -> q.iterator().next());
        show("iterator remove", () -> { q.iterator().remove(); return "no"; });
        List<String> out = new ArrayList<>();
        show("drain null", () -> q.drainTo(null));
        show("drain self", () -> q.drainTo(q));
        show("drain", () -> q.drainTo(out));
        show("drain max", () -> q.drainTo(out, 5));
        show("drain nothing to a refusing sink", () -> drainFailing(q));
        show("clear", () -> { q.clear(); return "ok"; });
    }

    static void syncFlow(SynchronousQueue<String> q) throws Exception {
        String[] res = new String[2];
        Thread taker = new Thread(() -> { try { res[0] = q.take(); } catch (InterruptedException e) { res[0] = "interrupted"; } });
        taker.start();
        until(() -> parked(taker));
        show("offer to taker", () -> q.offer("v"));
        taker.join();
        show("taker got", () -> res[0]);
        Thread giver = new Thread(() -> { try { q.put("w"); res[1] = "put returned"; } catch (InterruptedException e) { res[1] = "interrupted"; } });
        giver.start();
        until(() -> parked(giver));
        show("size while putter waits", q::size);
        show("peek while putter waits", q::peek);
        show("poll from putter", q::poll);
        giver.join();
        show("putter", () -> res[1]);
        Thread stuck = new Thread(() -> { try { q.put("z"); res[1] = "put returned"; } catch (InterruptedException e) { res[1] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); } });
        stuck.start();
        until(() -> parked(stuck));
        stuck.interrupt();
        stuck.join();
        show("interrupted put", () -> res[1]);
        show("nothing left", q::poll);
        Thread.currentThread().interrupt();
        show("take flagged", q::take);
        Thread.currentThread().interrupt();
        show("put flagged", () -> { q.put("x"); return "no"; });
        Thread.currentThread().interrupt();
        show("offer flagged", () -> q.offer("x"));
        Thread.currentThread().interrupt();
        show("poll flagged", q::poll);
        show("flag stays", Thread::interrupted);
    }

    static void fair() throws Exception {
        SynchronousQueue<Integer> q = new SynchronousQueue<>(true);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread[] ts = new Thread[3];
        for (int i = 0; i < 3; i++) {
            int id = i;
            ts[i] = new Thread(() -> { try { order.add("t" + id + " got " + q.take()); } catch (InterruptedException e) { } });
            ts[i].start();
            Thread t = ts[i];
            until(() -> parked(t));
        }
        for (int i = 0; i < 3; i++) {
            q.put(i);
            ts[i].join();
        }
        show("fair FIFO", () -> order);
    }

    static void transfer() throws Exception {
        LinkedTransferQueue<String> q = new LinkedTransferQueue<>();
        show("toString", () -> q);
        show("remaining", q::remainingCapacity);
        show("no consumer", q::hasWaitingConsumer);
        show("consumer count", q::getWaitingConsumerCount);
        show("tryTransfer none", () -> q.tryTransfer("x"));
        show("timed tryTransfer none", () -> q.tryTransfer("x", 1, TimeUnit.MILLISECONDS));
        show("left no trace", q::size);
        show("tryTransfer null", () -> q.tryTransfer(null));
        show("transfer null", () -> { q.transfer(null); return "no"; });
        show("timed tryTransfer null", () -> q.tryTransfer(null, 1, TimeUnit.SECONDS));
        show("offer null", () -> q.offer(null));
        show("add null", () -> q.add(null));
        show("put null", () -> { q.put(null); return "no"; });
        show("null collection", () -> new LinkedTransferQueue<String>(null));
        show("collection null element", () -> new LinkedTransferQueue<>(Arrays.asList("a", null)));
        show("collection", () -> new LinkedTransferQueue<>(Arrays.asList("a", "b")));
        String[] got = new String[1];
        Thread taker = new Thread(() -> { try { got[0] = q.take(); } catch (InterruptedException e) { got[0] = "interrupted"; } });
        taker.start();
        until(() -> q.hasWaitingConsumer());
        show("waiting", q::getWaitingConsumerCount);
        show("size ignores consumers", q::size);
        show("peek ignores consumers", q::peek);
        show("tryTransfer to consumer", () -> q.tryTransfer("direct"));
        taker.join();
        show("consumer got", () -> got[0]);
        show("drained", q::hasWaitingConsumer);
        String[] res = new String[1];
        Thread sender = new Thread(() -> { try { q.transfer("sent"); res[0] = "returned"; } catch (InterruptedException e) { res[0] = "interrupted"; } });
        sender.start();
        until(() -> q.size() == 1);
        show("pending visible", q::peek);
        show("contains pending", () -> q.contains("sent"));
        show("poll takes pending", q::poll);
        sender.join();
        show("sender", () -> res[0]);
        Thread stuck = new Thread(() -> { try { q.transfer("gone"); res[0] = "returned"; } catch (InterruptedException e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); } });
        stuck.start();
        until(() -> q.size() == 1);
        stuck.interrupt();
        stuck.join();
        show("interrupted transfer", () -> res[0]);
        show("element withdrawn", q::size);
        show("timed tryTransfer withdrawn", () -> q.tryTransfer("t", 5, TimeUnit.MILLISECONDS) + " " + q.size());
        Thread.currentThread().interrupt();
        show("transfer flagged", () -> { q.transfer("x"); return "no"; });
        show("not left behind", q::size);
        show("put never blocks", () -> { q.put("a"); q.add("b"); return q.offer("c") + " " + q.offer("d", 1, TimeUnit.SECONDS); });
        show("fifo", () -> q);
        show("peek", q::peek);
        show("element", q::element);
        show("contains null", () -> q.contains(null));
        show("remove null", () -> q.remove((Object) null));
        show("remove middle", () -> q.remove("b"));
        show("toArray", () -> Arrays.toString(q.toArray()));
        Iterator<String> it = q.iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        it.next();
        it.remove();
        show("iterator remove", () -> q);
        show("no CME", () -> { Iterator<String> w = q.iterator(); q.add("z"); while (w.hasNext()) w.next(); return "ok"; });
        List<String> out = new ArrayList<>();
        show("drain self", () -> q.drainTo(q));
        show("drain null", () -> q.drainTo(null));
        show("drain 1", () -> q.drainTo(out, 1));
        show("drain", () -> q.drainTo(out));
        show("out", () -> out);
        q.addAll(Arrays.asList("a", "b", "c"));
        show("drain refused midway", () -> drainFailing(q));
        q.clear();
        q.add("ready");
        Thread.currentThread().interrupt();
        show("take flagged, element ready", q::take);
        show("flag kept by take", () -> Thread.currentThread().isInterrupted());
        q.add("ready2");
        show("timed poll flagged, element ready", () -> q.poll(1, TimeUnit.SECONDS));
        show("flag kept by poll", Thread::interrupted);
        show("poll empty", q::poll);
        show("timed poll empty", () -> q.poll(1, TimeUnit.MILLISECONDS));
        show("element empty", q::element);
        Thread.currentThread().interrupt();
        show("take flagged", q::take);
        Thread stuck2 = new Thread(() -> { try { q.take(); } catch (InterruptedException e) { res[0] = "take " + n(e); } });
        stuck2.start();
        until(q::hasWaitingConsumer);
        stuck2.interrupt();
        stuck2.join();
        until(() -> !q.hasWaitingConsumer());
        show("interrupted consumer", () -> res[0]);
        show("consumer gone", q::getWaitingConsumerCount);
    }

    /** A timed offer that was met says so, whichever of the two came first, or a retry would deliver twice. */
    static void timedOfferMet(boolean fair) throws Exception {
        String mode = fair ? "fair" : "non-fair";
        SynchronousQueue<String> q = new SynchronousQueue<>(fair);
        Object[] answer = new Object[1];
        Thread giver = new Thread(() -> {
            try {
                answer[0] = q.offer("x", 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                answer[0] = n(e);
            }
        });
        giver.start();
        until(() -> parked(giver));
        show(mode + ": take from a waiting timed offer", q::take);
        giver.join();
        show(mode + ": that offer answers", () -> answer[0]);
        Object[] got = new Object[1];
        Thread taker = new Thread(() -> {
            try {
                got[0] = q.take();
            } catch (InterruptedException e) {
                got[0] = n(e);
            }
        });
        taker.start();
        until(() -> parked(taker));
        show(mode + ": timed offer to a waiting taker", () -> q.offer("y", 30, TimeUnit.SECONDS));
        taker.join();
        show(mode + ": that taker got", () -> got[0]);
        Thread giver2 = new Thread(() -> {
            try {
                answer[0] = q.offer("z", 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                answer[0] = n(e);
            }
        });
        giver2.start();
        until(() -> parked(giver2));
        show(mode + ": plain poll from a waiting timed offer", q::poll);
        giver2.join();
        show(mode + ": and that offer answers", () -> answer[0]);
        show(mode + ": nothing was left behind", q::poll);
    }

    /**
     * A sender taking back its own element owes itself no wake-up: a later park still parks. Any park may return
     * early, on any VM, so each of a few tries gets a timeout of its own: only a permit left by each of them stops all.
     */
    static void noPermitLeft() throws Exception {
        LinkedTransferQueue<String> q = new LinkedTransferQueue<>();
        show("timed tryTransfer alone", () -> q.tryTransfer("a", 1, TimeUnit.MILLISECONDS));
        show("no permit left by the timeout", () -> {
            boolean waited = parkWaits();
            for (int tries = 0; tries < 5 && !waited; tries++) {
                q.tryTransfer("a", 1, TimeUnit.MILLISECONDS);
                waited = parkWaits();
            }
            return waited;
        });
    }

    static boolean parkWaits() {
        long start = System.nanoTime();
        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
        return System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(200);
    }

    public static void main(String[] args) throws Exception {
        SynchronousQueue<String> q = new SynchronousQueue<>();
        syncShape(q);
        syncFlow(q);
        fair();
        transfer();
        timedOfferMet(false);
        timedOfferMet(true);
        noPermitLeft();
    }
}
