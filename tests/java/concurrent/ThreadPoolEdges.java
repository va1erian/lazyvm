package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** ThreadPoolExecutor: what the Javadoc leaves open. Hooks that throw, the terminated hook, odd factories and queues, sizes. */
public class ThreadPoolEdges {
    interface Body { Object run() throws Throwable; }
    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }
    interface Cond { boolean ok() throws Exception; }
    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!c.ok()) { if (System.nanoTime() > end) throw new AssertionError("stuck"); Thread.sleep(1); }
    }
    static <X extends Throwable> void sneak(Throwable t) throws X { throw (X) t; }
    static BlockingQueue<Runnable> lbq() { return new LinkedBlockingQueue<>(); }
    static class Named implements Runnable {
        final String s; final Runnable r;
        Named(String s, Runnable r) { this.s = s; this.r = r; }
        public void run() { r.run(); }
        public String toString() { return s; }
    }
    static void await(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    static class Factory implements ThreadFactory {
        int made; final List<String> uncaught = Collections.synchronizedList(new ArrayList<>());
        public Thread newThread(Runnable r) { Thread t = new Thread(r, "w" + (++made));
            t.setUncaughtExceptionHandler((th, e) -> uncaught.add(th.getName() + ":" + e)); return t; }
    }

    static void ctor() {
        show("ctor all bad", () -> new ThreadPoolExecutor(-1, 1, 1, null, null));
        show("ctor neg keep null unit", () -> new ThreadPoolExecutor(1, 1, -1, null, lbq()));
        show("ctor null queue null unit", () -> new ThreadPoolExecutor(1, 1, 1, null, null));
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 2, 1, TimeUnit.SECONDS, lbq());
        show("setKeep -1 null", () -> { p.setKeepAliveTime(-1, null); return "no"; });
        show("setKeep 1 null", () -> { p.setKeepAliveTime(1, null); return "no"; });
        show("getKeep null", () -> p.getKeepAliveTime(null));
        p.allowCoreThreadTimeOut(true);
        show("setKeep 0 null allowCore", () -> { p.setKeepAliveTime(0, null); return "no"; });
        show("setMax equal core", () -> { p.setMaximumPoolSize(1); return p.getMaximumPoolSize(); });
        show("remove null", () -> p.remove(null));
        show("taskCount fresh", p::getTaskCount);
        p.shutdown();
        ThreadFactory fa = Executors.defaultThreadFactory();
        String n1 = fa.newThread(() -> {}).getName();
        try { new ThreadPoolExecutor(-1, 1, 1, TimeUnit.SECONDS, lbq()); } catch (IllegalArgumentException e) { }
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, lbq(), fa);
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, lbq());
        String n2 = Executors.defaultThreadFactory().newThread(() -> {}).getName();
        show("pool numbers apart", () -> Integer.parseInt(n2.split("-")[1]) - Integer.parseInt(n1.split("-")[1]));
    }

    static void hooks() throws Exception {
        for (String mode : new String[] {"beforeThrows", "afterThrows", "bothThrow", "sneaky", "error", "ok"}) {
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            Factory f = new Factory();
            ThreadPoolExecutor[] box = new ThreadPoolExecutor[1];
            ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq(), f) {
                @Override protected void beforeExecute(Thread t, Runnable r) {
                    log.add("before " + r + " active=" + getActiveCount() + " done=" + getCompletedTaskCount() + " cur=" + (t == Thread.currentThread()));
                    if (mode.equals("beforeThrows")) throw new IllegalStateException("before");
                }
                @Override protected void afterExecute(Runnable r, Throwable e) {
                    log.add("after " + r + " " + e + " active=" + getActiveCount() + " done=" + getCompletedTaskCount());
                    if (mode.equals("afterThrows") || mode.equals("bothThrow")) throw new IllegalStateException("after");
                }
                @Override protected void terminated() { log.add("terminated size=" + getPoolSize()); }
            };
            p.execute(new Named("t", () -> {
                log.add("task");
                if (mode.equals("bothThrow")) throw new RuntimeException("task");
                if (mode.equals("sneaky")) ThreadPoolEdges.<RuntimeException>sneak(new java.io.IOException("io"));
                if (mode.equals("error")) throw new AssertionError("err");
            }));
            until(() -> p.getCompletedTaskCount() == 1);
            until(() -> mode.equals("ok") || f.uncaught.size() == 1);
            until(() -> mode.equals("ok") || f.made == 2);
            until(() -> p.getPoolSize() == 1);
            // A new worker counts as active until it first looks for work.
            until(() -> p.getActiveCount() == 0);
            show(mode + " log", () -> log);
            show(mode + " uncaught", () -> f.uncaught);
            show(mode + " made", () -> f.made);
            show(mode + " p", () -> p);
            p.shutdown();
            p.awaitTermination(20, TimeUnit.SECONDS);
            show(mode + " end", () -> log.get(log.size() - 1) + " " + p);
        }
    }

    static void terminatedHook() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq()) {
            @Override protected void terminated() {
                log.add("thread=" + Thread.currentThread().getName().equals("main")
                    + " isTerminated=" + isTerminated() + " isTerminating=" + isTerminating() + " isShutdown=" + isShutdown()
                    + " size=" + getPoolSize() + " s=" + ThreadPoolEdges.n(this));
                try { log.add("await0=" + awaitTermination(0, TimeUnit.SECONDS)); } catch (Exception e) { log.add("" + e); }
            }
        };
        p.shutdown();
        show("hook log (no workers)", () -> log);
        log.clear();
        p.shutdown();
        show("hook once", () -> log);
        ThreadPoolExecutor q = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq()) {
            @Override protected void terminated() { throw new IllegalStateException("hook"); }
        };
        show("hook throws in shutdown", () -> { q.shutdown(); return "no"; });
        show("after hook throw", () -> q + " " + q.isTerminated());
        show("after hook throw await", () -> q.awaitTermination(0, TimeUnit.SECONDS));

        CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
        ThreadPoolExecutor b = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq()) {
            @Override protected void terminated() { in.countDown(); ThreadPoolEdges.await(out); }
        };
        b.prestartCoreThread();
        b.shutdown();
        in.await();
        String[] names = {"isTerminated", "isTerminating", "isShutdown", "getPoolSize", "getActiveCount", "getLargestPoolSize", "getCompletedTaskCount", "getTaskCount", "toString", "await0", "shutdown", "shutdownNow", "prestart", "setCore", "execute", "getCore"};
        Body[] bodies = {b::isTerminated, b::isTerminating, b::isShutdown, b::getPoolSize, b::getActiveCount, b::getLargestPoolSize,
            b::getCompletedTaskCount, b::getTaskCount, b::toString, () -> b.awaitTermination(0, TimeUnit.SECONDS),
            () -> { b.shutdown(); return "ok"; }, b::shutdownNow, b::prestartCoreThread, () -> { b.setCorePoolSize(1); return "ok"; },
            () -> { try { b.execute(() -> {}); return "acc"; } catch (RejectedExecutionException e) { return "rej"; } }, b::getCorePoolSize};
        List<Thread> started = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String nm = names[i]; Body bd = bodies[i];
            String[] r = new String[1];
            Thread t = new Thread(() -> { try { r[0] = n(bd.run()); } catch (Throwable e) { r[0] = "" + e; } });
            t.setDaemon(true); t.start(); started.add(t);
            until(() -> r[0] != null || t.getState() == Thread.State.BLOCKED || t.getState() == Thread.State.WAITING);
            show("during hook " + nm, () -> r[0] != null ? r[0] : "(blocked)");
        }
        out.countDown();
        show("after blocked hook", () -> b.awaitTermination(20, TimeUnit.SECONDS));
    }

    static void hookFlag() throws Exception {
        boolean[] seen = new boolean[2];
        CountDownLatch e = new CountDownLatch(1), g = new CountDownLatch(1), end = new CountDownLatch(1);
        ThreadPoolExecutor a = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq()) {
            @Override protected void terminated() { seen[0] = Thread.currentThread().isInterrupted(); end.countDown(); }
        };
        a.execute(() -> {
            e.countDown();
            boolean flagged = false;
            while (g.getCount() > 0) {
                try { g.await(); } catch (InterruptedException x) { flagged = true; }
            }
            if (flagged) Thread.currentThread().interrupt();
        });
        e.await();
        a.shutdownNow();
        g.countDown();
        end.await();
        show("hook flag after stop", () -> seen[0]);
        CountDownLatch e2 = new CountDownLatch(1), g2 = new CountDownLatch(1), end2 = new CountDownLatch(1);
        ThreadPoolExecutor b = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq()) {
            @Override protected void terminated() { seen[1] = Thread.currentThread().isInterrupted(); end2.countDown(); }
        };
        b.execute(() -> { e2.countDown(); ThreadPoolEdges.await(g2); Thread.currentThread().interrupt(); });
        e2.await();
        b.shutdown();
        g2.countDown();
        end2.await();
        show("hook flag from task's own interrupt", () -> seen[1]);
    }

    static void factories() throws Exception {
        int[] seen = new int[1];
        ThreadPoolExecutor[] box = new ThreadPoolExecutor[1];
        ThreadFactory peek = r -> { seen[0] = box[0].getPoolSize(); return new Thread(r); };
        box[0] = new ThreadPoolExecutor(1, 2, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(1), peek);
        box[0].prestartCoreThread();
        show("size inside factory", () -> seen[0]);
        box[0].shutdown();
        ThreadPoolExecutor t = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq(), r -> { throw new IllegalStateException("factory"); });
        show("factory throws execute", () -> { t.execute(new Named("x", () -> {})); return "no"; });
        show("factory throws state", () -> t + " " + t.getQueue());
        show("factory throws prestart", t::prestartCoreThread);
        show("factory throws all", t::prestartAllCoreThreads);
        t.shutdown();
        show("factory throws terminated", t::isTerminated);
        ThreadPoolExecutor a = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq(), r -> { Thread th = new Thread(r); th.start(); return th; });
        show("factory started thread", () -> { a.execute(() -> {}); return "no"; });
        show("started state", () -> a.getPoolSize() + " " + a.getQueue().size());
        a.shutdown();
        ThreadPoolExecutor n = new ThreadPoolExecutor(0, 1, 1, TimeUnit.MINUTES, new SynchronousQueue<>(), r -> null);
        show("null thread sync queue", () -> { n.execute(new Named("s", () -> {})); return "no"; });
        ThreadPoolExecutor nq = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> null);
        nq.execute(new Named("q", () -> {}));
        show("null thread shutdown", () -> { nq.shutdown(); return nq + " " + nq.isTerminated(); });
        show("null thread shutdownNow", () -> nq.shutdownNow() + " " + nq);
        ThreadFactory dead = r -> new Thread(() -> { });
        ThreadPoolExecutor d = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq(), dead);
        d.execute(() -> {});
        Thread.sleep(50);
        show("factory ignoring runnable", () -> d + "");
        d.shutdownNow();
        show("ignoring await", () -> d.awaitTermination(50, TimeUnit.MILLISECONDS));
    }

    static void lifecycle() throws Exception {
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq());
        CountDownLatch g = new CountDownLatch(1);
        boolean[] flag = new boolean[3];
        p.execute(() -> { ThreadPoolEdges.await(g); flag[0] = Thread.currentThread().isInterrupted(); });
        p.execute(() -> { flag[1] = Thread.currentThread().isInterrupted(); });
        p.shutdown();
        show("after shutdown prestart", p::prestartCoreThread);
        show("after shutdown setCore", () -> { p.setCorePoolSize(3); return p.getPoolSize(); });
        g.countDown();
        p.awaitTermination(20, TimeUnit.SECONDS);
        show("shutdown no interrupt", () -> flag[0] + " " + flag[1]);

        ThreadPoolExecutor s = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq());
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch queuedIt = new CountDownLatch(1);
        s.execute(() -> {
            ThreadPoolEdges.await(queuedIt);
            log.add("in task flag=" + Thread.currentThread().isInterrupted());
            s.shutdown();
            log.add("after shutdown flag=" + Thread.currentThread().isInterrupted() + " " + s.isTerminating());
            log.add("queued list=" + s.shutdownNow());
            log.add("after now flag=" + Thread.currentThread().isInterrupted());
            Thread.interrupted();
        });
        s.execute(new Named("never", () -> log.add("never ran")));
        queuedIt.countDown();
        s.awaitTermination(20, TimeUnit.SECONDS);
        show("from inside task", () -> log.size() >= 4 ? log : log);

        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        ThreadPoolExecutor r = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq());
        Runnable running = new Named("running", () -> { entered.countDown(); ThreadPoolEdges.await(gate); });
        r.execute(running);
        entered.await();
        Runnable queued = new Named("queued", () -> {});
        r.execute(queued);
        Future<?> fut = r.submit(() -> { });
        Runnable orig = new Named("orig", () -> {});
        Future<?> wrapped = r.submit(orig);
        show("remove running", () -> r.remove(running));
        show("remove queued", () -> r.remove(queued));
        show("remove original of submit", () -> r.remove(orig));
        show("remove future", () -> r.remove((Runnable) fut));
        show("future after remove", () -> fut.isDone());
        ((FutureTask<?>) wrapped).cancel(false);
        r.shutdown();
        show("not yet", r::isTerminated);
        show("remove cancelled after shutdown", () -> r.remove((Runnable) wrapped));
        show("purge nothing", () -> { r.purge(); return r.getQueue().size(); });
        gate.countDown();
        r.awaitTermination(20, TimeUnit.SECONDS);
        show("terminated", r::isTerminated);

        // Queue emptied by remove after shutdown, with the worker idle-blocked is not possible on LBQ, but a busy worker is.
        CountDownLatch gate2 = new CountDownLatch(1), e2 = new CountDownLatch(1);
        ThreadPoolExecutor t2 = new ThreadPoolExecutor(2, 2, 1, TimeUnit.MINUTES, lbq());
        t2.execute(() -> { e2.countDown(); ThreadPoolEdges.await(gate2); });
        e2.await();
        t2.prestartAllCoreThreads();
        show("size", t2::getPoolSize);
        t2.shutdown();
        until(() -> t2.getPoolSize() == 1);
        show("idle left at shutdown", t2::getPoolSize);
        show("toString shutting", () -> t2);
        gate2.countDown();
        t2.awaitTermination(20, TimeUnit.SECONDS);

        ThreadPoolExecutor q = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq());
        Thread.currentThread().interrupt();
        show("await zero flagged running", () -> q.awaitTermination(0, TimeUnit.SECONDS));
        show("flag kept", Thread::interrupted);
        show("await neg", () -> q.awaitTermination(-1, TimeUnit.SECONDS));
        q.shutdown();
        show("await max", () -> q.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS));
        show("finalize", () -> { ThreadPoolExecutor x = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq()) {
            void f() { finalize(); } { f(); } }; return x.isShutdown(); });
        // A task on the caller after rejection with its interrupt state.
        ThreadPoolExecutor c = new ThreadPoolExecutor(0, 1, 1, TimeUnit.MINUTES, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        CountDownLatch g3 = new CountDownLatch(1), e3 = new CountDownLatch(1);
        c.execute(() -> { e3.countDown(); ThreadPoolEdges.await(g3); });
        e3.await();
        c.execute(() -> System.out.println("caller ran on main=" + Thread.currentThread().getName().equals("main")));
        g3.countDown();
        c.shutdown();
    }

    static void policies() {
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(1));
        show("abort null task", () -> { new ThreadPoolExecutor.AbortPolicy().rejectedExecution(null, p); return "no"; });
        show("abort null pool", () -> { new ThreadPoolExecutor.AbortPolicy().rejectedExecution(new Named("n", () -> {}), null); return "no"; });
        show("caller null pool", () -> { new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(new Named("n", () -> {}), null); return "no"; });
        show("discard nulls", () -> { new ThreadPoolExecutor.DiscardPolicy().rejectedExecution(null, null); return "ok"; });
        show("oldest null pool", () -> { new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(new Named("n", () -> {}), null); return "no"; });
        show("oldest null task", () -> { new ThreadPoolExecutor.DiscardOldestPolicy().rejectedExecution(null, p); return "no"; });
        p.shutdown();
    }

    static void sizes() throws Exception {
        CountDownLatch g = new CountDownLatch(1);
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq());
        p.execute(() -> ThreadPoolEdges.await(g));
        for (int i = 0; i < 4; i++) p.execute(() -> ThreadPoolEdges.await(g));
        show("before raise", () -> p.getPoolSize() + " q" + p.getQueue().size());
        p.setMaximumPoolSize(5);
        p.setCorePoolSize(3);
        until(() -> p.getQueue().size() == 2);
        show("raise with queued", () -> p.getPoolSize() + " q" + p.getQueue().size());
        p.setCorePoolSize(3);
        show("same core", p::getPoolSize);
        p.setCorePoolSize(10);
        until(() -> p.getQueue().isEmpty());
        show("raise beyond queue", p::getPoolSize);
        g.countDown();
        until(() -> p.getActiveCount() == 0);
        p.setCorePoolSize(1);
        p.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
        until(() -> p.getPoolSize() == 1);
        show("lowered core", p::getPoolSize);
        show("largest", p::getLargestPoolSize);
        p.shutdown();

        ThreadPoolExecutor over = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new SynchronousQueue<>());
        over.setCorePoolSize(3);
        CountDownLatch g2 = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) over.execute(() -> ThreadPoolEdges.await(g2));
        show("core above max size", over::getPoolSize);
        show("core above max reject", () -> { over.execute(new Named("o", () -> {})); return "acc"; });
        g2.countDown();
        until(() -> over.getActiveCount() == 0);
        until(() -> over.getPoolSize() <= 1);
        show("core above max settles", over::getPoolSize);
        over.shutdown();

        ThreadPoolExecutor k = new ThreadPoolExecutor(1, 3, 5, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        CountDownLatch g3 = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) k.execute(() -> ThreadPoolEdges.await(g3));
        show("grown", k::getPoolSize);
        g3.countDown();
        until(() -> k.getPoolSize() == 1);
        show("back to core", k::getPoolSize);
        Thread.sleep(30);
        show("core stays", k::getPoolSize);
        k.allowCoreThreadTimeOut(true);
        until(() -> k.getPoolSize() == 0);
        k.allowCoreThreadTimeOut(false);
        k.execute(() -> {});
        until(() -> k.getCompletedTaskCount() == 4);
        Thread.sleep(30);
        show("after allow off", k::getPoolSize);
        k.shutdown();

        for (int[] cfg : new int[][] {{0, 1}, {1, 1}}) {
            Factory f = new Factory();
            ThreadPoolExecutor z = new ThreadPoolExecutor(cfg[0], cfg[1], 1, TimeUnit.MINUTES, lbq(), f);
            z.execute(() -> { throw new IllegalStateException("x"); });
            until(() -> f.uncaught.size() == 1);
            until(() -> f.made == 2);
            until(() -> z.getPoolSize() == 1);
            show("abrupt replaced core" + cfg[0], () -> f.made + " size=" + z.getPoolSize());
            z.shutdown();
            z.awaitTermination(20, TimeUnit.SECONDS);
            Factory g4 = new Factory();
            ThreadPoolExecutor y = new ThreadPoolExecutor(cfg[0], cfg[1], 1, TimeUnit.MINUTES, lbq(), g4);
            CountDownLatch e = new CountDownLatch(1), h = new CountDownLatch(1);
            y.execute(() -> { e.countDown(); ThreadPoolEdges.await(h); throw new IllegalStateException("late"); });
            e.await();
            y.shutdown();
            h.countDown();
            y.awaitTermination(20, TimeUnit.SECONDS);
            until(() -> g4.uncaught.size() == 1);
            show("abrupt at shutdown core" + cfg[0], () -> g4.made);
            Factory g5 = new Factory();
            ThreadPoolExecutor w = new ThreadPoolExecutor(cfg[0], cfg[1], 1, TimeUnit.MINUTES, lbq(), g5);
            CountDownLatch e5 = new CountDownLatch(1), h5 = new CountDownLatch(1);
            w.execute(() -> { e5.countDown(); ThreadPoolEdges.await(h5); throw new IllegalStateException("late"); });
            e5.await();
            w.execute(new Named("rest", () -> {}));
            w.shutdown();
            h5.countDown();
            w.awaitTermination(20, TimeUnit.SECONDS);
            until(() -> g5.uncaught.size() == 1);
            show("abrupt at shutdown with queue core" + cfg[0], () -> g5.made + " " + w.getCompletedTaskCount());
        }
        Factory zf = new Factory();
        ThreadPoolExecutor zk = new ThreadPoolExecutor(0, 1, 0, TimeUnit.SECONDS, lbq(), zf);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 3; i++) zk.execute(ran::incrementAndGet);
        until(() -> ran.get() == 3);
        until(() -> zk.getPoolSize() == 0);
        show("zero keepalive", () -> zk.getPoolSize());
        zk.shutdown();
    }

    /** Waking a waiter, and a flag a queue would let through to the next task. */
    static void wakes() throws Exception {
        ThreadPoolExecutor k = new ThreadPoolExecutor(0, 1, 1, TimeUnit.HOURS, new SynchronousQueue<>());
        k.prestartAllCoreThreads();
        k.execute(() -> { });
        until(() -> k.getCompletedTaskCount() == 1 && k.getActiveCount() == 0);
        k.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
        until(() -> k.getPoolSize() == 0);
        show("shorter keep-alive wakes the idle", k::getPoolSize);
        k.shutdown();
        // A transfer queue's take keeps the flag when an element is ready, so the pool must clear it.
        ThreadPoolExecutor t = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedTransferQueue<>());
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        boolean[] flag = new boolean[1];
        t.execute(() -> { e.countDown(); P1Await(g); Thread.currentThread().interrupt(); });
        e.await();
        t.execute(() -> flag[0] = Thread.currentThread().isInterrupted());
        g.countDown();
        t.shutdown();
        t.awaitTermination(20, TimeUnit.SECONDS);
        show("interrupt not carried over a transfer queue", () -> flag[0]);
    }

    static void P1Await(CountDownLatch l) { await(l); }

    /** A queue that refuses to drain and one whose offer throws. */
    static void userQueues() throws Exception {
        BlockingQueue<Runnable> noDrain = new LinkedBlockingQueue<Runnable>() {
            @Override public int drainTo(Collection<? super Runnable> c) { return 0; }
        };
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, noDrain);
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        p.execute(() -> { e.countDown(); ThreadPoolEdges.await(g); });
        e.await();
        p.execute(new Named("a", () -> {}));
        p.execute(new Named("b", () -> {}));
        show("no drain", p::shutdownNow);
        show("no drain empty", noDrain::size);
        BlockingQueue<Runnable> bad = new LinkedBlockingQueue<Runnable>() {
            @Override public boolean offer(Runnable r) { throw new IllegalStateException("offer"); }
        };
        ThreadPoolExecutor b = new ThreadPoolExecutor(0, 1, 1, TimeUnit.MINUTES, bad);
        show("offer throws", () -> { b.execute(() -> {}); return "no"; });
        show("offer throws state", () -> b + "");
        b.shutdown();
        // A queue that shuts the pool during offer: the recheck rejects the task.
        ThreadPoolExecutor[] box = new ThreadPoolExecutor[1];
        BlockingQueue<Runnable> shutting = new LinkedBlockingQueue<Runnable>() {
            @Override public boolean offer(Runnable r) { boolean ok = super.offer(r); box[0].shutdown(); return ok; }
        };
        box[0] = new ThreadPoolExecutor(0, 1, 1, TimeUnit.MINUTES, shutting);
        show("shutdown during offer", () -> { box[0].execute(new Named("s", () -> {})); return "acc"; });
        show("shutdown during offer state", () -> box[0] + " " + box[0].getQueue());
        // A queue that takes the task and tells no: goes to a new worker up to max.
        BlockingQueue<Runnable> refusing = new LinkedBlockingQueue<Runnable>() {
            @Override public boolean offer(Runnable r) { return false; }
        };
        ThreadPoolExecutor rf = new ThreadPoolExecutor(1, 2, 1, TimeUnit.MINUTES, refusing);
        CountDownLatch g2 = new CountDownLatch(1);
        rf.execute(() -> ThreadPoolEdges.await(g2));
        rf.execute(() -> ThreadPoolEdges.await(g2));
        show("refusing", rf::getPoolSize);
        show("refusing third", () -> { rf.execute(new Named("t", () -> {})); return "acc"; });
        g2.countDown();
        rf.shutdown();
        // getQueue().add by hand does not start a worker.
        ThreadPoolExecutor h = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, lbq());
        boolean[] ranIt = new boolean[1];
        h.getQueue().add(() -> ranIt[0] = true);
        Thread.sleep(20);
        show("hand added", () -> h.getPoolSize() + " " + ranIt[0] + " " + h.getTaskCount());
        h.prestartCoreThread();
        until(() -> ranIt[0]);
        show("prestart serves it", () -> ranIt[0]);
        h.shutdown();
    }

    public static void main(String[] args) throws Exception {
        ctor();
        hooks();
        terminatedHook();
        hookFlag();
        factories();
        lifecycle();
        policies();
        sizes();
        wakes();
        userQueues();
        System.exit(0);
    }
}
