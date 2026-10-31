// VMARGS: -Djava.util.concurrent.ForkJoinPool.common.parallelism=3
package concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** ForkJoinPool and its tasks: results, failure forms, pool shape and lifecycle, thread identity. */
public class ForkJoin {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("worker-[0-9]+", "worker-N")
            .replaceAll("ForkJoinPool-[0-9]+", "ForkJoinPool-N");
    }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    /** Only class names: a failure may be rebuilt for the waiting thread, so its message's form varies. */
    static void showType(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + t.getClass().getName()); }
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

    static class SumAction extends RecursiveAction {
        final int[] a; final int lo, hi; long sum;
        SumAction(int[] a, int lo, int hi) { this.a = a; this.lo = lo; this.hi = hi; }
        @Override protected void compute() {
            if (hi - lo < 8) { for (int i = lo; i < hi; i++) sum += a[i]; return; }
            int mid = (lo + hi) >>> 1;
            SumAction l = new SumAction(a, lo, mid), r = new SumAction(a, mid, hi);
            invokeAll(l, r);
            sum = l.sum + r.sum;
        }
    }

    static class Boom extends RecursiveTask<String> {
        @Override protected String compute() { throw new IllegalStateException("boom"); }
    }

    static void poolShape() throws Exception {
        ForkJoinPool common = ForkJoinPool.commonPool();
        show("common parallelism from property", ForkJoinPool::getCommonPoolParallelism);
        show("common getParallelism", common::getParallelism);
        show("common same", () -> common == ForkJoinPool.commonPool());
        show("common async", common::getAsyncMode);
        common.shutdown();
        show("common ignores shutdown", common::isShutdown);
        show("common shutdownNow", common::shutdownNow);
        show("common still open", common::isTerminated);
        show("common never terminates", () -> common.awaitTermination(1, TimeUnit.MILLISECONDS));
        show("common toString prefix", () -> common.toString().replaceAll(", size.*", ""));
        show("default factory", () -> ForkJoinPool.defaultForkJoinWorkerThreadFactory != null);

        show("parallelism 0", () -> new ForkJoinPool(0));
        show("parallelism negative", () -> new ForkJoinPool(-1));
        show("parallelism limit", () -> new ForkJoinPool(32767).getParallelism());
        show("parallelism over limit", () -> new ForkJoinPool(32768));
        show("null factory", () -> new ForkJoinPool(1, null, null, false));
        show("no-arg parallelism", () -> {
            ForkJoinPool none = new ForkJoinPool();
            boolean same = none.getParallelism() == Runtime.getRuntime().availableProcessors();
            none.shutdown();
            return same;
        });
        ForkJoinPool p = new ForkJoinPool(2);
        show("fresh toString", () -> p);
        show("parallelism", p::getParallelism);
        show("async default", p::getAsyncMode);
        show("handler default", p::getUncaughtExceptionHandler);
        show("factory default", () -> p.getFactory() == ForkJoinPool.defaultForkJoinWorkerThreadFactory);
        show("idle counts", () -> p.getPoolSize() + " " + p.getActiveThreadCount() + " " + p.getRunningThreadCount()
            + " " + p.getQueuedTaskCount() + " " + p.getQueuedSubmissionCount() + " " + p.getStealCount());
        show("quiescent", p::isQuiescent);
        show("hasQueuedSubmissions", p::hasQueuedSubmissions);
        show("worker identity", () -> p.submit(() -> {
            Thread t = Thread.currentThread();
            ForkJoinWorkerThread w = (ForkJoinWorkerThread) t;
            return t.getName() + " daemon=" + t.isDaemon() + " pool=" + (w.getPool() == p) + " index>=0=" + (w.getPoolIndex() >= 0)
                + " inPool=" + ForkJoinTask.inForkJoinPool() + " getPool=" + (ForkJoinTask.getPool() == p);
        }).get());
        // Executed through a latch: a get() on a common-pool task may run it in the caller.
        show("common worker identity", () -> {
            String[] seen = new String[1];
            CountDownLatch ran = new CountDownLatch(1);
            common.execute(() -> {
                Thread t = Thread.currentThread();
                seen[0] = t.getName() + " daemon=" + t.isDaemon() + " worker=" + (t instanceof ForkJoinWorkerThread);
                ran.countDown();
            });
            ran.await();
            return seen[0];
        });
        show("outside pool", ForkJoinTask::inForkJoinPool);
        show("outside getPool", ForkJoinTask::getPool);
        show("outside getQueuedTaskCount", ForkJoinTask::getQueuedTaskCount);
        show("outside pollTask", ForkJoinTaskPeek::pollAny);
        show("null submit", () -> p.submit((Callable<String>) null));
        show("null submit runnable", () -> p.submit((Runnable) null));
        show("null submit task", () -> p.submit((ForkJoinTask<String>) null));
        show("null execute", () -> { p.execute((Runnable) null); return "no"; });
        show("null execute task", () -> { p.execute((ForkJoinTask<String>) null); return "no"; });
        show("null invoke", () -> p.invoke(null));
        show("null invokeAll", () -> p.invokeAll(null));
        show("awaitQuiescence", () -> p.awaitQuiescence(30, TimeUnit.SECONDS));
        p.shutdown();
        show("shutdown await", () -> p.awaitTermination(30, TimeUnit.SECONDS));
        show("flags", () -> p.isShutdown() + " " + p.isTerminating() + " " + p.isTerminated());
        show("terminated toString", () -> p.toString().replaceAll(", parallelism.*", ""));
        showType("submit after shutdown", () -> p.submit(() -> "x"));
        showType("execute after shutdown", () -> { p.execute(() -> { }); return "no"; });
        showType("invoke after shutdown", () -> p.invoke(new Fib(2)));
    }

    static void results() throws Exception {
        ForkJoinPool p = new ForkJoinPool(2);
        show("invoke fib", () -> p.invoke(new Fib(12)));
        show("common invoke", () -> new Fib(12).invoke());
        show("submit fib", () -> p.submit(new Fib(10)).get());
        ForkJoinTask<Integer> submitted = p.submit(new Fib(5));
        show("join", submitted::join);
        show("get", submitted::get);
        show("rawResult", submitted::getRawResult);
        show("flags", () -> submitted.isDone() + " " + submitted.isCompletedNormally() + " "
            + submitted.isCompletedAbnormally() + " " + submitted.isCancelled() + " " + submitted.getException());
        show("exec twice ok", () -> p.invoke(submitted));
        int[] data = new int[1000];
        for (int i = 0; i < data.length; i++) data[i] = i;
        SumAction sum = new SumAction(data, 0, data.length);
        show("action invoke", () -> p.invoke(sum));
        show("action sum", () -> sum.sum);
        show("action raw", sum::getRawResult);
        show("submit callable", () -> p.submit(() -> "c").get());
        show("submit runnable", () -> p.submit(() -> { }).get());
        show("submit runnable result", () -> p.submit(() -> { }, "r").get());
        p.execute(new Fib(3));
        CountDownLatch ran = new CountDownLatch(1);
        p.execute(ran::countDown);
        show("execute ran", () -> ran.await(30, TimeUnit.SECONDS));
        List<Callable<String>> calls = Arrays.asList(() -> "a", () -> "b", () -> "c");
        show("invokeAll", () -> {
            List<String> out = new ArrayList<>();
            for (Future<String> f : p.invokeAll(calls)) out.add(f.get());
            return out;
        });
        show("invokeAny", () -> p.invokeAny(Collections.singletonList(() -> "any")));
        Fib a = new Fib(5), b = new Fib(6), c = new Fib(7);
        ForkJoinTask.invokeAll(a, b, c);
        show("static invokeAll", () -> a.join() + " " + b.join() + " " + c.join());
        List<Fib> list = Arrays.asList(new Fib(3), new Fib(4));
        show("static invokeAll list same", () -> ForkJoinTask.invokeAll(list) == list);
        show("static list", () -> list.get(0).join() + " " + list.get(1).join());
        show("invokeAll null array", () -> { ForkJoinTask.invokeAll((ForkJoinTask<?>[]) null); return "no"; });
        show("invokeAll null element", () -> { ForkJoinTask.invokeAll(new Fib(1), null); return "no"; });
        show("invokeAll null collection", () -> ForkJoinTask.invokeAll((List<Fib>) null));
        show("adapt runnable", () -> ForkJoinTask.adapt(() -> { }).invoke());
        show("adapt value", () -> ForkJoinTask.adapt(() -> { }, "v").invoke());
        show("adapt callable", () -> ForkJoinTask.adapt(() -> "cv").invoke());
        show("adapt null", () -> ForkJoinTask.adapt((Runnable) null));
        show("adapt null callable", () -> ForkJoinTask.adapt((Callable<String>) null));
        p.shutdown();
    }

    static void failures() throws Exception {
        ForkJoinPool p = new ForkJoinPool(2);
        showType("invoke failure", () -> p.invoke(new Boom()));
        showType("submit join failure", () -> p.submit(new Boom()).join());
        showType("submit get failure", () -> p.submit(new Boom()).get());
        ForkJoinTask<String> f = p.submit(new Boom());
        f.quietlyJoin();
        show("quietlyJoin", f::isCompletedAbnormally);
        show("failed flags", () -> f.isDone() + " " + f.isCompletedNormally() + " " + f.isCancelled());
        show("getException type", () -> f.getException().getClass().getName());
        showType("cause of get", () -> { try { return f.get(); } catch (ExecutionException e) { throw e.getCause(); } });
        Boom local = new Boom();
        show("direct invoke, same thread", local::invoke);
        show("direct getException", local::getException);
        show("direct get", local::get);
        ForkJoinTask<String> checked = ForkJoinTask.adapt(() -> { throw new java.io.IOException("io"); });
        show("adapted checked wraps", checked::invoke);
        show("adapted exception", checked::getException);
        ForkJoinTask<?> viaR = ForkJoinTask.adapt(() -> { throw new IllegalArgumentException("r"); });
        show("adapted unchecked", viaR::invoke);
        showType("direct checked get type", () -> { try { return checked.get(); } catch (ExecutionException e) { throw e.getCause(); } });
        Boom q = new Boom();
        q.quietlyInvoke();
        show("quietlyInvoke", q::isCompletedAbnormally);
        Fib cancelled = new Fib(3);
        show("cancel fresh", () -> cancelled.cancel(true));
        show("cancel again", () -> cancelled.cancel(false));
        show("cancel flags", () -> cancelled.isDone() + " " + cancelled.isCancelled() + " " + cancelled.isCompletedAbnormally() + " " + cancelled.isCompletedNormally());
        show("cancel getException", cancelled::getException);
        show("cancel join", cancelled::join);
        show("cancel get", cancelled::get);
        show("cancel invoke", cancelled::invoke);
        Fib done = new Fib(1);
        done.invoke();
        show("cancel done", () -> done.cancel(false));
        Fib man = new Fib(3);
        man.complete(99);
        show("complete", man::join);
        show("complete flags", () -> man.isDone() + " " + man.isCompletedNormally());
        Fib man2 = new Fib(3);
        man2.completeExceptionally(new IllegalArgumentException("given"));
        show("completeExceptionally join", man2::join);
        show("completeExceptionally get", man2::get);
        show("completeExceptionally ex", man2::getException);
        Fib man3 = new Fib(3);
        man3.completeExceptionally(new java.io.IOException("checked"));
        show("checked via join", man3::join);
        show("checked via get", man3::get);
        show("checked ex", man3::getException);
        Fib nul = new Fib(3);
        show("completeExceptionally null", () -> { nul.completeExceptionally(null); return nul.isCompletedAbnormally(); });
        show("after null", nul::getException);
        Fib fresh = new Fib(1);
        show("fresh flags", () -> fresh.isDone() + " " + fresh.getException());
        show("timed get pending", () -> fresh.get(1, TimeUnit.MILLISECONDS));
        fresh.reinitialize();
        show("reinitialize then invoke", fresh::invoke);
        fresh.reinitialize();
        show("reinitialized", fresh::isDone);
        Thread.currentThread().interrupt();
        show("get flagged", () -> new Fib(1).get());
        Thread.currentThread().interrupt();
        show("timed get flagged", () -> new Fib(1).get(1, TimeUnit.SECONDS));
        show("cleared", () -> Thread.currentThread().isInterrupted());
        Fib finished = new Fib(1);
        finished.invoke();
        Thread.currentThread().interrupt();
        show("get flagged, done", finished::get);
        show("cleared after done", () -> Thread.currentThread().isInterrupted());
        Thread.currentThread().interrupt();
        show("timed get flagged, done", () -> finished.get(1, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        show("join flagged, done", finished::join);
        show("flag kept by join", Thread::interrupted);
        p.shutdown();
    }

    /** One worker, so the local task queue order and un-fork results are fixed. */
    static void localQueues() throws Exception {
        for (boolean async : new boolean[] {false, true}) {
            ForkJoinPool p = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, async);
            String tag = async ? "async " : "lifo ";
            show(tag + "mode", p::getAsyncMode);
            show(tag + "order", () -> p.submit(() -> {
                List<String> order = Collections.synchronizedList(new ArrayList<>());
                for (String s : new String[] {"a", "b", "c"}) ForkJoinTask.adapt(() -> order.add(s)).fork();
                ForkJoinTask.helpQuiesce();
                return order;
            }).get());
            show(tag + "local ops", () -> p.submit(() -> {
                ForkJoinTask<?> x = ForkJoinTask.adapt(() -> "x").fork();
                ForkJoinTask<?> y = ForkJoinTask.adapt(() -> "y").fork();
                StringBuilder b = new StringBuilder();
                b.append("queued=").append(ForkJoinTask.getQueuedTaskCount());
                b.append(" peek=").append(nameOf(x, y, ForkJoinTaskPeek.peek()));
                b.append(" tryUnfork=").append(y.tryUnfork());
                b.append(" tryUnfork again=").append(y.tryUnfork());
                b.append(" poll=").append(nameOf(x, y, ForkJoinTaskPeek.poll()));
                ForkJoinTask<?> z = ForkJoinTask.adapt(() -> "z").fork();
                b.append(" pollTask=").append(ForkJoinTaskPeek.pollAny() == z);
                b.append(" empty=").append(ForkJoinTask.getQueuedTaskCount());
                b.append(" surplus>=0=").append(RecursiveTaskProbe.surplus() >= 0);
                b.append(" unforked done=").append(y.isDone());
                return b.toString();
            }).get());
            p.shutdown();
        }
    }

    static String nameOf(ForkJoinTask<?> x, ForkJoinTask<?> y, ForkJoinTask<?> got) {
        return got == x ? "x" : got == y ? "y" : String.valueOf(got);
    }

    /** The protected statics are only reachable from a subclass. */
    abstract static class ForkJoinTaskPeek extends ForkJoinTask<Void> {
        static ForkJoinTask<?> peek() { return peekNextLocalTask(); }
        static ForkJoinTask<?> poll() { return pollNextLocalTask(); }
        static ForkJoinTask<?> pollAny() { return pollTask(); }
    }

    abstract static class RecursiveTaskProbe extends ForkJoinTask<Void> {
        static int surplus() { return getSurplusQueuedTaskCount(); }
    }

    static void blockers() throws Exception {
        int[] calls = new int[2];
        ForkJoinPool.ManagedBlocker done = new ForkJoinPool.ManagedBlocker() {
            @Override public boolean block() { calls[0]++; return true; }
            @Override public boolean isReleasable() { calls[1]++; return false; }
        };
        ForkJoinPool.managedBlock(done);
        show("outside pool blocker", () -> calls[0] + " " + (calls[1] >= 1));
        ForkJoinPool.ManagedBlocker ready = new ForkJoinPool.ManagedBlocker() {
            @Override public boolean block() { calls[0] = 100; return true; }
            @Override public boolean isReleasable() { return true; }
        };
        ForkJoinPool.managedBlock(ready);
        show("releasable skips block", () -> calls[0]);
        show("null blocker", () -> { ForkJoinPool.managedBlock(null); return "no"; });
        ForkJoinPool p = new ForkJoinPool(1);
        show("inside pool blocker", () -> p.submit(() -> {
            int[] c = new int[1];
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override public boolean block() { c[0]++; return c[0] >= 2; }
                @Override public boolean isReleasable() { return false; }
            });
            return c[0];
        }).get());
        p.shutdown();
        int[] seen = new int[1];
        ForkJoinPool handled = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, (t, e) -> seen[0]++, false);
        show("handler set", () -> handled.getUncaughtExceptionHandler() != null);
        handled.shutdown();
        ForkJoinPool custom = new ForkJoinPool(1, pool -> {
            ForkJoinWorkerThread t = new ForkJoinWorkerThread(pool) { };
            t.setName("mine");
            return t;
        }, null, false);
        show("custom factory", () -> custom.submit(() -> Thread.currentThread().getName()).get());
        custom.shutdown();
    }

    public static void main(String[] args) throws Exception {
        poolShape();
        results();
        failures();
        localQueues();
        blockers();
    }
}
