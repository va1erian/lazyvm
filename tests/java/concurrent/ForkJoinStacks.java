package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Deep and runaway recursion when workers can or cannot come, and workers that come in odd ways. The
 * oracle sometimes hangs in a pool call after an overflow (a third of the runs), so a bless may take
 * a second go; when it gets through, its output is the same each time.
 */
public class ForkJoinStacks {
    interface Body { Object run() throws Throwable; }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }
    static void awaitQuietly(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    static String outcome(Future<?> f) {
        try { return String.valueOf(f.get(20, TimeUnit.SECONDS)); }
        catch (ExecutionException e) { return e.getCause().getClass().getName(); }
        catch (TimeoutException e) { return "hung"; }
        catch (InterruptedException e) { return "interrupted"; }
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
    static class Runaway extends RecursiveTask<Integer> {
        @Override protected Integer compute() { Runaway c = new Runaway(); c.fork(); return c.join() + 1; }
    }
    static class RunawayInvoke extends RecursiveTask<Integer> {
        @Override protected Integer compute() { return new RunawayInvoke().invoke() + 1; }
    }

    /** A factory that makes one worker and then fails in the given way. */
    static ForkJoinPool oneWorkerThen(String way) {
        AtomicInteger made = new AtomicInteger();
        return new ForkJoinPool(1, pool -> {
            if (made.getAndIncrement() == 0) return new Plain(pool);
            switch (way) {
                case "null": return null;
                case "throw": throw new IllegalStateException("no worker");
                default: return new Plain(pool) { @Override public synchronized void start() { throw new OutOfMemoryError("no thread"); } };
            }
        }, null, false);
    }

    /** With no other worker to be had, a chain goes on where it is. */
    static void noOtherWorker() {
        for (String way : new String[] {"null", "throw", "start"}) {
            ForkJoinPool x = oneWorkerThen(way);
            show("chain 600, factory " + way, () -> outcome(x.submit(new Chain(600))));
            x.shutdownNow();
            ForkJoinPool y = oneWorkerThen(way);
            show("invoke chain 600, factory " + way, () -> outcome(y.submit(new Invoked(600))));
            y.shutdownNow();
        }
    }

    static void runaway() throws Exception {
        for (int par : new int[] {1, 2, 4}) {
            ForkJoinPool x = new ForkJoinPool(par);
            show("runaway forks at " + par, () -> outcome(x.submit(new Runaway())));
            show("few workers for it at " + par, () -> x.getPoolSize() <= 20);
            x.shutdown();
            show("pool ends at " + par, () -> x.awaitTermination(20, TimeUnit.SECONDS));
        }
        ForkJoinPool y = new ForkJoinPool(2);
        show("runaway invokes", () -> outcome(y.submit(new RunawayInvoke())));
        y.shutdown();
        show("pool ends after invokes", () -> y.awaitTermination(20, TimeUnit.SECONDS));
        ForkJoinPool z = new ForkJoinPool(2);
        show("chain of a million", () -> outcome(z.submit(new Chain(1000000))));
        z.shutdownNow();
        // The pool is still good for what comes next.
        ForkJoinPool w = new ForkJoinPool(1);
        show("overflow then a task", () -> outcome(w.submit(new Runaway())) + " " + w.submit(() -> "next").get(20, TimeUnit.SECONDS));
        w.shutdownNow();
    }

    /** Recursion through invoke on a thread outside any pool: past half its stack the common pool takes over. */
    static void outside() throws Exception {
        for (int d : new int[] {800, 1400}) {
            show("invoke chain " + d + " outside a pool", () -> new Invoked(d).invoke());
            show("invokeAll chain " + d + " outside a pool", () -> new Both(d).invoke());
            String[] seen = new String[1];
            Thread t = new Thread(() -> seen[0] = String.valueOf(new Invoked(d).invoke()));
            t.start();
            t.join();
            show("invoke chain " + d + " on another plain thread", () -> seen[0]);
            ForkJoinPool x = new ForkJoinPool(1);
            show("invoke chain " + d + " on another pool's worker", () -> outcome(x.submit(new Invoked(d))));
            x.shutdown();
        }
    }

    static int down(int n, Runnable r) { if (n == 0) { r.run(); return 0; } return down(n - 1, r) + 1; }

    static void tightQuiesce() throws Exception {
        AtomicInteger made = new AtomicInteger();
        ForkJoinPool x = new ForkJoinPool(2, pool -> {
            if (made.incrementAndGet() > 1) throw new IllegalStateException("no worker");
            return new Plain(pool);
        }, null, false);
        ForkJoinTask<String> t = x.submit(() -> {
            String[] said = new String[1];
            RecursiveAction child = new RecursiveAction() { @Override protected void compute() { } };
            down(2200, () -> {
                try { child.fork(); } catch (IllegalStateException e) { said[0] = "fork threw"; }
                ForkJoinTask.helpQuiesce();
                said[0] += ", helpQuiesce returned, child done=" + child.isDone();
            });
            return said[0];
        });
        show("helpQuiesce deep down, factory throws", () -> outcome(t));
        show("nothing left counted", () -> x.getRunningThreadCount() + " " + x.getQueuedTaskCount());
        x.shutdownNow();
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
        show("submission after a fork in onStart", () -> outcome(x.submit(rel::countDown)));
        rel.countDown();
        x.shutdownNow();
        // A worker built for another pool belongs to that pool, one in use: see the user's worker below.
        ForkJoinPool q = new ForkJoinPool(3);
        q.submit(() -> "first").get();
        ForkJoinPool y = new ForkJoinPool(2, ignored -> new Plain(q), null, false);
        y.submit(() -> { });
        show("task on the worker's own pool", () -> outcome(q.submit(() -> "ran")));
        q.shutdown();
        show("worker's own pool ends", () -> q.awaitTermination(20, TimeUnit.SECONDS));
        y.shutdownNow();
        // A worker the user made and started, on a pool in use: on a new pool the oracle can hang.
        ForkJoinPool z = new ForkJoinPool(2);
        z.submit(() -> "first").get();
        Plain mine = new Plain(z);
        mine.start();
        Thread.sleep(200);
        show("task beside a user's worker", () -> outcome(z.submit(() -> "ran")));
        z.shutdown();
        show("pool of a user's worker ends", () -> z.awaitTermination(20, TimeUnit.SECONDS));
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
            x.shutdown();
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
        y.shutdown();
        ForkJoinPool z = new ForkJoinPool(1);
        CountDownLatch rel = new CountDownLatch(1), in = new CountDownLatch(1);
        z.execute(() -> { in.countDown(); awaitQuietly(rel); });
        in.await();
        ForkJoinTask<String> queued = z.submit(() -> "x");
        show("timed get outside with the lowest timeout", () -> { try { return queued.get(Long.MIN_VALUE, TimeUnit.DAYS); } catch (TimeoutException e) { return "timeout"; } });
        show("awaitTermination with the lowest timeout", () -> z.awaitTermination(Long.MIN_VALUE, TimeUnit.DAYS));
        rel.countDown();
        z.shutdown();
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
        n.shutdownNow();
        Thread.UncaughtExceptionHandler quiet = (t, e) -> { };
        for (int who = 0; who < 2; who++) {
            ForkJoinPool y = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, quiet, false);
            CountDownLatch in = new CountDownLatch(1), rel = new CountDownLatch(1);
            y.execute(() -> { in.countDown(); awaitQuietly(rel); });
            in.await();
            Thread[] ranOn = new Thread[1];
            y.execute(() -> ranOn[0] = Thread.currentThread());
            Callable<String> c = () -> y.awaitQuiescence(100, TimeUnit.MILLISECONDS) + " here=" + (ranOn[0] == Thread.currentThread());
            String label = who == 0 ? "busy pool, waiting from outside" : "busy pool, waiting on another pool's worker";
            if (who == 0) {
                show(label, c::call);
            } else {
                ForkJoinPool z = new ForkJoinPool(1);
                show(label, () -> z.submit(c).get());
                z.shutdown();
            }
            y.execute(() -> { throw new IllegalStateException("boom"); });
            show("a failure it runs is thrown at it", () -> y.awaitQuiescence(100, TimeUnit.MILLISECONDS));
            rel.countDown();
            y.shutdown();
        }
        // With a worker free the task goes to it, and the wait can end while it runs.
        ForkJoinPool f = new ForkJoinPool(2);
        f.submit(() -> "first").get();
        f.awaitQuiescence(15, TimeUnit.SECONDS);
        CountDownLatch end = new CountDownLatch(1);
        ForkJoinTask<?> slow = f.submit(() -> awaitQuietly(end));
        show("a free worker takes it", () -> f.awaitQuiescence(10, TimeUnit.MILLISECONDS) + " done=" + slow.isDone());
        end.countDown();
        f.shutdown();
        // Waiting on the common pool's end also helps.
        int p = ForkJoinPool.getCommonPoolParallelism();
        CountDownLatch in = new CountDownLatch(p), rel = new CountDownLatch(1);
        ForkJoinPool c = ForkJoinPool.commonPool();
        for (int i = 0; i < p; i++) c.execute(() -> { in.countDown(); awaitQuietly(rel); });
        in.await();
        c.execute(() -> on[0] = Thread.currentThread() instanceof ForkJoinWorkerThread ? "worker" : "caller");
        show("common pool's awaitTermination", () -> c.awaitTermination(100, TimeUnit.MILLISECONDS) + " " + on[0]);
        rel.countDown();
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
        x.shutdown();
    }

    public static void main(String[] args) throws Exception {
        // The overflows come last, with every class in use already: on the oracle a class first
        // needed at the very end of a stack fails to initialize, and stays broken for the whole run.
        oddWorkers();
        timedGets();
        assist();
        nested();
        tightQuiesce();
        outside();
        noOtherWorker();
        runaway();
        System.exit(0);
    }
}
