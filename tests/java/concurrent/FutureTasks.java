package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** FutureTask: outcomes, cancellation, hooks, repeated runs, get under timeout and interrupt. */
public class FutureTasks {
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

    static String flags(Future<?> f) { return "done=" + f.isDone() + " cancelled=" + f.isCancelled(); }

    /** Exposes the protected members. */
    static class Open<V> extends FutureTask<V> {
        final List<String> log = new ArrayList<>();
        Open(Callable<V> c) { super(c); }
        @Override protected void done() { log.add("done " + flags(this)); }
        boolean again() { return runAndReset(); }
        void value(V v) { set(v); }
        void failure(Throwable t) { setException(t); }
    }

    static void basics() throws Exception {
        show("null callable", () -> new FutureTask<String>(null));
        show("null runnable", () -> new FutureTask<String>(null, "v"));
        int[] runs = new int[1];
        FutureTask<String> f = new FutureTask<>(() -> { runs[0]++; return "v"; });
        show("toString", () -> f);
        show("fresh", () -> flags(f));
        show("timed get pending", () -> f.get(1, TimeUnit.MILLISECONDS));
        show("get zero pending", () -> f.get(0, TimeUnit.SECONDS));
        show("get null unit pending", () -> f.get(1, null));
        f.run();
        show("after run", () -> flags(f));
        show("get", f::get);
        show("get zero", () -> f.get(0, TimeUnit.SECONDS));
        show("get null unit done", () -> f.get(1, null));
        f.run();
        show("runs once", () -> runs[0]);
        show("cancel after done", () -> f.cancel(true));
        show("flags after late cancel", () -> flags(f));
        show("toString done", () -> f);

        FutureTask<String> rv = new FutureTask<>(() -> runs[0] = 50, "fixed");
        rv.run();
        show("runnable result", rv::get);
        show("runnable ran", () -> runs[0]);
        FutureTask<String> nul = new FutureTask<>(() -> { }, null);
        nul.run();
        show("null result", nul::get);
    }

    static void failures() throws Exception {
        FutureTask<String> f = new FutureTask<>(() -> { throw new java.io.IOException("io"); });
        f.run();
        show("failed flags", () -> flags(f));
        show("failed get", f::get);
        show("failed timed get", () -> f.get(1, TimeUnit.SECONDS));
        try { f.get(); } catch (ExecutionException e) {
            show("cause type", () -> e.getCause().getClass().getName());
            show("message", e::getMessage);
        }
        FutureTask<String> err = new FutureTask<>(() -> { throw new StackOverflowError("deep"); });
        err.run();
        show("error captured", err::get);
    }

    static void cancellation() throws Exception {
        int[] runs = new int[1];
        FutureTask<String> f = new FutureTask<>(() -> { runs[0]++; return "v"; });
        show("cancel new", () -> f.cancel(false));
        show("flags", () -> flags(f));
        show("cancel again", () -> f.cancel(false));
        show("get cancelled", f::get);
        show("timed get cancelled", () -> f.get(1, TimeUnit.SECONDS));
        f.run();
        show("run after cancel skipped", () -> runs[0]);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        String[] seen = new String[2];
        FutureTask<String> running = new FutureTask<>(() -> {
            entered.countDown();
            try { gate.await(); seen[0] = "finished"; }
            catch (InterruptedException e) { seen[0] = "interrupted"; throw e; }
            return "v";
        });
        Thread t = new Thread(running);
        t.start();
        entered.await();
        show("cancel(true) running", () -> running.cancel(true));
        show("flags running", () -> flags(running));
        t.join();
        show("task saw", () -> seen[0]);
        show("get", running::get);

        CountDownLatch entered2 = new CountDownLatch(1);
        CountDownLatch gate2 = new CountDownLatch(1);
        FutureTask<String> running2 = new FutureTask<>(() -> {
            entered2.countDown();
            gate2.await();
            seen[1] = "finished flag=" + Thread.currentThread().isInterrupted();
            return "v";
        });
        Thread t2 = new Thread(running2);
        t2.start();
        entered2.await();
        show("cancel(false) running", () -> running2.cancel(false));
        show("flags before task ends", () -> flags(running2));
        gate2.countDown();
        t2.join();
        show("task finished anyway", () -> seen[1]);
        show("get ignores its result", running2::get);

        FutureTask<String> waited = new FutureTask<>(() -> "v");
        String[] res = new String[1];
        Thread w = new Thread(() -> {
            try { res[0] = waited.get(); } catch (Exception e) { res[0] = n(e); }
        });
        w.start();
        until(() -> w.getState() == Thread.State.WAITING);
        waited.cancel(false);
        w.join();
        show("waiter released by cancel", () -> res[0]);
    }

    static void interrupts() throws Exception {
        FutureTask<String> f = new FutureTask<>(() -> "v");
        Thread.currentThread().interrupt();
        show("get flagged pending", f::get);
        show("flag cleared", () -> Thread.currentThread().isInterrupted());
        Thread.currentThread().interrupt();
        show("timed get flagged pending", () -> f.get(1, TimeUnit.SECONDS));
        f.run();
        Thread.currentThread().interrupt();
        show("get flagged done", f::get);
        show("flag after done get", Thread::interrupted);
        FutureTask<String> p = new FutureTask<>(() -> "v");
        String[] res = new String[1];
        Thread w = new Thread(() -> {
            try { res[0] = p.get(); } catch (Exception e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        w.start();
        until(() -> w.getState() == Thread.State.WAITING);
        w.interrupt();
        w.join();
        show("waiter interrupted", () -> res[0]);
        show("task unaffected", () -> flags(p));
    }

    static void protectedMembers() throws Exception {
        Open<String> a = new Open<>(() -> "called");
        a.run();
        show("done hook once", () -> a.log);
        a.value("set late");
        show("set ignored", a::get);
        a.failure(new RuntimeException("late"));
        show("setException ignored", a::get);
        Open<String> b = new Open<>(() -> "called");
        b.value("set");
        show("set wins", b::get);
        b.run();
        show("set then run", () -> b.log);
        Open<String> c = new Open<>(() -> "called");
        c.failure(new RuntimeException("x"));
        show("setException", c::get);
        show("setException flags", () -> c.log);
        Open<String> d = new Open<>(() -> "called");
        d.cancel(false);
        show("cancel fires done", () -> d.log);
        d.value("late");
        show("set after cancel", () -> flags(d));

        int[] count = new int[1];
        Open<String> e = new Open<>(() -> { count[0]++; return "v"; });
        show("runAndReset", e::again);
        show("runAndReset 2", e::again);
        show("still pending", () -> flags(e));
        show("reset count", () -> count[0]);
        show("reset no done", () -> e.log);
        e.run();
        show("normal run after", e::get);
        show("reset after done", e::again);
        Open<String> g = new Open<>(() -> { throw new RuntimeException("r"); });
        show("runAndReset failing", g::again);
        show("failing get", g::get);
    }

    public static void main(String[] args) throws Exception {
        basics();
        failures();
        cancellation();
        interrupts();
        protectedMembers();
    }
}
