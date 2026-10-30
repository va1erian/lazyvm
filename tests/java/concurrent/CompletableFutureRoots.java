package concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/** Where a firing keeps what it has still to fire, who else can reach it, and what that costs. */
public class CompletableFutureRoots {
    interface Body { Object run() throws Throwable; }
    interface Visit { void go(CompletableFuture<String> s, CompletableFuture<String> mid) throws Exception; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + String.valueOf(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static <T> CompletableFuture<T> cf() { return new CompletableFuture<T>(); }

    static String name() { return Thread.currentThread().getName(); }

    static void await(CountDownLatch l) {
        try { l.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }

    static final Executor DIRECT = Runnable::run;
    static final Executor RUN_THEN_THROW = r -> { r.run(); throw new IllegalStateException("after"); };
    static final Executor REJECT = r -> { throw new RejectedExecutionException("no"); };

    /** s, mid below it, a blocker and another below that: a thread stays in the blocker while another visits. */
    static void visited(String label, Visit visit) {
        show(label, () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> mid = s.thenApply(v -> v);
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            int[] counts = new int[2];
            mid.thenRun(() -> {
                counts[0] = s.getNumberOfDependents();
                counts[1] = mid.getNumberOfDependents();
                in.countDown();
                await(out);
                log.add("blocker on " + name());
            });
            CompletableFuture<Void> other = mid.thenRun(() -> log.add("other on " + name()));
            Thread firer = new Thread(() -> s.complete("v"), "firer");
            firer.start();
            await(in);
            Thread visitor = new Thread(() -> {
                try { visit.go(s, mid); } catch (Exception e) { log.add(e.toString()); }
            }, "visitor");
            visitor.start();
            visitor.join();
            String seen = "in blocker s=" + counts[0] + " mid=" + counts[1] + ", after visit other done "
                    + other.isDone() + " s=" + s.getNumberOfDependents() + " mid=" + mid.getNumberOfDependents();
            out.countDown();
            firer.join();
            return seen + " " + log;
        });
    }

    static void visits() {
        visited("visit none", (s, m) -> { });
        visited("visit s.complete", (s, m) -> s.complete("w"));
        visited("visit s.completeExceptionally", (s, m) -> s.completeExceptionally(new Error("e")));
        visited("visit s.cancel", (s, m) -> s.cancel(false));
        visited("visit s.obtrudeValue", (s, m) -> s.obtrudeValue("o"));
        visited("visit s.obtrudeException", (s, m) -> s.obtrudeException(new Error("e")));
        visited("visit s async rejected", (s, m) -> s.thenRunAsync(() -> { }, REJECT));
        visited("visit s async direct", (s, m) -> s.thenRunAsync(() -> { }, DIRECT));
        visited("visit s async queued", (s, m) -> s.thenRunAsync(() -> { }, r -> { }));
        visited("visit mid.complete", (s, m) -> m.complete("w"));
        visited("visit s.thenRun", (s, m) -> s.thenRun(() -> { }));
        visited("visit mid.thenRun", (s, m) -> m.thenRun(() -> { }));
        visited("visit s.join", (s, m) -> s.join());
        visited("visit mid.join", (s, m) -> m.join());
        show("two levels below, a visit to the root", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> m1 = s.thenApply(v -> v);
            CompletableFuture<String> m2 = m1.thenApply(v -> v);
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            m2.thenRun(() -> { in.countDown(); await(out); });
            m2.thenRun(() -> log.add("other on " + name()));
            Thread firer = new Thread(() -> s.complete("v"), "firer");
            firer.start();
            await(in);
            String c = s.getNumberOfDependents() + " " + m1.getNumberOfDependents() + " " + m2.getNumberOfDependents();
            m1.complete("w");
            String afterMiddle = log.toString();
            Thread visitor = new Thread(() -> s.complete("w"), "visitor");
            visitor.start();
            visitor.join();
            out.countDown();
            firer.join();
            return c + " " + afterMiddle + " " + log;
        });
        show("root's list under what moved onto it", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("old of s, s=" + s.getNumberOfDependents()));
            CompletableFuture<String> mid = s.thenApply(v -> v);
            mid.thenRun(() -> log.add("m0, s=" + s.getNumberOfDependents()));
            mid.thenRun(() -> log.add("m1, s=" + s.getNumberOfDependents()));
            s.complete("v");
            return log;
        });
    }

    /** A step finds its target settled: the target's list goes on in that firing. */
    static void settledTargets() {
        show("step finds its target settled, in the target's own firing", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> d = s.thenApply(v -> v);
            List<String> log = new ArrayList<>();
            d.thenRun(() -> log.add("other"));
            d.thenRun(() -> { log.add("in"); s.complete("v"); log.add("out"); });
            d.complete("direct");
            return log;
        });
        show("handed over, then found again through the other list", () -> {
            CompletableFuture<String> a = cf(), b = cf();
            List<String> log = new ArrayList<>();
            List<Runnable> queue = new ArrayList<>();
            CompletableFuture<Void> d = a.runAfterBothAsync(b, () -> log.add("fn"), queue::add);
            d.thenRun(() -> log.add("other"));
            d.thenRun(() -> { log.add("in"); b.complete("v"); log.add("out"); });
            b.obtrudeValue("b");
            a.complete("v");
            int handed = queue.size();
            d.complete(null);
            return handed + " " + log + " " + b.getNumberOfDependents();
        });
        show("task of a compose left pending does not help", () -> {
            CompletableFuture<String> s = cf(), inner = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("sibling"));
            CompletableFuture<String> d = s.thenComposeAsync(v -> inner, r -> {
                r.run();
                log.add("task ran");
                throw new IllegalStateException("after");
            });
            s.complete("v");
            String c = s.getNumberOfDependents() + " " + d.isCompletedExceptionally();
            inner.complete("i");
            return log + " " + c + " " + d.isCompletedExceptionally();
        });
    }

    static void addRunAfterBoth(boolean receiver, CompletableFuture<Object> a, CompletableFuture<Object> b, Runnable r) {
        if (receiver) a.runAfterBoth(b, r); else b.runAfterBoth(a, r);
    }

    /** What an either step's task does with each source, and when. */
    static void taskEnds() {
        show("task end, either, receiver pending, through the other", () -> {
            CompletableFuture<Object> s = cf(), y = cf();
            List<String> l = new ArrayList<>();
            s.thenRun(() -> l.add("sib1=" + y.getNumberOfDependents()));
            s.thenRun(() -> l.add("sib0=" + y.getNumberOfDependents()));
            CompletableFuture<Void> d = y.runAfterEitherAsync(s, () -> l.add("fn=" + y.getNumberOfDependents()), DIRECT);
            d.thenRun(() -> l.add("own=" + y.getNumberOfDependents()));
            s.complete("v");
            return l + " " + y.getNumberOfDependents();
        });
        show("task end, either, other pending, through the receiver", () -> {
            CompletableFuture<Object> s = cf(), y = cf();
            List<String> l = new ArrayList<>();
            s.thenRun(() -> l.add("sib=" + y.getNumberOfDependents()));
            s.runAfterEitherAsync(y, () -> l.add("fn=" + y.getNumberOfDependents()), DIRECT);
            s.complete("v");
            return l + " " + y.getNumberOfDependents();
        });
        show("task end, either, seen from the executor", () -> {
            CompletableFuture<Object> s = cf(), y = cf();
            List<String> l = new ArrayList<>();
            s.thenRun(() -> l.add("sib=" + y.getNumberOfDependents()));
            y.runAfterEitherAsync(s, () -> { }, r -> {
                l.add("pre=" + y.getNumberOfDependents());
                r.run();
                l.add("post=" + y.getNumberOfDependents());
            });
            s.complete("v");
            return l;
        });
        show("task run from a sibling", () -> {
            CompletableFuture<Object> s = cf(), y = cf();
            List<String> l = new ArrayList<>();
            List<Runnable> queue = new ArrayList<>();
            s.thenRun(() -> l.add("sib1=" + y.getNumberOfDependents()));
            s.thenRun(() -> { queue.get(0).run(); l.add("sib0 after task=" + y.getNumberOfDependents()); });
            y.runAfterEitherAsync(s, () -> l.add("fn=" + y.getNumberOfDependents()), queue::add);
            s.complete("v");
            return l;
        });
        show("task's ended entry found again does nothing", () -> {
            CompletableFuture<Object> s = cf(), y = cf();
            List<String> l = new ArrayList<>();
            s.thenRun(() -> { y.complete("y"); l.add("y done, count " + y.getNumberOfDependents()); });
            CompletableFuture<Void> d = y.runAfterEitherAsync(s, () -> l.add("fn"), DIRECT);
            d.thenRun(() -> l.add("own0"));
            d.thenRun(() -> l.add("own1"));
            s.complete("v");
            return l;
        });
        show("rejected, either, leaves at once", () -> {
            CompletableFuture<Object> s = cf(), y = cf();
            List<String> l = new ArrayList<>();
            s.thenRun(() -> l.add("sib=" + y.getNumberOfDependents()));
            y.runAfterEitherAsync(s, () -> { }, REJECT);
            s.complete("v");
            return l;
        });
        for (int receiver = 0; receiver < 2; receiver++) {
            for (int fail = 0; fail < 2; fail++) {
                boolean r = receiver == 0, f = fail == 1;
                show("both, the second settled inside a task's end, receiver " + r + " failed " + f, () -> bothInTaskEnd(r, f));
            }
        }
    }

    /** a settled by a task whose end visits s, whose sibling settles b: a both step on a and b fires from b's list. */
    static String bothInTaskEnd(boolean receiver, boolean fail) {
        CompletableFuture<Object> s = cf(), b = cf();
        List<String> l = new ArrayList<>();
        CompletableFuture<?>[] a = new CompletableFuture<?>[1];
        s.thenRun(() -> { b.complete("b"); l.add("sib a=" + a[0].getNumberOfDependents() + " b=" + b.getNumberOfDependents()); });
        CompletableFuture<Object> mine = s.thenApplyAsync(v -> {
            if (fail) throw new IllegalStateException("x");
            return v;
        }, DIRECT);
        a[0] = mine;
        mine.thenRun(() -> l.add("a's own"));
        addRunAfterBoth(receiver, mine, b, () -> l.add("fn a=" + mine.getNumberOfDependents()));
        s.complete("v");
        return l + " " + mine.getNumberOfDependents();
    }

    /** Any of these at quadratic cost runs out the test's time. */
    static void wide() {
        int n = 20000;
        show("wide either, settled by the receiver", () -> {
            CompletableFuture<Integer> f = cf(), g = cf();
            int[] c = new int[1];
            for (int i = 0; i < n; i++) f.acceptEither(g, v -> c[0]++);
            f.complete(1);
            return c[0] + " " + f.getNumberOfDependents() + " " + g.getNumberOfDependents();
        });
        show("wide either, settled by the other", () -> {
            CompletableFuture<Integer> f = cf(), g = cf();
            int[] c = new int[1];
            for (int i = 0; i < n; i++) f.acceptEither(g, v -> c[0]++);
            g.complete(1);
            return c[0] + " " + f.getNumberOfDependents() + " " + g.getNumberOfDependents();
        });
        show("many requests against one shutdown, shuffled", () -> {
            CompletableFuture<Integer> shutdown = cf();
            List<CompletableFuture<Integer>> requests = new ArrayList<>();
            int[] c = new int[1];
            for (int i = 0; i < n; i++) {
                CompletableFuture<Integer> f = cf();
                f.acceptEither(shutdown, v -> c[0]++);
                requests.add(f);
            }
            Collections.shuffle(requests, new Random(1));
            String mid = "";
            for (int i = 0; i < n; i++) {
                requests.get(i).complete(1);
                if (i == n / 2) mid = "" + shutdown.getNumberOfDependents();
            }
            return c[0] + " " + mid + " " + shutdown.getNumberOfDependents();
        });
        show("anyOf of one future, many times", () -> {
            CompletableFuture<Object> d = cf();
            CompletableFuture<?>[] all = new CompletableFuture<?>[n];
            Arrays.fill(all, d);
            CompletableFuture<Object> any = CompletableFuture.anyOf(all);
            String before = "" + d.getNumberOfDependents();
            d.complete(1);
            return before + " " + any.join() + " " + d.getNumberOfDependents();
        });
    }

    static void recurse(int depth, Runnable r) {
        if (depth == 0) r.run();
        else recurse(depth - 1, r);
    }

    static boolean fits(int depth) {
        try {
            recurse(depth, () -> { });
            return true;
        } catch (StackOverflowError e) {
            return false;
        }
    }

    /** Completes at every depth up to the end of the stack, then visits: what is still pending is lost. */
    static int lostAfterOverflow() {
        int lo = 100, hi = 1 << 22;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (fits(m)) lo = m; else hi = m;
        }
        int worst = 0;
        for (int rep = 0; rep < 2; rep++) {
            for (int d = Math.max(0, lo - 300); d <= lo + 5; d++) {
                CompletableFuture<String> s = cf();
                CompletableFuture<String> mid = s.thenApply(v -> v);
                List<CompletableFuture<?>> stages = new ArrayList<>();
                stages.add(mid);
                for (int i = 0; i < 20; i++) stages.add(mid.thenApply(v -> v + "1").thenApply(v -> v + "2"));
                try { recurse(d, () -> s.complete("v")); } catch (StackOverflowError e) { /* looked at below */ }
                if (!s.isDone()) continue;
                s.complete("again");
                mid.complete("again");
                int pending = 0;
                for (CompletableFuture<?> f : stages) if (!f.isDone()) pending++;
                worst = Math.max(worst, pending);
            }
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        visits();
        settledTargets();
        taskEnds();
        wide();
        show("out of stack in a firing, then a visit: pending", CompletableFutureRoots::lostAfterOverflow);
    }
}
