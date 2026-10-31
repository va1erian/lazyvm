// VMARGS: -Djava.util.concurrent.ForkJoinPool.common.parallelism=1
package concurrent;

import java.util.concurrent.*;

/** With a common pool below parallelism two, each default async task gets a new thread. */
public class CompletableFutureNoCommon {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    public static void main(String[] args) throws Exception {
        show("parallelism", ForkJoinPool::getCommonPoolParallelism);
        show("supplyAsync thread", () -> CompletableFuture.supplyAsync(() -> {
            Thread t = Thread.currentThread();
            return "worker=" + (t instanceof ForkJoinWorkerThread) + " inPool=" + ForkJoinTask.inForkJoinPool()
                + " daemon=" + t.isDaemon() + " main=" + t.getName().equals("main");
        }).get(30, TimeUnit.SECONDS));
        show("thenApplyAsync thread", () -> CompletableFuture.completedFuture("v").thenApplyAsync(v -> {
            Thread t = Thread.currentThread();
            return "worker=" + (t instanceof ForkJoinWorkerThread) + " daemon=" + t.isDaemon();
        }).get(30, TimeUnit.SECONDS));
        Thread[] seen = new Thread[2];
        CompletableFuture.runAsync(() -> seen[0] = Thread.currentThread()).get(30, TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> seen[1] = Thread.currentThread()).get(30, TimeUnit.SECONDS);
        show("a new thread each time", () -> seen[0] != seen[1]);
        show("runAsync chain", () -> CompletableFuture.supplyAsync(() -> 1).thenApplyAsync(v -> v + 1).thenCombineAsync(CompletableFuture.completedFuture(1), Integer::sum).get(30, TimeUnit.SECONDS));
    }
}
