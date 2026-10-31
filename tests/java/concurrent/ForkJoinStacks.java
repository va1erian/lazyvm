package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Recursion on the caller's own stack, workers that come in odd ways, timed gets and helping from
 * outside. No line overflows a stack: the overflows are in {@code ForkJoinRunaway}. Every call that
 * could wait for good runs under a limit, and a call that passes it ends the run with a line that
 * tools/golden.py refuses, so a bless can never accept a hang as an outcome.
 */
public class ForkJoinStacks {
    interface Body { Object run() throws Throwable; }
    static final long LIMIT_MILLIS = 40_000;

    static void hung(String what) {
        System.out.println("--- hung: " + what);
        System.out.flush();
        Runtime.getRuntime().halt(3);
    }
    static Object bounded(String what, Body body) throws Throwable {
        Object[] out = new Object[1];
        Throwable[] thrown = new Throwable[1];
        Thread t = new Thread(() -> {
            try { out[0] = body.run(); } catch (Throwable e) { thrown[0] = e; }
        });
        t.setDaemon(true);
        t.start();
        t.join(LIMIT_MILLIS);
        if (t.isAlive()) hung(what);
        if (thrown[0] != null) throw thrown[0];
        return out[0];
    }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + bounded(label, body)); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }
    /** A step that prints nothing and must neither hang nor throw. */
    static void step(String what, Body body) throws Exception {
        try { bounded(what, body); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new AssertionError(t); }
    }
    static void awaitQuietly(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    static String outcome(String what, Future<?> f) throws InterruptedException {
        try { return String.valueOf(f.get(LIMIT_MILLIS / 2, TimeUnit.MILLISECONDS)); }
        catch (ExecutionException e) { return e.getCause().getClass().getName(); }
        catch (TimeoutException e) { hung(what); return null; }
    }
    static boolean ended(String what, ForkJoinPool p) throws InterruptedException {
        if (!p.awaitTermination(LIMIT_MILLIS / 2, TimeUnit.MILLISECONDS)) hung(what);
        return true;
    }

    static class Plain extends ForkJoinWorkerThread { Plain(ForkJoinPool p) { super(p); } }

    static class Chain extends RecursiveTask<Integer> {
        final int d;
        Chain(int d) { this.d = d; }
        @Override protected Integer compute() {
            if (d == 0) return 0;
            Chain c = new Chain(d - 1);
            c.fork();
            return c.join() + 1;
        }
    }
    static class Invoked extends RecursiveTask<Integer> {
        final int d;
        Invoked(int d) { this.d = d; }
        @Override protected Integer compute() { return d == 0 ? 0 : new Invoked(d - 1).invoke() + 1; }
    }
    static class Both extends RecursiveTask<Integer> {
        final int d;
        Both(int d) { this.d = d; }
        @Override protected Integer compute() {
            if (d == 0) return 0;
            Both l = new Both(d - 1), r = new Both(0);
            invokeAll(l, r);
            return l.join() + 1;
        }
    }
    /** Recursion through invoke on a thread outside any pool stays on that thread. */
    static void outside() throws Exception {
        for (int d : new int[] {800, 1400}) {
            show("invoke chain " + d + " outside a pool", () -> new Invoked(d).invoke());
            show("invokeAll chain " + d + " outside a pool", () -> new Both(d).invoke());
            ForkJoinPool x = new ForkJoinPool(1);
            show("invoke chain " + d + " on another pool's worker", () -> outcome("invoke chain", x.submit(new Invoked(d))));
            step("end of that pool", () -> { x.shutdown(); return null; });
        }
    }

    static int down(int n, Runnable r) { if (n == 0) { r.run(); return 0; } return down(n - 1, r) + 1; }

    static class Act extends RecursiveAction {
        final Runnable r;
        Act(Runnable r) { this.r = r; }
        @Override protected void compute() { r.run(); }
    }
    static final int DEEP = 2100;
    static class InitInvokes {
        static int v;
        static { down(DEEP, () -> new Act(() -> Later.touch()).invoke()); v++; }
    }
    static class Later { static void touch() { InitInvokes.v++; } }

    /** Far down a stack a task still runs on the caller's thread, so it has all the caller has. */
    static void deepCaller() throws Exception {
        Object m = new Object();
        Object[] seen = new Object[2];
        show("deep invoke, monitor held", () -> { synchronized (m) { down(DEEP, () -> new Act(() -> seen[0] = Thread.holdsLock(m)).invoke()); } return seen[0]; });
        ReentrantLock lock = new ReentrantLock();
        show("deep invoke, lock held", () -> { lock.lock(); try { down(DEEP, () -> new Act(() -> { lock.lock(); seen[0] = lock.getHoldCount(); lock.unlock(); }).invoke()); } finally { lock.unlock(); } return seen[0]; });
        ThreadLocal<String> local = new ThreadLocal<>();
        show("deep invoke, thread local", () -> { local.set("mine"); down(DEEP, () -> new Act(() -> seen[0] = local.get()).invoke()); return seen[0]; });
        show("deep invoke, who runs it", () -> { Thread me = Thread.currentThread(); down(DEEP, () -> new Act(() -> seen[0] = (Thread.currentThread() == me) + " " + ForkJoinTask.inForkJoinPool() + " " + ForkJoinTask.getPool()).invoke()); return seen[0]; });
        show("deep invoke, interrupt flag", () -> { Thread.currentThread().interrupt(); down(DEEP, () -> new Act(() -> seen[0] = Thread.currentThread().isInterrupted()).invoke()); return seen[0] + " " + Thread.interrupted(); });
        RuntimeException mine = new IllegalStateException("mine");
        show("deep invoke, same failure", () -> { down(DEEP, () -> { try { new Act(() -> { throw mine; }).invoke(); } catch (RuntimeException e) { seen[0] = e == mine; } }); return seen[0]; });
        show("deep invoke in a class's initializer", () -> InitInvokes.v);
        show("deep invoke with the common pool blocked", () -> {
            int p = ForkJoinPool.getCommonPoolParallelism();
            CountDownLatch rel = new CountDownLatch(1);
            for (int i = 0; i < p; i++) ForkJoinPool.commonPool().execute(() -> awaitQuietly(rel));
            down(DEEP, () -> new Act(() -> seen[0] = "ran").invoke());
            rel.countDown();
            return seen[0];
        });
        ForkJoinPool one = new ForkJoinPool(1);
        show("deep fork and join on a worker, monitor held", () -> outcome("deep fork", one.submit(() -> { synchronized (m) { down(DEEP, () -> { Act c = new Act(() -> seen[1] = Thread.holdsLock(m)); c.fork(); c.join(); }); } return seen[1]; })));
        ForkJoinPool two = new ForkJoinPool(2);
        show("deep invoke on a worker, monitor held", () -> outcome("deep invoke", two.submit(() -> { synchronized (m) { down(DEEP, () -> new Act(() -> seen[1] = Thread.holdsLock(m)).invoke()); } return seen[1]; })));
        step("end of those pools", () -> { one.shutdown(); two.shutdown(); return null; });
        ForkJoinTask<String> never = new ForkJoinTask<String>() {
            @Override public String getRawResult() { return null; }
            @Override protected void setRawResult(String v) { }
            @Override protected boolean exec() { return false; }
        };
        show("deep timed get keeps its limit", () -> {
            long t0 = System.nanoTime();
            down(DEEP, () -> { try { never.get(200, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { seen[0] = "timeout"; } catch (Exception e) { seen[0] = e; } });
            return seen[0] + " " + (System.nanoTime() - t0 >= 150_000_000L);
        });
        deepWithNoHelp();
        siblings();
    }

    /** The task runs on the joiner's stack even when the next worker cannot come until it has run. */
    static void deepWithNoHelp() throws Exception {
        AtomicInteger made = new AtomicInteger();
        CountDownLatch rel = new CountDownLatch(1);
        ForkJoinPool x = new ForkJoinPool(1, pool -> new Plain(pool) {
            @Override protected void onStart() { if (made.getAndIncrement() == 1) awaitQuietly(rel); }
        }, null, false);
        show("deep join, next worker stuck in onStart", () -> outcome("stuck in onStart", x.submit(() -> down(DEEP, () -> { Act c = new Act(rel::countDown); c.fork(); c.join(); }))));
        rel.countDown();
        step("end of that pool", () -> { x.shutdownNow(); return null; });
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch rel2 = new CountDownLatch(1);
        ForkJoinPool y = new ForkJoinPool(1, pool -> {
            if (asked.getAndIncrement() == 1) awaitQuietly(rel2);
            return new Plain(pool);
        }, null, false);
        show("deep join, factory stuck", () -> outcome("factory stuck", y.submit(() -> down(DEEP, () -> { Act c = new Act(rel2::countDown); c.fork(); c.join(); }))));
        rel2.countDown();
        step("end of that pool", () -> { y.shutdownNow(); return null; });
    }

    static class WithSibling extends RecursiveTask<Integer> {
        final int d;
        WithSibling(int d) { this.d = d; }
        @Override protected Integer compute() {
            if (d == 0) return 0;
            ForkJoinTask<Integer> older = ForkJoinTask.adapt(() -> 1);
            older.fork();
            WithSibling c = new WithSibling(d - 1);
            c.fork();
            int v = c.join() + 1;
            older.join();
            return v;
        }
    }
    static class Span extends RecursiveTask<Integer> {
        final int k, d;
        Span(int k, int d) { this.k = k; this.d = d; }
        @Override protected Integer compute() {
            List<Chain> l = new ArrayList<>();
            for (int i = 0; i < k; i++) l.add((Chain) new Chain(d).fork());
            int s = 0;
            for (Chain c : l) s += c.join();
            return s;
        }
    }

    /** Depth takes nothing from what other tasks and chains are doing. */
    static void siblings() throws Exception {
        for (int par : new int[] {1, 2, 4}) {
            ForkJoinPool x = new ForkJoinPool(par);
            show("chain 1000 with older siblings at " + par, () -> outcome("siblings", x.submit(new WithSibling(1000))));
            step("end of that pool", () -> { x.shutdownNow(); return null; });
        }
        ForkJoinPool x = new ForkJoinPool(16);
        show("16 chains of 1400", () -> outcome("16 chains", x.submit(new Span(16, 1400))));
        step("end of that pool", () -> { x.shutdownNow(); return null; });
    }

    static void deepQuiesce() throws Exception {
        AtomicInteger made = new AtomicInteger();
        ForkJoinPool x = new ForkJoinPool(2, pool -> {
            if (made.incrementAndGet() > 1) throw new IllegalStateException("no worker");
            return new Plain(pool);
        }, null, false);
        ForkJoinTask<String> t = x.submit(() -> {
            String[] said = new String[1];
            RecursiveAction child = new RecursiveAction() { @Override protected void compute() { } };
            down(DEEP, () -> {
                try { child.fork(); } catch (IllegalStateException e) { said[0] = "fork threw"; }
                ForkJoinTask.helpQuiesce();
                said[0] += ", helpQuiesce returned, child done=" + child.isDone();
            });
            return said[0];
        });
        show("helpQuiesce deep down, factory throws", () -> outcome("deep helpQuiesce", t));
        show("nothing left counted", () -> x.getRunningThreadCount() + " " + x.getQueuedTaskCount());
        step("end of that pool", () -> { x.shutdownNow(); return null; });
    }

    static void oddWorkers() throws Exception {
        // A fork in onStart: on the oracle it may never run, but it must not keep the next submission waiting.
        CountDownLatch rel = new CountDownLatch(1);
        AtomicInteger first = new AtomicInteger();
        ForkJoinPool x = new ForkJoinPool(2, pool -> new ForkJoinWorkerThread(pool) {
            @Override protected void onStart() {
                if (first.getAndIncrement() == 0) new RecursiveAction() { @Override protected void compute() { awaitQuietly(rel); } }.fork();
            }
        }, null, false);
        x.execute(() -> { });
        Thread.sleep(200);
        show("submission after a fork in onStart", () -> outcome("submission", x.submit(rel::countDown)));
        rel.countDown();
        step("end of that pool", () -> { x.shutdownNow(); return null; });
        // A worker built for another pool belongs to that pool, one in use: see the user's worker below.
        ForkJoinPool q = new ForkJoinPool(3);
        step("first task", () -> q.submit(() -> "first").get());
        ForkJoinPool y = new ForkJoinPool(2, ignored -> new Plain(q), null, false);
        y.submit(() -> { });
        show("task on the worker's own pool", () -> outcome("own pool", q.submit(() -> "ran")));
        q.shutdown();
        show("worker's own pool ends", () -> ended("own pool ends", q));
        step("end of the other pool", () -> { y.shutdownNow(); return null; });
        // A worker the user made and started, on a pool in use: on a new pool the oracle can hang.
        ForkJoinPool z = new ForkJoinPool(2);
        step("first task", () -> z.submit(() -> "first").get());
        Plain mine = new Plain(z);
        mine.start();
        Thread.sleep(200);
        show("task beside a user's worker", () -> outcome("beside", z.submit(() -> "ran")));
        z.shutdown();
        show("pool of a user's worker ends", () -> ended("user's worker ends", z));
        show("user's worker counted right", () -> z.getActiveThreadCount() + " " + z.getRunningThreadCount());
    }

    static void timedGets() throws Exception {
        for (int par : new int[] {1, 2}) {
            ForkJoinPool x = new ForkJoinPool(par);
            show("timed get in worker outlasts its limit at " + par, () -> x.submit(() -> {
                Thread me = Thread.currentThread();
                boolean[] same = new boolean[1];
                RecursiveAction t = new RecursiveAction() { @Override protected void compute() {
                    same[0] = Thread.currentThread() == me;
                    try { Thread.sleep(300); } catch (InterruptedException e) { }
                } };
                t.fork();
                t.get(20, TimeUnit.MILLISECONDS);
                return "here=" + same[0] + " done=" + t.isDone();
            }).get());
            step("end of that pool", () -> { x.shutdown(); return null; });
        }
        ForkJoinPool y = new ForkJoinPool(1);
        for (long to : new long[] {0, -1, Long.MIN_VALUE}) {
            show("timed get in worker with no time " + to, () -> y.submit(() -> {
                ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x").fork();
                try { return f.get(to, TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { return "timeout queued=" + ForkJoinTask.getQueuedTaskCount() + " unfork=" + f.tryUnfork(); }
            }).get());
        }
        show("timed get in a tree of tasks", () -> y.invoke(new Fib(16)) + " workers=" + y.getPoolSize());
        step("end of that pool", () -> { y.shutdown(); return null; });
        ForkJoinPool z = new ForkJoinPool(1);
        CountDownLatch rel = new CountDownLatch(1), in = new CountDownLatch(1);
        z.execute(() -> { in.countDown(); awaitQuietly(rel); });
        step("worker in", () -> { in.await(); return null; });
        ForkJoinTask<String> queued = z.submit(() -> "x");
        show("timed get outside with the lowest timeout", () -> { try { return queued.get(Long.MIN_VALUE, TimeUnit.DAYS); } catch (TimeoutException e) { return "timeout"; } });
        show("awaitTermination with the lowest timeout", () -> z.awaitTermination(Long.MIN_VALUE, TimeUnit.DAYS));
        rel.countDown();
        step("end of that pool", () -> { z.shutdown(); return null; });
    }
    static class Fib extends RecursiveTask<Integer> {
        final int n;
        Fib(int n) { this.n = n; }
        @Override protected Integer compute() {
            if (n < 2) return n;
            Fib a = new Fib(n - 1);
            a.fork();
            int b = new Fib(n - 2).compute();
            try { return b + a.get(1, TimeUnit.MINUTES); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    /** An outside thread waiting for quiet runs what is queued. */
    static void assist() throws Exception {
        String[] on = new String[1];
        ForkJoinPool n = new ForkJoinPool(1, pool -> null, null, false);
        n.execute(() -> on[0] = "ran");
        show("no worker ever: quiet at once", () -> n.awaitQuiescence(200, TimeUnit.MILLISECONDS) + " ran=" + on[0] + " queued=" + n.getQueuedSubmissionCount());
        step("end of that pool", () -> { n.shutdownNow(); return null; });
        Thread.UncaughtExceptionHandler quiet = (t, e) -> { };
        for (int who = 0; who < 2; who++) assistBusy(who, quiet);
        // With a worker free the task goes to it, and the wait can end while it runs.
        ForkJoinPool f = new ForkJoinPool(2);
        step("first task", () -> f.submit(() -> "first").get());
        step("quiet again", () -> f.awaitQuiescence(15, TimeUnit.SECONDS));
        CountDownLatch end = new CountDownLatch(1);
        ForkJoinTask<?> slow = f.submit(() -> awaitQuietly(end));
        show("a free worker takes it", () -> f.awaitQuiescence(10, TimeUnit.MILLISECONDS) + " done=" + slow.isDone());
        end.countDown();
        step("end of that pool", () -> { f.shutdown(); return null; });
        // Waiting on the common pool's end also helps.
        int p = ForkJoinPool.getCommonPoolParallelism();
        CountDownLatch in = new CountDownLatch(p), rel = new CountDownLatch(1);
        ForkJoinPool c = ForkJoinPool.commonPool();
        for (int i = 0; i < p; i++) c.execute(() -> { in.countDown(); awaitQuietly(rel); });
        step("common workers in", () -> { in.await(); return null; });
        c.execute(() -> on[0] = Thread.currentThread() instanceof ForkJoinWorkerThread ? "worker" : "caller");
        show("common pool's awaitTermination", () -> c.awaitTermination(100, TimeUnit.MILLISECONDS) + " " + on[0]);
        rel.countDown();
    }

    static void assistBusy(int who, Thread.UncaughtExceptionHandler quiet) throws Exception {
        ForkJoinPool y = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, quiet, false);
        CountDownLatch in = new CountDownLatch(1), rel = new CountDownLatch(1);
        y.execute(() -> { in.countDown(); awaitQuietly(rel); });
        step("worker in", () -> { in.await(); return null; });
        Thread[] ranOn = new Thread[1];
        y.execute(() -> ranOn[0] = Thread.currentThread());
        Callable<String> c = () -> y.awaitQuiescence(100, TimeUnit.MILLISECONDS) + " here=" + (ranOn[0] == Thread.currentThread());
        if (who == 0) {
            show("busy pool, waiting from outside", c::call);
        } else {
            ForkJoinPool z = new ForkJoinPool(1);
            show("busy pool, waiting on another pool's worker", () -> outcome("other worker", z.submit(c)));
            step("end of that pool", () -> { z.shutdown(); return null; });
        }
        y.execute(() -> { throw new IllegalStateException("boom"); });
        show("a failure it runs is thrown at it", () -> y.awaitQuiescence(100, TimeUnit.MILLISECONDS));
        rel.countDown();
        step("end of that pool", () -> { y.shutdown(); return null; });
    }

    static void nested() throws Exception {
        ForkJoinPool x = new ForkJoinPool(1);
        boolean[] notNegative = new boolean[2];
        ForkJoinPool.ManagedBlocker inner = new ForkJoinPool.ManagedBlocker() {
            @Override public boolean block() { notNegative[1] = x.getRunningThreadCount() >= 0; return true; }
            @Override public boolean isReleasable() { return false; }
        };
        ForkJoinPool.ManagedBlocker outer = new ForkJoinPool.ManagedBlocker() {
            @Override public boolean block() throws InterruptedException { notNegative[0] = x.getRunningThreadCount() >= 0; ForkJoinPool.managedBlock(inner); return true; }
            @Override public boolean isReleasable() { return false; }
        };
        show("nested blockers", () -> { x.submit(() -> { ForkJoinPool.managedBlock(outer); return null; }).get(); return Arrays.toString(notNegative); });
        step("end of that pool", () -> { x.shutdown(); return null; });
    }

    public static void main(String[] args) throws Exception {
        oddWorkers();
        timedGets();
        assist();
        nested();
        deepQuiesce();
        outside();
        deepCaller();
        System.exit(0);
    }
}
