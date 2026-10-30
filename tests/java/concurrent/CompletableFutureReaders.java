package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** Threads blocked in get and join: how they count, when they are let go, what they do on the way out, interrupts, pool workers. */
public class CompletableFutureReaders {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + String.valueOf(body.run()).replaceAll("@[0-9a-f]+", "@H")); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static <T> CompletableFuture<T> cf() { return new CompletableFuture<T>(); }

    static void blocked(Thread t) throws InterruptedException {
        for (;;) {
            Thread.State s = t.getState();
            if (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING) return;
            Thread.sleep(1);
        }
    }

    static Thread start(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.start();
        return t;
    }

    static String name() { return Thread.currentThread().getName(); }

    static void await(CountDownLatch l) {
        try { l.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }

    /** A read that blocks: 0 get, 1 timed get, 2 join. */
    static Runnable reading(CompletableFuture<String> s, int how) {
        return () -> {
            try {
                if (how == 0) s.get();
                else if (how == 1) s.get(20, TimeUnit.SECONDS);
                else s.join();
            } catch (Exception e) {
                // Only the state of `s` is of interest here.
            }
        };
    }

    static void counts() {
        for (int how = 0; how < 3; how++) {
            int h = how;
            show("reader counts, kind " + how, () -> {
                CompletableFuture<String> s = cf();
                Thread t = start("r", reading(s, h));
                blocked(t);
                String r = s.getNumberOfDependents() + " " + s;
                s.complete("v");
                t.join();
                return r + " / " + s.getNumberOfDependents();
            });
        }
        show("interrupted reader leaves the count", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("k0"));
            Thread t = start("r", reading(s, 0));
            blocked(t);
            s.thenRun(() -> log.add("k1"));
            String c = "" + s.getNumberOfDependents();
            t.interrupt();
            t.join();
            c += " " + s.getNumberOfDependents();
            s.complete("v");
            return c + " " + log;
        });
        show("timed out reader leaves the count", () -> {
            CompletableFuture<String> s = cf();
            s.thenRun(() -> { });
            try { s.get(1, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { return e + " " + s.getNumberOfDependents() + " " + s; }
            return "no";
        });
    }

    /** A dependent that holds its thread until released. */
    static void holding(CompletableFuture<String> s, List<String> log, CountDownLatch in, CountDownLatch out) {
        s.thenRun(() -> { in.countDown(); await(out); log.add("holder on " + name()); });
    }

    interface Read { void from(CompletableFuture<String> s) throws Exception; }

    /**
     * After its release the reader races the completer for what the list holds. The holder keeps only the
     * completer back, for as long as the reader takes, so whoever takes it the reader has run a callback
     * before it returns, if it helps at all.
     */
    static String readerHelps(Read read) throws Exception {
        CompletableFuture<String> s = cf();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch returned = new CountDownLatch(1);
        s.thenRun(() -> log.add("k0 on " + name()));
        s.thenRun(() -> {
            if (name().equals("completer")) await(returned);
            log.add("holder on " + name());
        });
        boolean[] helped = new boolean[1];
        Thread r = start("reader", () -> {
            try { read.from(s); } catch (Exception e) { log.add(e.toString()); }
            helped[0] = log.toString().contains("on reader");
            returned.countDown();
        });
        blocked(r);
        Thread c = start("completer", () -> s.complete("v"));
        r.join();
        c.join();
        return "helped before returning " + helped[0] + ", callbacks " + log.size();
    }

    static void release() throws Exception {
        show("reader attached last goes first and helps", () -> readerHelps(s -> s.join()));
        show("timed reader helps too", () -> readerHelps(s -> s.get(20, TimeUnit.SECONDS)));
        show("reader attached before waits its turn", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            s.thenRun(() -> log.add("k0"));
            Thread r = start("reader", () -> { s.join(); log.add("reader returned"); });
            blocked(r);
            holding(s, log, in, out);
            Thread c = start("completer", () -> s.complete("v"));
            await(in);
            r.join(200);
            log.add("reader alive " + r.isAlive());
            out.countDown();
            c.join();
            r.join();
            log.remove("reader returned");
            return log;
        });
        show("late reader of a settled stage does not help", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            s.thenRun(() -> log.add("k0 on " + name()));
            holding(s, log, in, out);
            Thread c = start("completer", () -> s.complete("v"));
            await(in);
            Thread r = start("late", () -> { s.join(); log.add("late returned"); });
            r.join();
            out.countDown();
            c.join();
            return log;
        });
        show("late complete helps", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            s.thenRun(() -> log.add("k0 on " + name()));
            holding(s, log, in, out);
            Thread c = start("completer", () -> s.complete("v"));
            await(in);
            Thread r = start("helper", () -> log.add("helper " + s.complete("w")));
            r.join();
            out.countDown();
            c.join();
            return log;
        });
        show("below a firing, reader in first-attached order", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> m = s.thenApply(v -> v);
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            Thread r = start("reader", () -> { m.join(); log.add("reader returned"); });
            blocked(r);
            holding(m, log, in, out);
            m.thenRun(() -> log.add("k1 on " + name()));
            Thread c = start("completer", () -> s.complete("v"));
            await(in);
            r.join();
            out.countDown();
            c.join();
            return log;
        });
        show("below a firing, reader after a holder", () -> {
            CompletableFuture<String> s = cf();
            CompletableFuture<String> m = s.thenApply(v -> v);
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
            holding(m, log, in, out);
            Thread r = start("reader", () -> { m.join(); log.add("reader returned"); });
            blocked(r);
            m.thenRun(() -> log.add("k1 on " + name()));
            Thread c = start("completer", () -> s.complete("v"));
            await(in);
            r.join(200);
            log.add("reader alive " + r.isAlive());
            out.countDown();
            c.join();
            r.join();
            log.remove("reader returned");
            return log;
        });
        show("obtrude releases readers", () -> {
            CompletableFuture<String> s = cf();
            String[] got = new String[2];
            Thread a = start("a", () -> got[0] = s.join());
            Thread b = start("b", () -> { try { got[1] = s.get(); } catch (Exception e) { got[1] = e.toString(); } });
            blocked(a);
            blocked(b);
            s.obtrudeValue("o");
            a.join();
            b.join();
            return got[0] + " " + got[1];
        });
    }

    static void interrupts() {
        show("get interrupted", () -> {
            CompletableFuture<String> s = cf();
            String[] r = new String[1];
            Thread t = start("t", () -> {
                try { r[0] = "v=" + s.get(); } catch (Throwable e) { r[0] = e + " flag=" + Thread.currentThread().isInterrupted(); }
            });
            blocked(t);
            t.interrupt();
            t.join();
            return r[0] + " " + s.isDone();
        });
        show("timed get interrupted", () -> {
            CompletableFuture<String> s = cf();
            String[] r = new String[1];
            Thread t = start("t", () -> {
                try { r[0] = "v=" + s.get(20, TimeUnit.SECONDS); } catch (Throwable e) { r[0] = e + " flag=" + Thread.currentThread().isInterrupted(); }
            });
            blocked(t);
            t.interrupt();
            t.join();
            return r[0];
        });
        show("join keeps waiting, flag kept", () -> {
            CompletableFuture<String> s = cf();
            String[] r = new String[1];
            Thread t = start("t", () -> r[0] = s.join() + " flag=" + Thread.currentThread().isInterrupted());
            blocked(t);
            t.interrupt();
            Thread.sleep(20);
            blocked(t);
            int c = s.getNumberOfDependents();
            s.complete("v");
            t.join();
            return c + " " + r[0];
        });
        show("join flagged at entry", () -> {
            CompletableFuture<String> s = cf();
            String[] r = new String[1];
            Thread t = start("t", () -> {
                Thread.currentThread().interrupt();
                try { r[0] = s.join(); } catch (Throwable e) { r[0] = e.toString(); }
                r[0] += " flag=" + Thread.currentThread().isInterrupted();
            });
            blocked(t);
            s.completeExceptionally(new Error("e"));
            t.join();
            return r[0];
        });
        show("timed zero, flagged and pending", () -> {
            Thread.currentThread().interrupt();
            try { return new CompletableFuture<String>().get(0, TimeUnit.SECONDS); }
            catch (Exception e) { return e + " flag=" + Thread.interrupted(); }
        });
        show("timed null unit keeps the flag", () -> {
            Thread.currentThread().interrupt();
            try { return new CompletableFuture<String>().get(1, null); }
            catch (Exception e) { return e + " flag=" + Thread.interrupted(); }
        });
        show("timed negative", () -> new CompletableFuture<String>().get(-1, TimeUnit.SECONDS));
        show("timed huge", () -> {
            CompletableFuture<String> s = cf();
            start("t", () -> s.complete("v"));
            return s.get(Long.MAX_VALUE, TimeUnit.DAYS);
        });
        show("complete flagged, in a callback", () -> {
            CompletableFuture<String> s = cf();
            List<String> log = new ArrayList<>();
            s.thenRun(() -> log.add("flag=" + Thread.currentThread().isInterrupted()));
            Thread.currentThread().interrupt();
            boolean c = s.complete("v");
            return c + " " + log + " flag=" + Thread.interrupted();
        });
    }

    static void workers() {
        show("worker join, settled by a sibling, parallelism 1", () -> {
            ForkJoinPool p = new ForkJoinPool(1);
            try {
                CompletableFuture<String> g = cf();
                CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> {
                    CompletableFuture.runAsync(() -> g.complete("sib"), p);
                    return g.join();
                }, p);
                return a.get(20, TimeUnit.SECONDS);
            } finally {
                p.shutdownNow();
            }
        });
        show("worker get, settled by a sibling, parallelism 1", () -> {
            ForkJoinPool p = new ForkJoinPool(1);
            try {
                CompletableFuture<String> g = cf();
                CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> {
                    CompletableFuture.runAsync(() -> g.complete("sib"), p);
                    try { return g.get(); } catch (Exception e) { return e.toString(); }
                }, p);
                return a.get(20, TimeUnit.SECONDS);
            } finally {
                p.shutdownNow();
            }
        });
        show("worker timed get, settled by a sibling, parallelism 1", () -> {
            ForkJoinPool p = new ForkJoinPool(1);
            try {
                CompletableFuture<String> g = cf();
                CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> {
                    CompletableFuture.runAsync(() -> g.complete("sib"), p);
                    try { return g.get(15, TimeUnit.SECONDS); } catch (Exception e) { return e.toString(); }
                }, p);
                return a.get(20, TimeUnit.SECONDS);
            } finally {
                p.shutdownNow();
            }
        });
        show("worker interrupted in get", () -> {
            ForkJoinPool p = new ForkJoinPool(2);
            try {
                CompletableFuture<String> g = cf();
                String[] r = new String[1];
                Thread[] w = new Thread[1];
                CountDownLatch in = new CountDownLatch(1);
                CompletableFuture<Void> a = CompletableFuture.runAsync(() -> {
                    w[0] = Thread.currentThread();
                    in.countDown();
                    try { g.get(); } catch (Exception e) { r[0] = e + " flag=" + Thread.currentThread().isInterrupted(); }
                }, p);
                in.await();
                blocked(w[0]);
                w[0].interrupt();
                a.get(20, TimeUnit.SECONDS);
                return r[0];
            } finally {
                p.shutdownNow();
            }
        });
    }

    static void races() {
        show("many complete one", () -> {
            CompletableFuture<Integer> s = cf();
            int[] wins = new int[1], fires = new int[1];
            for (int i = 0; i < 100; i++) s.thenRun(() -> { synchronized (fires) { fires[0]++; } });
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i++) {
                int k = i;
                ts[i] = start("t" + i, () -> { if (s.complete(k)) synchronized (wins) { wins[0]++; } });
            }
            for (Thread t : ts) t.join();
            return wins[0] + " " + fires[0];
        });
        show("attach races settle", () -> {
            int bad = 0;
            for (int r = 0; r < 100; r++) {
                CompletableFuture<Integer> s = cf();
                int[] c = new int[1];
                Thread a = start("a", () -> { for (int i = 0; i < 20; i++) s.thenRun(() -> { synchronized (c) { c[0]++; } }); });
                Thread b = start("b", () -> s.complete(1));
                a.join();
                b.join();
                synchronized (c) { if (c[0] != 20) bad++; }
            }
            return bad;
        });
        show("two sources race", () -> {
            int bad = 0;
            for (int r = 0; r < 100; r++) {
                CompletableFuture<Integer> s = cf(), q = cf();
                int[] c = new int[1];
                s.acceptEither(q, v -> { synchronized (c) { c[0]++; } });
                s.thenAcceptBoth(q, (u, v) -> { synchronized (c) { c[0] += 10; } });
                Thread a = start("a", () -> s.complete(1));
                Thread b = start("b", () -> q.complete(2));
                a.join();
                b.join();
                synchronized (c) { if (c[0] != 11) bad++; }
            }
            return bad;
        });
        show("reader races settle", () -> {
            int bad = 0;
            for (int r = 0; r < 100; r++) {
                CompletableFuture<Integer> s = cf();
                int[] c = new int[1];
                Thread a = start("a", () -> c[0] = s.join());
                Thread b = start("b", () -> s.complete(1));
                a.join();
                b.join();
                if (c[0] != 1) bad++;
            }
            return bad;
        });
    }

    public static void main(String[] args) throws Exception {
        counts();
        release();
        interrupts();
        workers();
        races();
    }
}
