package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** On one thread: counts during firing, a settled stage's unfired dependents, what a task reads, allOf and anyOf's shape, subclasses. */
public class CompletableFutureEdges {
    interface Body { Object run() throws Throwable; }
    interface Inside { void go(CompletableFuture<String> s, List<String> log) throws Exception; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + String.valueOf(body.run()).replaceAll("@[0-9a-f]+", "@H")); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static <T> CompletableFuture<T> cf() { return new CompletableFuture<T>(); }

    static String counts(CompletableFuture<?>... fs) {
        StringBuilder b = new StringBuilder();
        for (CompletableFuture<?> f : fs) b.append(f.getNumberOfDependents()).append(' ');
        return b.toString().trim();
    }

    static String state(CompletableFuture<?> f) {
        if (!f.isDone()) return "pending";
        try { return "ok " + f.join(); } catch (Throwable t) { return "failed " + t; }
    }

    static void countsWhileFiring() {
        show("count falls while firing", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int k = i;
                s.thenRun(() -> log.add("k" + k + "=" + s.getNumberOfDependents() + " " + s));
            }
            s.complete("v");
            return log;
        });
        show("count below is 0 at once", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> m = s.thenApply(v -> v);
            List<String> log = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int k = i;
                m.thenRun(() -> log.add("k" + k + "=" + m.getNumberOfDependents()));
            }
            s.complete("v");
            return log;
        });
        show("attached inside never counts", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("a=" + s.getNumberOfDependents()));
            s.thenRun(() -> {
                s.thenRun(() -> log.add("in=" + s.getNumberOfDependents()));
                log.add("b=" + s.getNumberOfDependents());
            });
            s.thenRun(() -> log.add("c=" + s.getNumberOfDependents()));
            s.complete("v");
            return log;
        });
    }

    static void deadEntries() {
        show("settled dependent stays counted", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApply(v -> v);
            CompletableFuture<String> c = s.thenApply(v -> v);
            d.complete("x");
            c.cancel(false);
            s.thenRun(() -> { });
            return counts(s);
        });
        show("settled dependent's function is dropped", () -> {
            int[] ran = new int[2];
            Executor e = r -> { ran[0]++; r.run(); };
            CompletableFuture<String> s = cf();
            CompletableFuture<String> a = s.thenApply(v -> { ran[1]++; return v; });
            CompletableFuture<String> b = s.thenApplyAsync(v -> { ran[1]++; return v; }, e);
            a.complete("x");
            b.complete("y");
            s.complete("v");
            return ran[0] + " " + ran[1] + " " + a.join() + b.join();
        });
        show("either leaves the other source", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            b.thenRun(() -> { });
            a.applyToEither(b, v -> v);
            b.thenRun(() -> { });
            String before = counts(a, b);
            a.complete("x");
            return before + " / " + counts(a, b);
        });
        show("either, other side settled first", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            a.applyToEither(b, v -> v);
            b.complete("x");
            return counts(a, b);
        });
        show("either with a settled dependent leaves too", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> d = a.applyToEither(b, v -> v);
            d.complete("x");
            String before = counts(a, b);
            a.complete("y");
            return before + " / " + counts(a, b);
        });
        show("both with a settled dependent stays", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            a.thenCombine(b, (p, q) -> p).complete("x");
            a.complete("y");
            return counts(a, b);
        });
        show("either leaves after its function", () -> {
            String[] seen = new String[1];
            CompletableFuture<String> a = cf(), b = cf();
            a.applyToEither(b, v -> { seen[0] = counts(a, b); return v; });
            a.complete("x");
            return seen[0] + " / " + counts(a, b);
        });
        show("either task leaves when it runs", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> a = cf(), b = cf();
            a.applyToEitherAsync(b, v -> v, q::add);
            a.complete("x");
            String before = q.size() + " " + counts(a, b);
            q.get(0).run();
            return before + " / " + counts(a, b);
        });
        show("either rejected leaves", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            a.applyToEitherAsync(b, v -> v, r -> { throw new RejectedExecutionException(); });
            a.complete("x");
            return counts(a, b);
        });
        show("either settled at attach joins nothing", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            b.complete("x");
            a.applyToEither(b, v -> v);
            return counts(a, b);
        });
        show("both settled on one side joins the other", () -> {
            CompletableFuture<String> a = cf(), b = cf(), c = cf(), d = cf();
            a.complete("x");
            a.thenCombine(b, (p, q) -> p);
            d.complete("x");
            c.thenCombine(d, (p, q) -> p);
            return counts(a, b, c, d);
        });
        show("self sources count once", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            a.thenCombine(a, (p, q) -> p);
            b.applyToEither(b, p -> p);
            return counts(a, b);
        });
    }

    /** Four dependents on a stage, the third attached doing `in`. */
    static void inside(String label, Inside in) {
        show("inside " + label, () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("k0"));
            s.thenRun(() -> log.add("k1"));
            s.thenRun(() -> {
                log.add("[");
                try { in.go(s, log); } catch (Exception e) { log.add(e.toString()); }
                log.add("]");
            });
            s.thenRun(() -> log.add("k3"));
            s.complete("v");
            return log;
        });
    }

    /** Which calls on a settled stage go on with what its list still holds. */
    static void helping() {
        inside("complete", (s, l) -> l.add("c=" + s.complete("again")));
        inside("completeExceptionally", (s, l) -> l.add("c=" + s.completeExceptionally(new Error())));
        inside("cancel", (s, l) -> l.add("c=" + s.cancel(false)));
        inside("obtrudeValue", (s, l) -> s.obtrudeValue("o"));
        inside("obtrudeException", (s, l) -> s.obtrudeException(new Error()));
        inside("join", (s, l) -> l.add("c=" + s.join()));
        inside("get", (s, l) -> l.add("c=" + s.get()));
        inside("timed get", (s, l) -> l.add("c=" + s.get(1, TimeUnit.SECONDS)));
        inside("getNow", (s, l) -> l.add("c=" + s.getNow("x")));
        inside("count", (s, l) -> l.add("c=" + s.getNumberOfDependents()));
        inside("sync thenApply", (s, l) -> s.thenApply(v -> l.add("attached")));
        inside("sync thenCombine", (s, l) -> s.thenCombine(CompletableFuture.completedFuture("o"), (p, q) -> l.add("attached")));
        inside("sync throwing", (s, l) -> s.thenApply(v -> { throw new Error(); }));
        inside("sync applyToEither", (s, l) -> s.applyToEither(new CompletableFuture<String>(), p -> l.add("attached")));
        inside("sync thenCompose", (s, l) -> s.thenCompose(p -> { l.add("attached"); return CompletableFuture.completedFuture("x"); }));
        inside("allOf", (s, l) -> CompletableFuture.allOf(s).thenRun(() -> l.add("attached")));
        inside("anyOf", (s, l) -> CompletableFuture.anyOf(s).thenRun(() -> l.add("attached")));
        inside("async direct", (s, l) -> s.thenApplyAsync(p -> l.add("attached"), Runnable::run));
        inside("async queued", (s, l) -> s.thenApplyAsync(p -> l.add("attached"), r -> { }));
        inside("async rejected", (s, l) -> s.thenApplyAsync(p -> p, r -> { throw new RejectedExecutionException(); }));
        inside("async both rejected", (s, l) -> s.thenCombineAsync(CompletableFuture.completedFuture("z"), (p, q) -> p, r -> { throw new RejectedExecutionException(); }));
        show("inside async skipped on failure", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.whenComplete((v, t) -> log.add("k0"));
            s.whenComplete((v, t) -> {
                log.add("[");
                s.thenApplyAsync(w -> w, r -> log.add("executor"));
                log.add("]");
            });
            s.completeExceptionally(new Error());
            return log;
        });
        show("complete from another stage's firing", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("k0"));
            s.thenRun(() -> log.add("k1"));
            CompletableFuture<String> m = s.thenApply(v -> v);
            m.thenRun(() -> { log.add("[deep"); s.complete("x"); log.add("deep]"); });
            s.complete("v");
            return log;
        });
    }

    static void tasks() {
        show("task end, source then own", () -> {
            List<String> log = new ArrayList<>();
            CompletableFuture<String> s = cf();
            s.thenRun(() -> log.add("x0"));
            s.thenRun(() -> log.add("x1"));
            CompletableFuture<String> d = s.thenApplyAsync(v -> { log.add("body"); return v; }, Runnable::run);
            d.thenRun(() -> log.add("k0"));
            d.thenRun(() -> log.add("k1"));
            s.complete("v");
            return log;
        });
        show("task below a firing", () -> {
            List<String> log = new ArrayList<>();
            CompletableFuture<String> r = cf();
            CompletableFuture<String> s = r.thenApply(v -> v);
            s.thenRun(() -> log.add("x0"));
            CompletableFuture<String> d = s.thenApplyAsync(v -> { log.add("body"); return v; }, Runnable::run);
            d.thenRun(() -> log.add("k0"));
            d.thenRun(() -> log.add("k1"));
            s.thenRun(() -> log.add("x1"));
            r.complete("v");
            return log;
        });
        for (boolean receiverA : new boolean[] {true, false}) {
            for (boolean combine : new boolean[] {true, false}) {
                show("task helps other first, receiver " + (receiverA ? "a" : "b") + (combine ? " both" : " either"), () -> {
                    List<String> log = new ArrayList<>();
                    CompletableFuture<String> a = cf(), b = cf();
                    a.thenRun(() -> log.add("a0"));
                    a.thenRun(() -> log.add("a1"));
                    b.thenRun(() -> log.add("b0"));
                    a.thenRun(() -> b.complete("x"));
                    CompletableFuture<String> recv = receiverA ? a : b, other = receiverA ? b : a;
                    CompletableFuture<String> d = combine
                        ? recv.thenCombineAsync(other, (p, q) -> { log.add("body"); return p; }, Runnable::run)
                        : recv.applyToEitherAsync(other, p -> { log.add("body"); return p; }, Runnable::run);
                    d.thenRun(() -> log.add("k"));
                    a.complete("y");
                    return log;
                });
            }
        }
        show("task of a settled target still helps", () -> {
            List<String> log = new ArrayList<>();
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> s = cf();
            s.thenRun(() -> log.add("x0"));
            s.thenRun(() -> log.add("x1"));
            CompletableFuture<String> d = s.thenApplyAsync(v -> { log.add("body"); return v; }, q::add);
            s.thenRun(() -> { log.add("["); d.complete("e"); q.get(0).run(); log.add("]"); });
            s.complete("v");
            return log + " " + d.join();
        });
        show("task runs once", () -> {
            List<String> log = new ArrayList<>();
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> s = cf();
            s.thenRun(() -> log.add("x0"));
            s.thenRun(() -> { log.add("["); q.get(0).run(); log.add("|"); q.get(0).run(); log.add("]"); });
            s.thenApplyAsync(v -> { log.add("body"); return v; }, q::add);
            s.thenRun(() -> log.add("x2"));
            s.complete("v");
            return log;
        });
        show("supply task runs once", () -> {
            List<Runnable> q = new ArrayList<>();
            int[] c = new int[1];
            CompletableFuture<String> d = CompletableFuture.supplyAsync(() -> "s" + ++c[0], q::add);
            q.get(0).run();
            q.get(0).run();
            return c[0] + " " + d.join();
        });
        show("supply settled before its task", () -> {
            List<Runnable> q = new ArrayList<>();
            int[] c = new int[1];
            CompletableFuture<String> d = CompletableFuture.supplyAsync(() -> "s" + ++c[0], q::add);
            d.cancel(false);
            q.get(0).run();
            return c[0] + " " + d.isCancelled();
        });
        show("target settled between hand-over and run", () -> {
            List<Runnable> q = new ArrayList<>();
            int[] c = new int[1];
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApplyAsync(v -> { c[0]++; return v; }, q::add);
            s.complete("y");
            d.complete("x");
            q.get(0).run();
            return c[0] + " " + d.join();
        });
        show("async reads at run", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApplyAsync(v -> v, q::add);
            s.complete("1");
            s.obtrudeValue("2");
            q.get(0).run();
            return d.join();
        });
        show("async failure at run", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApplyAsync(v -> v, q::add);
            s.complete("1");
            s.obtrudeException(new Error("o"));
            q.get(0).run();
            return state(d);
        });
        show("async skip decided at firing", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApplyAsync(v -> v, q::add);
            s.completeExceptionally(new Error("o"));
            s.obtrudeValue("2");
            return q.size() + " " + state(d);
        });
        show("async either reads the receiver first", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> d = a.applyToEitherAsync(b, v -> v, q::add);
            b.complete("b");
            a.complete("a");
            q.get(0).run();
            return d.join();
        });
        show("async both reads at run", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> d = a.thenCombineAsync(b, (p, r) -> p + r, q::add);
            a.complete("a");
            b.complete("b");
            a.obtrudeValue("A");
            q.get(0).run();
            return d.join();
        });
        show("both reads the latest", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> d = a.thenCombine(b, (p, q) -> p + q);
            a.completeExceptionally(new RuntimeException("x"));
            a.obtrudeValue("2");
            b.complete("3");
            return d.join();
        });
        show("either failed uses the executor", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> d = a.applyToEitherAsync(b, v -> v, q::add);
            a.completeExceptionally(new Error("o"));
            String before = q.size() + " " + d.isDone() + " " + counts(a, b);
            q.get(0).run();
            return before + " / " + state(d) + " " + counts(a, b);
        });
        show("both failed on one side waits, no executor", () -> {
            List<Runnable> q = new ArrayList<>();
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> d = a.thenCombineAsync(b, (p, r) -> p, q::add);
            a.completeExceptionally(new Error("o"));
            String before = q.size() + " " + d.isDone() + " " + counts(a, b);
            b.complete("b");
            return before + " / " + q.size() + " " + state(d) + " " + counts(a, b);
        });
        show("executor runs then throws", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApplyAsync(v -> "ran", r -> { r.run(); throw new Error("ex"); });
            s.complete("v");
            return state(d);
        });
        show("executor throws an Error", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApplyAsync(v -> v, r -> { throw new Error("ex"); });
            return s.complete("v") + " " + state(d);
        });
        show("supply executor runs then throws", () -> CompletableFuture.supplyAsync(() -> "ran", r -> { r.run(); throw new Error("ex"); }));
    }

    static void tasksOnce() {
        for (boolean both : new boolean[] {true, false}) {
            show("two lists, one hand-over" + (both ? " both" : " either"), () -> {
                List<Runnable> q = new ArrayList<>();
                CompletableFuture<String> r = cf(), b = cf();
                CompletableFuture<String> a = r.thenApply(v -> v);
                a.thenRun(() -> b.complete("b"));
                if (both) a.thenCombineAsync(b, (p, o) -> p, q::add); else a.applyToEitherAsync(b, p -> p, q::add);
                r.complete("r");
                return q.size();
            });
            show("task leaves a pending source alone" + (both ? ", receiver" : ", other"), () -> {
                List<String> log = new ArrayList<>();
                CompletableFuture<String> a = cf(), b = cf();
                (both ? a : b).thenRun(() -> log.add("pending source fired"));
                (both ? a : b).applyToEitherAsync(both ? b : a, p -> { log.add("body"); return p; }, Runnable::run);
                (both ? b : a).complete("x");
                String before = counts(a, b);
                (both ? a : b).complete("y");
                return log + " " + before + " " + counts(a, b);
            });
        }
    }

    /** Which level a stage's dependents fire at, by how it settled. */
    static void levels() {
        show("level rejected", () -> below(s -> s.thenApplyAsync(v -> v, r -> { throw new RejectedExecutionException(); }), false));
        show("level skip", () -> below(s -> s.thenApplyAsync(v -> v, Runnable::run), true));
        show("level compose task, inner done", () -> below(s -> s.thenComposeAsync(v -> CompletableFuture.completedFuture("i"), Runnable::run), false));
        show("level compose, inner done", () -> below(s -> s.thenCompose(v -> CompletableFuture.completedFuture("i")), false));
        show("level whenComplete throws", () -> below(s -> s.whenComplete((v, t) -> { throw new Error(); }), false));
        show("level own attach in a function", () -> {
            List<String> log = new ArrayList<>();
            CompletableFuture<String> s = cf();
            Object[] hold = new Object[1];
            @SuppressWarnings("unchecked")
            CompletableFuture<String> d = s.thenApply(v -> { ((CompletableFuture<String>) hold[0]).thenRun(() -> log.add("late")); return v; });
            hold[0] = d;
            d.thenRun(() -> log.add("early"));
            s.complete("v");
            return log;
        });
        show("level own attach in a task", () -> {
            List<String> log = new ArrayList<>();
            CompletableFuture<String> s = cf();
            Object[] hold = new Object[1];
            @SuppressWarnings("unchecked")
            CompletableFuture<String> d = s.thenApplyAsync(v -> { ((CompletableFuture<String>) hold[0]).thenRun(() -> log.add("late")); return v; }, Runnable::run);
            hold[0] = d;
            d.thenRun(() -> log.add("early"));
            s.complete("v");
            return log;
        });
        show("rejected in a firing, no helping", () -> {
            List<String> log = new ArrayList<>();
            CompletableFuture<String> s = cf();
            s.thenRun(() -> log.add("x0"));
            CompletableFuture<String> d = s.thenApplyAsync(v -> v, r -> { throw new RejectedExecutionException(); });
            d.handle((v, t) -> log.add("k0"));
            d.handle((v, t) -> log.add("k1"));
            s.thenRun(() -> log.add("x2"));
            s.complete("v");
            return log;
        });
    }

    interface Make { CompletableFuture<String> on(CompletableFuture<String> s); }

    static List<String> below(Make make, boolean fail) {
        List<String> log = new ArrayList<>();
        CompletableFuture<String> s = cf();
        CompletableFuture<String> d = make.on(s);
        d.handle((v, t) -> log.add("k0"));
        d.handle((v, t) -> log.add("k1"));
        if (fail) s.completeExceptionally(new Error()); else s.complete("v");
        return log;
    }

    public static void main(String[] args) {
        countsWhileFiring();
        deadEntries();
        helping();
        tasks();
        tasksOnce();
        levels();
    }
}
