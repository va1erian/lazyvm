package concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** ExecutorService.invokeAll/invokeAny, AbstractExecutorService and ExecutorCompletionService. */
public class InvokeAllAny {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static Callable<String> value(String v) { return () -> v; }

    static Callable<String> fails(String m) { return () -> { throw new IllegalStateException(m); }; }

    static Callable<String> slow(CountDownLatch gate, String v) { return () -> { gate.await(); return v; }; }

    static String outcomes(List<Future<String>> fs) {
        StringBuilder b = new StringBuilder();
        for (Future<String> f : fs) {
            b.append(f.isDone() ? "D" : "P").append(f.isCancelled() ? "C:" : ":");
            try { b.append(f.get(0, TimeUnit.SECONDS)); }
            catch (Exception e) { b.append(n(e)); }
            b.append("; ");
        }
        return b.toString();
    }

    static void invokeAll(ExecutorService p) throws Exception {
        show("all order", () -> outcomes(p.invokeAll(Arrays.asList(value("a"), value("b"), fails("c"), value("d")))));
        show("all empty", () -> p.invokeAll(Collections.<Callable<String>>emptyList()));
        show("all null", () -> p.invokeAll(null));
        show("all null element", () -> p.invokeAll(Arrays.asList(value("a"), null)));
        show("all timed null", () -> p.invokeAll(null, 1, TimeUnit.SECONDS));
        show("all timed null unit", () -> p.invokeAll(Arrays.asList(value("a")), 1, null));
        show("all timed ok", () -> outcomes(p.invokeAll(Arrays.asList(value("a"), value("b")), 30, TimeUnit.SECONDS)));
        CountDownLatch never = new CountDownLatch(1);
        show("all timed out cancels", () -> outcomes(p.invokeAll(Arrays.asList(slow(never, "x"), slow(never, "y")), 30, TimeUnit.MILLISECONDS)));
        show("all timed zero", () -> outcomes(p.invokeAll(Arrays.asList(slow(never, "x")), 0, TimeUnit.SECONDS)));
        Thread.currentThread().interrupt();
        show("all flagged", () -> p.invokeAll(Arrays.asList(slow(never, "x"))));
        Thread.interrupted();
    }

