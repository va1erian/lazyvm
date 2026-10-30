package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** The shape of allOf and anyOf, subclasses, which outcomes read how, and long chains of every kind. */
public class CompletableFutureShapes {
    interface Body { Object run() throws Throwable; }

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

    interface Build { Object of(CompletableFuture<?>... fs); }

    /** Sources named by letters, `.` for null, then the counts of the sources: this shows the tree. */
    static void shape(String method, Build build, String names) {
        show(method + " " + names, () -> {
            CompletableFuture<?>[] byLetter = new CompletableFuture<?>[26];
            CompletableFuture<?>[] args = new CompletableFuture<?>[names.length()];
            List<CompletableFuture<?>> distinct = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                char c = names.charAt(i);
                if (c == '.') continue;
                if (byLetter[c - 'a'] == null) {
                    byLetter[c - 'a'] = cf();
                    distinct.add(byLetter[c - 'a']);
                }
                args[i] = byLetter[c - 'a'];
            }
            String thrown = "";
            try { build.of(args); } catch (NullPointerException e) { thrown = "NPE "; }
            return thrown + counts(distinct.toArray(new CompletableFuture<?>[0]));
        });
    }

    static void shapes() {
        for (String m : new String[] {"allOf", "anyOf"}) {
            Build b = m.equals("allOf") ? CompletableFuture::allOf : CompletableFuture::anyOf;
            for (String names : new String[] {"a", "ab", "abc", "abcde", "aa", "aaa", "aaaa", "aaaaa", "aba", "aabb", "abba", "a.", ".a",
                    "a.b", "ab.c", "abc.", ".abc", "abcd."}) {
                shape(m, b, names);
            }
        }
        show("anyOf after first", () -> {
            CompletableFuture<Object> a = cf(), b = cf(), c = cf();
            CompletableFuture.anyOf(a, b, c);
            a.complete("x");
            return counts(a, b, c);
        });
        show("anyOf duplicate after another", () -> {
            CompletableFuture<Object> a = cf(), b = cf();
            CompletableFuture.anyOf(a, a, b, a);
            b.complete("x");
            return counts(a, b);
        });
        show("allOf after first", () -> {
            CompletableFuture<Object> a = cf(), b = cf(), c = cf();
            CompletableFuture.allOf(a, b, c);
            a.complete("x");
            return counts(a, b, c);
        });
        show("allOf done first joins the rest", () -> {
            CompletableFuture<Object> a = cf(), b = cf(), c = cf();
            a.complete("x");
            CompletableFuture.allOf(a, b, c);
            return counts(a, b, c);
        });
        show("anyOf done joins nothing", () -> {
            CompletableFuture<Object> a = cf(), b = cf(), c = cf();
            b.complete("x");
            return CompletableFuture.anyOf(a, b, c).join() + " " + counts(a, b, c);
        });
        show("anyOf done then null", () -> CompletableFuture.anyOf(CompletableFuture.completedFuture("v"), null));
        show("allOf obtrude, pair still open", () -> {
            CompletableFuture<Object> a = cf(), b = cf();
            CompletableFuture<Void> d = CompletableFuture.allOf(a, b);
            a.completeExceptionally(new RuntimeException("x"));
            a.obtrudeValue("2");
            b.complete("c");
            return d.isCompletedExceptionally();
        });
        show("allOf obtrude, pair closed", () -> {
            CompletableFuture<Object> a = cf(), b = cf(), c = cf();
            CompletableFuture<Void> d = CompletableFuture.allOf(a, b, c);
            a.completeExceptionally(new RuntimeException("x"));
            b.complete("c");
            a.obtrudeValue("2");
            c.complete("c");
            return d.isCompletedExceptionally();
        });
        show("allOf one reads when it fires", () -> {
            CompletableFuture<Object> a = cf();
            CompletableFuture<Void> d = CompletableFuture.allOf(a);
            a.complete("v");
            a.obtrudeException(new Error());
            return state(d);
        });
        show("allOf array changed after", () -> {
            CompletableFuture<Object> a = cf(), b = cf(), c = cf();
            CompletableFuture<?>[] arr = {a, b};
            CompletableFuture<Void> d = CompletableFuture.allOf(arr);
            arr[1] = c;
            a.complete("");
            b.complete("");
            return d.isDone();
        });
        show("anyOf a nested settle keeps the lowest", () -> {
            CompletableFuture<Object> a = cf(), b = cf();
            CompletableFuture<Object> r = CompletableFuture.anyOf(a, b);
            a.thenRun(() -> b.complete("b"));
            a.complete("a");
            return r.join();
        });
        show("anyOf a nested settle, lower index later", () -> {
            CompletableFuture<Object> a = cf(), b = cf();
            CompletableFuture<Object> r = CompletableFuture.anyOf(b, a);
            a.thenRun(() -> b.complete("b"));
            a.complete("a");
            return r.join();
        });
        show("either a nested settle keeps the receiver", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            CompletableFuture<String> r = b.applyToEither(a, v -> v);
            a.thenRun(() -> b.complete("b"));
            a.complete("a");
            return r.join();
        });
    }

    static class Five extends CompletableFuture<String> {
        int calls;
        @Override public boolean complete(String v) { calls++; return false; }
        @Override public boolean completeExceptionally(Throwable t) { calls++; return false; }
        @Override public boolean cancel(boolean b) { calls++; return false; }
        @Override public void obtrudeValue(String v) { calls++; }
        @Override public void obtrudeException(Throwable t) { calls++; }
        void superComplete(String v) { super.complete(v); }
        void superFail(Throwable t) { super.completeExceptionally(t); }
        void superCancel() { super.cancel(true); }
        void superObtrude(String v) { super.obtrudeValue(v); }
        void superObtrudeEx(Throwable t) { super.obtrudeException(t); }
    }

    /** Overrides that would all fail: nothing here may depend on what they return. */
    static class Traps extends CompletableFuture<String> {
        @Override public boolean isDone() { throw new Error("isDone"); }
        @Override public String join() { throw new Error("join"); }
        @Override public String get() { throw new Error("get"); }
        @Override public String getNow(String v) { throw new Error("getNow"); }
        @Override public boolean isCompletedExceptionally() { throw new Error("isCompletedExceptionally"); }
        @Override public boolean isCancelled() { throw new Error("isCancelled"); }
        @Override public CompletableFuture<String> toCompletableFuture() { return this; }
        String justJoin() { return super.join(); }
        String justGet() throws Exception { return super.get(1, TimeUnit.SECONDS); }
        String justNow() { return super.getNow("absent"); }
        boolean justCancelled() { return super.isCancelled(); }
        boolean justFailed() { return super.isCompletedExceptionally(); }
    }

    static void subclasses() {
        show("subclass, each completion alone", () -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                Five f = new Five();
                CompletableFuture<String> d = f.handle((v, t) -> v + "/" + (t == null ? null : t.getClass().getSimpleName()));
                switch (i) {
                    case 0: f.superComplete("v"); break;
                    case 1: f.superFail(new Error("e")); break;
                    case 2: f.superCancel(); break;
                    case 3: f.superObtrude("o"); break;
                    default: f.superObtrudeEx(new Error("e")); break;
                }
                b.append(state(f)).append(" | ").append(state(d)).append(" calls=").append(f.calls).append("; ");
            }
            return b;
        });
        show("subclass dependents are plain", () -> {
            Five f = new Five();
            return f.thenApply(v -> v).getClass().getName() + " " + f.thenRun(() -> { }).getClass().getName() + " "
                + f.thenCombine(f, (p, q) -> p).getClass().getName() + " " + f.applyToEither(f, p -> p).getClass().getName() + " "
                + f.thenCompose(v -> f).getClass().getName() + " " + f.whenComplete((v, t) -> { }).getClass().getName() + " "
                + f.exceptionally(t -> "x").getClass().getName() + " " + f.toCompletableFuture().getClass().getSimpleName();
        });
        show("subclass read through its fields", () -> {
            Traps t = new Traps();
            t.complete("t");
            return state(t.thenApply(v -> v)) + " " + state(CompletableFuture.completedFuture("v").thenCompose(v -> t)) + " "
                + state(new CompletableFuture<String>().applyToEither(t, v -> v)) + " " + state(CompletableFuture.anyOf(t, cf()))
                + " " + state(CompletableFuture.allOf(t));
        });
        show("public reads do not ask one another", () -> {
            Traps t = new Traps();
            t.complete("t");
            Traps f = new Traps();
            f.completeExceptionally(new Error("e"));
            return t.justJoin() + " " + t.justGet() + " " + t.justNow() + " " + t.justCancelled() + " " + t.justFailed() + " " + f.justFailed() + " " + f.justCancelled();
        });
        show("subclass other through toCompletableFuture", () -> {
            CompletableFuture<String> real = CompletableFuture.completedFuture("real");
            CompletableFuture<String> f = new CompletableFuture<String>() {
                @Override public CompletableFuture<String> toCompletableFuture() { return real; }
            };
            return state(new CompletableFuture<String>().applyToEither(f, v -> v)) + " "
                + state(CompletableFuture.completedFuture("a").thenCombine(f, (p, q) -> p + q));
        });
        show("subclass receiver not through toCompletableFuture", () -> {
            CompletableFuture<String> f = new CompletableFuture<String>() {
                @Override public CompletableFuture<String> toCompletableFuture() { throw new Error("tcf"); }
            };
            return f.thenCombine(CompletableFuture.completedFuture(""), (p, q) -> p).isDone() + " " + f.thenApply(v -> v).isDone();
        });
        show("toString asks the count", () -> {
            CompletableFuture<String> f = new CompletableFuture<String>() {
                @Override public int getNumberOfDependents() { return 99; }
            };
            return String.valueOf(f).replaceAll(".*@", "@");
        });
        show("toString count 0", () -> {
            CompletableFuture<String> f = new CompletableFuture<String>() {
                @Override public int getNumberOfDependents() { return 0; }
            };
            f.thenRun(() -> { });
            return String.valueOf(f).replaceAll(".*@", "@");
        });
        show("toString settled asks nothing", () -> {
            CompletableFuture<String> f = new CompletableFuture<String>() {
                @Override public int getNumberOfDependents() { throw new Error(); }
                @Override public boolean isCompletedExceptionally() { return true; }
                @Override public boolean isDone() { return false; }
            };
            f.complete("v");
            return String.valueOf(f).replaceAll(".*@", "@");
        });
    }

    static class NoCause extends CompletionException { NoCause() { super(null, null); } }
    static class SubCE extends CompletionException { SubCE(Throwable c) { super(c); } }
    static class SubCancel extends CancellationException { }

    static String read(CompletableFuture<?> f, Throwable x) {
        StringBuilder b = new StringBuilder(f.isCancelled() ? "C " : "- ");
        try { f.join(); } catch (Throwable t) { b.append("join ").append(t == x ? "same" : t.toString()); }
        try { f.get(); } catch (Throwable t) { b.append(" get ").append(t == x ? "same" : t.toString()); }
        try { f.getNow(null); } catch (Throwable t) { b.append(" now ").append(t == x ? "same" : t.toString()); }
        return b.toString();
    }

    static void reads() {
        Throwable[] outcomes = {new NoCause(), new SubCE(new Error("c")), new SubCancel(), new ExecutionException(new Exception("e")), new Exception("chk")};
        for (Throwable x : outcomes) {
            String n = x.getClass().getSimpleName();
            show("read " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return read(s, x);
            });
            show("read dependent of " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return read(s.thenApply(v -> v), x);
            });
            show("read whenComplete of " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return read(s.whenComplete((v, t) -> { }), x);
            });
            show("read allOf of " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return read(CompletableFuture.allOf(s, CompletableFuture.completedFuture("v")), x);
            });
            show("read anyOf of " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return read(CompletableFuture.anyOf(s), x);
            });
            show("read compose of " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return read(CompletableFuture.completedFuture("v").thenCompose(v -> s), x);
            });
            show("handle of a dependent of " + n, () -> {
                CompletableFuture<String> s = cf();
                s.completeExceptionally(x);
                return s.thenApply(v -> v).handle((v, t) -> t == x ? "same" : t.toString()).join();
            });
        }
        show("thrown checked through a dependent", () -> {
            CompletableFuture<String> s = CompletableFuture.completedFuture("v");
            return s.thenApply(v -> { throw new CompletionException("msg", null); }).handle((v, t) -> t.toString() + " " + t.getCause()).join();
        });
        show("thrown by toCompletableFuture in compose", () -> {
            CompletableFuture<String> stage = new CompletableFuture<String>() {
                @Override public CompletableFuture<String> toCompletableFuture() { throw new IllegalStateException("tcf"); }
            };
            return state(CompletableFuture.completedFuture("v").thenCompose(v -> stage));
        });
        show("thrown by other's toCompletableFuture", () -> {
            CompletableFuture<String> stage = new CompletableFuture<String>() {
                @Override public CompletableFuture<String> toCompletableFuture() { throw new IllegalStateException("tcf"); }
            };
            return new CompletableFuture<String>().thenCombine(stage, (p, q) -> p);
        });
        show("null function before other's toCompletableFuture", () -> {
            CompletableFuture<String> stage = new CompletableFuture<String>() {
                @Override public CompletableFuture<String> toCompletableFuture() { throw new IllegalStateException("tcf"); }
            };
            return new CompletableFuture<String>().thenCombine(stage, null);
        });
        show("compose task, run twice", () -> {
            List<Runnable> q = new ArrayList<>();
            int[] c = new int[1];
            CompletableFuture<String> in = cf();
            CompletableFuture.completedFuture("v").thenComposeAsync(v -> { c[0]++; return in; }, q::add);
            q.get(0).run();
            q.get(0).run();
            return c[0] + " " + counts(in);
        });
        show("rejection reads as wrapped", () -> {
            CompletableFuture<String> d = CompletableFuture.completedFuture("v").thenApplyAsync(v -> v, r -> { throw new RejectedExecutionException("no"); });
            return d.handle((v, t) -> t.toString()).join();
        });
        show("lost cancel answers after firing", () -> {
            CompletableFuture<String> s = cf();
            String[] r = new String[1];
            s.thenRun(() -> s.obtrudeException(new CancellationException()));
            s.thenRun(() -> r[0] = "cancel=" + s.cancel(false));
            s.complete("v");
            return r[0];
        });
        show("a value that is a Throwable", () -> {
            CompletableFuture<Object> s = cf();
            s.complete(new Error("v"));
            return s.isCompletedExceptionally() + " " + s.join() + " " + s.handle((v, t) -> v + "/" + t).join();
        });
        show("compose of the dependent itself", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<?>[] h = new CompletableFuture<?>[1];
            @SuppressWarnings("unchecked")
            CompletableFuture<String> d = s.thenCompose(v -> (CompletableFuture<String>) h[0]);
            h[0] = d;
            s.complete("v");
            String r = d.isDone() + " " + counts(d);
            d.complete("x");
            return r + " " + d.join() + " " + counts(d);
        });
        show("target settled inside its own function", () -> {
            CompletableFuture<String> s = cf();
            Object[] h = new Object[1];
            List<String> log = new ArrayList<>();
            @SuppressWarnings("unchecked")
            CompletableFuture<String> d = s.thenApply(v -> { ((CompletableFuture<String>) h[0]).complete("inner"); log.add("end"); return "fn"; });
            h[0] = d;
            d.thenRun(() -> log.add("k0"));
            d.thenRun(() -> log.add("k1"));
            s.complete("v");
            return log + " " + d.join();
        });
        show("obtrude the source inside a dependent", () -> {
            List<String> log = new ArrayList<>();
            CompletableFuture<String> s = cf();
            s.thenAccept(v -> log.add("k0=" + v));
            s.thenAccept(v -> { log.add("k1=" + v); s.obtrudeValue("ob"); });
            s.thenAccept(v -> log.add("k2=" + v));
            s.complete("v");
            return log;
        });
        show("cancel, then a dependent obtrudes", () -> {
            CompletableFuture<String> s = cf();
            s.handle((v, t) -> { s.obtrudeValue("o"); return v; });
            return s.cancel(true) + " " + state(s);
        });
    }

    static void deep() {
        int depth = 20000;
        show("deep whenComplete", () -> {
            CompletableFuture<Integer> h = cf(), t = h;
            for (int i = 0; i < depth; i++) t = t.whenComplete((v, e) -> { });
            h.complete(1);
            return t.join();
        });
        show("deep combine", () -> {
            CompletableFuture<Integer> h = cf(), t = h, o = CompletableFuture.completedFuture(1);
            for (int i = 0; i < depth; i++) t = o.thenCombine(t, Integer::sum);
            h.complete(0);
            return t.join();
        });
        show("deep either", () -> {
            CompletableFuture<Integer> h = cf(), t = h;
            for (int i = 0; i < depth; i++) t = t.applyToEither(new CompletableFuture<Integer>(), v -> v + 1);
            h.complete(0);
            return t.join();
        });
        show("deep allOf", () -> {
            CompletableFuture<?> h = cf(), t = h;
            for (int i = 0; i < depth; i++) t = CompletableFuture.allOf(t);
            ((CompletableFuture<Object>) h).complete(0);
            return t.join();
        });
        show("deep anyOf", () -> {
            CompletableFuture<?> h = cf(), t = h;
            for (int i = 0; i < depth; i++) t = CompletableFuture.anyOf(t, new CompletableFuture<String>());
            ((CompletableFuture<Object>) h).complete(7);
            return t.join();
        });
        show("deep compose, inner pending", () -> {
            CompletableFuture<Integer> h = cf(), t = h;
            List<CompletableFuture<Integer>> in = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                CompletableFuture<Integer> g = cf();
                t = t.thenCompose(v -> g);
                in.add(g);
            }
            h.complete(0);
            for (int i = 0; i < depth; i++) in.get(i).complete(i);
            return t.join();
        });
        show("deep relay of relays", () -> {
            CompletableFuture<Integer> src = cf(), inner = src;
            for (int i = 0; i < depth; i++) {
                CompletableFuture<Integer> me = inner;
                inner = CompletableFuture.completedFuture(0).thenCompose(v -> me);
            }
            src.complete(9);
            return inner.join();
        });
        show("deep cancel", () -> {
            CompletableFuture<Integer> h = cf(), t = h;
            for (int i = 0; i < depth; i++) t = t.thenApply(v -> v);
            h.cancel(true);
            return state(t) + " " + t.isCancelled();
        });
        show("deep obtrude", () -> {
            CompletableFuture<Integer> h = cf(), t = h;
            for (int i = 0; i < depth; i++) t = t.thenApply(v -> v + 1);
            h.obtrudeValue(1);
            return t.join();
        });
        show("wide either", () -> {
            CompletableFuture<Integer> a = cf(), b = cf();
            int[] c = new int[1];
            for (int i = 0; i < 2000; i++) a.acceptEither(b, v -> c[0]++);
            a.complete(1);
            return c[0] + " " + counts(a, b);
        });
        show("wide anyOf", () -> {
            CompletableFuture<?>[] a = new CompletableFuture<?>[5000];
            for (int i = 0; i < a.length; i++) a[i] = cf();
            CompletableFuture<Object> any = CompletableFuture.anyOf(a);
            ((CompletableFuture<Object>) a[2500]).complete(1);
            return any.join() + " " + counts(a[0], a[2499], a[2501], a[4999]);
        });
    }

    public static void main(String[] args) {
        shapes();
        subclasses();
        reads();
        deep();
    }
}
