package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** The ForkJoin extension points: a hand-written task, tags, worker hooks, the protected pool calls. */
public class ForkJoinTasks {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    /** Records calls to the three abstract members. */
    static class Manual extends ForkJoinTask<String> {
        final List<String> log = new ArrayList<>();
        final boolean finish;
        String value = "initial";
        Manual(boolean finish) { this.finish = finish; }
        @Override protected boolean exec() { log.add("exec"); return finish; }
        @Override public String getRawResult() { log.add("get"); return value; }
        @Override protected void setRawResult(String v) { log.add("set " + v); value = v; }
    }

    static void manual() throws Exception {
        Manual done = new Manual(true);
        show("exec true: invoke", done::invoke);
        show("exec true: log", () -> done.log);
        show("exec true: flags", () -> done.isDone() + " " + done.isCompletedNormally());
        Manual again = new Manual(true);
        again.invoke();
        again.invoke();
        show("no second exec", () -> again.log);
        Manual open = new Manual(false);
        show("exec false: runs and waits", () -> {
            ForkJoinPool p = new ForkJoinPool(1);
            p.execute(open);
            until(() -> open.log.contains("exec"));
            String s = "done=" + open.isDone();
            open.complete("given");
            String r = open.join();
            p.shutdown();
            return s + " then " + r;
        });
        show("complete calls set", () -> open.log);
        Manual quiet = new Manual(false);
        quiet.quietlyComplete();
        show("quietlyComplete", () -> quiet.isDone() + " " + quiet.isCompletedNormally() + " " + quiet.getRawResult());
        show("quietlyComplete log", () -> quiet.log);
        Manual throwing = new Manual(true) {
            @Override protected boolean exec() { throw new IllegalStateException("exec"); }
        };
        show("exec throws", throwing::invoke);
        show("exec throws flags", throwing::isCompletedAbnormally);
        Manual setBad = new Manual(false) {
            @Override protected void setRawResult(String v) { throw new IllegalStateException("set"); }
        };
        setBad.complete("x");
        show("setRawResult throws", setBad::isCompletedAbnormally);
        show("setRawResult exception", setBad::getException);
        RecursiveAction a = new RecursiveAction() {
            @Override protected void compute() { }
        };
        a.complete(null);
        show("action complete null", a::getRawResult);
        show("action normal", a::isCompletedNormally);
    }

    static void tags() {
        Manual t = new Manual(true);
        show("tag default", t::getForkJoinTaskTag);
        show("tag set returns old", () -> t.setForkJoinTaskTag((short) 5));
        show("tag", t::getForkJoinTaskTag);
        show("cas wrong", () -> t.compareAndSetForkJoinTaskTag((short) 4, (short) 6));
        show("cas right", () -> t.compareAndSetForkJoinTaskTag((short) 5, (short) -6));
        show("tag negative", t::getForkJoinTaskTag);
        t.invoke();
        show("tag survives completion", t::getForkJoinTaskTag);
        show("tag set after completion", () -> t.setForkJoinTaskTag((short) 7));
        show("flags intact", t::isCompletedNormally);
    }

    static class Hooked extends ForkJoinWorkerThread {
        static final List<String> log = Collections.synchronizedList(new ArrayList<>());
        Hooked(ForkJoinPool p) { super(p); }
        @Override protected void onStart() { super.onStart(); log.add("onStart"); }
        @Override protected void onTermination(Throwable e) { log.add("onTermination " + e); super.onTermination(e); }
    }

    static void workers() throws Exception {
        Thread[] worker = new Thread[1];
        ForkJoinPool p = new ForkJoinPool(1, pool -> {
            Hooked h = new Hooked(pool);
            worker[0] = h;
            return h;
        }, null, false);
        show("run", () -> p.submit(() -> { Hooked.log.add("task"); return "ok"; }).get());
        p.shutdown();
        p.awaitTermination(30, TimeUnit.SECONDS);
        worker[0].join();
        show("hooks in order", () -> Hooked.log);
        ForkJoinPool bad = new ForkJoinPool(1, pool -> null, null, false);
        show("null worker: no thread", () -> {
            bad.execute(() -> { });
            return bad.getPoolSize();
        });
        bad.shutdownNow();
        show("thrown by factory", () -> {
            ForkJoinPool t = new ForkJoinPool(1, pool -> { throw new IllegalStateException("factory"); }, null, false);
            t.execute(() -> { });
            int size = t.getPoolSize();
            t.shutdownNow();
            return size;
        });
        List<String> caught = Collections.synchronizedList(new ArrayList<>());
        ForkJoinPool handled = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, (t, e) -> caught.add(e.toString()), false);
        handled.execute(() -> { throw new IllegalStateException("escaped"); });
        until(() -> !caught.isEmpty());
        show("handler sees execute failure", () -> caught);
        show("pool keeps working", () -> handled.submit(() -> "still").get());
        handled.shutdown();
    }

    /** Exposes the protected queue operations. */
    static class Open extends ForkJoinPool {
        Open() { super(1); }
        ForkJoinTask<?> poll() { return pollSubmission(); }
        int drain(List<ForkJoinTask<?>> into) { return drainTasksTo(into); }
    }

    static void queues() throws Exception {
        Open p = new Open();
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        p.execute(() -> {
            entered.countDown();
            try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        entered.await();
        ForkJoinTask<String> a = p.submit(() -> "a");
        ForkJoinTask<String> b = p.submit(() -> "b");
        ForkJoinTask<String> c = p.submit(() -> "c");
        show("queued submissions", p::getQueuedSubmissionCount);
        show("hasQueuedSubmissions", p::hasQueuedSubmissions);
        show("active", p::getActiveThreadCount);
        show("not quiescent", p::isQuiescent);
        show("poll one is ours", () -> {
            ForkJoinTask<?> got = p.poll();
            return got == a || got == b || got == c;
        });
        List<ForkJoinTask<?>> sink = new ArrayList<>();
        show("drain rest", () -> p.drain(sink));
        show("drained", sink::size);
        show("queue empty", p::getQueuedSubmissionCount);
        show("poll empty", p::poll);
        gate.countDown();
        show("drained stay pending", () -> sink.get(0).isDone());
        p.shutdown();
        show("terminated", () -> p.awaitTermination(30, TimeUnit.SECONDS));
    }

    public static void main(String[] args) throws Exception {
        manual();
        tags();
        workers();
        queues();
    }
}
