package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Below the first level the order is not pinned (the Javadoc promises nothing and any
     * order would do): only that a stage fires before those depending on it and that each
     * subtree fires as one run.
     */
    static void tree() {
        List<String> log = new ArrayList<>();
        CompletableFuture<String> root = new CompletableFuture<>();
        String[] names = {"a", "b", "c"};
        for (String top : names) {
            CompletableFuture<Void> t = root.thenRun(() -> log.add(top));
            for (int i = 0; i < 3; i++) {
                String mid = top + i;
                CompletableFuture<Void> m = t.thenRun(() -> log.add(mid));
                for (int j = 0; j < 2; j++) {
                    String leaf = mid + j;
                    m.thenRun(() -> log.add(leaf));
                }
            }
        }
        root.complete("v");
        show("tree size", log::size);
        show("tree top level", () -> {
            List<String> tops = new ArrayList<>();
            for (String s : log) if (s.length() == 1) tops.add(s);
            return tops;
        });
        show("tree parents first", () -> {
            for (int i = 0; i < log.size(); i++) {
                String s = log.get(i);
                if (s.length() > 1 && log.indexOf(s.substring(0, s.length() - 1)) > i) return false;
            }
            return true;
        });
        show("tree subtrees contiguous", () -> {
            for (String prefixSource : log) {
                int first = -1, last = -1, count = 0;
                for (int i = 0; i < log.size(); i++) {
                    if (log.get(i).startsWith(prefixSource)) {
                        if (first < 0) first = i;
                        last = i;
                        count++;
                    }
                }
                if (last - first + 1 != count) return false;
            }
            return true;
        });
        List<String> chain = new ArrayList<>();
        CompletableFuture<String> c = new CompletableFuture<>();
        CompletableFuture<Void> x = c.thenRun(() -> chain.add("x"));
        x.thenRun(() -> chain.add("x1")).thenRun(() -> chain.add("x2"));
        c.thenRun(() -> chain.add("y"));
        c.complete("v");
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
        int depth = 20000;
        CompletableFuture<Integer> head = new CompletableFuture<>();
        CompletableFuture<Integer> tail = head;
        for (int i = 0; i < depth; i++) tail = tail.thenApply(v -> v + 1);
        head.complete(0);
        CompletableFuture<Integer> end = tail;
        show("deep chain", end::join);
        CompletableFuture<String> fan = new CompletableFuture<>();
        int[] count = new int[1];
        for (int i = 0; i < depth; i++) fan.thenRun(() -> count[0]++);
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
        tree();
        reentrant();
        counts();
        deep();
    }
}
