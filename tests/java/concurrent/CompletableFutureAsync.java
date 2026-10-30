// VMARGS: -Djava.util.concurrent.ForkJoinPool.common.parallelism=3
package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;

/** Async variants: where each runs (given executor, common pool), the marker interface, rejection. */
public class CompletableFutureAsync {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("worker-[0-9]+", "worker-N");
    }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + chain(t)); }
    }

    static String chain(Throwable t) {
        if (t == null) return "null";
        String s = t.getClass().getSimpleName() + "(" + t.getMessage() + ")";
        return t.getCause() == null ? s : s + " <- " + chain(t.getCause());
    }

    static String full(CompletableFuture<?> f) {
        if (!f.isDone()) return "pending";
        StringBuilder b = new StringBuilder(f.isCancelled() ? "cancelled" : f.isCompletedExceptionally() ? "failed" : "ok");
        try { Object v = f.join(); b.append(" join=").append(v); } catch (Throwable t) { b.append(" join!").append(chain(t)); }
        return b.toString();
    }

    /** Runs tasks on one named thread, and counts and classifies them. */
    static final class Named implements Executor {
        final ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "named"));
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        @Override public void execute(Runnable r) {
            seen.add("marker=" + (r instanceof CompletableFuture.AsynchronousCompletionTask));
            pool.execute(r);
        }
    }

    static final Named NAMED = new Named();

    static String where() {
        Thread t = Thread.currentThread();
        return t.getName();
    }

    /** Every async method with a given executor (null: its default). */
    static Map<String, Function<CompletableFuture<String>, CompletableFuture<String>>> ops(Executor e) {
        CompletableFuture<String> o = CompletableFuture.completedFuture("o");
        Map<String, Function<CompletableFuture<String>, CompletableFuture<String>>> m = new LinkedHashMap<>();
        String[] box = new String[1];
        m.put("thenApplyAsync", s -> e == null ? s.thenApplyAsync(v -> v + "@" + where()) : s.thenApplyAsync(v -> v + "@" + where(), e));
        m.put("thenAcceptAsync", s -> (e == null ? s.thenAcceptAsync(v -> box[0] = v + "@" + where()) : s.thenAcceptAsync(v -> box[0] = v + "@" + where(), e)).thenApply(x -> box[0]));
        m.put("thenRunAsync", s -> (e == null ? s.thenRunAsync(() -> box[0] = "@" + where()) : s.thenRunAsync(() -> box[0] = "@" + where(), e)).thenApply(x -> box[0]));
        m.put("thenCombineAsync", s -> e == null ? s.thenCombineAsync(o, (x, y) -> x + y + "@" + where()) : s.thenCombineAsync(o, (x, y) -> x + y + "@" + where(), e));
        m.put("thenAcceptBothAsync", s -> (e == null ? s.thenAcceptBothAsync(o, (x, y) -> box[0] = x + y + "@" + where()) : s.thenAcceptBothAsync(o, (x, y) -> box[0] = x + y + "@" + where(), e)).thenApply(x -> box[0]));
        m.put("runAfterBothAsync", s -> (e == null ? s.runAfterBothAsync(o, () -> box[0] = "@" + where()) : s.runAfterBothAsync(o, () -> box[0] = "@" + where(), e)).thenApply(x -> box[0]));
        m.put("applyToEitherAsync", s -> e == null ? s.applyToEitherAsync(new CompletableFuture<String>(), x -> x + "@" + where()) : s.applyToEitherAsync(new CompletableFuture<String>(), x -> x + "@" + where(), e));
        m.put("acceptEitherAsync", s -> (e == null ? s.acceptEitherAsync(new CompletableFuture<String>(), x -> box[0] = x + "@" + where()) : s.acceptEitherAsync(new CompletableFuture<String>(), x -> box[0] = x + "@" + where(), e)).thenApply(x -> box[0]));
        m.put("runAfterEitherAsync", s -> (e == null ? s.runAfterEitherAsync(new CompletableFuture<String>(), () -> box[0] = "@" + where()) : s.runAfterEitherAsync(new CompletableFuture<String>(), () -> box[0] = "@" + where(), e)).thenApply(x -> box[0]));
        m.put("thenComposeAsync", s -> e == null ? s.thenComposeAsync(v -> CompletableFuture.completedFuture(v + "@" + where())) : s.thenComposeAsync(v -> CompletableFuture.completedFuture(v + "@" + where()), e));
        m.put("whenCompleteAsync", s -> (e == null ? s.whenCompleteAsync((v, t) -> box[0] = v + "@" + where()) : s.whenCompleteAsync((v, t) -> box[0] = v + "@" + where(), e)).thenApply(x -> box[0]));
        m.put("handleAsync", s -> e == null ? s.handleAsync((v, t) -> v + "@" + where()) : s.handleAsync((v, t) -> v + "@" + where(), e));
        return m;
    }

    static void withExecutor(String tag, Executor e) {
        for (Map.Entry<String, Function<CompletableFuture<String>, CompletableFuture<String>>> op : ops(e).entrySet()) {
            CompletableFuture<String> done = CompletableFuture.completedFuture("v");
            show(tag + " " + op.getKey() + " on done", () -> op.getValue().apply(done).get(30, TimeUnit.SECONDS));
            CompletableFuture<String> pending = new CompletableFuture<>();
            CompletableFuture<String> d = op.getValue().apply(pending);
            pending.complete("p");
            show(tag + " " + op.getKey() + " on pending", () -> d.get(30, TimeUnit.SECONDS));
        }
    }

    static void statics() throws Exception {
        show("supplyAsync on executor", () -> CompletableFuture.supplyAsync(CompletableFutureAsync::where, NAMED).get());
        show("runAsync on executor", () -> {
            String[] box = new String[1];
            CompletableFuture.runAsync(() -> box[0] = where(), NAMED).get();
            return box[0];
        });
        show("runAsync value", () -> CompletableFuture.runAsync(() -> { }, NAMED).get());
        show("supply fails get", () -> CompletableFuture.supplyAsync(() -> { throw new IllegalStateException("s"); }, NAMED).get());
        show("supply fails join", () -> CompletableFuture.supplyAsync(() -> { throw new IllegalStateException("s"); }, NAMED).join());
        show("run fails get", () -> CompletableFuture.runAsync(() -> { throw new IllegalStateException("r"); }, NAMED).get());
        show("supply throws CE", () -> CompletableFuture.supplyAsync(() -> { throw new CompletionException(new java.io.IOException("x")); }, NAMED).get());
        NAMED.seen.clear();
        CompletableFuture.supplyAsync(() -> "x", NAMED).get();
        CompletableFuture.runAsync(() -> { }, NAMED).get();
        show("marker", () -> NAMED.seen);

        show("supply on common", () -> {
            CompletableFuture<String> f = CompletableFuture.supplyAsync(CompletableFutureAsync::where);
            return f.get(30, TimeUnit.SECONDS);
        });
        show("common thread", () -> CompletableFuture.supplyAsync(() -> {
            Thread t = Thread.currentThread();
            return t.isDaemon() + " worker=" + (t instanceof ForkJoinWorkerThread)
                + " common=" + (ForkJoinTask.getPool() == ForkJoinPool.commonPool());
        }).get(30, TimeUnit.SECONDS));
        show("default marker", () -> CompletableFuture.supplyAsync(() -> "m").get(30, TimeUnit.SECONDS));

        show("rejecting supplyAsync", () -> {
            Executor no = r -> { throw new RejectedExecutionException("full"); };
            return CompletableFuture.supplyAsync(() -> "x", no);
        });
        show("rejecting runAsync", () -> {
            Executor no = r -> { throw new RejectedExecutionException("full"); };
            return CompletableFuture.runAsync(() -> { }, no);
        });
        show("rejecting dependent on done", () -> {
            Executor no = r -> { throw new RejectedExecutionException("full"); };
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            return full(s.thenApplyAsync(v -> v, no));
        });
        show("rejecting dependent on pending", () -> {
            Executor no = r -> { throw new RejectedExecutionException("full"); };
            CompletableFuture<String> s = new CompletableFuture<>();
            CompletableFuture<String> d = s.thenApplyAsync(v -> v, no);
            boolean ok = s.complete("v");
            return ok + " " + full(s) + " / " + full(d);
        });
        show("failed source skips executor", () -> {
            int[] uses = new int[1];
            Executor counting = r -> { uses[0]++; r.run(); };
            CompletableFuture<String> s = new CompletableFuture<>();
            s.completeExceptionally(new RuntimeException("x"));
            CompletableFuture<String> a = s.thenApplyAsync(v -> v, counting);
            CompletableFuture<String> h = s.handleAsync((v, t) -> "h", counting);
            CompletableFuture<String> w = s.whenCompleteAsync((v, t) -> { }, counting);
            return uses[0] + " " + full(a) + " / " + full(h) + " / " + full(w);
        });
        show("one executor call per dependent", () -> {
            int[] uses = new int[1];
            Executor counting = r -> { uses[0]++; r.run(); };
            CompletableFuture<String> s = new CompletableFuture<>();
            s.thenApplyAsync(v -> v, counting);
            s.thenApplyAsync(v -> v, counting);
            s.complete("v");
            return uses[0];
        });
        show("direct executor, thread", () -> CompletableFuture.supplyAsync(CompletableFutureAsync::where, Runnable::run).get());
        show("direct executor done at return", () -> CompletableFuture.supplyAsync(() -> "x", Runnable::run).isDone());
    }

    static void threadsOfDependents() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CompletableFuture<String> s = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return "v";
        }, NAMED);
        CompletableFuture<String> sync = s.thenApply(v -> where());
        gate.countDown();
        show("sync dependent runs where source finished", sync::join);
        show("attached after done runs in caller", () -> CompletableFuture.completedFuture("x").thenApply(v -> where()).join());
        CompletableFuture<String> settled = new CompletableFuture<>();
        CompletableFuture<String> viaThread = settled.thenApply(v -> where());
        Thread t = new Thread(() -> settled.complete("v"), "completer");
        t.start();
        t.join();
        show("runs on completing thread", viaThread::join);
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        CompletableFuture<String> viaCancel = cancelled.handle((v, e) -> where());
        Thread c = new Thread(() -> cancelled.cancel(false), "canceller");
        c.start();
        c.join();
        show("runs on cancelling thread", viaCancel::join);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean[] interrupted = new boolean[1];
        CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { interrupted[0] = true; }
            return "late result";
        }, NAMED);
        entered.await();
        show("cancel while running", () -> running.cancel(true));
        show("result is cancellation", () -> full(running));
        release.countDown();
        NAMED.pool.submit(() -> { }).get();
        show("running task was not interrupted", () -> interrupted[0]);
        show("still cancelled", running::isCancelled);
    }

    static void marker() {
        show("marker declared", () -> CompletableFuture.AsynchronousCompletionTask.class.isInterface());
        show("marker empty", () -> CompletableFuture.AsynchronousCompletionTask.class.getDeclaredMethods().length);
    }

    public static void main(String[] args) throws Exception {
        withExecutor("named", NAMED);
        statics();
        threadsOfDependents();
        marker();
        NAMED.pool.shutdown();
        withExecutor("default", null);
    }
}
