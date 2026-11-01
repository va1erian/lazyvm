package lazy.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Delays fire in time order, on the loop's thread, and never early; a zero delay is still later;
 * a delay set from main wakes a reactor parked on a far one, which on LazyOS also shows threads
 * share the reactor's waker descriptor; and the loop keeps the VM up after main returns.
 */
public class Timers {
    static CompletableFuture<Void> delay(long ms) {
        return Loop.delay(ms, TimeUnit.MILLISECONDS).toCompletableFuture();
    }

    public static void main(String[] args) throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> c = delay(300).thenRun(() -> order.add("300"));
        CompletableFuture<Void> a = delay(100).thenRun(() -> order.add("100"));
        CompletableFuture<Void> b = delay(200).thenRun(() -> order.add("200"));
        CompletableFuture.allOf(a, b, c).join();
        System.out.println("order " + order);

        long start = System.nanoTime();
        String[] thread = new String[1];
        delay(50).thenRun(() -> thread[0] = Thread.currentThread().getName()).join();
        System.out.println("not early " + (System.nanoTime() - start >= 50_000_000L));
        System.out.println("on " + thread[0]);

        CountDownLatch checked = new CountDownLatch(1);
        Loop.executor().execute(() -> {
            // Nothing can complete while this task holds the loop.
            CompletableFuture<Void> zero = delay(0), negative = delay(-5);
            System.out.println("zero done in the call " + zero.isDone());
            System.out.println("negative done in the call " + negative.isDone());
            CompletableFuture.allOf(zero, negative).thenRun(() -> {
                System.out.println("zero and negative done later");
                checked.countDown();
            });
        });
        checked.await();

        CompletableFuture<Void> far = delay(3_600_000);
        CompletableFuture<Void> never = Loop.delay(Long.MAX_VALUE, TimeUnit.DAYS).toCompletableFuture();
        Thread.sleep(50);
        delay(20).join();
        System.out.println("woke a parked reactor, far done " + far.isDone() + " " + never.isDone());
        far.cancel(true);
        never.cancel(true);

        try {
            Loop.delay(1, null);
        } catch (NullPointerException e) {
            System.out.println("null unit " + e.getClass().getSimpleName());
        }

        Loop.delay(100, TimeUnit.MILLISECONDS).thenRun(() -> System.out.println("after main"));
        System.out.println("main returns");
    }
}
