// VMARGS: -Djava.util.concurrent.ForkJoinPool.common.parallelism=1
package concurrent;

import java.util.concurrent.*;

/** With a common pool below parallelism two, naming that pool means the same as naming none; what the new thread inherits. */
public class CompletableFutureNoCommonEdges {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static String here() {
        Thread t = Thread.currentThread();
        return "worker=" + (t instanceof ForkJoinWorkerThread) + " daemon=" + t.isDaemon() + " priority=" + t.getPriority()
            + " group=" + t.getThreadGroup().getName() + " name=" + t.getName().replaceAll("[0-9]+", "N");
    }

    public static void main(String[] args) throws Exception {
        show("named common, apply", () -> CompletableFuture.completedFuture("v").thenApplyAsync(v -> here(), ForkJoinPool.commonPool()).get(30, TimeUnit.SECONDS));
        show("named common, supply", () -> CompletableFuture.supplyAsync(CompletableFutureNoCommonEdges::here, ForkJoinPool.commonPool()).get(30, TimeUnit.SECONDS));
        show("named common, run", () -> {
            String[] r = new String[1];
            CompletableFuture.runAsync(() -> r[0] = here(), ForkJoinPool.commonPool()).get(30, TimeUnit.SECONDS);
            return r[0];
        });
        show("inherits from the caller", () -> {
            String[] r = new String[1];
            Thread t = new Thread(() -> r[0] = CompletableFuture.supplyAsync(CompletableFutureNoCommonEdges::here).join());
            t.setDaemon(true);
            t.setPriority(7);
            t.start();
            t.join();
            return r[0];
        });
        show("another pool is used as given", () -> {
            ForkJoinPool p = new ForkJoinPool(1);
            try {
                return CompletableFuture.supplyAsync(() -> ForkJoinTask.getPool() == p, p).get(30, TimeUnit.SECONDS);
            } finally {
                p.shutdown();
            }
        });
    }
}
