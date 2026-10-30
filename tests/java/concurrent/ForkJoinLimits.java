package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fork/join at its limits: waiting for quiet, failed workers, stand-ins coming back, deep stacks, failures crossing threads. */
public class ForkJoinLimits {
    interface Body { Object run() throws Throwable; }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }
    interface Cond { boolean ok() throws Exception; }
    static boolean until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!c.ok()) { if (System.nanoTime() > end) return false; Thread.sleep(1); }
        return true;
    }
    static final Thread.UncaughtExceptionHandler QUIET = (t, e) -> { };
    static ForkJoinPool oneWorkerQuiet() { return new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, QUIET, false); }
    static void managedAwait(CountDownLatch l) {
        try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override public boolean block() throws InterruptedException { l.await(); return true; }
                @Override public boolean isReleasable() { return l.getCount() == 0; }
            });
        } catch (InterruptedException e) { throw new RuntimeException(e); }
    }
    static void awaitQuietly(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    static void quiet() throws Exception {
        ForkJoinPool p = new ForkJoinPool(2);
        CountDownLatch both = new CountDownLatch(2);
        Callable<String> hq = () -> { both.countDown(); both.await(); ForkJoinTask.helpQuiesce(); return "out"; };
        ForkJoinTask<String> a = p.submit(hq), b = p.submit(hq);
        show("two in helpQuiesce", () -> a.get(15, TimeUnit.SECONDS) + " " + b.get(15, TimeUnit.SECONDS));
        p.shutdown();
        ForkJoinPool q = new ForkJoinPool(2);
        CountDownLatch both2 = new CountDownLatch(2);
        Callable<Boolean> aq = () -> { both2.countDown(); both2.await(); return q.awaitQuiescence(1, TimeUnit.SECONDS); };
        ForkJoinTask<Boolean> c = q.submit(aq), d = q.submit(aq);
        show("two in awaitQuiescence", () -> c.get(15, TimeUnit.SECONDS) + " " + d.get(15, TimeUnit.SECONDS));
        q.shutdown();
        // Waiting is the point: the other worker's task is over when the call returns.
        for (boolean timed : new boolean[] {false, true}) {
            ForkJoinPool r = new ForkJoinPool(2);
            CountDownLatch in = new CountDownLatch(1);
            AtomicBoolean done = new AtomicBoolean();
            r.execute(() -> { in.countDown(); sleep(200); done.set(true); });
            in.await();
            show(timed ? "awaitQuiescence in worker outlasts its timeout" : "helpQuiesce waits for a busy worker", () -> r.submit(() -> {
                if (timed) r.awaitQuiescence(1, TimeUnit.MILLISECONDS); else ForkJoinTask.helpQuiesce();
                return done.get();
            }).get());
            r.shutdown();
        }
        // A worker blocked in a managed wait does not count as busy.
        ForkJoinPool s = new ForkJoinPool(2);
        CountDownLatch in = new CountDownLatch(1), rel = new CountDownLatch(1);
        s.execute(() -> { in.countDown(); managedAwait(rel); });
        in.await();
        show("helpQuiesce ignores a blocked worker", () -> until(() -> s.getRunningThreadCount() <= 1) + " " + s.submit(() -> { ForkJoinTask.helpQuiesce(); return rel.getCount(); }).get(15, TimeUnit.SECONDS));
        rel.countDown();
        s.shutdown();
        // The first out counts as busy again, so the second stays until its task ends.
        ForkJoinPool t = new ForkJoinPool(2);
        CountDownLatch meet = new CountDownLatch(2), out = new CountDownLatch(2);
        Runnable r2 = () -> { meet.countDown(); awaitQuietly(meet); ForkJoinTask.helpQuiesce(); out.countDown(); try { out.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { } };
        ForkJoinTask<?> e = t.submit(r2), f = t.submit(r2);
        show("second out of helpQuiesce stays", () -> { e.get(); f.get(); return out.getCount(); });
        t.shutdown();
        ForkJoinPool u = new ForkJoinPool(1);
        List<String> l = Collections.synchronizedList(new ArrayList<>());
        show("helpQuiesce runs own forks then submissions", () -> u.submit(() -> {
            for (String x : new String[] {"a", "b"}) new RecursiveAction() { @Override protected void compute() { l.add(x); } }.fork();
            u.execute(() -> l.add("sub"));
            ForkJoinTask.helpQuiesce();
            return l.toString();
        }).get());
        u.shutdown();
        ForkJoinPool v = new ForkJoinPool(1), w = new ForkJoinPool(1);
        CountDownLatch vIn = new CountDownLatch(1), vRel = new CountDownLatch(1);
        v.execute(() -> { vIn.countDown(); awaitQuietly(vRel); });
        vIn.await();
        show("another pool's worker times out", () -> w.submit(() -> v.awaitQuiescence(5, TimeUnit.MILLISECONDS)).get());
        vRel.countDown();
        v.shutdown(); w.shutdown();
    }

    /** A worker that cannot start, in two ways, and what the pool makes of it. */
    static class NoStart extends ForkJoinWorkerThread {
        NoStart(ForkJoinPool p) { super(p); }
        @Override public synchronized void start() { throw new OutOfMemoryError("cannot start"); }
    }
    static class NoCtor extends ForkJoinWorkerThread {
        NoCtor(ForkJoinPool p) { super(p); throw new IllegalStateException("ctor"); }
    }
    static void failedWorkers() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            int m = mode;
            String name = mode == 0 ? "start throws" : "constructor throws";
            AtomicInteger made = new AtomicInteger(), n = new AtomicInteger();
            ForkJoinPool x = new ForkJoinPool(1, pool -> {
                if (made.incrementAndGet() == 1) return m == 0 ? new NoStart(pool) : new NoCtor(pool);
                return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            }, null, false);
            show(name + ": execute", () -> { x.execute(n::incrementAndGet); return "ok"; });
            show(name + ": after", () -> "made=" + made + " size=" + x.getPoolSize() + " ran=" + n);
            show(name + ": second", () -> {
                x.execute(n::incrementAndGet);
                return until(() -> n.get() == 2 && x.isQuiescent()) + " made=" + made + " size=" + x.getPoolSize();
            });
            x.shutdown();
            // A worker constructed but never started keeps the pool from ending for good.
            show(name + ": ends", () -> x.awaitTermination(m == 0 ? 15000 : 300, TimeUnit.MILLISECONDS) + " " + x.isTerminated());
        }
    }

    static boolean allDone(ForkJoinTask<?>[] ts) { for (ForkJoinTask<?> t : ts) if (!t.isDone()) return false; return true; }
    static int cancelled(ForkJoinTask<?>[] ts) { int n = 0; for (ForkJoinTask<?> t : ts) if (t.isCancelled()) n++; return n; }

    static void workerDeath() throws Exception {
        for (int forks : new int[] {0, 2}) {
            AtomicInteger ran = new AtomicInteger();
            RecursiveAction[] kids = new RecursiveAction[forks];
            ForkJoinPool y = oneWorkerQuiet();
            CountDownLatch thrown = new CountDownLatch(1);
            y.execute(() -> {
                for (int i = 0; i < forks; i++) {
                    kids[i] = new RecursiveAction() { @Override protected void compute() { ran.incrementAndGet(); } };
                    kids[i].fork();
                }
                thrown.countDown();
                throw new RuntimeException("die");
            });
            thrown.await();
            // The dead worker's forks are cancelled, not run, and a new worker takes its place.
            show("killed worker's forks: " + forks, () -> until(() -> y.getPoolSize() == 1 && allDone(kids) && y.isQuiescent())
                    + " ran=" + ran + " cancelled=" + cancelled(kids) + " size=" + y.getPoolSize());
            y.shutdown();
            show("killed worker's pool ends: " + forks, () -> y.awaitTermination(15, TimeUnit.SECONDS));
        }
        // With another worker idle, no new one comes.
        ForkJoinPool z = new ForkJoinPool(3, ForkJoinPool.defaultForkJoinWorkerThreadFactory, QUIET, false);
        CountDownLatch three = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) z.execute(() -> { three.countDown(); awaitQuietly(three); });
        three.await();
        until(z::isQuiescent);
        CountDownLatch thrown = new CountDownLatch(1);
        z.execute(() -> { thrown.countDown(); throw new RuntimeException("die"); });
        thrown.await();
        show("killed worker with one idle beside it", () -> until(() -> z.getPoolSize() == 2) + " " + z.getPoolSize());
        z.shutdown();
        // A submission waiting behind a worker that dies does not wait for another call.
        AtomicInteger later = new AtomicInteger();
        ForkJoinPool q = oneWorkerQuiet();
        CountDownLatch go = new CountDownLatch(1), started = new CountDownLatch(1);
        q.execute(() -> { started.countDown(); awaitQuietly(go); throw new RuntimeException("die"); });
        started.await();
        q.execute(later::incrementAndGet);
        go.countDown();
        show("submission behind a killed worker", () -> until(() -> later.get() == 1));
        q.shutdown();
        // A worker whose onStart throws is not replaced, and what is queued waits for the next call.
        AtomicInteger made = new AtomicInteger(), n = new AtomicInteger();
        CountDownLatch dead = new CountDownLatch(1);
        ForkJoinPool s = new ForkJoinPool(1, pool -> {
            if (made.incrementAndGet() == 1) return new ForkJoinWorkerThread(pool) {
                @Override protected void onStart() { throw new RuntimeException("no start"); }
                @Override protected void onTermination(Throwable e) { dead.countDown(); }
            };
            return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        }, QUIET, false);
        s.execute(n::incrementAndGet);
        dead.await();
        show("onStart failure: gone", () -> until(() -> s.getPoolSize() == 0) + " made=" + made + " ran=" + n);
        s.execute(n::incrementAndGet);
        show("onStart failure: next call", () -> until(() -> n.get() == 2) + " made=" + made);
        s.shutdown();
    }

    static void retire() throws Exception {
        ForkJoinPool x = new ForkJoinPool(2);
        x.submit(() -> { }).get();
        show("idle workers go away", () -> until(() -> x.getPoolSize() == 0));
        show("and come back", () -> x.submit(() -> "yes").get());
        x.shutdown();
    }

    /** A stand-in for a blocked worker, which is back meanwhile. */
    static void aboveTarget() throws Exception {
        ForkJoinPool x = new ForkJoinPool(1);
        CountDownLatch standIn = new CountDownLatch(1), leftRan = new CountDownLatch(1);
        RecursiveAction t = new RecursiveAction() {
            @Override protected void compute() {
                standIn.countDown();
                new RecursiveAction() { @Override protected void compute() { leftRan.countDown(); } }.fork();
                sleep(100);
            }
        };
        ForkJoinTask<String> f = x.submit(() -> {
            t.fork();
            managedAwait(standIn);
            while (!t.isDone()) Thread.sleep(1);
            Thread.sleep(100);
            return leftRan.await(15, TimeUnit.SECONDS) + " size=" + x.getPoolSize();
        });
        show("fork left on a stand-in", f::get);
        x.shutdown();
        // Once all is quiet, the idle workers are held to the target again.
        ForkJoinPool y = new ForkJoinPool(1);
        CountDownLatch in = new CountDownLatch(1), rel = new CountDownLatch(1);
        AtomicInteger cur = new AtomicInteger(), max = new AtomicInteger();
        Runnable plain = () -> { max.accumulateAndGet(cur.incrementAndGet(), Math::max); sleep(5); cur.decrementAndGet(); };
        ForkJoinTask<?> blocker = y.submit(() -> { in.countDown(); managedAwait(rel); });
        in.await();
        y.submit(plain).join();
        rel.countDown();
        blocker.join();
        until(y::isQuiescent);
        List<ForkJoinTask<?>> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(y.submit(plain));
        for (ForkJoinTask<?> f2 : many) f2.join();
        show("plain tasks at once after a stand-in", max::get);
        y.shutdown();
        // A stand-in that ends its task with the other back and busy still takes what is queued.
        ForkJoinPool z = new ForkJoinPool(1);
        CountDownLatch aIn = new CountDownLatch(1), aRel = new CountDownLatch(1), aBack = new CountDownLatch(1), standing = new CountDownLatch(1), standRel = new CountDownLatch(1), aEnd = new CountDownLatch(1);
        ForkJoinTask<?> a = z.submit(() -> { aIn.countDown(); managedAwait(aRel); aBack.countDown(); awaitQuietly(aEnd); });
        aIn.await();
        ForkJoinTask<?> stand = z.submit(() -> { standing.countDown(); awaitQuietly(standRel); });
        standing.await();
        aRel.countDown();
        aBack.await();
        AtomicBoolean ran = new AtomicBoolean();
        ForkJoinTask<?> queued = z.submit(() -> ran.set(true));
        standRel.countDown();
        stand.join();
        show("queue served above the target", () -> { queued.get(15, TimeUnit.SECONDS); return ran.get() + " other still running=" + !a.isDone(); });
        aEnd.countDown();
        a.join();
        z.shutdown();
    }

    /** Tasks that can only finish together, however they wait. */
    static void together() throws Exception {
        for (int par : new int[] {2, 8}) {
            ForkJoinPool x = new ForkJoinPool(par);
            CountDownLatch all = new CountDownLatch(par);
            List<ForkJoinTask<?>> ts = new ArrayList<>();
            for (int i = 0; i < par; i++) ts.add(x.submit(() -> { all.countDown(); awaitQuietly(all); }));
            for (ForkJoinTask<?> t : ts) t.join();
            show("latched on each other at " + par, () -> x.getRunningThreadCount() <= par);
            AtomicInteger arrived = new AtomicInteger();
            ts.clear();
            for (int i = 0; i < par; i++) ts.add(x.submit(() -> { arrived.incrementAndGet(); while (arrived.get() < par) { } }));
            for (ForkJoinTask<?> t : ts) t.join();
            show("spinning on each other at " + par, arrived::get);
            CountDownLatch forks = new CountDownLatch(par);
            show("forks latched on each other at " + par, () -> x.submit(() -> {
                for (int i = 1; i < par; i++) new RecursiveAction() { @Override protected void compute() { forks.countDown(); awaitQuietly(forks); } }.fork();
                forks.countDown();
                forks.await();
                return "met";
            }).get());
            x.shutdown();
        }
    }

    static int extra;
    static class Chain extends RecursiveTask<Integer> {
        final int d;
        Chain(int d) { this.d = d; }
        @Override protected Integer compute() { return level(extra); }
        int level(int e) {
            if (e > 0) return level(e - 1);
            if (d == 0) return 0;
            Chain c = new Chain(d - 1);
            c.fork();
            return c.join() + 1;
        }
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
    static class Three extends RecursiveTask<Integer> {
        final int d;
        Three(int d) { this.d = d; }
        @Override protected Integer compute() {
            if (d == 0) return 0;
            Three a = new Three(d - 1), b = new Three(0), c = new Three(0);
            invokeAll(a, b, c);
            return a.join() + 1;
        }
    }
    static void deep() throws Exception {
        int[][] chains = {{350, 4}, {390, 3}, {200, 15}, {800, 0}};
        for (int[] c : chains) {
            for (int par : new int[] {1, 2}) {
                ForkJoinPool p = new ForkJoinPool(par);
                extra = c[1];
                show("chain " + c[0] + " with " + c[1] + " extra frames at " + par, () -> p.invoke(new Chain(c[0])));
                p.shutdown();
            }
        }
        for (int d : new int[] {650, 1000}) {
            ForkJoinPool p = new ForkJoinPool(2);
            show("invokeAll two deep " + d, () -> p.invoke(new Both(d)));
            show("invokeAll many deep " + d, () -> p.invoke(new Three(d)));
            p.shutdown();
        }
    }

    // Each class below takes one way through the rule for a failure read on another thread.
    public static class OnlyNoArg extends RuntimeException { public OnlyNoArg() { } public OnlyNoArg(String m, int x) { super(m); } }
    public static class OnlyCause extends RuntimeException { public OnlyCause(Throwable c) { super(c); } public OnlyCause(String m, int x) { super(m); } }
    public static class CauseFirst extends RuntimeException { public CauseFirst(Throwable c) { super(c); } public CauseFirst() { super("n"); } public CauseFirst(String m, int x) { super(m); } }
    public static class NoArgFirst extends RuntimeException { public NoArgFirst() { super("n"); } public NoArgFirst(Throwable c) { super(c); } public NoArgFirst(String m, int x) { super(m); } }
    static class Hidden extends RuntimeException { public Hidden() { } public Hidden(String m, int x) { super(m); } }
    protected static class Prot extends RuntimeException { public Prot() { } public Prot(String m, int x) { super(m); } }
    private static class Priv extends RuntimeException { public Priv() { } public Priv(String m, int x) { super(m); } }
    public static class CtorThrows extends RuntimeException { static boolean armed; public CtorThrows() { if (armed) throw new IllegalStateException("no"); } public CtorThrows(String m, int x) { super(m); } }
    public static class CauseFixed extends RuntimeException { public CauseFixed() { super((Throwable) null); } public CauseFixed(String m, int x) { super(m); } }
    public static class ProtCtor extends RuntimeException { protected ProtCtor(Throwable c) { super(c); } public ProtCtor(String m, int x) { super(m); } }
    public static class NarrowCtor extends RuntimeException { public NarrowCtor(Exception c) { super(c); } public NarrowCtor(String m, int x) { super(m); } }
    public static class Checked extends Exception { public Checked() { } public Checked(String m, int x) { super(m); } }
    public static class Err extends Error { public Err() { } public Err(String m, int x) { super(m); } }
    @SuppressWarnings("unchecked") static <E extends Throwable> void sneak(Throwable t) throws E { throw (E) t; }
    static String seen(Throwable original, Throwable e) {
        String cls = e.getClass().getName().replaceFirst(".*[.$]", "");
        return (e == original ? "same" : "copy") + " " + cls + " msg=" + e.getMessage() + " cause=" + (e.getCause() == original ? "original" : String.valueOf(e.getCause()));
    }
    static void failures() throws Exception {
        Throwable[] all = {new OnlyNoArg("a", 1), new OnlyCause("b", 1), new CauseFirst("c", 1), new NoArgFirst("d", 1), new Hidden("e", 1), new Prot("f", 1), new Priv("g", 1),
            new CtorThrows("h", 1), new CauseFixed("i", 1), new ProtCtor("j", 1), new NarrowCtor("k", 1), new Checked("l", 1), new Err("m", 1),
            new IllegalArgumentException("n"), new ArithmeticException("o"), new StackOverflowError(), new CancellationException("p")};
        CtorThrows.armed = true;
        ForkJoinPool x = new ForkJoinPool(1);
        for (Throwable o : all) {
            ForkJoinTask<?> t = x.submit(() -> { ForkJoinLimits.<RuntimeException>sneak(o); });
            until(t::isDone);
            String name = o.getClass().getSimpleName();
            show(name + " join", () -> { try { t.join(); return "none"; } catch (Throwable e) { return seen(o, e); } });
            show(name + " getException", () -> seen(o, t.getException()) + " again the same=" + (t.getException() == t.getException()));
            show(name + " get", () -> { try { t.get(); return "none"; } catch (ExecutionException e) { return seen(o, e.getCause()) + " outer=" + e.getMessage(); } });
        }
        RuntimeException ise = new IllegalStateException("ise");
        class T extends RecursiveTask<Integer> { @Override protected Integer compute() { return 1; } }
        T t1 = new T();
        t1.completeExceptionally(ise);
        show("completed here, read here", () -> { try { return t1.join(); } catch (Throwable e) { return seen(ise, e); } });
        show("completed here, read on a worker", () -> x.submit(() -> { try { t1.join(); return "none"; } catch (Throwable e) { return seen(ise, e); } }).get());
        T t2 = new T();
        x.submit(() -> t2.completeExceptionally(ise)).get();
        show("completed on a worker, read here", () -> { try { return t2.join(); } catch (Throwable e) { return seen(ise, e); } });
        Exception io = new java.io.IOException("io");
        T t3 = new T();
        x.submit(() -> t3.completeExceptionally(io)).get();
        show("checked completed on a worker, read here", () -> { try { return t3.join(); } catch (Throwable e) { return e + " cause=" + e.getCause() + " then=" + e.getCause().getCause(); } });
        RecursiveAction direct = new RecursiveAction() { @Override protected void compute() { throw ise; } };
        show("invoked here", () -> { try { direct.invoke(); return "none"; } catch (Throwable e) { return seen(ise, e); } });
        Thread r = new Thread(() -> show("read on another plain thread", () -> seen(ise, direct.getException())));
        r.start();
        r.join();
        show("read here again", () -> seen(ise, direct.getException()));
        show("forked and joined on one worker", () -> x.submit(() -> {
            RecursiveAction q = new RecursiveAction() { @Override protected void compute() { throw ise; } };
            q.fork();
            try { q.join(); return "none"; } catch (Throwable e) { return seen(ise, e); }
        }).get());
        ForkJoinPool y = new ForkJoinPool(2);
        show("run by a thief, joined by the forker", () -> y.submit(() -> {
            CountDownLatch in = new CountDownLatch(1);
            RecursiveAction q = new RecursiveAction() { @Override protected void compute() { in.countDown(); throw ise; } };
            q.fork();
            in.await();
            until(q::isDone);
            try { q.join(); return "none"; } catch (Throwable e) { return seen(ise, e); }
        }).get());
        T t4 = new T();
        x.submit(() -> t4.completeExceptionally(ise)).get();
        t4.reinitialize();
        t4.completeExceptionally(ise);
        show("reinitialized, completed here", () -> { try { return t4.join(); } catch (Throwable e) { return seen(ise, e); } });
        x.shutdown(); y.shutdown();
    }

    public static void main(String[] args) throws Exception {
        quiet();
        failedWorkers();
        workerDeath();
        retire();
        aboveTarget();
        together();
        deep();
        failures();
        System.exit(0);
    }
}
