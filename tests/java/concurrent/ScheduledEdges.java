package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** ScheduledThreadPoolExecutor: the queue's view, shutdown policies changed late, decorated tasks. */
public class ScheduledEdges {
    interface Body { Object run() throws Throwable; }
    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("\\$\\$Lambda\\$[0-9]+/[0-9]+", "-Lambda").replaceAll("-Lambda-[0-9]+", "-Lambda").replaceAll("Task \\S+ rejected", "Task T rejected"); }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }
    interface Cond { boolean ok() throws Exception; }
    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!c.ok()) { if (System.nanoTime() > end) throw new AssertionError("stuck"); Thread.sleep(1); }
    }
    static void await(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    static final Runnable NOOP = () -> { };

    static void arguments() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        show("null and zero period", () -> p.scheduleAtFixedRate(null, 1, 0, TimeUnit.SECONDS));
        show("null unit and zero period", () -> p.scheduleWithFixedDelay(NOOP, 1, 0, null));
        show("submit null", () -> p.submit((Runnable) null));
        show("submit null with result", () -> p.submit((Runnable) null, "r"));
        show("submit null callable", () -> p.submit((Callable<String>) null));
        show("setCore negative", () -> { p.setCorePoolSize(-1); return "no"; });
        show("setMax is free", () -> { p.setMaximumPoolSize(5); return p.getMaximumPoolSize(); });
        show("allowCore", () -> { p.allowCoreThreadTimeOut(true); return "no"; });
        show("queue class is blocking", () -> p.getQueue().remainingCapacity());
        show("queue add plain", () -> { try { return p.getQueue().add(NOOP); } catch (ClassCastException e) { return "ClassCastException"; } });
        show("queue offer plain", () -> { try { return p.getQueue().offer(NOOP); } catch (ClassCastException e) { return "ClassCastException"; } });
        show("queue size after", () -> p.getQueue().size());
        p.shutdown();
        ScheduledThreadPoolExecutor z = new ScheduledThreadPoolExecutor(0);
        show("core 0 runs", () -> z.schedule(() -> "ran", 1, TimeUnit.MILLISECONDS).get());
        z.shutdown();
        show("core 0 ends", () -> z.awaitTermination(20, TimeUnit.SECONDS));
        // A new STPE consumes a pool number
    }

    static void queueView() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        p.execute(() -> { e.countDown(); ScheduledEdges.await(g); });
        e.await();
        BlockingQueue<Runnable> q = p.getQueue();
        ScheduledFuture<?> far = p.schedule(NOOP, 1, TimeUnit.HOURS);
        ScheduledFuture<?> near = p.schedule(NOOP, 1, TimeUnit.MINUTES);
        ScheduledFuture<?> now = p.schedule(NOOP, 0, TimeUnit.SECONDS);
        show("peek is soonest", () -> q.peek() == now);
        show("poll zero due", () -> q.poll() == now);
        show("poll not due", q::poll);
        show("peek not due", () -> q.peek() == near);
        show("size", q::size);
        show("contains", () -> q.contains(far));
        show("timed poll not due", () -> q.poll(1, TimeUnit.MILLISECONDS));
        List<Runnable> sink = new ArrayList<>();
        show("drain not due", () -> q.drainTo(sink) + " " + sink.size());
        show("iterator sees", () -> { int c = 0; for (Runnable r : q) c++; return c; });
        show("toArray", () -> q.toArray().length);
        show("remove from view", () -> q.remove(near));
        show("clear", () -> { q.clear(); return q.size(); });
        g.countDown();
        p.shutdown();
        show("ended", () -> p.awaitTermination(20, TimeUnit.SECONDS));
    }

    static void orders() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        p.execute(() -> { e.countDown(); ScheduledEdges.await(g); });
        e.await();
        ScheduledFuture<?> due1 = p.schedule(NOOP, -1, TimeUnit.SECONDS);
        ScheduledFuture<?> hour = p.schedule(NOOP, 1, TimeUnit.HOURS);
        ScheduledFuture<?> due2 = p.schedule(NOOP, 0, TimeUnit.SECONDS);
        ScheduledFuture<?> day = p.schedule(NOOP, 1, TimeUnit.DAYS);
        Thread.sleep(2);
        List<Runnable> rest = p.shutdownNow();
        Map<Object, String> names = new IdentityHashMap<>();
        names.put(due1, "due1"); names.put(hour, "hour"); names.put(due2, "due2"); names.put(day, "day");
        // What is due comes first, in order; the order of the rest is the heap's and not pinned.
        StringBuilder b = new StringBuilder();
        for (Runnable r : rest.subList(0, 2)) b.append(names.get(r)).append(' ');
        Set<String> later = new TreeSet<>();
        for (Runnable r : rest.subList(2, rest.size())) later.add(names.get(r));
        show("shutdownNow order", () -> b + "then " + later);
        g.countDown();
        p.awaitTermination(20, TimeUnit.SECONDS);
    }

    static void policies() throws Exception {
        // A cancelled delayed task stays queued and holds off termination unless removed at shutdown.
        ScheduledThreadPoolExecutor a = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture<?> c = a.schedule(NOOP, 1, TimeUnit.HOURS);
        c.cancel(false);
        a.shutdown();
        show("cancelled removed at shutdown", () -> a.awaitTermination(20, TimeUnit.SECONDS));

        ScheduledThreadPoolExecutor b = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture<?> d = b.schedule(NOOP, 1, TimeUnit.HOURS);
        b.shutdown();
        show("delayed waits", () -> b.awaitTermination(30, TimeUnit.MILLISECONDS));
        show("cancel after shutdown", () -> d.cancel(false));
        show("cancelled stays", () -> b.getQueue().size() + " " + b.isTerminated());
        b.purge();
        show("purge ends", () -> b.awaitTermination(20, TimeUnit.SECONDS));

        ScheduledThreadPoolExecutor r = new ScheduledThreadPoolExecutor(1);
        r.setRemoveOnCancelPolicy(true);
        ScheduledFuture<?> d2 = r.schedule(NOOP, 1, TimeUnit.HOURS);
        r.shutdown();
        d2.cancel(false);
        show("remove policy ends", () -> r.awaitTermination(20, TimeUnit.SECONDS));

        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture<?> d3 = s.schedule(NOOP, 1, TimeUnit.HOURS);
        s.shutdown();
        s.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        show("policy off after shutdown", () -> s.awaitTermination(20, TimeUnit.SECONDS) + " " + d3.isCancelled());

        ScheduledThreadPoolExecutor k = new ScheduledThreadPoolExecutor(1);
        k.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
        AtomicInteger c2 = new AtomicInteger();
        ScheduledFuture<?> kept = k.scheduleAtFixedRate(c2::incrementAndGet, 0, 1, TimeUnit.MILLISECONDS);
        k.shutdown();
        until(() -> c2.get() > 2);
        k.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        show("periodic off after shutdown", () -> k.awaitTermination(20, TimeUnit.SECONDS));

        ScheduledThreadPoolExecutor both = new ScheduledThreadPoolExecutor(1);
        both.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        both.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
        AtomicInteger c3 = new AtomicInteger();
        ScheduledFuture<?> p3 = both.scheduleAtFixedRate(c3::incrementAndGet, 1, 1, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> d4 = both.schedule(NOOP, 1, TimeUnit.HOURS);
        both.shutdown();
        show("both: flags", () -> p3.isCancelled() + " " + d4.isCancelled() + " " + both.getQueue().contains(d4));
        both.shutdownNow();
        show("both: end", () -> both.awaitTermination(20, TimeUnit.SECONDS));

        // A task due while shutting down with the delayed policy off, already due and queued.
        ScheduledThreadPoolExecutor due = new ScheduledThreadPoolExecutor(1);
        due.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        CountDownLatch g = new CountDownLatch(1), e = new CountDownLatch(1);
        due.execute(() -> { e.countDown(); ScheduledEdges.await(g); });
        e.await();
        boolean[] ran = new boolean[1];
        ScheduledFuture<?> ready = due.schedule(() -> ran[0] = true, 0, TimeUnit.SECONDS);
        due.shutdown();
        g.countDown();
        due.awaitTermination(20, TimeUnit.SECONDS);
        show("due with policy off", () -> ran[0] + " " + ready.isCancelled());

        // A running periodic task at shutdown: its next run is cancelled.
        ScheduledThreadPoolExecutor rp = new ScheduledThreadPoolExecutor(1);
        CountDownLatch g2 = new CountDownLatch(1), e2 = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> running = rp.scheduleAtFixedRate(() -> { runs.incrementAndGet(); e2.countDown(); ScheduledEdges.await(g2); }, 0, 1, TimeUnit.MILLISECONDS);
        e2.await();
        rp.shutdown();
        show("running periodic after shutdown", () -> running.isCancelled() + " " + running.isDone());
        g2.countDown();
        rp.awaitTermination(20, TimeUnit.SECONDS);
        show("running periodic end", () -> running.isCancelled() + " " + runs.get());

        // shutdownNow with a running periodic task
        ScheduledThreadPoolExecutor rq = new ScheduledThreadPoolExecutor(1);
        CountDownLatch e3 = new CountDownLatch(1);
        boolean[] flag = new boolean[1];
        ScheduledFuture<?> r2 = rq.scheduleAtFixedRate(() -> { e3.countDown(); try { new CountDownLatch(1).await(); } catch (InterruptedException x) { flag[0] = true; } }, 0, 1, TimeUnit.HOURS);
        e3.await();
        show("shutdownNow running list", () -> rq.shutdownNow());
        rq.awaitTermination(20, TimeUnit.SECONDS);
        show("shutdownNow running", () -> flag[0] + " " + r2.isCancelled() + " " + r2.isDone());
    }

    static class Wrapped<V> implements RunnableScheduledFuture<V> {
        final RunnableScheduledFuture<V> t; final List<String> log;
        Wrapped(RunnableScheduledFuture<V> t, List<String> log) { this.t = t; this.log = log; }
        public boolean isPeriodic() { return t.isPeriodic(); }
        public void run() { log.add("wrapped run"); t.run(); }
        public boolean cancel(boolean b) { log.add("wrapped cancel"); return t.cancel(b); }
        public boolean isCancelled() { return t.isCancelled(); }
        public boolean isDone() { return t.isDone(); }
        public V get() throws InterruptedException, ExecutionException { return t.get(); }
        public V get(long a, TimeUnit u) throws InterruptedException, ExecutionException, TimeoutException { return t.get(a, u); }
        public long getDelay(TimeUnit u) { return t.getDelay(u); }
        public int compareTo(Delayed o) { return t.compareTo(o instanceof Wrapped ? ((Wrapped<?>) o).t : o); }
    }

    static void decorated() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1) {
            @Override protected <V> RunnableScheduledFuture<V> decorateTask(Runnable r, RunnableScheduledFuture<V> t) { return new Wrapped<>(t, log); }
            @Override protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> c, RunnableScheduledFuture<V> t) { return new Wrapped<>(t, log); }
        };
        show("returned is decorated", () -> p.schedule(NOOP, 0, TimeUnit.SECONDS) instanceof Wrapped);
        AtomicInteger c = new AtomicInteger();
        ScheduledFuture<?> rep = p.scheduleAtFixedRate(c::incrementAndGet, 0, 1, TimeUnit.MILLISECONDS);
        until(() -> c.get() >= 3);
        rep.cancel(false);
        until(() -> p.getQueue().size() == 0 || rep.isDone());
        Thread.sleep(5);
        int wrapped = 0;
        synchronized (log) { for (String s : log) if (s.equals("wrapped run")) wrapped++; }
        final int w = wrapped;
        show("periodic reruns go through the decorated one", () -> w >= 4);
        // Nothing left from above, so the sizes below show only the next task.
        until(() -> { p.purge(); return p.getQueue().isEmpty(); });
        p.setRemoveOnCancelPolicy(true);
        ScheduledFuture<?> d = p.schedule(NOOP, 1, TimeUnit.HOURS);
        d.cancel(false);
        show("remove policy with decorated", () -> p.getQueue().size());
        p.purge();
        show("purge decorated", () -> p.getQueue().size());
        p.shutdownNow();
        p.awaitTermination(20, TimeUnit.SECONDS);
        ScheduledThreadPoolExecutor nul = new ScheduledThreadPoolExecutor(1) {
            @Override protected <V> RunnableScheduledFuture<V> decorateTask(Runnable r, RunnableScheduledFuture<V> t) { return null; }
        };
        show("decorate null", () -> nul.schedule(NOOP, 0, TimeUnit.SECONDS));
        nul.shutdown();
    }

    static void misc() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(2);
        show("future class", () -> ((Object) p.schedule(NOOP, 0, TimeUnit.SECONDS)) instanceof FutureTask);
        show("future toString shape", () -> !p.schedule(NOOP, 0, TimeUnit.SECONDS).toString().contains("["));
        show("sizes grow to core", () -> { until(() -> p.getPoolSize() == 2); return p.getPoolSize(); });
        show("schedule on idle pool at core", () -> p.schedule(() -> "x", 0, TimeUnit.SECONDS).get());
        show("compare other kind", () -> {
            ScheduledFuture<?> f = p.schedule(NOOP, 1, TimeUnit.HOURS);
            Delayed near = new Delayed() {
                public long getDelay(TimeUnit u) { return 0; }
                public int compareTo(Delayed o) { return 0; }
            };
            int r = f.compareTo(near);
            f.cancel(false);
            return r;
        });
        show("compare null", () -> p.schedule(NOOP, 1, TimeUnit.HOURS).compareTo(null));
        show("getDelay null", () -> p.schedule(NOOP, 1, TimeUnit.HOURS).getDelay(null));
        ScheduledFuture<?> huge = p.schedule(NOOP, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        show("huge getDelay days", () -> huge.getDelay(TimeUnit.DAYS));
        ScheduledFuture<?> huge2 = p.schedule(NOOP, Long.MAX_VALUE, TimeUnit.DAYS);
        show("huge2 getDelay days", () -> huge2.getDelay(TimeUnit.DAYS));
        show("hug compare", () -> huge.compareTo(huge2) < 0);
        show("cancel done", () -> { ScheduledFuture<String> d = p.schedule(() -> "x", 0, TimeUnit.SECONDS); d.get(); return d.cancel(true); });
        show("execute failure not to handler", () -> {
            int[] uncaught = new int[1];
            ScheduledThreadPoolExecutor q = new ScheduledThreadPoolExecutor(1, r -> { Thread t = new Thread(r); t.setUncaughtExceptionHandler((a, b) -> uncaught[0]++); return t; });
            q.execute(() -> { throw new IllegalStateException("x"); });
            q.shutdown();
            q.awaitTermination(20, TimeUnit.SECONDS);
            return uncaught[0] + " " + q.getCompletedTaskCount();
        });
        // rejected handler gets the decorated task; a discarding handler
        ScheduledThreadPoolExecutor dis = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());
        dis.shutdown();
        ScheduledFuture<?> never = dis.schedule(NOOP, 0, TimeUnit.SECONDS);
        show("discarded future", () -> never.isDone() + " " + never.isCancelled());
        ScheduledThreadPoolExecutor cr = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.CallerRunsPolicy());
        cr.shutdown();
        ScheduledFuture<?> cf = cr.schedule(NOOP, 0, TimeUnit.SECONDS);
        show("caller policy after shutdown", () -> cf.isDone());
        // hooks: beforeExecute sees the future, afterExecute a null, periodic count completes.
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        ScheduledThreadPoolExecutor h = new ScheduledThreadPoolExecutor(1) {
            @Override protected void beforeExecute(Thread t, Runnable r) { log.add("before " + (r instanceof RunnableScheduledFuture)); }
            @Override protected void afterExecute(Runnable r, Throwable e) { log.add("after " + e + " done=" + ((Future<?>) r).isDone()); }
            @Override protected void terminated() { log.add("terminated"); }
        };
        ScheduledFuture<?> rep = h.scheduleAtFixedRate(() -> { }, 0, 1, TimeUnit.HOURS);
        until(() -> h.getCompletedTaskCount() == 1);
        h.schedule(() -> { throw new IllegalStateException("x"); }, 0, TimeUnit.SECONDS);
        until(() -> h.getCompletedTaskCount() == 2);
        h.shutdown();
        h.awaitTermination(20, TimeUnit.SECONDS);
        show("hooks", () -> log);
        show("task counts", () -> h.getTaskCount() + " " + h.getCompletedTaskCount());
        p.shutdownNow();
        p.awaitTermination(20, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        arguments();
        queueView();
        orders();
        policies();
        decorated();
        misc();
        System.exit(0);
    }
}
