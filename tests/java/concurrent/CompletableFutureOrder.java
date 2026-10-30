package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** Single-thread firing order of dependents, dependents counts, re-entrant completion and deep chains. */
public class CompletableFutureOrder {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static void flat() {
        CompletableFuture<String> s = new CompletableFuture<>();
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "d" + i;
            s.thenRun(() -> log.add(name));
        }
        s.complete("v");
        show("flat", () -> log);
        CompletableFuture<String> m = new CompletableFuture<>();
        List<String> mixed = new ArrayList<>();
        m.thenApply(v -> mixed.add("apply"));
        m.thenAccept(v -> mixed.add("accept"));
        m.whenComplete((v, t) -> mixed.add("when"));
        m.handle((v, t) -> mixed.add("handle"));
        m.exceptionally(t -> { mixed.add("exc"); return "x"; });
        m.thenRun(() -> mixed.add("run"));
        m.complete("v");
        show("mixed kinds", () -> mixed);
        CompletableFuture<String> fails = new CompletableFuture<>();
        List<String> flog = new ArrayList<>();
        fails.thenRun(() -> flog.add("skipped"));
        fails.exceptionally(t -> { flog.add("exc"); return "x"; });
        fails.handle((v, t) -> flog.add("handle"));
        fails.completeExceptionally(new RuntimeException("x"));
        show("on failure", () -> flog);
    }

    /** Four dependents on `f` which log their tag and index. */
    static void fan(CompletableFuture<?> f, List<String> log, String tag) {
        for (int i = 0; i < 4; i++) {
            String name = tag + i;
            f.thenRun(() -> log.add(name));
        }
    }

    /** A stage settled by a call, or by a task an executor ran, fires its own dependents last attached first. */
    static void firstLevel() throws Exception {
        List<String> c = new ArrayList<>();
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.handle((v, t) -> c.add("h0"));
        cancelled.handle((v, t) -> c.add("h1"));
        cancelled.cancel(false);
        show("settled by cancel", () -> c);
        List<String> e = new ArrayList<>();
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.handle((v, t) -> e.add("h0"));
        failed.handle((v, t) -> e.add("h1"));
        failed.completeExceptionally(new RuntimeException("x"));
        show("settled by completeExceptionally", () -> e);
        List<String> o = new ArrayList<>();
        CompletableFuture<String> obtruded = new CompletableFuture<>();
        fan(obtruded, o, "k");
        obtruded.obtrudeValue("v");
        show("settled by obtrudeValue", () -> o);
        List<String> oe = new ArrayList<>();
        CompletableFuture<String> obtrudedEx = new CompletableFuture<>();
        obtrudedEx.handle((v, t) -> oe.add("h0"));
        obtrudedEx.handle((v, t) -> oe.add("h1"));
        obtrudedEx.obtrudeException(new RuntimeException("x"));
        show("settled by obtrudeException", () -> oe);
        List<String> d = new ArrayList<>();
        CompletableFuture<String> viaApply = new CompletableFuture<>();
        CompletableFuture<String> settledEarly = viaApply.thenApply(v -> v);
        fan(settledEarly, d, "k");
        settledEarly.complete("early");
        show("dependent settled by a direct complete", () -> d);

        ExecutorService ex = Executors.newSingleThreadExecutor();
        List<String> sa = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch gate = new CountDownLatch(1);
        CompletableFuture<String> supplied = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); } catch (InterruptedException x) { throw new IllegalStateException(x); }
            return "v";
        }, ex);
        for (int i = 0; i < 4; i++) {
            String name = "k" + i;
            supplied.thenAccept(v -> sa.add(name));
        }
        gate.countDown();
        List<String> ad = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> src = new CompletableFuture<>();
        CompletableFuture<String> viaExecutor = src.thenApplyAsync(v -> v, ex);
        fan(viaExecutor, ad, "k");
        src.complete("v");
        ex.shutdown();
        ex.awaitTermination(30, TimeUnit.SECONDS);
        show("supplyAsync, several thenAccept", () -> sa);
        show("dependent run by an executor", () -> ad);
        List<String> dd = new ArrayList<>();
        CompletableFuture<String> src2 = new CompletableFuture<>();
        CompletableFuture<String> viaDirect = src2.thenApplyAsync(v -> v, Runnable::run);
        fan(viaDirect, dd, "k");
        src2.complete("v");
        show("dependent run by a direct executor", () -> dd);
    }

    /** A stage settled as part of another's firing fires its own dependents first attached first. */
    static void deeper() {
        List<String> a = new ArrayList<>();
        CompletableFuture<String> s = new CompletableFuture<>();
        fan(s.thenApply(v -> v), a, "k");
        s.complete("v");
        show("below thenApply", () -> a);
        List<String> b = new ArrayList<>();
        CompletableFuture<String> x = new CompletableFuture<>(), y = new CompletableFuture<>();
        fan(x.thenCombine(y, (p, q) -> p), b, "k");
        x.complete("a");
        y.complete("b");
        show("below thenCombine", () -> b);
        List<String> c = new ArrayList<>();
        CompletableFuture<String> u = new CompletableFuture<>(), w = new CompletableFuture<>();
        fan(CompletableFuture.allOf(u, w), c, "k");
        u.complete("a");
        w.complete("b");
        show("below allOf", () -> c);
        List<String> an = new ArrayList<>();
        CompletableFuture<String> u2 = new CompletableFuture<>(), w2 = new CompletableFuture<>();
        fan(CompletableFuture.anyOf(u2, w2), an, "k");
        u2.complete("a");
        show("below anyOf", () -> an);
        List<String> e = new ArrayList<>();
        CompletableFuture<String> u3 = new CompletableFuture<>(), w3 = new CompletableFuture<>();
        fan(u3.applyToEither(w3, v -> v), e, "k");
        u3.complete("a");
        show("below applyToEither", () -> e);
        List<String> d = new ArrayList<>();
        CompletableFuture<String> outer = new CompletableFuture<>(), inner = new CompletableFuture<>();
        fan(outer.thenCompose(v -> inner), d, "k");
        outer.complete("v");
        d.add("|");
        inner.complete("i");
        show("below a pending compose", () -> d);
        List<String> f = new ArrayList<>();
        CompletableFuture<String> failed = new CompletableFuture<>();
        CompletableFuture<String> skip = failed.thenApply(v -> v);
        skip.handle((v, t) -> f.add("h0"));
        skip.handle((v, t) -> f.add("h1"));
        skip.handle((v, t) -> f.add("h2"));
        failed.completeExceptionally(new RuntimeException("x"));
        show("below a failed thenApply", () -> f);

        List<String> log = new ArrayList<>();
        CompletableFuture<String> root = new CompletableFuture<>();
        for (String top : new String[] {"a", "b", "c"}) {
            CompletableFuture<Void> t = root.thenRun(() -> log.add(top));
            for (int i = 0; i < 3; i++) {
                String mid = top + i;
                CompletableFuture<Void> m = t.thenRun(() -> log.add(mid));
                for (int j = 0; j < 2; j++) {
                    String leaf = mid + j;
                    CompletableFuture<Void> l = m.thenRun(() -> log.add(leaf));
                    if (j == 1) l.thenRun(() -> log.add(leaf + "!"));
                }
            }
        }
        root.complete("v");
        show("whole tree", () -> log);
        List<String> chain = new ArrayList<>();
        CompletableFuture<String> c2 = new CompletableFuture<>();
        CompletableFuture<Void> z = c2.thenRun(() -> chain.add("x"));
        z.thenRun(() -> chain.add("x1")).thenRun(() -> chain.add("x2"));
        c2.thenRun(() -> chain.add("y"));
        c2.complete("v");
        show("chain and sibling", () -> chain);
    }

    static void reentrant() {
        List<String> log = new ArrayList<>();
        CompletableFuture<String> s = new CompletableFuture<>();
        s.thenRun(() -> log.add("first attached"));
        s.thenRun(() -> {
            log.add("attaching inside");
            s.thenRun(() -> log.add("attached inside, source done"));
            log.add("after attach");
        });
        s.thenRun(() -> log.add("last attached"));
        s.complete("v");
        show("attach during firing", () -> log);
        List<String> nested = new ArrayList<>();
        CompletableFuture<String> a = new CompletableFuture<>(), b = new CompletableFuture<>();
        b.thenRun(() -> nested.add("b dep1"));
        b.thenRun(() -> nested.add("b dep2"));
        a.thenRun(() -> nested.add("a dep1"));
        a.thenRun(() -> { nested.add("completing b"); b.complete("x"); nested.add("b completed"); });
        a.thenRun(() -> nested.add("a dep3"));
        a.complete("v");
        show("complete another inside", () -> nested);
        List<String> self = new ArrayList<>();
        CompletableFuture<String> me = new CompletableFuture<>();
        me.thenAccept(v -> self.add("saw " + v + " complete=" + me.complete("again")));
        me.complete("v");
        show("complete self inside", () -> self);
        show("getNow inside", () -> {
            CompletableFuture<String> g = new CompletableFuture<>();
            String[] r = new String[1];
            g.thenRun(() -> r[0] = g.getNow("absent") + " " + g.isDone());
            g.complete("v");
            return r[0];
        });
        show("dependent throws, others run", () -> {
            List<String> l = new ArrayList<>();
            CompletableFuture<String> g = new CompletableFuture<>();
            g.thenRun(() -> l.add("before"));
            g.thenRun(() -> { throw new IllegalStateException("x"); });
            g.thenRun(() -> l.add("after"));
            boolean c = g.complete("v");
            return c + " " + l;
        });
    }

    static void counts() {
        CompletableFuture<String> s = new CompletableFuture<>();
        show("none", s::getNumberOfDependents);
        CompletableFuture<Void> d1 = s.thenRun(() -> { });
        show("one", s::getNumberOfDependents);
        show("one toString", () -> s);
        s.thenRun(() -> { });
        s.thenApply(v -> v);
        show("three", s::getNumberOfDependents);
        show("three toString", () -> s);
        d1.thenRun(() -> { });
        show("grandchild not counted", s::getNumberOfDependents);
        show("child count", d1::getNumberOfDependents);
        s.complete("v");
        show("after completion", s::getNumberOfDependents);
        show("after completion toString", () -> s);
        s.thenRun(() -> { });
        show("attached after done", s::getNumberOfDependents);
        CompletableFuture<String> a = new CompletableFuture<>(), b = new CompletableFuture<>();
        a.thenCombine(b, (x, y) -> x);
        show("both counts each", () -> a.getNumberOfDependents() + " " + b.getNumberOfDependents());
        a.complete("v");
        show("both after first", () -> a.getNumberOfDependents() + " " + b.getNumberOfDependents());
        CompletableFuture<String> c = new CompletableFuture<>(), e = new CompletableFuture<>();
        c.applyToEither(e, x -> x);
        show("either counts each", () -> c.getNumberOfDependents() + " " + e.getNumberOfDependents());
        CompletableFuture<String> self = new CompletableFuture<>();
        self.thenCompose(v -> CompletableFuture.completedFuture(v));
        show("compose counts", self::getNumberOfDependents);
        CompletableFuture<String> x = new CompletableFuture<>();
        x.whenComplete((v, t) -> { });
        x.handle((v, t) -> v);
        x.exceptionally(t -> "z");
        show("handlers count", x::getNumberOfDependents);
        CompletableFuture<String> q = new CompletableFuture<>();
        CompletableFuture<String> cd = q.thenApply(v -> v);
        cd.cancel(false);
        show("cancelled dependent", () -> q.getNumberOfDependents() >= 0);
    }

    /** Very long chains settle without exhausting the stack, on any thread. */
    static void deep() throws Exception {
        int depth = 20000, width = 5000;
        CompletableFuture<Integer> head = new CompletableFuture<>();
        CompletableFuture<Integer> tail = head;
        for (int i = 0; i < depth; i++) tail = tail.thenApply(v -> v + 1);
        head.complete(0);
        CompletableFuture<Integer> end = tail;
        show("deep chain", end::join);
        CompletableFuture<String> fan = new CompletableFuture<>();
        int[] count = new int[1];
        for (int i = 0; i < width; i++) fan.thenRun(() -> count[0]++);
        fan.complete("v");
        show("wide fan", () -> count[0]);
        CompletableFuture<Integer> src = new CompletableFuture<>();
        CompletableFuture<Integer> t2 = src;
        for (int i = 0; i < depth; i++) t2 = t2.thenCompose(v -> CompletableFuture.completedFuture(v + 1));
        src.complete(0);
        CompletableFuture<Integer> composed = t2;
        show("deep compose", composed::join);
        CompletableFuture<Integer> fsrc = new CompletableFuture<>();
        CompletableFuture<Integer> ft = fsrc;
        for (int i = 0; i < depth; i++) ft = ft.thenApply(v -> v + 1);
        fsrc.completeExceptionally(new RuntimeException("deep"));
        CompletableFuture<Integer> failed = ft;
        show("deep failure", () -> {
            try { failed.join(); return "no"; } catch (Exception e) { return e + " cause depth " + depthOf(e); }
        });
    }

    static int depthOf(Throwable t) {
        int d = 0;
        while (t != null) { d++; t = t.getCause(); }
        return d;
    }

    public static void main(String[] args) throws Exception {
        flat();
        firstLevel();
        deeper();
        reentrant();
        counts();
        deep();
    }
}
