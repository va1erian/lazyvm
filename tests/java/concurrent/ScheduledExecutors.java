package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** ScheduledThreadPoolExecutor: delays, periodic tasks, cancellation, shutdown policies. */
public class ScheduledExecutors {
    interface Body { Object run() throws Throwable; }

    /** A rejection names the wrapping task's class, which is not API: only its text around that is kept. */
    static String n(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("Task \\S+ rejected", "Task <task> rejected");
    }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static void arguments() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        Runnable r = () -> { };
        show("toString", () -> p);
        show("core negative", () -> new ScheduledThreadPoolExecutor(-1));
        show("null factory", () -> new ScheduledThreadPoolExecutor(1, (ThreadFactory) null));
        show("null handler", () -> new ScheduledThreadPoolExecutor(1, (RejectedExecutionHandler) null));
        show("full constructor", () -> {
            ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(2, t -> new Thread(t, "named"), new ThreadPoolExecutor.DiscardPolicy());
            String info = s.getCorePoolSize() + " " + s.getRejectedExecutionHandler().getClass().getSimpleName()
                + " " + s.schedule(() -> Thread.currentThread().getName(), 0, TimeUnit.SECONDS).get();
            s.shutdown();
            return info;
        });
        show("full constructor null factory", () -> new ScheduledThreadPoolExecutor(1, null, new ThreadPoolExecutor.AbortPolicy()));
        show("full constructor null handler", () -> new ScheduledThreadPoolExecutor(1, Executors.defaultThreadFactory(), null));
        show("schedule null", () -> p.schedule((Runnable) null, 1, TimeUnit.SECONDS));
        show("schedule null callable", () -> p.schedule((Callable<String>) null, 1, TimeUnit.SECONDS));
        show("schedule null unit", () -> p.schedule(r, 1, null));
        show("fixedRate null", () -> p.scheduleAtFixedRate(null, 1, 1, TimeUnit.SECONDS));
        show("fixedRate null unit", () -> p.scheduleAtFixedRate(r, 1, 1, null));
        show("fixedRate zero", () -> p.scheduleAtFixedRate(r, 1, 0, TimeUnit.SECONDS));
        show("fixedDelay negative", () -> p.scheduleWithFixedDelay(r, 1, -1, TimeUnit.SECONDS));
        show("execute null", () -> { p.execute(null); return "no"; });
        show("policy existing delayed", p::getExecuteExistingDelayedTasksAfterShutdownPolicy);
        show("policy periodic", p::getContinueExistingPeriodicTasksAfterShutdownPolicy);
        show("policy remove on cancel", p::getRemoveOnCancelPolicy);
        show("max", p::getMaximumPoolSize);
        show("keepAlive", () -> p.getKeepAliveTime(TimeUnit.SECONDS));
        p.shutdown();
        show("after shutdown", () -> p.schedule(r, 1, TimeUnit.SECONDS));
        p.awaitTermination(30, TimeUnit.SECONDS);
    }

    static void delays() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch gate = new CountDownLatch(1);
        p.execute(() -> { try { gate.await(); } catch (InterruptedException e) { } });
        // Immediate work goes in first, so the far delays cannot expire before it is queued.
        p.schedule(() -> log.add("neg2"), -1, TimeUnit.SECONDS);
        p.schedule(() -> log.add("neg1"), -5, TimeUnit.SECONDS);
        p.schedule(() -> log.add("z1"), 0, TimeUnit.SECONDS);
        p.execute(() -> log.add("exec"));
        p.submit(() -> log.add("sub"));
        // The one time margin left: the next two calls must be under 700 ms apart.
        p.schedule(() -> log.add("late"), 1500, TimeUnit.MILLISECONDS);
        p.schedule(() -> log.add("early"), 800, TimeUnit.MILLISECONDS);
        gate.countDown();
        until(() -> log.size() == 7);
        show("delay order", () -> log);

        ScheduledFuture<String> far = p.schedule(() -> "x", 1, TimeUnit.HOURS);
        ScheduledFuture<String> near = p.schedule(() -> "near", 10, TimeUnit.MILLISECONDS);
        show("getDelay", () -> far.getDelay(TimeUnit.MINUTES));
        show("getDelay in range", () -> far.getDelay(TimeUnit.SECONDS) <= 3600 && far.getDelay(TimeUnit.SECONDS) > 3500);
        show("compare", () -> far.compareTo(near) > 0 && near.compareTo(far) < 0);
        show("compare self", () -> far.compareTo(far));
        show("schedule result", near::get);
        show("periodic flag", () -> ((RunnableScheduledFuture<String>) near).isPeriodic());
        show("near delay expired", () -> near.getDelay(TimeUnit.NANOSECONDS) <= 0);
        show("queue has far", () -> p.getQueue().size());
        show("queue elem is future", () -> p.getQueue().peek() == far);
        show("cancel far", () -> far.cancel(false));
        show("cancelled stays queued", () -> p.getQueue().size());
        show("far get", far::get);
        p.purge();
        show("purged", () -> p.getQueue().size());
        p.setRemoveOnCancelPolicy(true);
        ScheduledFuture<?> gone = p.schedule(() -> { }, 1, TimeUnit.HOURS);
        show("queued", () -> p.getQueue().size());
        gone.cancel(false);
        show("removed on cancel", () -> p.getQueue().size());
        ScheduledFuture<?> huge = p.schedule(() -> { }, Long.MAX_VALUE, TimeUnit.DAYS);
        show("huge delay positive", () -> huge.getDelay(TimeUnit.DAYS) > 0);
        ScheduledFuture<?> huge2 = p.schedule(() -> { }, 1, TimeUnit.SECONDS);
        show("huge after near", () -> huge.compareTo(huge2) > 0);
        p.shutdownNow();
        p.awaitTermination(30, TimeUnit.SECONDS);
    }

    static void periodic() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        AtomicInteger rate = new AtomicInteger();
        ScheduledFuture<?> f = p.scheduleAtFixedRate(rate::incrementAndGet, 0, 1, TimeUnit.MILLISECONDS);
        show("periodic flag", () -> ((RunnableScheduledFuture<?>) f).isPeriodic());
        until(() -> rate.get() >= 5);
        show("not done while repeating", f::isDone);
        show("timed get repeats", () -> f.get(1, TimeUnit.MILLISECONDS));
        show("cancel", () -> f.cancel(false));
        show("done", f::isDone);
        show("cancelled", f::isCancelled);
        show("get", f::get);
        int at = rate.get();
        Thread.sleep(20);
        show("no more runs", () -> rate.get() - at <= 1);

        AtomicInteger delay = new AtomicInteger();
        ScheduledFuture<?> d = p.scheduleWithFixedDelay(delay::incrementAndGet, 0, 1, TimeUnit.MILLISECONDS);
        until(() -> delay.get() >= 3);
        d.cancel(true);
        show("fixed delay cancelled", d::isCancelled);

        AtomicInteger bad = new AtomicInteger();
        ScheduledFuture<?> thrower = p.scheduleAtFixedRate(() -> {
            if (bad.incrementAndGet() == 3) throw new IllegalStateException("third");
        }, 0, 1, TimeUnit.MILLISECONDS);
        show("failure stops", () -> thrower.get());
        show("failure run count", bad::get);
        show("failure flags", () -> thrower.isDone() + " " + thrower.isCancelled());
        p.shutdownNow();
        p.awaitTermination(30, TimeUnit.SECONDS);
    }

    static void shutdownPolicies() throws Exception {
        ScheduledThreadPoolExecutor a = new ScheduledThreadPoolExecutor(1);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch hold = new CountDownLatch(1);
        ScheduledFuture<?> delayed = a.schedule(() -> { hold.await(); return log.add("delayed"); }, 1, TimeUnit.MILLISECONDS);
        AtomicInteger c = new AtomicInteger();
        ScheduledFuture<?> rep = a.scheduleAtFixedRate(c::incrementAndGet, 1, 1, TimeUnit.HOURS);
        a.shutdown();
        show("default: periodic cancelled on shutdown", () -> rep.isCancelled());
        show("default: delayed stays", delayed::isCancelled);
        show("default: terminating", a::isTerminating);
        hold.countDown();
        a.awaitTermination(30, TimeUnit.SECONDS);
        show("default: delayed ran", () -> log);
        show("default: terminated", a::isTerminated);

        ScheduledThreadPoolExecutor b = new ScheduledThreadPoolExecutor(1);
        b.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ScheduledFuture<?> dropped = b.schedule(() -> log.add("dropped"), 1, TimeUnit.HOURS);
        show("drop policy flag", b::getExecuteExistingDelayedTasksAfterShutdownPolicy);
        b.shutdown();
        show("drop: awaited", () -> b.awaitTermination(30, TimeUnit.SECONDS));
        show("drop: cancelled", dropped::isCancelled);
        show("drop: log", () -> log);

        ScheduledThreadPoolExecutor k = new ScheduledThreadPoolExecutor(1);
        k.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
        AtomicInteger keep = new AtomicInteger();
        ScheduledFuture<?> kept = k.scheduleAtFixedRate(keep::incrementAndGet, 0, 1, TimeUnit.MILLISECONDS);
        k.shutdown();
        int was = keep.get();
        until(() -> keep.get() > was + 2);
        show("keep: still running", () -> kept.isCancelled());
        show("keep: not terminated", k::isTerminated);
        show("keep: reject new", () -> {
            try { k.schedule(() -> { }, 0, TimeUnit.SECONDS); return "accepted"; }
            catch (RejectedExecutionException e) { return "rejected"; }
        });
        show("keep: shutdownNow", () -> k.shutdownNow().size() <= 1);
        show("keep: terminated", () -> k.awaitTermination(30, TimeUnit.SECONDS));

        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture<?> sa = s.schedule(() -> { }, 1, TimeUnit.HOURS);
        List<Runnable> rest = s.shutdownNow();
        show("shutdownNow returned", rest::size);
        show("shutdownNow same future", () -> rest.get(0) == sa);
        show("shutdownNow leaves it", () -> sa.isDone() + " " + sa.isCancelled());
        show("awaited", () -> s.awaitTermination(30, TimeUnit.SECONDS));
    }

    static void decorate() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1) {
            @Override protected <V> RunnableScheduledFuture<V> decorateTask(Runnable r, RunnableScheduledFuture<V> t) {
                log.add("decorate runnable");
                return t;
            }
            @Override protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> c, RunnableScheduledFuture<V> t) {
                log.add("decorate callable");
                return t;
            }
        };
        p.schedule(() -> { }, 0, TimeUnit.SECONDS).get();
        p.schedule(() -> "x", 0, TimeUnit.SECONDS).get();
        p.submit(() -> "y").get();
        p.submit(() -> { }).get();
        p.execute(() -> { });
        ScheduledFuture<?> rate = p.scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.HOURS);
        rate.cancel(false);
        show("decorate calls", () -> log);
        p.shutdown();
    }

    public static void main(String[] args) throws Exception {
        arguments();
        delays();
        periodic();
        shutdownPolicies();
        decorate();
    }
}
