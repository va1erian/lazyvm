package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fork/join on a worker: interrupts in get and join, compensation, order of submissions, deep joins. */
public class ForkJoinWorkers {
    interface Body { Object run() throws Throwable; }
    static String n(Object o) { return String.valueOf(o).replaceAll("ForkJoinPool-[0-9]+", "ForkJoinPool-N").replaceAll("@[0-9a-f]+", "@H").replaceAll("\\$\\$Lambda\\$[0-9]+/[0-9]+", "-Lambda").replaceAll("-Lambda-[0-9]+", "-Lambda"); }
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

    static void workerGets() throws Exception {
        ForkJoinPool p = new ForkJoinPool(2);
        show("get flagged queued keeps flag", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x").fork();
            Thread.currentThread().interrupt();
            String r = f.get();
            return r + " flag=" + Thread.interrupted();
        }).get());
        show("timed get flagged queued", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x").fork();
            Thread.currentThread().interrupt();
            try { String r = f.get(1, TimeUnit.SECONDS); return r + " flag=" + Thread.interrupted(); }
            catch (InterruptedException e) { return "ie flag=" + Thread.interrupted(); }
        }).get());
        show("get flagged done", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x"); f.invoke();
            Thread.currentThread().interrupt();
            try { String r = f.get(); return r + " flag=" + Thread.interrupted(); }
            catch (InterruptedException e) { return "ie flag=" + Thread.interrupted(); }
        }).get());
        show("timed get flagged done", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x"); f.invoke();
            Thread.currentThread().interrupt();
            try { String r = f.get(1, TimeUnit.SECONDS); return r + " flag=" + Thread.interrupted(); }
            catch (InterruptedException e) { return "ie flag=" + Thread.interrupted(); }
        }).get());
        show("timed get null unit worker", () -> p.submit(() -> {
            ForkJoinTask<String> f = ForkJoinTask.adapt(() -> "x"); f.invoke();
            try { return f.get(1, null); } catch (NullPointerException e) { return "npe"; }
        }).get());
        // Pending, with nothing to help: an exec-false task.
        for (boolean timed : new boolean[] {false, true}) {
            CountDownLatch waiting = new CountDownLatch(1);
            ForkJoinTask<String> never = new ForkJoinTask<String>() {
                @Override public String getRawResult() { return "raw"; }
                @Override protected void setRawResult(String s) { }
                @Override protected boolean exec() { return false; }
            };
            Thread[] wt = new Thread[1];
            ForkJoinTask<String> joiner = p.submit(() -> {
                wt[0] = Thread.currentThread();
                try { String r = timed ? never.get(30, TimeUnit.SECONDS) : never.get(); return r + " flag=" + Thread.interrupted(); }
                catch (InterruptedException e) { return "ie flag=" + Thread.interrupted(); }
            });
            until(() -> wt[0] != null && wt[0].getState() != Thread.State.RUNNABLE);
            show("state pending get timed=" + timed, () -> wt[0].getState());
            wt[0].interrupt();
            Thread.sleep(50);
            show("done after interrupt", joiner::isDone);
            never.complete("x");
            show("pending get interrupted timed=" + timed, joiner::get);
        }
        // Timed get in worker: times out while the task stays queued elsewhere?
        CountDownLatch g = new CountDownLatch(1);
        ForkJoinTask<String> blocked = new ForkJoinTask<String>() {
            @Override public String getRawResult() { return "raw"; }
            @Override protected void setRawResult(String s) { }
            @Override protected boolean exec() { return false; }
        };
        show("worker timed get expires", () -> p.submit(() -> { try { return blocked.get(5, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { return "timeout"; } }).get());
        show("worker get cancelled", () -> { blocked.cancel(false); return p.submit(() -> { try { return blocked.get(); } catch (CancellationException e) { return "ce"; } }).get(); });
        // Outside: interrupted while blocked in get and timed get
        ForkJoinTask<String> open = new ForkJoinTask<String>() {
            @Override public String getRawResult() { return "raw"; }
            @Override protected void setRawResult(String s) { }
            @Override protected boolean exec() { return false; }
        };
        Thread main = Thread.currentThread();
        Thread killer = new Thread(() -> { try { until(() -> main.getState() == Thread.State.WAITING); main.interrupt(); } catch (Exception e) { } });
        killer.start();
        show("outside get interrupted", open::get);
        show("outside flag", Thread::interrupted);
        Thread killer2 = new Thread(() -> { try { until(() -> main.getState() == Thread.State.TIMED_WAITING); main.interrupt(); } catch (Exception e) { } });
        killer2.start();
        show("outside timed get interrupted", () -> open.get(1, TimeUnit.MINUTES));
        show("outside flag 2", Thread::interrupted);
        p.shutdown();
    }

    static void managed() throws Exception {
        ForkJoinPool p = new ForkJoinPool(1);
        CountDownLatch gate = new CountDownLatch(1);
        // A blocked worker in managedBlock lets a queued task run at parallelism 1.
        ForkJoinTask<String> blocker = p.submit(() -> {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override public boolean block() throws InterruptedException { gate.await(); return true; }
                @Override public boolean isReleasable() { return gate.getCount() == 0; }
            });
            return "unblocked";
        });
        ForkJoinTask<String> second = p.submit(() -> { gate.countDown(); return "second ran"; });
        show("managed compensates", second::get);
        show("blocker", blocker::get);
        // Plain latch at parallelism 1 does not.
        CountDownLatch gate2 = new CountDownLatch(1), in = new CountDownLatch(1);
        p.execute(() -> { in.countDown(); ForkJoinWorkers.await(gate2); });
        in.await();
        ForkJoinTask<String> third = p.submit(() -> "third");
        show("unmanaged no compensation", () -> { try { return third.get(50, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { return "timeout"; } });
        gate2.countDown();
        show("third after", third::get);
        // Throwing blocker in worker: exception passes, accounting restored.
        ForkJoinTask<String> thrower = p.submit(() -> {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override public boolean block() throws InterruptedException { throw new InterruptedException("mine"); }
                @Override public boolean isReleasable() { return false; }
            });
            return "no";
        });
        show("blocker throws", () -> { try { return thrower.get(); } catch (ExecutionException e) { return e.getCause().getClass().getName(); } });
        // Join at parallelism 1 on a task an exec-false: a queued task still gets to run.
        ForkJoinTask<String> later = new ForkJoinTask<String>() {
            String v;
            @Override public String getRawResult() { return v; }
            @Override protected void setRawResult(String s) { v = s; }
            @Override protected boolean exec() { return false; }
        };
        ForkJoinTask<String> joiner = p.submit(() -> later.join());
        ForkJoinTask<String> completer = p.submit(() -> { later.complete("freed"); return "completer"; });
        show("join compensates", joiner::get);
        show("completer", completer::get);
        p.shutdown();
        show("end", () -> p.awaitTermination(20, TimeUnit.SECONDS));
    }

    static void concurrency() throws Exception {
        // Parallelism bound for plain tasks.
        ForkJoinPool p = new ForkJoinPool(3);
        AtomicInteger cur = new AtomicInteger(), max = new AtomicInteger();
        CountDownLatch all = new CountDownLatch(3);
        List<ForkJoinTask<?>> ts = new ArrayList<>();
        for (int i = 0; i < 3; i++) ts.add(p.submit(() -> { int c = cur.incrementAndGet(); max.accumulateAndGet(c, Math::max); all.countDown(); try { all.await(); } catch (InterruptedException e) { } cur.decrementAndGet(); }));
        for (ForkJoinTask<?> t : ts) t.join();
        show("runs to parallelism", max::get);
        show("size at most", () -> p.getPoolSize() <= 3);
        ts.clear();
        AtomicInteger cur2 = new AtomicInteger(), max2 = new AtomicInteger();
        for (int i = 0; i < 12; i++) ts.add(p.submit(() -> { int c = cur2.incrementAndGet(); max2.accumulateAndGet(c, Math::max); try { Thread.sleep(2); } catch (InterruptedException e) { } cur2.decrementAndGet(); }));
        for (ForkJoinTask<?> t : ts) t.join();
        show("never above parallelism", () -> max2.get() <= 3);
        p.shutdown();
        // FIFO of submissions on one worker.
        ForkJoinPool one = new ForkJoinPool(1);
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        one.execute(() -> { e.countDown(); ForkJoinWorkers.await(g); });
        e.await();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<ForkJoinTask<?>> sub = new ArrayList<>();
        for (String s : new String[] {"a", "b", "c", "d"}) sub.add(one.submit(() -> order.add(s)));
        g.countDown();
        for (ForkJoinTask<?> t : sub) t.join();
        show("submission order", () -> order);
        one.shutdown();
        ForkJoinPool asy = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
        CountDownLatch g2 = new CountDownLatch(1), e2 = new CountDownLatch(1);
        asy.execute(() -> { e2.countDown(); ForkJoinWorkers.await(g2); });
        e2.await();
        List<String> o2 = Collections.synchronizedList(new ArrayList<>());
        List<ForkJoinTask<?>> s2 = new ArrayList<>();
        for (String s : new String[] {"a", "b", "c"}) s2.add(asy.submit(() -> o2.add(s)));
        g2.countDown();
        for (ForkJoinTask<?> t : s2) t.join();
        show("async submission order", () -> o2);
        asy.shutdown();
        // Awaiting quiescence from outside waits for forked trees.
        ForkJoinPool q = new ForkJoinPool(2);
        AtomicInteger cnt = new AtomicInteger();
        class Tree extends RecursiveAction {
            final int d; Tree(int d) { this.d = d; }
            @Override protected void compute() { cnt.incrementAndGet(); if (d > 0) { new Tree(d - 1).fork(); new Tree(d - 1).fork(); } }
        }
        AtomicInteger cnt2 = new AtomicInteger();
        class Tree2 extends RecursiveAction {
            final int d; Tree2(int d) { this.d = d; }
            @Override protected void compute() { cnt2.incrementAndGet(); if (d > 0) { new Tree2(d - 1).fork(); new Tree2(d - 1).fork(); } }
        }
        q.execute(new Tree2(8));
        q.shutdown();
        show("shutdown finishes forked trees", () -> q.awaitTermination(30, TimeUnit.SECONDS) + " " + cnt2.get());
        // The pool's helpQuiesce from a task counts everything.
        ForkJoinPool hq = new ForkJoinPool(2);
        AtomicInteger cnt3 = new AtomicInteger();
        show("helpQuiesce in task", () -> hq.submit(() -> {
            for (int i = 0; i < 100; i++) ForkJoinTask.adapt((Runnable) cnt3::incrementAndGet).fork();
            ForkJoinTask.helpQuiesce();
            return cnt3.get();
        }).get());
        hq.shutdown();
    }

    static void names() throws Exception {
        ForkJoinPool a = new ForkJoinPool(1);
        show("name a", () -> a.submit(() -> Thread.currentThread().getName()).get());
        ForkJoinPool b = new ForkJoinPool(1);
        show("name b", () -> b.submit(() -> Thread.currentThread().getName()).get());
        show("index", () -> b.submit(() -> ((ForkJoinWorkerThread) Thread.currentThread()).getPoolIndex() >= 0).get());
        a.shutdown(); b.shutdown();
        ForkJoinPool c = new ForkJoinPool(1, pool -> new ForkJoinWorkerThread(pool) { @Override protected void onStart() { setName(getName() + "!"); } }, null, false);
        show("rename in start", () -> c.submit(() -> Thread.currentThread().getName().endsWith("!")).get());
        c.shutdown();
        Thread[] seen = new Thread[1];
        ForkJoinPool d = new ForkJoinPool(1);
        d.submit(() -> { seen[0] = Thread.currentThread(); }).get();
        d.shutdown();
        d.awaitTermination(20, TimeUnit.SECONDS);
        seen[0].join();
        show("terminated worker getPool", () -> ((ForkJoinWorkerThread) seen[0]).getPool() == d);
        show("terminated worker index", () -> ((ForkJoinWorkerThread) seen[0]).getPoolIndex() >= 0);
    }

    static class Deep extends RecursiveTask<Integer> {
        final int n; Deep(int n) { this.n = n; }
        @Override protected Integer compute() {
            if (n == 0) return 0;
            Deep d = new Deep(n - 1);
            d.fork();
            return d.join() + 1;
        }
    }

    static class Fib extends RecursiveTask<Integer> {
        final int n;
        Fib(int n) { this.n = n; }
        @Override protected Integer compute() {
            if (n < 2) return n;
            Fib a = new Fib(n - 1);
            a.fork();
            return new Fib(n - 2).compute() + a.join();
        }
    }

    static void load() throws Exception {
        ForkJoinPool p = new ForkJoinPool(1);
        show("deep 300", () -> p.invoke(new Deep(300)));
        show("deep 800", () -> p.invoke(new Deep(800)));
        p.shutdown();
        for (int par : new int[] {1, 2, 8}) {
            ForkJoinPool q = new ForkJoinPool(par);
            show("fib 22 par " + par, () -> q.invoke(new Fib(22)));
            q.shutdown();
        }
    }

    public static void main(String[] args) throws Exception {
        workerGets();
        show("common default parallelism", () -> ForkJoinPool.getCommonPoolParallelism() == Math.max(Runtime.getRuntime().availableProcessors() - 1, 1));
        managed();
        concurrency();
        names();
        load();
        System.exit(0);
    }
}
