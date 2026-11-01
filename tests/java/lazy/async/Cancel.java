package lazy.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lazy.internal.Reactor;

/**
 * Settling a stage from outside withdraws its operation exactly once: the stage ends as
 * CompletableFuture says, the pending count is right (so the VM exits with an hour's timer
 * withdrawn), and a timer that lost the race changes nothing. Dependents run on the settling
 * thread.
 */
public class Cancel {
    static CompletableFuture<Void> far() {
        return Loop.delay(1, TimeUnit.HOURS).toCompletableFuture();
    }

    static void pendingIs(int expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Reactor.pending() != expected) {
            if (System.nanoTime() > end) throw new AssertionError("pending " + Reactor.pending());
            Thread.sleep(1);
        }
    }

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> f = far();
        f.whenComplete((v, e) -> System.out.println("dependent on " + Thread.currentThread().getName()
                + " with " + e.getClass().getSimpleName()));
        System.out.println("pending " + Reactor.pending());
        System.out.println("cancel " + f.cancel(true));
        System.out.println("pending " + Reactor.pending());
        System.out.println("cancelled " + f.isCancelled());
        System.out.println("cancel again " + f.cancel(true));
        System.out.println("complete after " + f.complete(null));
        System.out.println("exceptionally after " + f.completeExceptionally(new Error()));
        System.out.println("pending " + Reactor.pending());

        // On the loop, so the timer cannot come due before the cancel however the machine stalls.
        boolean[] ran = new boolean[1];
        boolean[] failed = new boolean[1];
        Loop.executor().execute(() -> {
            CompletableFuture<Void> soon = Loop.delay(50, TimeUnit.MILLISECONDS).toCompletableFuture();
            CompletableFuture<Void> after = soon.thenRun(() -> ran[0] = true);
            soon.cancel(false);
            failed[0] = after.isCompletedExceptionally();
        });
        Thread.sleep(150);
        pendingIs(0);
        System.out.println("cancelled delay ran " + ran[0] + ", dependent failed " + failed[0]);

        f = far();
        System.out.println("complete " + f.complete(null) + " " + f.isDone() + " " + f.join());
        System.out.println("pending " + Reactor.pending());

        f = far();
        System.out.println("exceptionally " + f.completeExceptionally(new IllegalStateException("x"))
                + " " + f.isCompletedExceptionally());
        System.out.println("pending " + Reactor.pending());

        f = far();
        try {
            f.completeExceptionally(null);
        } catch (NullPointerException e) {
            System.out.println("null cause leaves it pending " + !f.isDone() + " " + Reactor.pending());
        }
        try {
            f.obtrudeException(null);
        } catch (NullPointerException e) {
            System.out.println("null obtrude leaves it pending " + !f.isDone() + " " + Reactor.pending());
        }
        f.obtrudeValue(null);
        System.out.println("obtrudeValue " + f.isDone() + " " + f.isCompletedExceptionally());
        System.out.println("pending " + Reactor.pending());
        f.obtrudeException(new IllegalStateException("y"));
        System.out.println("obtrude on done " + f.isCompletedExceptionally());
        System.out.println("pending " + Reactor.pending());

        f = far();
        f.obtrudeException(new IllegalStateException("z"));
        System.out.println("obtrudeException " + f.isCompletedExceptionally());
        System.out.println("pending " + Reactor.pending());

        // Cancels racing timers that are due at once: each stage settles once, whoever wins.
        int mismatches = 0;
        for (int i = 0; i < 2000; i++) {
            CompletableFuture<Void> racing = Loop.delay(0, TimeUnit.MILLISECONDS).toCompletableFuture();
            if (i % 2 == 0) Thread.yield();
            boolean won = racing.cancel(true);
            if (won && !racing.isCancelled()) mismatches++;
        }
        pendingIs(0);
        System.out.println("race mismatches " + mismatches);
        far().cancel(true);
        System.out.println("exits with nothing pending");
    }
}
