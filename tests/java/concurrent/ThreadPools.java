package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** ThreadPoolExecutor: arguments, toString, growth, rejection, shutdown, hooks. */
public class ThreadPools {
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

    /** A named task whose toString is stable and which waits for a gate. */
    static class Task implements Runnable {
        final String name; final CountDownLatch gate; final List<String> log;
        Task(String name, CountDownLatch gate, List<String> log) { this.name = name; this.gate = gate; this.log = log; }
        @Override public void run() {
            try { if (gate != null) gate.await(); synchronized (log) { log.add(name); } }
            catch (InterruptedException e) { synchronized (log) { log.add(name + " interrupted"); } }
        }
        @Override public String toString() { return "Task(" + name + ")"; }
    }

    static ThreadPoolExecutor pool(int core, int max, BlockingQueue<Runnable> q) {
        return new ThreadPoolExecutor(core, max, 1, TimeUnit.MINUTES, q);
    }

    static void arguments() {
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
        ThreadFactory f = Executors.defaultThreadFactory();
        RejectedExecutionHandler h = new ThreadPoolExecutor.AbortPolicy();
        show("core negative", () -> pool(-1, 1, q));
        show("max zero", () -> pool(0, 0, q));
        show("max below core", () -> pool(2, 1, q));
        show("negative keepalive", () -> new ThreadPoolExecutor(1, 1, -1, TimeUnit.SECONDS, q));
        show("null queue", () -> pool(1, 1, null));
        show("null unit", () -> new ThreadPoolExecutor(1, 1, 1, null, q));
        show("null factory", () -> new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, q, (ThreadFactory) null));
        show("null handler", () -> new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, q, (RejectedExecutionHandler) null));
        show("null both", () -> new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, q, f, null));
        ThreadPoolExecutor p = pool(2, 3, q);
        show("toString", () -> p);
        show("core", p::getCorePoolSize);
        show("max", p::getMaximumPoolSize);
        show("keepAlive", () -> p.getKeepAliveTime(TimeUnit.SECONDS));
        show("keepAlive ms", () -> p.getKeepAliveTime(TimeUnit.MILLISECONDS));
        show("queue same", () -> p.getQueue() == q);
        show("default handler", () -> p.getRejectedExecutionHandler().getClass().getName());
        show("factory set", () -> p.getThreadFactory() != null);
        show("setCore negative", () -> { p.setCorePoolSize(-1); return "no"; });
        show("setMax zero", () -> { p.setMaximumPoolSize(0); return "no"; });
        show("setMax below core", () -> { p.setMaximumPoolSize(1); return "no"; });
        show("setCore above max", () -> { p.setCorePoolSize(9); return p.getCorePoolSize() + "/" + p.getMaximumPoolSize(); });
        show("setKeepAlive negative", () -> { p.setKeepAliveTime(-1, TimeUnit.SECONDS); return "no"; });
        show("setFactory null", () -> { p.setThreadFactory(null); return "no"; });
        show("setHandler null", () -> { p.setRejectedExecutionHandler(null); return "no"; });
        show("allowCore default", p::allowsCoreThreadTimeOut);
        ThreadPoolExecutor zero = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, q);
        show("allowCore zero keepalive", () -> { zero.allowCoreThreadTimeOut(true); return "no"; });
        show("allowCore off zero keepalive", () -> { zero.allowCoreThreadTimeOut(false); return "ok"; });
        p.allowCoreThreadTimeOut(true);
        show("setKeepAlive zero with allowCore", () -> { p.setKeepAliveTime(0, TimeUnit.SECONDS); return "no"; });
        show("execute null", () -> { p.execute(null); return "no"; });
        show("submit null", () -> p.submit((Runnable) null));
        show("submit null callable", () -> p.submit((Callable<String>) null));
        show("await null unit", () -> { p.shutdown(); return p.awaitTermination(0, null); });
        zero.shutdown();
    }

    static void growthAndRejection() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<String> log = new ArrayList<>();
        ThreadPoolExecutor p = pool(1, 2, new ArrayBlockingQueue<>(1));
        show("empty", () -> p);
        p.execute(new Task("a", gate, log));
        until(() -> p.getActiveCount() == 1);
        show("one core", () -> p);
        p.execute(new Task("b", gate, log));
        show("queued", () -> p);
        p.execute(new Task("c", gate, log));
        until(() -> p.getActiveCount() == 2);
        show("overflow worker", () -> p);
        show("rejected", () -> { p.execute(new Task("d", gate, log)); return "no"; });
        show("largest", p::getLargestPoolSize);
        show("taskCount", p::getTaskCount);
        show("queue", () -> p.getQueue());
        show("remove queued", () -> p.remove(p.getQueue().peek()));
        show("remove absent", () -> p.remove(new Task("x", null, log)));
        gate.countDown();
        until(() -> p.getCompletedTaskCount() == 2);
        until(() -> p.getActiveCount() == 0);
        show("completed", p::getCompletedTaskCount);
        show("idle", () -> p);
        p.shutdown();
        p.awaitTermination(30, TimeUnit.SECONDS);
        show("terminated", () -> p);
        show("after shutdown reject", () -> { p.execute(new Task("late", null, log)); return "no"; });
        show("after shutdown submit", () -> p.submit(new Task("late", null, log)));
        java.util.Collections.sort(log);
        show("ran", () -> log);
    }

    static void policies() throws Exception {
        for (RejectedExecutionHandler h : new RejectedExecutionHandler[] {
                new ThreadPoolExecutor.CallerRunsPolicy(), new ThreadPoolExecutor.DiscardPolicy(),
                new ThreadPoolExecutor.DiscardOldestPolicy(), new ThreadPoolExecutor.AbortPolicy()}) {
            CountDownLatch gate = new CountDownLatch(1);
            List<String> log = new ArrayList<>();
            ThreadPoolExecutor p = pool(1, 1, new ArrayBlockingQueue<>(2));
            p.setRejectedExecutionHandler(h);
            p.execute(new Task("w", gate, log));
            until(() -> p.getActiveCount() == 1);
            p.execute(new Task("q1", null, log));
            p.execute(new Task("q2", null, log));
            String name = h.getClass().getSimpleName();
            show(name + " overflow", () -> { p.execute(new Task("q3", null, log)); return "returned"; });
            show(name + " queue", () -> p.getQueue());
            show(name + " log", () -> log);
            gate.countDown();
            p.shutdown();
            p.awaitTermination(30, TimeUnit.SECONDS);
            show(name + " final", () -> log);
            show(name + " after shutdown", () -> { h.rejectedExecution(new Task("dead", null, log), p); return log; });
        }
        ThreadPoolExecutor p = pool(1, 1, new ArrayBlockingQueue<>(1));
        show("policy null task", () -> { new ThreadPoolExecutor.DiscardPolicy().rejectedExecution(null, p); return "ok"; });
        show("caller null task", () -> { new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(null, p); return "ok"; });
        p.shutdown();
    }

    static void shutdowns() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<String> log = new ArrayList<>();
        ThreadPoolExecutor p = pool(1, 1, new LinkedBlockingQueue<>());
        p.execute(new Task("run", gate, log));
        until(() -> p.getActiveCount() == 1);
        p.execute(new Task("q1", null, log));
        p.execute(new Task("q2", null, log));
        show("await not done", () -> p.awaitTermination(5, TimeUnit.MILLISECONDS));
        show("flags before", () -> p.isShutdown() + " " + p.isTerminating() + " " + p.isTerminated());
        p.shutdown();
        show("flags shutdown", () -> p.isShutdown() + " " + p.isTerminating() + " " + p.isTerminated());
        show("toString shutting", () -> p);
        p.shutdown();
        gate.countDown();
        show("await done", () -> p.awaitTermination(30, TimeUnit.SECONDS));
        show("flags terminated", () -> p.isShutdown() + " " + p.isTerminating() + " " + p.isTerminated());
        show("queued ran after shutdown", () -> log);
        show("await terminated zero", () -> p.awaitTermination(0, TimeUnit.SECONDS));
        show("shutdownNow terminated", p::shutdownNow);

        List<String> log2 = new ArrayList<>();
        ThreadPoolExecutor p2 = pool(1, 1, new LinkedBlockingQueue<>());
        CountDownLatch never = new CountDownLatch(1);
        p2.execute(new Task("run", never, log2));
        until(() -> p2.getActiveCount() == 1);
        p2.execute(new Task("q1", null, log2));
        p2.execute(new Task("q2", null, log2));
        show("shutdownNow", p2::shutdownNow);
        show("queue empty", () -> p2.getQueue());
        p2.awaitTermination(30, TimeUnit.SECONDS);
        show("interrupted worker", () -> log2);
        show("final", () -> p2);
        ThreadPoolExecutor p3 = pool(1, 1, new LinkedBlockingQueue<>());
        p3.shutdown();
        show("no workers terminate at once", p3::isTerminated);
        Thread.currentThread().interrupt();
        show("await flagged", () -> p3.awaitTermination(1, TimeUnit.SECONDS));
        ThreadPoolExecutor p4 = pool(1, 1, new LinkedBlockingQueue<>());
        Thread.currentThread().interrupt();
        show("await flagged running", () -> p4.awaitTermination(1, TimeUnit.SECONDS));
        p4.shutdown();
    }

    /** Thread-factory: counts creations; a dying worker is replaced. Handlers print, no stack frames. */
    static void hooksAndWorkers() throws Exception {
        List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> uncaught = java.util.Collections.synchronizedList(new ArrayList<>());
        int[] made = new int[1];
        ThreadFactory f = r -> {
            Thread t = new Thread(r, "w" + (++made[0]));
            // A separate list: no order against the other log is pinned.
            t.setUncaughtExceptionHandler((th, e) -> uncaught.add("uncaught " + th.getName() + " " + e));
            return t;
        };
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), f) {
            @Override protected void beforeExecute(Thread t, Runnable r) { log.add("before " + t.getName() + " " + r); }
            @Override protected void afterExecute(Runnable r, Throwable e) { log.add("after " + r + " " + e); }
            @Override protected void terminated() { log.add("terminated"); }
        };
        p.execute(new Task("ok", null, log));
        p.execute(new Runnable() {
            @Override public void run() { throw new IllegalStateException("boom"); }
            @Override public String toString() { return "Thrower"; }
        });
        p.execute(new Task("next", null, log));
        Future<?> sub = p.submit((Runnable) () -> { throw new IllegalStateException("sub"); });
        show("submit failure", () -> sub.get());
        p.shutdown();
        p.awaitTermination(30, TimeUnit.SECONDS);
        until(() -> uncaught.size() == 1);
        show("uncaught", () -> uncaught);
        for (String s : log) System.out.println("log " + n(s).replaceAll("FutureTask@H", "FutureTask"));
        show("threads made", () -> made[0]);
        show("completed counts failure", p::getCompletedTaskCount);

        boolean[] flag = new boolean[1];
        ThreadPoolExecutor one = pool(1, 1, new LinkedBlockingQueue<>());
        one.execute(() -> Thread.currentThread().interrupt());
        one.execute(() -> flag[0] = Thread.currentThread().isInterrupted());
        one.shutdown();
        one.awaitTermination(30, TimeUnit.SECONDS);
        show("interrupt not carried to next task", () -> flag[0]);

        ThreadPoolExecutor nul = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> null);
        show("null thread execute", () -> { nul.execute(() -> { }); return nul.getPoolSize() + " " + nul.getQueue().size(); });
        show("null thread prestart", nul::prestartCoreThread);
        nul.shutdownNow();
    }

    static void sizing() throws Exception {
        ThreadPoolExecutor p = new ThreadPoolExecutor(2, 2, 10, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        show("prestart one", p::prestartCoreThread);
        show("size", p::getPoolSize);
        show("prestart two", p::prestartCoreThread);
        show("prestart full", p::prestartCoreThread);
        ThreadPoolExecutor q = pool(3, 3, new LinkedBlockingQueue<>());
        q.prestartCoreThread();
        show("prestart all", q::prestartAllCoreThreads);
        show("size all", q::getPoolSize);
        show("no core-thread timeout", () -> { Thread.sleep(30); return p.getPoolSize(); });
        p.allowCoreThreadTimeOut(true);
        until(() -> p.getPoolSize() == 0);
        show("core timed out", p::getPoolSize);
        show("largest kept", p::getLargestPoolSize);
        q.setCorePoolSize(1);
        q.setMaximumPoolSize(1);
        until(() -> q.getPoolSize() == 1);
        show("shrunk", q::getPoolSize);
        q.setCorePoolSize(0);
        q.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
        until(() -> q.getPoolSize() == 0);
        show("shrunk to none", q::getPoolSize);
        ThreadPoolExecutor w = new ThreadPoolExecutor(0, 1, 1, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch ran = new CountDownLatch(1);
        w.execute(ran::countDown);
        show("zero core still runs", () -> ran.await(30, TimeUnit.SECONDS));
        p.shutdown();
        q.shutdown();
        w.shutdown();
        ThreadPoolExecutor c = pool(1, 1, new LinkedBlockingQueue<>());
        CountDownLatch gate = new CountDownLatch(1);
        c.execute(() -> { try { gate.await(); } catch (InterruptedException e) { } });
        until(() -> c.getActiveCount() == 1);
        Future<?> f = c.submit(() -> { });
        f.cancel(false);
        show("queue before purge", () -> c.getQueue().size());
        c.purge();
        show("queue after purge", () -> c.getQueue().size());
        gate.countDown();
        c.shutdown();
    }

    public static void main(String[] args) throws Exception {
        arguments();
        growthAndRejection();
        policies();
        shutdowns();
        hooksAndWorkers();
        sizing();
    }
}
