package concurrent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;

/** One-source dependents: each method against each source state, and each kind of failing function. */
public class CompletableFutureStages {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

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
        try { Object v = f.get(); b.append(" get=").append(v); } catch (Throwable t) { b.append(" get!").append(chain(t)); }
        String[] raw = new String[1];
        f.whenComplete((v, t) -> raw[0] = t == null ? "value " + v : chain(t));
        b.append(" handed=").append(raw[0]);
        return b.toString();
    }

    static final List<String> calls = new ArrayList<>();

    /** Records each function call with its arguments and the thread it ran on. */
    static void called(String what) {
        calls.add(what + " on " + Thread.currentThread().getName());
    }

    static final RuntimeException FN = new IllegalArgumentException("fn");

    /** Which functions the dependents are given: normal, or all throwing. */
    static Map<String, Function<CompletableFuture<String>, CompletableFuture<?>>> ops(boolean thrower) {
        Map<String, Function<CompletableFuture<String>, CompletableFuture<?>>> m = new LinkedHashMap<>();
        m.put("thenApply", s -> s.thenApply(v -> { called("thenApply(" + v + ")"); if (thrower) throw FN; return v + "+apply"; }));
        m.put("thenApply to null", s -> s.thenApply(v -> { called("thenApply(" + v + ")"); if (thrower) throw FN; return null; }));
        m.put("thenAccept", s -> s.thenAccept(v -> { called("thenAccept(" + v + ")"); if (thrower) throw FN; }));
        m.put("thenRun", s -> s.thenRun(() -> { called("thenRun"); if (thrower) throw FN; }));
        m.put("thenCompose", s -> s.thenCompose(v -> {
            called("thenCompose(" + v + ")");
            if (thrower) throw FN;
            return CompletableFuture.completedFuture(v + "+compose");
        }));
        m.put("whenComplete", s -> s.whenComplete((v, t) -> { called("whenComplete(" + v + "," + chain(t) + ")"); if (thrower) throw FN; }));
        m.put("handle", s -> s.handle((v, t) -> { called("handle(" + v + "," + chain(t) + ")"); if (thrower) throw FN; return v + "+handle"; }));
        m.put("exceptionally", s -> s.exceptionally(t -> { called("exceptionally(" + chain(t) + ")"); if (thrower) throw FN; return "recovered"; }));
        return m;
    }

    /** A source in a given state; pending ones are settled after the dependent is attached. */
    static void matrix(boolean thrower) throws Exception {
        String[] sources = {"done ok", "done null", "done fail", "done CE", "done cancelled", "pending ok", "pending fail", "pending cancel"};
        for (Map.Entry<String, Function<CompletableFuture<String>, CompletableFuture<?>>> op : ops(thrower).entrySet()) {
            for (String src : sources) {
                calls.clear();
                CompletableFuture<String> s = new CompletableFuture<>();
                if (src.equals("done ok")) s.complete("val");
                if (src.equals("done null")) s.complete(null);
                if (src.equals("done fail")) s.completeExceptionally(new java.io.IOException("src"));
                if (src.equals("done CE")) s.completeExceptionally(new CompletionException(new java.io.IOException("src")));
                if (src.equals("done cancelled")) s.cancel(false);
                CompletableFuture<?> d = op.getValue().apply(s);
                String before = d.isDone() ? "" : "attached pending, ";
                Thread settle = new Thread(() -> {
                    if (src.equals("pending ok")) s.complete("val");
                    if (src.equals("pending fail")) s.completeExceptionally(new java.io.IOException("src"));
                    if (src.equals("pending cancel")) s.cancel(false);
                }, "settler");
                settle.start();
                settle.join();
                show((thrower ? "throwing " : "") + op.getKey() + " / " + src, () -> before + full(d) + " calls=" + calls);
            }
        }
    }

    static class Redirect<T> extends CompletableFuture<T> {
        final CompletableFuture<T> target;
        Redirect(CompletableFuture<T> target) { this.target = target; }
        @Override public CompletableFuture<T> toCompletableFuture() { return target; }
    }

    static void composes() {
        CompletableFuture<String> ok = CompletableFuture.completedFuture("v");
        show("compose null stage", () -> full(ok.thenCompose(v -> null)));
        show("compose failed", () -> {
            CompletableFuture<String> bad = new CompletableFuture<>();
            bad.completeExceptionally(new java.io.IOException("inner"));
            return full(ok.thenCompose(v -> bad));
        });
        show("compose CE", () -> {
            CompletableFuture<String> bad = new CompletableFuture<>();
            bad.completeExceptionally(new CompletionException(new java.io.IOException("inner")));
            return full(ok.thenCompose(v -> bad));
        });
        show("compose cancelled", () -> {
            CompletableFuture<String> bad = new CompletableFuture<>();
            bad.cancel(false);
            CompletableFuture<String> d = ok.thenCompose(v -> bad);
            return full(d) + " isCancelled=" + d.isCancelled();
        });
        show("compose pending", () -> {
            CompletableFuture<String> later = new CompletableFuture<>();
            CompletableFuture<String> d = ok.thenCompose(v -> later);
            String before = full(d);
            later.complete("late");
            return before + " then " + full(d);
        });
        show("compose pending fails", () -> {
            CompletableFuture<String> later = new CompletableFuture<>();
            CompletableFuture<String> d = ok.thenCompose(v -> later);
            later.completeExceptionally(new java.io.IOException("late"));
            return full(d);
        });
        show("compose result is new", () -> {
            CompletableFuture<String> same = CompletableFuture.completedFuture("x");
            return ok.thenCompose(v -> same) != same;
        });
        show("compose follows toCompletableFuture", () -> {
            CompletableFuture<String> real = CompletableFuture.completedFuture("real");
            return full(ok.thenCompose(v -> new Redirect<>(real)));
        });
        show("compose toCompletableFuture null", () -> full(ok.thenCompose(v -> new Redirect<String>(null))));
    }

    static void chains() {
        show("failure skips stages, stays wrapped", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            CompletableFuture<String> d = s.thenApply(v -> v + "1").thenApply(v -> v + "2").thenApply(v -> v + "3");
            s.completeExceptionally(new java.io.IOException("src"));
            return full(d);
        });
        show("exceptionally sees wrapped downstream", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            CompletableFuture<String> d = s.thenApply(v -> v + "1").exceptionally(t -> chain(t));
            s.completeExceptionally(new java.io.IOException("src"));
            return d.join();
        });
        show("handle recovers", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            CompletableFuture<String> d = s.handle((v, t) -> "h").thenApply(v -> v + "+next");
            s.completeExceptionally(new java.io.IOException("src"));
            return full(d);
        });
        show("whenComplete keeps source failure over its own", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            s.completeExceptionally(new java.io.IOException("src"));
            CompletableFuture<String> d = s.whenComplete((v, t) -> { throw FN; });
            return full(d);
        });
        show("whenComplete keeps value", () -> full(CompletableFuture.completedFuture("v").whenComplete((v, t) -> { })));
        show("whenComplete no suppression", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            java.io.IOException src = new java.io.IOException("src");
            s.completeExceptionally(src);
            s.whenComplete((v, t) -> { throw FN; }).exceptionally(t -> "").join();
            return src.getSuppressed().length;
        });
        show("thrown CE kept, not rewrapped", () -> {
            CompletionException ce = new CompletionException(new java.io.IOException("x"));
            CompletableFuture<String> d = CompletableFuture.completedFuture("v").thenApply(v -> { throw ce; });
            try { d.join(); return "no"; } catch (CompletionException e) { return e == ce; }
        });
        show("thrown cancellation is wrapped", () -> {
            CompletableFuture<String> d = CompletableFuture.completedFuture("v").thenApply(v -> { throw new CancellationException("c"); });
            return full(d) + " isCancelled=" + d.isCancelled();
        });
        show("source cancellation wrapped downstream", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            s.cancel(false);
            return full(s.thenApply(v -> v));
        });
        show("thrown error captured", () -> full(CompletableFuture.completedFuture("v").thenApply(v -> { throw new StackOverflowError("so"); })));
        show("exceptionally of value skips", () -> {
            boolean[] ran = new boolean[1];
            CompletableFuture<String> d = CompletableFuture.completedFuture("v").exceptionally(t -> { ran[0] = true; return "x"; });
            return d.join() + " " + ran[0];
        });
        show("new stage each time", () -> {
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            return s.thenApply(v -> v) != s.thenApply(v -> v);
        });
        show("whenComplete is new", () -> {
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            return s.whenComplete((v, t) -> { }) != s;
        });
        show("exceptionally is new", () -> {
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            return s.exceptionally(t -> "") != s;
        });
        show("complete a dependent early", () -> {
            CompletableFuture<String> s = new CompletableFuture<>();
            boolean[] ran = new boolean[1];
            CompletableFuture<String> d = s.thenApply(v -> { ran[0] = true; return "fn"; });
            d.complete("early");
            s.complete("v");
            return d.join() + " fnRan=" + ran[0];
        });
    }

    public static void main(String[] args) throws Exception {
        matrix(false);
        matrix(true);
        composes();
        chains();
    }
}
