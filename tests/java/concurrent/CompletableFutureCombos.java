package concurrent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Two-source dependents, allOf and anyOf: each order of settling, each mix of outcomes, null arguments. */
public class CompletableFutureCombos {
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
        String[] raw = new String[1];
        f.whenComplete((v, t) -> raw[0] = t == null ? "value " + v : chain(t));
        return b.append(" handed=").append(raw[0]).toString();
    }

    static final List<String> calls = new ArrayList<>();

    static void settle(CompletableFuture<String> f, String how) {
        if (how.equals("ok1")) f.complete("v1");
        if (how.equals("ok2")) f.complete("v2");
        if (how.equals("fail1")) f.completeExceptionally(new java.io.IOException("f1"));
        if (how.equals("fail2")) f.completeExceptionally(new java.io.IOException("f2"));
        if (how.equals("cancel")) f.cancel(false);
        if (how.equals("ce")) f.completeExceptionally(new CompletionException(new java.io.IOException("in-ce")));
    }

    static Map<String, BiFunction<CompletableFuture<String>, CompletableFuture<String>, CompletableFuture<?>>> ops(boolean thrower) {
        Map<String, BiFunction<CompletableFuture<String>, CompletableFuture<String>, CompletableFuture<?>>> m = new LinkedHashMap<>();
        m.put("thenCombine", (a, b) -> a.thenCombine(b, (x, y) -> { calls.add("combine(" + x + "," + y + ")"); if (thrower) throw new IllegalArgumentException("fn"); return x + y; }));
        m.put("thenAcceptBoth", (a, b) -> a.thenAcceptBoth(b, (x, y) -> { calls.add("acceptBoth(" + x + "," + y + ")"); if (thrower) throw new IllegalArgumentException("fn"); }));
        m.put("runAfterBoth", (a, b) -> a.runAfterBoth(b, () -> { calls.add("runAfterBoth"); if (thrower) throw new IllegalArgumentException("fn"); }));
        m.put("applyToEither", (a, b) -> a.applyToEither(b, x -> { calls.add("apply(" + x + ")"); if (thrower) throw new IllegalArgumentException("fn"); return x + "!"; }));
        m.put("acceptEither", (a, b) -> a.acceptEither(b, x -> { calls.add("accept(" + x + ")"); if (thrower) throw new IllegalArgumentException("fn"); }));
        m.put("runAfterEither", (a, b) -> a.runAfterEither(b, () -> { calls.add("runAfterEither"); if (thrower) throw new IllegalArgumentException("fn"); }));
        return m;
    }

    /**
     * Each line: the two sources' fates and when each settles relative to attaching:
     * "before" sets both up first, "a,b" and "b,a" settle after attaching in that order.
     */
    static void pairs() {
        String[][] outcomes = {
            {"ok1", "ok2"}, {"fail1", "ok2"}, {"ok1", "fail2"}, {"fail1", "fail2"}, {"cancel", "ok2"}, {"ok1", "cancel"}, {"ce", "ok2"},
        };
        for (boolean thrower : new boolean[] {false, true}) {
            for (Map.Entry<String, BiFunction<CompletableFuture<String>, CompletableFuture<String>, CompletableFuture<?>>> op : ops(thrower).entrySet()) {
                for (String[] o : outcomes) {
                    String tag = (thrower ? "throwing " : "") + op.getKey() + " " + o[0] + "," + o[1];
                    calls.clear();
                    CompletableFuture<String> a = new CompletableFuture<>(), b = new CompletableFuture<>();
                    settle(a, o[0]);
                    settle(b, o[1]);
                    CompletableFuture<?> d = op.getValue().apply(a, b);
                    show(tag + " before", () -> full(d) + " calls=" + calls);
                    calls.clear();
                    CompletableFuture<String> a2 = new CompletableFuture<>(), b2 = new CompletableFuture<>();
                    CompletableFuture<?> d2 = op.getValue().apply(a2, b2);
                    settle(a2, o[0]);
                    String mid = full(d2);
                    settle(b2, o[1]);
                    show(tag + " a,b", () -> mid + " / " + full(d2) + " calls=" + calls);
                    calls.clear();
                    CompletableFuture<String> a3 = new CompletableFuture<>(), b3 = new CompletableFuture<>();
                    CompletableFuture<?> d3 = op.getValue().apply(a3, b3);
                    settle(b3, o[1]);
                    String mid3 = full(d3);
                    settle(a3, o[0]);
                    show(tag + " b,a", () -> mid3 + " / " + full(d3) + " calls=" + calls);
                }
            }
        }
    }

    static void sameSource() {
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> both = a.thenCombine(a, (x, y) -> x + y);
        CompletableFuture<String> either = a.applyToEither(a, x -> x + "!");
        a.complete("s");
        show("combine with itself", () -> both.join());
        show("either with itself", () -> either.join());
    }

    @SafeVarargs
    static CompletableFuture<String>[] fresh(int count, CompletableFuture<String>... none) {
        @SuppressWarnings("unchecked") CompletableFuture<String>[] fs = new CompletableFuture[count];
        for (int i = 0; i < count; i++) fs[i] = new CompletableFuture<>();
        return fs;
    }

    static void allAndAny() {
        show("allOf empty", () -> full(CompletableFuture.allOf()));
        show("anyOf empty", () -> full(CompletableFuture.anyOf()));
        show("allOf null array", () -> CompletableFuture.allOf((CompletableFuture<?>[]) null));
        show("anyOf null array", () -> CompletableFuture.anyOf((CompletableFuture<?>[]) null));
        show("allOf null element", () -> CompletableFuture.allOf(new CompletableFuture<String>(), null));
        show("anyOf null element", () -> CompletableFuture.anyOf(new CompletableFuture<String>(), null));
        show("allOf one", () -> {
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            CompletableFuture<Void> d = CompletableFuture.allOf(s);
            return full(d) + " same=" + ((Object) d == s);
        });
        show("anyOf one", () -> {
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            CompletableFuture<Object> d = CompletableFuture.anyOf(s);
            return full(d) + " same=" + ((Object) d == s);
        });
        String[][] plans = {
            {"ok1", "ok2", "ok1"}, {"fail1", "ok2", "ok1"}, {"ok1", "fail2", "ok1"}, {"ok1", "ok2", "fail1"},
            {"fail1", "fail2", "ok1"}, {"ok1", "fail1", "fail2"}, {"fail1", "ok1", "fail2"}, {"fail1", "fail2", "cancel"},
            {"cancel", "ok1", "ok2"}, {"ce", "ok1", "ok2"},
        };
        for (String[] plan : plans) {
            String tag = String.join(",", plan);
            CompletableFuture<String>[] x = fresh(3);
            for (int i = 0; i < 3; i++) settle(x[i], plan[i]);
            show("allOf done " + tag, () -> full(CompletableFuture.allOf(x)));
            show("anyOf done " + tag, () -> full(CompletableFuture.anyOf(x)));
            for (int[] order : new int[][] {{0, 1, 2}, {2, 1, 0}, {1, 2, 0}}) {
                CompletableFuture<String>[] y = fresh(3);
                CompletableFuture<Void> all = CompletableFuture.allOf(y);
                CompletableFuture<Object> any = CompletableFuture.anyOf(y);
                List<String> steps = new ArrayList<>();
                for (int i : order) {
                    settle(y[i], plan[i]);
                    steps.add(i + ": all " + full(all) + " any " + full(any));
                }
                show("order " + order[0] + order[1] + order[2] + " of " + tag, () -> steps);
            }
        }
        show("anyOf first wins, later ignored", () -> {
            CompletableFuture<String>[] y = fresh(2);
            CompletableFuture<Object> any = CompletableFuture.anyOf(y);
            y[1].complete("second array slot");
            y[0].completeExceptionally(new RuntimeException("late"));
            return full(any);
        });
        show("allOf waits behind failure", () -> {
            CompletableFuture<String>[] y = fresh(2);
            CompletableFuture<Void> all = CompletableFuture.allOf(y);
            y[0].completeExceptionally(new RuntimeException("first"));
            return all.isDone();
        });
        show("allOf many", () -> {
            int size = 200;
            CompletableFuture<?>[] many = new CompletableFuture<?>[size];
            for (int i = 0; i < size; i++) many[i] = new CompletableFuture<String>();
            CompletableFuture<Void> all = CompletableFuture.allOf(many);
            CompletableFuture<Object> any = CompletableFuture.anyOf(many);
            for (int i = size - 1; i > 0; i--) ((CompletableFuture<String>) many[i]).complete("x" + i);
            String mid = full(all) + " any " + any.join();
            ((CompletableFuture<String>) many[0]).complete("x0");
            return mid + " then " + full(all);
        });
        show("allOf reports the lowest index that failed", () -> {
            List<String> out = new ArrayList<>();
            for (int[] failing : new int[][] {{5, 2}, {6, 4}, {1, 6}, {3, 4}, {0, 6}}) {
                CompletableFuture<?>[] seven = new CompletableFuture<?>[7];
                for (int i = 0; i < 7; i++) seven[i] = new CompletableFuture<String>();
                CompletableFuture<Void> all = CompletableFuture.allOf(seven);
                for (int i = 6; i >= 0; i--) {
                    boolean fail = i == failing[0] || i == failing[1];
                    if (fail) seven[i].completeExceptionally(new java.io.IOException("at " + i));
                    else ((CompletableFuture<String>) seven[i]).complete("x");
                }
                try { all.join(); out.add("none"); } catch (CompletionException e) { out.add(e.getCause().getMessage()); }
            }
            return out;
        });
        show("cancel allOf leaves sources", () -> {
            CompletableFuture<String>[] y = fresh(2);
            CompletableFuture<Void> all = CompletableFuture.allOf(y);
            all.cancel(true);
            return y[0].isCancelled() + " " + y[0].isDone();
        });
    }

    static void nulls() {
        CompletableFuture<String> a = new CompletableFuture<>(), b = new CompletableFuture<>();
        CompletableFuture<String> done = CompletableFuture.completedFuture("d");
        Executor ex = Runnable::run;
        for (CompletableFuture<String> s : java.util.Arrays.asList(a, done)) {
            String t = s == a ? "pending " : "done ";
            show(t + "supplyAsync null", () -> CompletableFuture.supplyAsync(null));
            show(t + "supplyAsync null exec", () -> CompletableFuture.supplyAsync(() -> "x", null));
            show(t + "runAsync null", () -> CompletableFuture.runAsync(null));
            show(t + "runAsync null exec", () -> CompletableFuture.runAsync(() -> { }, null));
            show(t + "thenApply", () -> s.thenApply(null));
            show(t + "thenApplyAsync", () -> s.thenApplyAsync(null));
            show(t + "thenApplyAsync exec", () -> s.thenApplyAsync(v -> v, null));
            show(t + "thenAccept", () -> s.thenAccept(null));
            show(t + "thenAcceptAsync", () -> s.thenAcceptAsync(null));
            show(t + "thenAcceptAsync exec", () -> s.thenAcceptAsync(v -> { }, null));
            show(t + "thenRun", () -> s.thenRun(null));
            show(t + "thenRunAsync", () -> s.thenRunAsync(null));
            show(t + "thenRunAsync exec", () -> s.thenRunAsync(() -> { }, null));
            show(t + "thenCombine fn", () -> s.thenCombine(b, null));
            show(t + "thenCombine other", () -> s.thenCombine(null, (x, y) -> x));
            show(t + "thenCombineAsync", () -> s.thenCombineAsync(b, null));
            show(t + "thenCombineAsync exec", () -> s.thenCombineAsync(b, (x, y) -> x, null));
            show(t + "thenAcceptBoth fn", () -> s.thenAcceptBoth(b, null));
            show(t + "thenAcceptBoth other", () -> s.thenAcceptBoth(null, (x, y) -> { }));
            show(t + "thenAcceptBothAsync exec", () -> s.thenAcceptBothAsync(b, (x, y) -> { }, null));
            show(t + "runAfterBoth fn", () -> s.runAfterBoth(b, null));
            show(t + "runAfterBoth other", () -> s.runAfterBoth(null, () -> { }));
            show(t + "runAfterBothAsync exec", () -> s.runAfterBothAsync(b, () -> { }, null));
            show(t + "applyToEither fn", () -> s.applyToEither(b, null));
            show(t + "applyToEither other", () -> s.applyToEither(null, x -> x));
            show(t + "applyToEitherAsync exec", () -> s.applyToEitherAsync(b, x -> x, null));
            show(t + "acceptEither fn", () -> s.acceptEither(b, null));
            show(t + "acceptEither other", () -> s.acceptEither(null, x -> { }));
            show(t + "acceptEitherAsync exec", () -> s.acceptEitherAsync(b, x -> { }, null));
            show(t + "runAfterEither fn", () -> s.runAfterEither(b, null));
            show(t + "runAfterEither other", () -> s.runAfterEither(null, () -> { }));
            show(t + "runAfterEitherAsync exec", () -> s.runAfterEitherAsync(b, () -> { }, null));
            show(t + "thenCompose", () -> s.thenCompose(null));
            show(t + "thenComposeAsync", () -> s.thenComposeAsync(null));
            show(t + "thenComposeAsync exec", () -> s.thenComposeAsync(v -> s, null));
            show(t + "whenComplete", () -> s.whenComplete(null));
            show(t + "whenCompleteAsync", () -> s.whenCompleteAsync(null));
            show(t + "whenCompleteAsync exec", () -> s.whenCompleteAsync((v, e) -> { }, null));
            show(t + "handle", () -> s.handle(null));
            show(t + "handleAsync", () -> s.handleAsync(null));
            show(t + "handleAsync exec", () -> s.handleAsync((v, e) -> v, null));
            show(t + "exceptionally", () -> s.exceptionally(null));
            show(t + "other toCompletableFuture null", () -> s.thenCombine(new Redirect<String>(), (x, y) -> x));
            show(t + "either toCompletableFuture null", () -> s.applyToEither(new Redirect<String>(), x -> x));
        }
        show("executor unused for null", () -> ex != null);
    }

    /** A stage whose toCompletableFuture yields nothing. */
    static class Redirect<T> extends CompletableFuture<T> {
        @Override public CompletableFuture<T> toCompletableFuture() { return null; }
    }

    public static void main(String[] args) {
        pairs();
        sameSource();
        allAndAny();
        nulls();
    }
}
