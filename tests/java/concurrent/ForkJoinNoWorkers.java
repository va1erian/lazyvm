// VMARGS: -Djava.util.concurrent.ForkJoinPool.common.parallelism=0
package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A common pool set to parallelism 0: it reports 1, makes no workers, and outside threads run what they await. */
public class ForkJoinNoWorkers {
    interface Body { Object run() throws Throwable; }
    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("worker-[0-9]+", "worker-N"); }
    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    public static class F implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        public ForkJoinWorkerThread newThread(ForkJoinPool p) {
            ForkJoinWorkerThread t = new ForkJoinWorkerThread(p) { };
            t.setName("custom-" + t.getName());
            return t;
        }
    }
    public static class H implements Thread.UncaughtExceptionHandler {
        public void uncaughtException(Thread t, Throwable e) { System.out.println("handler H: " + e); }
    }

    public static void main(String[] args) throws Exception {
        show("common parallelism", ForkJoinPool::getCommonPoolParallelism);
        ForkJoinPool c = ForkJoinPool.commonPool();
        show("getParallelism", c::getParallelism);
        show("handler", () -> c.getUncaughtExceptionHandler() != null);
        show("toString", () -> c.toString().replaceAll(", size.*", ""));
        // Queued, then awaiting from outside.
        boolean[] ran = new boolean[3];
        c.execute(() -> ran[0] = true);
        show("awaitQuiescence", () -> c.awaitQuiescence(200, TimeUnit.MILLISECONDS));
        show("execute ran", () -> ran[0]);
        show("joined from outside", () -> c.submit(() -> Thread.currentThread() instanceof ForkJoinWorkerThread ? "worker" : "caller").join().length() > 0);
        show("fork join", () -> ForkJoinTask.adapt(() -> "v").fork().join());
        show("get", () -> c.submit(() -> "w").get());
        show("timed get", () -> { try { return c.submit(() -> "w").get(200, TimeUnit.MILLISECONDS); } catch (TimeoutException e) { return "timeout"; } });
        show("worker thread name", () -> { String[] s = new String[1]; CountDownLatch l = new CountDownLatch(1); c.execute(() -> { s[0] = Thread.currentThread().getName(); l.countDown(); }); return l.await(500, TimeUnit.MILLISECONDS) ? s[0] : "no worker"; });
        show("tryUnfork outside", () -> { ForkJoinTask<String> t = ForkJoinTask.adapt(() -> "v").fork(); boolean b = t.tryUnfork(); return b || t.join().equals("v"); });
        show("pool quiet", () -> c.awaitQuiescence(2, TimeUnit.SECONDS));
    }
}
