package concurrent;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stacks that overflow, and chains when a factory gives no other worker. After an overflow the
 * oracle's pools sometimes hang, so a call past its limit ends the run with a line tools/golden.py
 * refuses: run the bless again. It has few overflows for that reason, and {@code
 * lazy.internal.ForkJoinOverflow} has the rest, for LazyVM alone.
 */
public class ForkJoinRunaway {
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
    static void shutdownNow(ForkJoinPool p) throws Throwable { bounded("shutdownNow", () -> { p.shutdownNow(); return null; }); }
    static String outcome(String what, Future<?> f) throws InterruptedException {
        try { return String.valueOf(f.get(LIMIT_MILLIS / 2, TimeUnit.MILLISECONDS)); }
        catch (ExecutionException e) { return e.getCause().getClass().getName(); }
        catch (TimeoutException e) { hung(what); return null; }
    }
    static boolean ended(String what, ForkJoinPool p) throws Throwable {
        bounded("shutdown", () -> { p.shutdown(); return null; });
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
    static class Runaway extends RecursiveTask<Integer> {
        @Override protected Integer compute() { Runaway c = new Runaway(); c.fork(); return c.join() + 1; }
    }
    static class RunawayInvoke extends RecursiveTask<Integer> {
        @Override protected Integer compute() { return new RunawayInvoke().invoke() + 1; }
    }
    static class Noop extends RecursiveAction { @Override protected void compute() { } }

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

    /** A chain needs no other worker: it runs on the stack it started on. */
    static void noOtherWorker() throws Throwable {
        for (String way : new String[] {"null", "throw", "start"}) {
            ForkJoinPool x = oneWorkerThen(way);
            show("chain 600, factory " + way, () -> outcome("chain", x.submit(new Chain(600))));
            shutdownNow(x);
            ForkJoinPool y = oneWorkerThen(way);
            show("invoke chain 600, factory " + way, () -> outcome("invoke chain", y.submit(new Invoked(600))));
            shutdownNow(y);
        }
    }

    static void runaway() throws Throwable {
        ForkJoinPool x = new ForkJoinPool(2);
        show("runaway forks", () -> outcome("runaway", x.submit(new Runaway())));
        show("few workers for it", () -> x.getPoolSize() <= 20);
        show("pool ends", () -> ended("pool ends", x));
        ForkJoinPool y = new ForkJoinPool(2);
        show("runaway invokes", () -> outcome("runaway invokes", y.submit(new RunawayInvoke())));
        show("pool ends after invokes", () -> ended("pool ends", y));
        // The pool is still good for what comes next.
        ForkJoinPool w = new ForkJoinPool(1);
        show("overflow then a task", () -> outcome("overflow", w.submit(new Runaway())) + " " + outcome("next", w.submit(() -> "next")));
        shutdownNow(w);
    }

    /**
     * Every class the overflows will need, in use before them: on the oracle a class first needed at
     * the very end of a stack fails to initialize and stays broken for the whole run.
     */
    static void warm() throws Throwable {
        ForkJoinPool x = new ForkJoinPool(2);
        show("warm", () -> outcome("warm", x.submit(new Chain(100))) + " " + outcome("warm", x.submit(() -> { throw new StackOverflowError(); })));
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch waiting = new CountDownLatch(1);
        lock.lock();
        Thread t = new Thread(() -> { waiting.countDown(); lock.lock(); lock.unlock(); });
        t.setDaemon(true);
        t.start();
        waiting.await();
        while (!lock.hasQueuedThreads()) Thread.sleep(1);
        lock.unlock();
        t.join(LIMIT_MILLIS);
        shutdownNow(x);
    }

    interface Act { void go() throws Exception; }
    static boolean reached;

    /** Goes to the end of the stack, then tries the call at each depth on the way back until one passes. */
    static void dive(Act act) {
        try { dive(act); } catch (StackOverflowError e) { }
        if (!reached) {
            try {
                act.go();
                reached = true;
            } catch (StackOverflowError e) {
                throw e;
            } catch (Exception e) {
                reached = true;
            }
        }
    }

    /** What is left of a pool after its call overflowed at every depth that it can. */
    static void endOfStack(String label, Act act, int par) throws Throwable {
        ForkJoinPool x = new ForkJoinPool(par);
        reached = false;
        show(label + " at " + par + ", run", () -> outcome(label, x.submit(() -> { dive(act); return reached; })));
        CountDownLatch next = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) x.execute(next::countDown);
        show(label + " at " + par + ", next tasks", () -> next.await(LIMIT_MILLIS / 2, TimeUnit.MILLISECONDS));
        show(label + " at " + par + ", helpQuiesce after", () -> outcome(label, x.submit(() -> { ForkJoinTask.helpQuiesce(); return "back"; })));
        show(label + " at " + par + ", quiet", () -> x.awaitQuiescence(LIMIT_MILLIS / 2, TimeUnit.MILLISECONDS));
        show(label + " at " + par + ", ends", () -> ended(label, x));
    }

    public static void main(String[] args) throws Throwable {
        warm();
        noOtherWorker();
        for (int par : new int[] {1, 2}) {
            endOfStack("helpQuiesce", ForkJoinTask::helpQuiesce, par);
            endOfStack("awaitQuiescence", () -> ((ForkJoinWorkerThread) Thread.currentThread()).getPool().awaitQuiescence(1, TimeUnit.SECONDS), par);
        }
        runaway();
        System.exit(0);
    }
}
