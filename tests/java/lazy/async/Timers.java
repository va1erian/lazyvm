package lazy.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Delays fire in time order, on the loop's thread, and never early; a zero delay is still later;
 * a delay set from main wakes a reactor parked on a far one, which on LazyOS also shows threads
 * share the reactor's waker descriptor; and the loop keeps the VM up after main returns. Handlers
 * are attached on the loop, where nothing can complete until the task ends, so a stalled machine
 * cannot change what this prints.
 */
public class Timers {
    static CompletableFuture<Void> delay(long ms) {
        return Loop.delay(ms, TimeUnit.MILLISECONDS).toCompletableFuture();
    }

    public static void main(String[] args) throws Exception {
        List<String> order = new ArrayList<>();
        CountDownLatch fired = new CountDownLatch(3);
        Loop.executor().execute(() -> {
            delay(1500).thenRun(() -> { order.add("1500"); fired.countDown(); });
            delay(500).thenRun(() -> { order.add("500"); fired.countDown(); });
            delay(1000).thenRun(() -> { order.add("1000"); fired.countDown(); });
        });
        fired.await();
        System.out.println("order " + order);

        CountDownLatch timed = new CountDownLatch(1);
        Loop.executor().execute(() -> {
            long start = System.nanoTime();
            delay(50).thenRun(() -> {
                System.out.println("not early " + (System.nanoTime() - start >= 50_000_000L));
                System.out.println("on " + Thread.currentThread().getName());
                timed.countDown();
            });
        });
        timed.await();

        CountDownLatch checked = new CountDownLatch(1);
        Loop.executor().execute(() -> {
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

        System.out.println("main returns");
        Loop.delay(100, TimeUnit.MILLISECONDS).thenRun(() -> System.out.println("after main"));
    }
}
