// VMARGS: -Djava.util.concurrent.ForkJoinPool.common.parallelism=3
package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** ForkJoinTask and ForkJoinPool: invokeAll's order and failures, status corners, workers, shutdown. */
public class ForkJoinEdges {
    interface Body { Object run() throws Throwable; }
    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("\\$\\$Lambda\\$[0-9]+/[0-9]+", "-Lambda").replaceAll("-Lambda-[0-9]+", "-Lambda").replaceAll("worker-[0-9]+", "worker-N").replaceAll("ForkJoinPool-[0-9]+", "ForkJoinPool-N"); }
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
    static <X extends Throwable> void sneak(Throwable t) throws X { throw (X) t; }
    static ForkJoinPool one() { return new ForkJoinPool(1); }

    static class Rec extends RecursiveAction {
        final String name; final List<String> log; final RuntimeException fail;
        Rec(String name, List<String> log, RuntimeException fail) { this.name = name; this.log = log; this.fail = fail; }
        @Override protected void compute() { synchronized (log) { log.add(name); } if (fail != null) throw fail; }
        @Override public String toString() { return name; }
    }
    static class Manual extends ForkJoinTask<String> {
        final List<String> log = new ArrayList<>(); final boolean finish; String value = "initial";
        Manual(boolean finish) { this.finish = finish; }
        @Override protected boolean exec() { log.add("exec"); return finish; }
        @Override public String getRawResult() { log.add("get"); return value; }
        @Override protected void setRawResult(String v) { log.add("set " + v); value = v; }
    }
    abstract static class Peek extends ForkJoinTask<Void> {
        static ForkJoinTask<?> peek() { return peekNextLocalTask(); }
        static ForkJoinTask<?> poll() { return pollNextLocalTask(); }
        static ForkJoinTask<?> any() { return pollTask(); }
    }

    static void invokeAlls() throws Exception {
        ForkJoinPool p = one();
        show("two order", () -> p.submit(() -> { List<String> l = new ArrayList<>(); ForkJoinTask.invokeAll(new Rec("a", l, null), new Rec("b", l, null)); return l; }).get());
        show("three order", () -> p.submit(() -> { List<String> l = new ArrayList<>(); ForkJoinTask.invokeAll(new Rec("a", l, null), new Rec("b", l, null), new Rec("c", l, null)); return l; }).get());
        show("list order", () -> p.submit(() -> { List<String> l = new ArrayList<>(); ForkJoinTask.invokeAll(Arrays.asList(new Rec("a", l, null), new Rec("b", l, null), new Rec("c", l, null))); return l; }).get());
        show("linked list order", () -> p.submit(() -> { List<String> l = new ArrayList<>(); ForkJoinTask.invokeAll(new LinkedList<>(Arrays.asList(new Rec("a", l, null), new Rec("b", l, null), new Rec("c", l, null)))); return l; }).get());
        show("empty array", () -> { ForkJoinTask.invokeAll(new ForkJoinTask<?>[0]); return "ok"; });
        show("empty list", () -> ForkJoinTask.invokeAll(new ArrayList<Rec>()).size());
        show("one element", () -> { List<String> l = new ArrayList<>(); ForkJoinTask.invokeAll(new Rec("a", l, null)); return l; });
        for (int failAt = 0; failAt < 3; failAt++) {
            final int fa = failAt;
            show("three fail at " + fa, () -> p.submit(() -> {
                List<String> l = new ArrayList<>();
                Rec[] r = new Rec[3];
                for (int i = 0; i < 3; i++) r[i] = new Rec("t" + i, l, i == fa ? new IllegalStateException("f" + i) : null);
                String ex;
                try { ForkJoinTask.invokeAll(r[0], r[1], r[2]); ex = "none"; } catch (RuntimeException e) { ex = e.toString(); }
                return ex + " ran=" + l + " " + r[0].isCancelled() + r[1].isCancelled() + r[2].isCancelled() + " done " + r[0].isDone() + r[1].isDone() + r[2].isDone();
            }).get());
            if (fa < 2) show("two fail at " + fa, () -> p.submit(() -> {
                List<String> l = new ArrayList<>();
                Rec[] r = new Rec[2];
                for (int i = 0; i < 2; i++) r[i] = new Rec("t" + i, l, i == fa ? new IllegalStateException("f" + i) : null);
                String ex;
                try { ForkJoinTask.invokeAll(r[0], r[1]); ex = "none"; } catch (RuntimeException e) { ex = e.toString(); }
                return ex + " ran=" + l + " " + r[0].isCancelled() + r[1].isCancelled() + " done " + r[0].isDone() + r[1].isDone();
            }).get());
        }
        show("both fail", () -> p.submit(() -> {
            List<String> l = new ArrayList<>();
            Rec a = new Rec("a", l, new IllegalStateException("fa")), b = new Rec("b", l, new IllegalArgumentException("fb"));
            try { ForkJoinTask.invokeAll(a, b); return "none"; } catch (RuntimeException e) { return e + " ran=" + l + " " + b.isCancelled() + b.isDone(); }
        }).get());
        show("null first of two", () -> p.submit(() -> { List<String> l = new ArrayList<>(); Rec b = new Rec("b", l, null);
            try { ForkJoinTask.invokeAll(null, b); return "no"; } catch (NullPointerException e) { return "npe " + l + " " + b.isDone() + " queued=" + ForkJoinTask.getQueuedTaskCount() + " unfork=" + b.tryUnfork(); } }).get());
        show("null second of two", () -> p.submit(() -> { List<String> l = new ArrayList<>(); Rec a = new Rec("a", l, null);
            try { ForkJoinTask.invokeAll(a, null); return "no"; } catch (NullPointerException e) { return "npe " + l + " " + a.isDone() + " queued=" + ForkJoinTask.getQueuedTaskCount(); } }).get());
        show("null in array", () -> p.submit(() -> { List<String> l = new ArrayList<>(); Rec a = new Rec("a", l, null), c = new Rec("c", l, null);
            try { ForkJoinTask.invokeAll(a, null, c); return "no"; } catch (NullPointerException e) { return "npe " + l + " a=" + a.isDone() + a.isCancelled() + " c=" + c.isDone() + c.isCancelled() + " queued=" + ForkJoinTask.getQueuedTaskCount(); } }).get());
        show("null first in array", () -> p.submit(() -> { List<String> l = new ArrayList<>(); Rec b = new Rec("b", l, null), c = new Rec("c", l, null);
            try { ForkJoinTask.invokeAll(null, b, c); return "no"; } catch (NullPointerException e) { return "npe " + l + " b=" + b.isDone() + b.isCancelled() + " c=" + c.isDone() + c.isCancelled(); } }).get());
        show("null in list", () -> p.submit(() -> { List<String> l = new ArrayList<>(); Rec a = new Rec("a", l, null), c = new Rec("c", l, null);
            try { ForkJoinTask.invokeAll(Arrays.asList(a, null, c)); return "no"; } catch (NullPointerException e) { return "npe " + l + " a=" + a.isDone() + " c=" + c.isDone() + c.isCancelled(); } }).get());
        p.shutdown();
    }

    static void statuses() throws Exception {
        Manual m = new Manual(true);
        m.invoke();
        m.complete("after");
        show("complete after done", () -> m.log + " " + m.isCompletedNormally() + " " + m.join());
        Manual n = new Manual(true);
        n.invoke();
        n.completeExceptionally(new IllegalStateException("late"));
        show("completeExceptionally after done", () -> n.isCompletedNormally() + " " + n.getException());
        Manual c = new Manual(true);
        c.cancel(true);
        c.complete("x");
        show("complete after cancel", () -> c.log + " " + c.isCancelled());
        Manual r = new Manual(true);
        r.setForkJoinTaskTag((short) 5);
        r.invoke();
        r.reinitialize();
        show("reinit tag", () -> r.getForkJoinTaskTag() + " done=" + r.isDone());
        Manual f = new Manual(false);
        f.setForkJoinTaskTag((short) 7);
        f.completeExceptionally(new IllegalStateException("e"));
        show("tag after fail", f::getForkJoinTaskTag);
        f.reinitialize();
        show("reinit after fail", () -> f.getForkJoinTaskTag() + " " + f.isDone() + " " + f.getException());
        Manual k = new Manual(false);
        k.cancel(false);
        k.reinitialize();
        show("reinit after cancel", () -> k.isDone() + " " + k.isCancelled() + " " + k.getForkJoinTaskTag());
        // sneaky checked exception out of exec
        ForkJoinTask<String> sn = new RecursiveTask<String>() { @Override protected String compute() { ForkJoinEdges.<RuntimeException>sneak(new java.io.IOException("io")); return null; } };
        show("sneaky invoke", sn::invoke);
        show("sneaky ex", sn::getException);
        show("sneaky get", sn::get);
        ForkJoinTask<String> err = new RecursiveTask<String>() { @Override protected String compute() { throw new AssertionError("a"); } };
        show("error invoke", err::invoke);
        show("error ex", err::getException);
        ForkJoinTask<?> runSneak = ForkJoinTask.adapt((Runnable) () -> ForkJoinEdges.<RuntimeException>sneak(new java.io.IOException("io")));
        show("adapted runnable sneaky", runSneak::invoke);
        ForkJoinTask<?> ce = ForkJoinTask.adapt(() -> { throw new CancellationException("mine"); });
        show("task throws CancellationException", ce::invoke);
        show("task throws CE flags", () -> ce.isCancelled() + " " + ce.isCompletedAbnormally() + " " + ce.getException());
        show("task throws CE get", ce::get);
        Manual ex = new Manual(false);
        ex.completeExceptionally(new CancellationException("given"));
        show("complete with CE", () -> ex.isCancelled() + " " + ex.getException());
        show("complete with CE join", ex::join);
        show("complete with error", () -> { Manual e = new Manual(false); e.completeExceptionally(new AssertionError("x")); return e.getException(); });
        show("complete with error join", () -> { Manual e = new Manual(false); e.completeExceptionally(new AssertionError("x")); return e.join(); });
        // Getters on pending
        Manual pend = new Manual(false);
        show("raw pending", pend::getRawResult);
        show("get timed null unit", () -> pend.get(1, null));
        Thread.currentThread().interrupt();
        show("get timed null unit flagged", () -> pend.get(1, null));
        show("flag after npe", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("get timed 0 flagged", () -> pend.get(0, TimeUnit.SECONDS));
        show("flag after", Thread::interrupted);
        show("get timed neg", () -> pend.get(-1, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        Manual cancelled = new Manual(false); cancelled.cancel(false);
        show("get cancelled flagged", cancelled::get);
        show("flag after cancel get", Thread::interrupted);
        Thread.currentThread().interrupt();
        Manual done = new Manual(true); done.invoke();
        show("quietlyJoin flagged", () -> { done.quietlyJoin(); return Thread.interrupted(); });
        // Pending join outside a pool, released by another thread, with an interrupt in between.
        Manual pj = new Manual(false);
        Thread main = Thread.currentThread();
        Thread helper = new Thread(() -> { try { until(() -> main.getState() == Thread.State.WAITING); main.interrupt(); Thread.sleep(20); pj.complete("late"); } catch (Exception e) { } });
        helper.start();
        show("join keeps waiting past interrupt", pj::join);
        show("join flag set after", Thread::interrupted);
        // exec false in invoke outside
        Manual pi = new Manual(false);
        Thread h2 = new Thread(() -> { try { until(() -> main.getState() == Thread.State.WAITING); pi.complete("done"); } catch (Exception e) { } });
        h2.start();
        show("invoke exec false waits", pi::invoke);
        show("invoke exec false log", () -> pi.log);
        show("adapter toString shape", () -> !ForkJoinTask.adapt(() -> "x").toString().contains("["));
        show("adapter is runnable", () -> (ForkJoinTask.adapt(() -> "x") instanceof Runnable) + " " + (ForkJoinTask.adapt(() -> "x") instanceof RunnableFuture));
        show("adapter run", () -> { ForkJoinTask<String> t = ForkJoinTask.adapt(() -> "x"); ((Runnable) t).run(); return t.join(); });
        show("adapter run failing", () -> { ForkJoinTask<String> t = ForkJoinTask.adapt(() -> { throw new IllegalStateException("q"); }); ((Runnable) t).run(); return t.isDone(); });
        show("serializable", () -> ForkJoinTask.adapt(() -> "x") instanceof java.io.Serializable);
    }

    static void workers() throws Exception {
        ForkJoinPool p = one();
        show("thread facts", () -> p.submit(() -> { Thread t = Thread.currentThread(); return t.getThreadGroup().getName() + " " + t.getPriority() + " " + t.isDaemon() + " handler=" + (t.getUncaughtExceptionHandler() != null); }).get());
        show("flag between tasks", () -> { p.submit(() -> Thread.currentThread().interrupt()).get(); return p.submit(() -> Thread.currentThread().isInterrupted()).get(); });
        show("get flagged in worker", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x").fork();
            Thread.currentThread().interrupt();
            try { return f.get(); } catch (InterruptedException e) { return "ie queued=" + ForkJoinTask.getQueuedTaskCount() + " flag=" + Thread.interrupted() + " unfork=" + f.tryUnfork(); }
        }).get());
        show("get in worker helps", () -> p.submit(() -> ForkJoinTask.adapt(() -> "x").fork().get()).get());
        show("timed get in worker helps", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x").fork();
            try { return f.get(20, TimeUnit.SECONDS); } catch (TimeoutException e) { return "timeout queued=" + ForkJoinTask.getQueuedTaskCount() + " unfork=" + f.tryUnfork(); }
        }).get());
        show("join in worker runs other local", () -> p.submit(() -> {
            List<String> l = Collections.synchronizedList(new ArrayList<>());
            ForkJoinTask<?> a = new Rec("a", l, null).fork(), b = new Rec("b", l, null).fork(), c = new Rec("c", l, null).fork();
            a.join();
            String s = l.toString();
            ForkJoinTask.helpQuiesce();
            return s + " then " + l;
        }).get());
        show("submit from worker queued", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.getPool().submit(() -> "inner");
            String s = "local=" + ForkJoinTask.getQueuedTaskCount() + " subs=" + ForkJoinTask.getPool().getQueuedSubmissionCount();
            return s + " " + f.join();
        }).get());
        show("execute from worker queued", () -> p.submit(() -> {
            boolean[] ran = new boolean[1];
            ForkJoinTask.getPool().execute(() -> ran[0] = true);
            String s = "local=" + ForkJoinTask.getQueuedTaskCount() + " subs=" + ForkJoinTask.getPool().getQueuedSubmissionCount();
            ForkJoinTask.helpQuiesce();
            return s + " ran=" + ran[0];
        }).get());
        show("invoke from worker", () -> p.submit(() -> ForkJoinTask.getPool().invoke(ForkJoinTask.adapt(() -> "x"))).get());
        ForkJoinPool other = one();
        show("other pool's fork from worker", () -> p.submit(() -> {
            ForkJoinTask<String> f = other.submit(() -> Thread.currentThread().getName().contains("worker") + " same=" + (ForkJoinTask.getPool() == other));
            return f.get();
        }).get());
        other.shutdown();
        show("poolTask order", () -> p.submit(() -> {
            ForkJoinTask<?> a = ForkJoinTask.adapt(() -> "a").fork();
            ForkJoinTask<?> b = ForkJoinTask.adapt(() -> "b").fork();
            boolean first = Peek.any() == b, second = Peek.any() == a;
            return first + " " + second + " " + Peek.any();
        }).get());
        show("tryUnfork of older", () -> p.submit(() -> {
            ForkJoinTask<?> a = ForkJoinTask.adapt(() -> "a").fork();
            ForkJoinTask<?> b = ForkJoinTask.adapt(() -> "b").fork();
            boolean r = a.tryUnfork();
            Peek.any(); Peek.any();
            return r;
        }).get());
        show("fork twice", () -> p.submit(() -> {
            int[] c = new int[1];
            ForkJoinTask<?> a = ForkJoinTask.adapt(() -> c[0]++);
            a.fork(); a.fork();
            int q = ForkJoinTask.getQueuedTaskCount();
            a.join();
            ForkJoinTask.helpQuiesce();
            return q + " runs=" + c[0];
        }).get());
        p.shutdown();
        show("await after", () -> p.awaitTermination(20, TimeUnit.SECONDS));
        show("after shutdown toString", () -> p.toString().replaceAll("steals = [0-9]+", "steals = S"));
    }

    static void lifecycle() throws Exception {
        ForkJoinPool p = new ForkJoinPool(2);
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        p.execute(() -> { e.countDown(); ForkJoinEdges.await(g); });
        e.await();
        ForkJoinTask<String> q = p.submit(() -> "queued");
        q.join();
        p.shutdown();
        show("word while busy", () -> p.toString().replaceAll(", parallelism.*", ""));
        show("flags while busy", () -> p.isShutdown() + " " + p.isTerminating() + " " + p.isTerminated());
        show("await while busy", () -> p.awaitTermination(10, TimeUnit.MILLISECONDS));
        g.countDown();
        show("end", () -> p.awaitTermination(20, TimeUnit.SECONDS));

        ForkJoinPool n = new ForkJoinPool(1);
        CountDownLatch e2 = new CountDownLatch(1);
        boolean[] seen = new boolean[1];
        ForkJoinTask<?> running = n.submit(() -> { e2.countDown(); try { new CountDownLatch(1).await(); } catch (InterruptedException x) { seen[0] = true; } });
        e2.await();
        ForkJoinTask<String> queued = n.submit(() -> "never");
        show("shutdownNow list", n::shutdownNow);
        show("queued cancelled", () -> queued.isCancelled() + " " + queued.isDone());
        show("get queued", queued::get);
        show("end now", () -> n.awaitTermination(20, TimeUnit.SECONDS));
        show("running saw", () -> seen[0] + " " + running.isCancelled() + " " + running.isDone());
        show("flags now", () -> n.isShutdown() + " " + n.isTerminating() + " " + n.isTerminated());
        show("shutdownNow again", n::shutdownNow);

        ForkJoinPool idle = new ForkJoinPool(1);
        idle.submit(() -> "x").get();
        until(idle::isQuiescent);
        show("idle quiescent", () -> idle.getActiveThreadCount() + " " + idle.getRunningThreadCount() + " " + idle.getPoolSize());
        idle.shutdown();
        show("idle shutdown end", () -> idle.awaitTermination(20, TimeUnit.SECONDS));

        ForkJoinPool nf = new ForkJoinPool(1, pool -> null, null, false);
        nf.execute(() -> { });
        show("null factory queued", () -> nf.getQueuedSubmissionCount() + " quiescent=" + nf.isQuiescent());
        nf.shutdown();
        show("null factory shutdown", () -> nf.isTerminated() + " " + nf.isTerminating() + " " + nf.isShutdown());
        nf.shutdownNow();
        show("null factory stop", nf::isTerminated);

        ForkJoinPool tf = new ForkJoinPool(1, pool -> { throw new IllegalStateException("f"); }, null, false);
        show("throwing factory submit", () -> tf.submit(() -> "x"));
        show("throwing factory queue", tf::getQueuedSubmissionCount);
        show("throwing factory flags", tf::getPoolSize);
        tf.shutdownNow();

        show("ctor order", () -> new ForkJoinPool(0, null, null, false));
        show("worker null pool", () -> new ForkJoinWorkerThread(null) { });
        show("await null", () -> new ForkJoinPool(1).awaitTermination(1, null));
        show("quiesce null", () -> new ForkJoinPool(1).awaitQuiescence(1, null));
        ForkJoinPool f = new ForkJoinPool(1);
        Thread.currentThread().interrupt();
        show("await flagged", () -> f.awaitTermination(1, TimeUnit.MILLISECONDS));
        show("cleared", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("awaitQuiescence flagged", () -> f.awaitQuiescence(1, TimeUnit.MILLISECONDS));
        show("kept", Thread::interrupted);
        f.shutdown();
        Thread.currentThread().interrupt();
        show("await flagged terminated", () -> f.awaitTermination(1, TimeUnit.MILLISECONDS));
        show("cleared 2", Thread::interrupted);
        show("list type", () -> f.shutdownNow().size());
        // A runnable that is also a task goes in as the task.
        class Both extends RecursiveAction implements Runnable {
            String seen = "";
            @Override protected void compute() { seen += "compute"; }
            @Override public void run() { seen += "run"; }
        }
        ForkJoinPool bp = one();
        Both bo = new Both();
        bp.execute((Runnable) bo);
        bo.join();
        show("execute runnable task", () -> bo.seen);
        Both bo2 = new Both();
        bp.submit((Runnable) bo2).join();
        show("submit runnable task", () -> bo2.seen);
        show("submit runnable same", () -> { Both x = new Both(); return bp.submit((Runnable) x) == x; });
        show("invokeAll empty", () -> bp.invokeAll(new ArrayList<Callable<String>>()));
        show("invokeAll null element", () -> bp.invokeAll(Arrays.asList(() -> "a", null)));
        show("invokeAll failing", () -> { Future<String> x = bp.invokeAll(Arrays.<Callable<String>>asList(() -> { throw new java.io.IOException("io"); })).get(0); return x.isDone() + " " + x.getClass().getSuperclass().getSimpleName(); });
        show("invokeAll failing get class", () -> { Future<String> x = bp.invokeAll(Arrays.<Callable<String>>asList(() -> { throw new java.io.IOException("io"); })).get(0); try { return x.get(); } catch (ExecutionException ee) { return ee.getCause().getClass().getName(); } });
        show("invokeAny failing", () -> { try { return bp.invokeAny(Arrays.<Callable<String>>asList(() -> { throw new java.io.IOException("io"); })); } catch (ExecutionException ee) { return ee.getCause().getClass().getName(); } });
        show("newTaskFor class", () -> new ForkJoinPool(1) { boolean q() { return newTaskFor(() -> "x") instanceof ForkJoinTask; } }.q());
        show("newTaskFor runnable class", () -> new ForkJoinPool(1) { boolean q() { return newTaskFor(() -> { }, "x") instanceof ForkJoinTask; } }.q());
        show("newTaskFor null", () -> new ForkJoinPool(1) { Object q() { return newTaskFor((Callable<String>) null); } }.q());
        bp.shutdown();
    }

    static class Hooked extends ForkJoinWorkerThread {
        static final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final boolean bad;
        Hooked(ForkJoinPool p, boolean bad) { super(p); this.bad = bad; }
        @Override protected void onStart() { log.add("onStart"); if (bad) throw new IllegalStateException("start"); }
        @Override protected void onTermination(Throwable e) { log.add("onTermination " + e); }
    }

    static void failures() throws Exception {
        List<String> handled = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        ForkJoinPool p = new ForkJoinPool(1, pool -> { Hooked h = new Hooked(pool, false); threads.add(h); return h; }, (t, e) -> handled.add(t.getClass().getSimpleName() + " " + e), false);
        p.execute(() -> { throw new IllegalStateException("escaped"); });
        until(() -> !handled.isEmpty());
        show("handled", () -> handled);
        p.submit(() -> "after").get();
        show("hook log after failure", () -> Hooked.log);
        show("threads made", threads::size);
        Hooked.log.clear();
        p.submit(() -> { throw new IllegalStateException("captured"); });
        p.execute(ForkJoinTask.adapt((Runnable) () -> { throw new IllegalStateException("task form"); }));
        p.submit(() -> "barrier").get();
        show("submit failure no handler", () -> handled.size());
        show("execute(task) failure no handler", () -> threads.size());
        p.shutdown();
        p.awaitTermination(20, TimeUnit.SECONDS);
        // Failure from onStart.
        Hooked.log.clear();
        List<String> h2 = Collections.synchronizedList(new ArrayList<>());
        ForkJoinPool bad = new ForkJoinPool(1, pool -> new Hooked(pool, true), (t, e) -> h2.add("" + e), false);
        bad.execute(() -> { });
        until(() -> h2.size() >= 1);
        show("onStart throws", () -> h2.get(0));
        show("onStart throws log", () -> Hooked.log.get(0) + " " + Hooked.log.get(1));
        bad.shutdownNow();
    }

    /** A worker's leftover flag, and what shutdownNow does to a worker blocked in a join. */
    static void stops() throws Exception {
        ForkJoinPool one = new ForkJoinPool(1);
        CountDownLatch g0 = new CountDownLatch(1), e0 = new CountDownLatch(1);
        one.execute(() -> { e0.countDown(); P4Await(g0); Thread.currentThread().interrupt(); });
        e0.await();
        ForkJoinTask<Boolean> next = one.submit(() -> Thread.currentThread().isInterrupted());
        g0.countDown();
        show("interrupt carried to a queued next task", next::get);
        show("no interrupt once idle", () -> {
            one.submit(() -> Thread.currentThread().interrupt()).get();
            until(one::isQuiescent);
            return one.submit(() -> Thread.currentThread().isInterrupted()).get();
        });
        one.shutdown();
        ForkJoinPool p = new ForkJoinPool(1);
        ForkJoinTask<String> open = new Manual(false);
        Thread[] w = new Thread[1];
        ForkJoinTask<String> joiner = p.submit(() -> { w[0] = Thread.currentThread(); return open.join(); });
        until(() -> w[0] != null && w[0].getState() == Thread.State.WAITING);
        p.shutdownNow();
        show("stop with blocked join", () -> p.awaitTermination(20, TimeUnit.SECONDS));
        show("joiner and awaited cancelled", () -> joiner.isCancelled() + " " + open.isCancelled());
        ForkJoinPool q = new ForkJoinPool(1);
        CountDownLatch in = new CountDownLatch(1);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        ForkJoinTask<?> forker = q.submit(() -> {
            in.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { seen.add("interrupted"); }
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "late").fork();
            seen.add("forked cancelled=" + f.isCancelled());
            try { seen.add("joined " + f.join()); } catch (CancellationException e) { seen.add("join cancelled"); }
        });
        in.await();
        q.shutdownNow();
        show("after stop", () -> q.awaitTermination(20, TimeUnit.SECONDS));
        until(() -> seen.size() == 3);
        show("fork after stop", () -> seen);
        ForkJoinPool r = new ForkJoinPool(1);
        CountDownLatch g = new CountDownLatch(1), e2 = new CountDownLatch(1);
        ForkJoinTask<String> inside = r.submit(() -> {
            e2.countDown();
            g.await();
            String s = "fork=" + ForkJoinTask.adapt(() -> "f").fork().join();
            try { s += " submit=" + ForkJoinTask.getPool().submit(() -> "s").join(); } catch (RejectedExecutionException x) { s += " submit rejected"; }
            return s;
        });
        e2.await();
        r.shutdown();
        g.countDown();
        show("inside after shutdown", inside::get);
        show("after shutdown", () -> r.awaitTermination(20, TimeUnit.SECONDS));
    }

    static void P4Await(CountDownLatch l) { await(l); }

    static void outside() throws Exception {
        show("outside helpQuiesce", () -> { ForkJoinTask.helpQuiesce(); return "ok"; });
        show("outside tryUnfork never forked", () -> ForkJoinTask.adapt(() -> "x").tryUnfork());
        show("outside surplus", ForkJoinTask::getSurplusQueuedTaskCount);
        show("outside peek", Peek::peek);
        show("outside poll", Peek::poll);
        show("common execute task", () -> { ForkJoinTask<String> t = ForkJoinTask.adapt(() -> "x"); ForkJoinPool.commonPool().execute(t); return t.join(); });
        show("fork outside", () -> { ForkJoinTask<String> t = ForkJoinTask.adapt(() -> Thread.currentThread() instanceof ForkJoinWorkerThread ? "w" : "w"); return t.fork().join(); });
        show("quiesce common", () -> ForkJoinPool.commonPool().awaitQuiescence(20, TimeUnit.SECONDS));
    }

    public static void main(String[] args) throws Exception {
        invokeAlls();
        statuses();
        workers();
        lifecycle();
        failures();
        stops();
        outside();
        System.exit(0);
    }
}