    static void invokeAny(ExecutorService p) throws Exception {
        show("any single", () -> p.invokeAny(Arrays.asList(value("a"))));
        show("any failing then ok", () -> p.invokeAny(Arrays.asList(fails("x"), value("ok"), fails("y"))));
        show("any one failed", () -> p.invokeAny(Arrays.asList(fails("only"))));
        show("any empty", () -> p.invokeAny(Collections.<Callable<String>>emptyList()));
        show("any null", () -> p.invokeAny(null));
        // Null first: behind a task that finishes quickly it may never be reached.
        show("any null element", () -> p.invokeAny(Arrays.asList(null, value("a"))));
        show("any timed null", () -> p.invokeAny(null, 1, TimeUnit.SECONDS));
        show("any timed empty", () -> p.invokeAny(Collections.<Callable<String>>emptyList(), 1, TimeUnit.SECONDS));
        show("any timed ok", () -> p.invokeAny(Arrays.asList(value("a")), 30, TimeUnit.SECONDS));
        CountDownLatch never = new CountDownLatch(1);
        show("any timeout", () -> p.invokeAny(Arrays.asList(slow(never, "x")), 20, TimeUnit.MILLISECONDS));
        show("any timed null unit", () -> p.invokeAny(Arrays.asList(value("a")), 1, null));
        CountDownLatch entered = new CountDownLatch(1);
        boolean[] seen = new boolean[1];
        Callable<String> canceled = () -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { seen[0] = true; }
            return "cancelled one";
        };
        Callable<String> winner = () -> { entered.await(); return "winner"; };
        ExecutorService two = Executors.newFixedThreadPool(2);
        show("any winner", () -> two.invokeAny(Arrays.asList(canceled, winner)));
        two.shutdown();
        show("any terminated", () -> two.awaitTermination(30, TimeUnit.SECONDS));
        show("any interrupted the loser", () -> seen[0]);
    }

    /** Runs each task on the caller: shows that the abstract base routes through execute and newTaskFor. */
    static class Direct extends AbstractExecutorService {
        final List<String> log = new ArrayList<>();
        boolean down;
        @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> c) {
            log.add("newTaskFor callable");
            return super.newTaskFor(c);
        }
        @Override protected <T> RunnableFuture<T> newTaskFor(Runnable r, T v) {
            log.add("newTaskFor runnable " + v);
            return super.newTaskFor(r, v);
        }
        @Override public void execute(Runnable r) { log.add("execute"); r.run(); }
        @Override public void shutdown() { down = true; }
        @Override public List<Runnable> shutdownNow() { down = true; return new ArrayList<>(); }
        @Override public boolean isShutdown() { return down; }
        @Override public boolean isTerminated() { return down; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return down; }
    }

    static void abstractBase() throws Exception {
        Direct d = new Direct();
        show("submit callable", () -> d.submit(value("v")).get());
        show("submit runnable", () -> d.submit(() -> { }).get());
        show("submit runnable result", () -> d.submit(() -> { }, "r").get());
        show("submit type", () -> d.submit(value("v")) instanceof RunnableFuture);
        show("submit null", () -> d.submit((Callable<String>) null));
        show("invokeAll", () -> outcomes(d.invokeAll(Arrays.asList(value("a"), value("b")))));
        show("invokeAny", () -> d.invokeAny(Arrays.asList(value("a"), value("b"))));
        show("log", () -> d.log);
        Executor rejecting = r -> { throw new RejectedExecutionException("no"); };
        show("completion service reject", () -> new ExecutorCompletionService<String>(rejecting).submit(value("x")));
    }

    static void completionService() throws Exception {
        show("null executor", () -> new ExecutorCompletionService<String>(null));
        show("null queue", () -> new ExecutorCompletionService<String>(Runnable::run, null));
        ExecutorService single = Executors.newSingleThreadExecutor();
        CompletionService<String> cs = new ExecutorCompletionService<>(single);
        show("poll empty", cs::poll);
        show("timed poll empty", () -> cs.poll(1, TimeUnit.MILLISECONDS));
        show("submit null", () -> cs.submit((Callable<String>) null));
        show("submit null runnable", () -> cs.submit((Runnable) null, "v"));
        Future<String> a = cs.submit(value("a"));
        Future<String> b = cs.submit(() -> { }, "b");
        Future<String> c = cs.submit(fails("c"));
        show("take first", () -> cs.take().get());
        show("take second", () -> cs.take().get());
        Future<String> third = cs.take();
        show("take third same future", () -> third == c);
        show("take third", third::get);
        show("drained", cs::poll);
        CountDownLatch gate = new CountDownLatch(1);
        Future<String> blocker = cs.submit(slow(gate, "late"));
        blocker.cancel(true);
        show("cancelled is delivered", () -> cs.take().isCancelled());
        single.shutdown();
        BlockingQueue<Future<String>> q = new LinkedBlockingQueue<>();
        CompletionService<String> direct = new ExecutorCompletionService<>(Runnable::run, q);
        direct.submit(value("x"));
        show("own queue used", () -> q.size());
        show("own queue value", () -> q.take().get());
        Thread.currentThread().interrupt();
        show("take flagged", () -> direct.take());
        Thread.interrupted();
    }

    public static void main(String[] args) throws Exception {
        ExecutorService p = Executors.newFixedThreadPool(2);
        invokeAll(p);
        invokeAny(p);
        p.shutdownNow();
        p.awaitTermination(30, TimeUnit.SECONDS);
        show("invokeAll after shutdown", () -> {
            try { return p.invokeAll(Arrays.asList(value("a"))); }
            catch (RejectedExecutionException e) { return "rejected"; }
        });
        abstractBase();
        completionService();
    }
}
