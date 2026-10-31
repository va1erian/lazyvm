package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** ThreadPoolExecutor and its scheduled form: who gets woken by a late change, the end of shutdownNow, what fixed rate means, huge delays. */
public class PoolPins {
    interface Body { Object run() throws Throwable; }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }
    interface Cond { boolean ok() throws Exception; }
    static boolean until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!c.ok()) { if (System.nanoTime() > end) return false; Thread.sleep(1); }
        return true;
    }

    static void wakes() throws Exception {
        // Workers waiting with no time limit must be told, or they never see the new size.
        ThreadPoolExecutor x = new ThreadPoolExecutor(3, 3, 1, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        x.prestartAllCoreThreads();
        until(() -> x.getActiveCount() == 0);
        x.setCorePoolSize(0);
        show("setCorePoolSize wakes idle workers", () -> until(() -> x.getPoolSize() == 0));
        x.shutdown();
        ThreadPoolExecutor y = new ThreadPoolExecutor(1, 3, 1, TimeUnit.HOURS, new SynchronousQueue<>());
        // Each holds its worker until all three are in, so no worker can be reused.
        CountDownLatch three = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) y.execute(() -> { three.countDown(); try { three.await(); } catch (InterruptedException e) { } });
        three.await();
        show("three workers held", y::getPoolSize);
        until(() -> y.getActiveCount() == 0);
        y.setMaximumPoolSize(1);
        show("setMaximumPoolSize wakes idle workers", () -> until(() -> y.getPoolSize() == 1));
        y.shutdown();
    }

    static void stops() throws Exception {
        // The worker's thread only gets to its first task after the stop: the task sees the interrupt.
        CountDownLatch stopped = new CountDownLatch(1);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        ThreadPoolExecutor x = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(() -> { try { stopped.await(); } catch (InterruptedException e) { } r.run(); }));
        x.execute(() -> seen.add("first task interrupted=" + Thread.currentThread().isInterrupted()));
        x.shutdownNow();
        stopped.countDown();
        x.awaitTermination(15, TimeUnit.SECONDS);
        show("first task after a stop", () -> seen);
        // The pool has not ended while shutdownNow is still emptying the queue.
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<Runnable>() {
            @Override public int drainTo(Collection<? super Runnable> c) {
                try { Thread.sleep(200); } catch (InterruptedException e) { }
                return super.drainTo(c);
            }
        };
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        ThreadPoolExecutor y = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, q) {
            @Override protected void terminated() { log.add("hook sees queue=" + getQueue().size()); }
        };
        CountDownLatch in = new CountDownLatch(1);
        y.execute(() -> { in.countDown(); try { Thread.sleep(15000); } catch (InterruptedException e) { } });
        y.execute(() -> { });
        in.await();
        Thread w = new Thread(() -> {
            try { log.add("await=" + y.awaitTermination(15, TimeUnit.SECONDS) + " queue then=" + q.size()); } catch (InterruptedException e) { }
        });
        w.start();
        List<Runnable> left = y.shutdownNow();
        w.join();
        show("end waits for the drain", () -> log + " returned=" + left.size());
    }

    static void fixedRate() throws Exception {
        // A slow first run: fixed rate is behind by three periods and runs them back to back,
        // where fixed delay would wait a whole period each time.
        long period = 1000;
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1);
        long[] at = new long[4];
        AtomicInteger n = new AtomicInteger();
        CountDownLatch four = new CountDownLatch(4);
        ScheduledFuture<?> f = s.scheduleAtFixedRate(() -> {
            int i = n.getAndIncrement();
            if (i < 4) {
                if (i == 0) { try { Thread.sleep(3 * period); } catch (InterruptedException e) { } }
                at[i] = System.nanoTime();
                four.countDown();
            }
        }, 0, period, TimeUnit.MILLISECONDS);
        four.await();
        f.cancel(false);
        s.shutdown();
        // Fixed delay would take exactly two periods here at the least, whatever else goes on.
        show("fixed rate catches up after a slow run", () -> at[3] - at[1] < TimeUnit.MILLISECONDS.toNanos(2 * period));
    }

    static void hugeDelays() throws Exception {
        // A huge delay must not sort ahead of a task already overdue.
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1);
        List<String> l = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch in = new CountDownLatch(1), rel = new CountDownLatch(1);
        s.execute(() -> { in.countDown(); try { rel.await(); } catch (InterruptedException e) { } });
        in.await();
        s.schedule(() -> { l.add("overdue"); }, 0, TimeUnit.NANOSECONDS);
        Thread.sleep(50);
        s.schedule(() -> { l.add("huge"); }, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        s.schedule(() -> { l.add("soon"); }, 1, TimeUnit.MILLISECONDS);
        rel.countDown();
        show("huge delay behind an overdue task", () -> until(() -> l.size() == 2) + " " + l + " queued=" + s.getQueue().size());
        s.shutdownNow();
        // The same when the huge time is reckoned as a run ends, with the other task overdue by then.
        ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1);
        List<String> m = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inRun = new CountDownLatch(1);
        t.scheduleWithFixedDelay(() -> { m.add("periodic"); inRun.countDown(); try { Thread.sleep(100); } catch (InterruptedException e) { } }, 0, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        inRun.await();
        t.schedule(() -> { m.add("overdue"); }, 0, TimeUnit.NANOSECONDS);
        show("huge period behind an overdue task", () -> until(() -> m.size() == 2) + " " + m);
        t.shutdownNow();
    }

    /** A queue whose take sits out the first interrupt each thread gets: only one that follows wakes it. */
    static class Stubborn extends LinkedBlockingQueue<Runnable> {
        final ThreadLocal<Boolean> woken = new ThreadLocal<>();
        @Override public Runnable take() throws InterruptedException {
            for (;;) {
                try { return super.take(); }
                catch (InterruptedException e) { if (woken.get() != null) throw e; woken.set(true); }
            }
        }
    }

    static void chain() throws Exception {
        // Shutdown's interrupts are sat out: a remove then wakes the idle workers, each passing it on.
        ThreadPoolExecutor x = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new Stubborn());
        x.prestartAllCoreThreads();
        until(() -> x.getActiveCount() == 0);
        Thread.sleep(100);
        x.shutdown();
        Thread.sleep(100);
        x.remove(() -> { });
        show("idle workers wake each other", () -> x.awaitTermination(15, TimeUnit.SECONDS) + " " + x.getPoolSize());
    }

    /** Past a stop the queue is not asked, so one that throws does not keep the pool from ending. */
    static void badQueue() throws Exception {
        boolean[] bad = new boolean[1];
        BlockingQueue<Runnable> q = new LinkedBlockingQueue<Runnable>() {
            @Override public boolean isEmpty() { if (bad[0]) throw new IllegalStateException("isEmpty"); return super.isEmpty(); }
        };
        AtomicInteger hook = new AtomicInteger();
        ThreadPoolExecutor x = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, q) {
            @Override protected void terminated() { hook.incrementAndGet(); }
        };
        x.prestartAllCoreThreads();
        until(() -> x.getActiveCount() == 0);
        Thread.sleep(100);
        bad[0] = true;
        show("shutdownNow, queue throws", x::shutdownNow);
        show("ends all the same", () -> x.awaitTermination(15, TimeUnit.SECONDS) + " hook=" + hook);
    }

    /** At the lowest timeout there is, as far as a call gives up at once. */
    static void lowestTimeout() throws Exception {
        ExecutorService e = Executors.newFixedThreadPool(1);
        List<Callable<String>> l = Collections.singletonList(() -> "v");
        CountDownLatch rel = new CountDownLatch(1), in = new CountDownLatch(1);
        e.execute(() -> { in.countDown(); try { rel.await(); } catch (InterruptedException x) { } });
        in.await();
        show("invokeAny", () -> e.invokeAny(l, Long.MIN_VALUE, TimeUnit.DAYS));
        show("awaitTermination", () -> e.awaitTermination(Long.MIN_VALUE, TimeUnit.DAYS));
        rel.countDown();
        e.shutdown();
    }

    public static void main(String[] args) throws Exception {
        chain();
        badQueue();
        lowestTimeout();
        wakes();
        stops();
        fixedRate();
        hugeDelays();
        System.exit(0);
    }
}
