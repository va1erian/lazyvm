package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** FutureTask, AbstractExecutorService, ExecutorCompletionService and Executors: the corners. */
public class FutureEdges {
    interface Body { Object run() throws Throwable; }
    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("\\$\\$Lambda\\$[0-9]+/[0-9]+", "-Lambda").replaceAll("-Lambda-[0-9]+", "-Lambda"); }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }
    interface Cond { boolean ok() throws Exception; }
    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!c.ok()) { if (System.nanoTime() > end) throw new AssertionError("stuck"); Thread.sleep(1); }
    }
    static void await(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static class Logged<V> extends FutureTask<V> {
        final List<String> log;
        Logged(Callable<V> c, List<String> log) { super(c); this.log = log; }
        @Override protected void done() { log.add("done cancelled=" + isCancelled() + " isDone=" + isDone()); }
        @Override protected void set(V v) { log.add("set " + v); super.set(v); }
        @Override protected void setException(Throwable t) { log.add("setException " + t); super.setException(t); }
        void s(V v) { set(v); }
        void e(Throwable t) { setException(t); }
        boolean r() { return runAndReset(); }
    }

    static void futureTask() throws Exception {
        List<String> log = new ArrayList<>();
        Logged<String> a = new Logged<>(() -> "v", log);
        a.run();
        show("run calls set virtually", () -> log);
        log.clear();
        Logged<String> b = new Logged<>(() -> { throw new Exception("x"); }, log);
        b.run();
        show("run calls setException virtually", () -> log);
        log.clear();
        Logged<String> c = new Logged<>(() -> "v", log);
        c.cancel(false);
        c.run();
        show("cancel skips set", () -> log);
        log.clear();
        // done() throws from set and from run and from cancel
        FutureTask<String> throwsDone = new FutureTask<String>(() -> "v") { @Override protected void done() { throw new IllegalStateException("done"); } };
        show("done throws in run", () -> { throwsDone.run(); return "returned"; });
        show("after done throws", throwsDone::get);
        FutureTask<String> throwsDone2 = new FutureTask<String>(() -> "v") { @Override protected void done() { throw new IllegalStateException("done"); } };
        show("done throws in cancel", () -> throwsDone2.cancel(false));
        show("after cancel throw", throwsDone2::isCancelled);
        // Re-entrant: run inside itself; get inside callable with timeout; cancel inside callable.
        FutureTask<String>[] self = new FutureTask[1];
        self[0] = new FutureTask<>(() -> { self[0].run(); return "outer"; });
        self[0].run();
        show("run inside run", self[0]::get);
        self[0] = new FutureTask<>(() -> { boolean x = self[0].cancel(true); return "flag=" + Thread.currentThread().isInterrupted() + x; });
        self[0].run();
        show("cancel inside own run", () -> self[0].isCancelled() + " flag=" + Thread.interrupted());
        self[0] = new FutureTask<>(() -> { try { return self[0].get(1, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { return "timed out"; } });
        self[0].run();
        show("get inside own run", self[0]::get);
        Logged<String> reset = new Logged<>(() -> "r", log);
        show("runAndReset no set", () -> reset.r() + " " + log);
        // runAndReset with cancel during
        Logged<String>[] sf = new Logged[1];
        sf[0] = new Logged<>(() -> { sf[0].cancel(false); return "x"; }, log);
        show("runAndReset cancelled during", () -> sf[0].r());
        log.clear();
        Logged<String>[] sg = new Logged[1];
        sg[0] = new Logged<>(() -> { sg[0].s("from inside"); return "x"; }, log);
        show("runAndReset set during", () -> sg[0].r() + " " + log + " " + sg[0].get());
        log.clear();
        // run concurrently: second run returns at once
        CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        FutureTask<String> busy = new FutureTask<>(() -> { runs.incrementAndGet(); in.countDown(); out.await(); return "b"; });
        Thread t = new Thread(busy);
        t.start();
        in.await();
        busy.run();
        show("second run while running", runs::get);
        show("runAndReset style while running", () -> new FutureTask<String>(() -> "z") { boolean q() { run(); return runAndReset(); } }.q());
        out.countDown();
        t.join();
        show("value", busy::get);
        // flag on the runner survives after run with cancel(true) before start
        FutureTask<String> ft = new FutureTask<>(() -> "x");
        ft.cancel(true);
        ft.run();
        show("cancel(true) before run no flag", Thread::interrupted);
        Thread.currentThread().interrupt();
        FutureTask<String> flagRun = new FutureTask<>(() -> "f=" + Thread.currentThread().isInterrupted());
        flagRun.run();
        show("run with flag", flagRun::get);
        show("flag still", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("timed zero flagged pending", () -> new FutureTask<>(() -> "x").get(0, TimeUnit.SECONDS));
        show("cleared", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("null unit flagged pending", () -> new FutureTask<>(() -> "x").get(1, null));
        show("flag kept by npe", Thread::interrupted);
        Thread.currentThread().interrupt();
        FutureTask<String> dn = new FutureTask<>(() -> "d"); dn.run();
        show("timed get flagged done", () -> dn.get(1, TimeUnit.SECONDS));
        show("flag kept", Thread::interrupted);
        Thread.currentThread().interrupt();
        FutureTask<String> cn = new FutureTask<>(() -> "d"); cn.cancel(false);
        show("get flagged cancelled", cn::get);
        show("flag kept 2", Thread::interrupted);
        // Thread state while waiting.
        FutureTask<String> w = new FutureTask<>(() -> "x");
        Thread waiter = new Thread(() -> { try { w.get(); } catch (Exception e) { } });
        waiter.start();
        until(() -> waiter.getState() == Thread.State.WAITING);
        Thread tw = new Thread(() -> { try { w.get(1, TimeUnit.MINUTES); } catch (Exception e) { } });
        tw.start();
        until(() -> tw.getState() == Thread.State.TIMED_WAITING);
        w.run();
        waiter.join(); tw.join();
        show("states ok", () -> "yes");
    }

    static class Direct extends AbstractExecutorService {
        final List<String> log = new ArrayList<>();
        boolean rejectSecond; int execs;
        @Override public void execute(Runnable r) { execs++; if (rejectSecond && execs == 2) throw new RejectedExecutionException("second"); log.add("exec"); r.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return false; }
    }

    static Callable<String> v(List<String> log, String s) { return () -> { log.add(s); return s; }; }
    static Callable<String> f(List<String> log, String s) { return () -> { log.add(s); throw new IllegalStateException(s); }; }

    /** An executor that queues and runs on demand. */
    static class Later extends AbstractExecutorService {
        final List<Runnable> q = new ArrayList<>();
        @Override public void execute(Runnable r) { q.add(r); }
        void runAll() { while (!q.isEmpty()) q.remove(0).run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return false; }
    }

    static void bases() throws Exception {
        List<String> log = new ArrayList<>();
        Direct d = new Direct();
        show("all null middle", () -> d.invokeAll(Arrays.asList(v(log, "a"), null, v(log, "b"))));
        show("all null middle log", () -> log + " " + d.log);
        log.clear(); d.log.clear();
        show("all timed null middle", () -> d.invokeAll(Arrays.asList(v(log, "a"), null, v(log, "b")), 1, TimeUnit.SECONDS));
        show("all timed null middle log", () -> log + " " + d.log);
        log.clear(); d.log.clear();
        Direct rej = new Direct(); rej.rejectSecond = true;
        Later lat = new Later();
        List<Future<String>>[] held = new List[1];
        show("all reject second", () -> rej.invokeAll(Arrays.asList(v(log, "a"), v(log, "b"), v(log, "c"))));
        show("all reject log", () -> log + " " + rej.log);
        log.clear();
        Direct rej2 = new Direct(); rej2.rejectSecond = true;
        show("any reject second", () -> rej2.invokeAny(Arrays.asList(f(log, "a"), v(log, "b"), v(log, "c"))));
        show("any reject log", () -> log + " " + rej2.log);
        log.clear();
        show("any all failed which", () -> d.invokeAny(Arrays.asList(f(log, "first"), f(log, "second"), f(log, "third"))));
        show("any all failed log", () -> log);
        log.clear();
        show("any null later", () -> d.invokeAny(Arrays.asList(f(log, "first"), null, v(log, "x"))));
        show("any null later log", () -> log);
        log.clear();
        Thread.currentThread().interrupt();
        show("any flagged direct", () -> d.invokeAny(Arrays.asList(v(log, "x"))));
        show("any flagged kept", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("all flagged direct", () -> d.invokeAll(Arrays.asList(v(log, "x"))).size());
        show("all flagged kept", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("all timed flagged direct", () -> d.invokeAll(Arrays.asList(v(log, "x")), 1, TimeUnit.SECONDS).size());
        show("all timed flagged kept", Thread::interrupted);
        Thread.currentThread().interrupt();
        Later l = new Later();
        show("any flagged later", () -> l.invokeAny(Arrays.asList(v(log, "x"))));
        show("any flagged later kept", Thread::interrupted);
        show("any later cancels", () -> { Future<?> x = (Future<?>) l.q.get(0); return "wrapper done=" + x.isDone(); });
        Later t = new Later();
        show("any timed zero", () -> t.invokeAny(Arrays.asList(v(log, "x"), v(log, "y")), 0, TimeUnit.SECONDS));
        show("any timed zero queued", () -> t.q.size());
        Later t2 = new Later();
        show("any timed neg", () -> t2.invokeAny(Arrays.asList(v(log, "x")), -1, TimeUnit.SECONDS));
        Later t3 = new Later();
        show("all timed zero executes", () -> { List<Future<String>> fs = t3.invokeAll(Arrays.asList(v(log, "x"), v(log, "y")), 0, TimeUnit.SECONDS); return fs.size() + " queued=" + t3.q.size() + " " + fs.get(0).isCancelled(); });
        Later t4 = new Later();
        Thread.currentThread().interrupt();
        show("all timed flagged", () -> t4.invokeAll(Arrays.asList(v(log, "x")), 1, TimeUnit.SECONDS));
        show("all timed flagged kept 2", Thread::interrupted);
        show("all timed flagged queued", () -> t4.q.size());
        show("submit runnable null result log", () -> { Direct x = new Direct(); return x.submit(() -> {}, null).get(); });
        // The type of what submit returns.
        show("submit class", () -> new Direct().submit(() -> {}).getClass().getName());
        // A cancelled inner future, seen by invokeAny
    }

    static void ecs() throws Exception {
        ExecutorService one = Executors.newSingleThreadExecutor();
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        one.execute(() -> { e.countDown(); FutureEdges.await(g); });
        e.await();
        ExecutorCompletionService<String> cs = new ExecutorCompletionService<>(one);
        Future<String> b = cs.submit(() -> "b");
        show("cancel queued", () -> b.cancel(false));
        show("not delivered until its turn", cs::poll);
        g.countDown();
        show("delivered then", () -> cs.take() == b);
        one.shutdown();
        one.awaitTermination(20, TimeUnit.SECONDS);
        show("rejected names", () -> { try { return cs.submit(() -> "c"); } catch (RejectedExecutionException x) { return x.getMessage().replaceAll("Task .* rejected", "Task T rejected"); } });
        // newTaskFor is used when the executor is an AbstractExecutorService.
        List<String> tasks = new ArrayList<>();
        AbstractExecutorService a = new Direct() {
            @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> c) { tasks.add("c"); return super.newTaskFor(c); }
            @Override protected <T> RunnableFuture<T> newTaskFor(Runnable r, T v) { tasks.add("r"); return super.newTaskFor(r, v); }
        };
        ExecutorCompletionService<String> cs2 = new ExecutorCompletionService<>(a);
        cs2.submit(() -> "x");
        cs2.submit(() -> { }, "y");
        show("ecs newTaskFor", () -> tasks);
        BlockingQueue<Future<String>> full = new ArrayBlockingQueue<>(1);
        ExecutorCompletionService<String> cs3 = new ExecutorCompletionService<>(Runnable::run, full);
        cs3.submit(() -> "1");
        show("queue full", () -> cs3.submit(() -> "2").get());
        show("queue full size", full::size);
        Executor seen = r -> { System.out.println("execute got FutureTask=" + (r instanceof FutureTask) + " future=" + (r instanceof Future)); r.run(); };
        Future<String> r1 = new ExecutorCompletionService<String>(seen).submit(() -> "x");
        show("returned class", () -> r1.getClass().getName());
    }

    static void factories() throws Exception {
        Callable<Object> c = Executors.callable(() -> { });
        show("privileged same", () -> { Callable<String> x = () -> "s"; return Executors.privilegedCallable(x) == x; });
        ThreadFactory f = Executors.defaultThreadFactory();
        ThreadGroup g = new ThreadGroup("mine");
        Thread[] made = new Thread[2];
        Thread maker = new Thread(g, () -> { made[0] = Executors.defaultThreadFactory().newThread(() -> { }); made[1] = f.newThread(() -> { }); });
        maker.start(); maker.join();
        show("group of factory made there", () -> made[0].getThreadGroup().getName());
        show("group of factory made in main", () -> made[1].getThreadGroup().getName());
        ThreadGroup low = new ThreadGroup("low"); low.setMaxPriority(3);
        Thread[] lo = new Thread[1];
        Thread lm = new Thread(low, () -> lo[0] = Executors.defaultThreadFactory().newThread(() -> { }));
        lm.start(); lm.join();
        show("priority in low group", () -> lo[0].getPriority());
        ExecutorService s = Executors.newSingleThreadExecutor();
        show("single class not pool", () -> s.getClass().getSimpleName().contains("ThreadPool"));
        show("single toString not bracket", () -> !s.toString().contains("["));
        s.shutdown();
        ExecutorService u = Executors.unconfigurableExecutorService(Executors.newFixedThreadPool(1));
        show("unconfigurable null submit", () -> u.submit((Runnable) null));
        show("unconfigurable execute null", () -> { u.execute(null); return "no"; });
        show("unconfigurable shutdownNow", u::shutdownNow);
        show("unconfigurable flags", () -> u.isShutdown() + " " + u.awaitTermination(20, TimeUnit.SECONDS) + " " + u.isTerminated());
        ScheduledExecutorService ss = Executors.newSingleThreadScheduledExecutor();
        show("single sched period", () -> { ScheduledFuture<?> sf = ss.scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.HOURS); boolean cn = sf.cancel(false); return cn; });
        show("single sched shutdownNow", () -> ss.shutdownNow().size());
        show("fixed negative", () -> Executors.newFixedThreadPool(-1));
        show("cached sized", () -> { ThreadPoolExecutor t = (ThreadPoolExecutor) Executors.newCachedThreadPool(); return t.getCorePoolSize() + " " + t.getLargestPoolSize() + " " + t.allowsCoreThreadTimeOut(); });
        show("factory given is kept", () -> { ThreadFactory x = r -> new Thread(r); ThreadPoolExecutor t = (ThreadPoolExecutor) Executors.newFixedThreadPool(1, x); return t.getThreadFactory() == x; });
        show("thread toString", () -> { Thread t = Executors.defaultThreadFactory().newThread(() -> { }); return t.toString().replaceAll("pool-[0-9]+", "pool-N"); });
        ExecutorService st = Executors.newWorkStealingPool(1);
        show("stealing invokeAll", () -> st.invokeAll(Arrays.asList(() -> "a")).get(0).get());
        st.shutdown();
    }

    public static void main(String[] args) throws Exception {
        futureTask();
        bases();
        ecs();
        factories();
        System.exit(0);
    }
}
