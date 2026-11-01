package lazy.async;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Tasks run in the order given, on one daemon thread, and one that throws does not stop the loop. */
public class ExecutorOrder {
    public static void main(String[] args) throws Exception {
        Executor loop = Loop.executor();
        System.out.println("same executor " + (loop == Loop.executor()));
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                System.out.println("reported " + e.getMessage() + " on " + t.getName()));

        int[] next = new int[1];
        int[] wrong = new int[1];
        Thread[] thread = new Thread[1];
        CountDownLatch done = new CountDownLatch(1);
        for (int i = 0; i < 1000; i++) {
            int expected = i;
            loop.execute(() -> {
                if (thread[0] == null) thread[0] = Thread.currentThread();
                if (next[0]++ != expected || thread[0] != Thread.currentThread()) wrong[0]++;
                if (expected == 999) done.countDown();
            });
        }
        done.await();
        System.out.println("out of order " + wrong[0]);
        System.out.println(thread[0].getName() + " daemon " + thread[0].isDaemon());

        CountDownLatch more = new CountDownLatch(1);
        loop.execute(() -> { throw new RuntimeException("boom"); });
        loop.execute(() -> { throw new Error("bang"); });
        loop.execute(() -> {
            loop.execute(more::countDown);
            System.out.println("queued from the loop, runs after this");
        });
        more.await();

        try {
            loop.execute(null);
        } catch (NullPointerException e) {
            System.out.println("null task " + e.getClass().getSimpleName());
        }

        // A task that keeps queueing tasks does not starve a timer, and main need not wait.
        boolean[] stop = new boolean[1];
        Runnable[] spin = new Runnable[1];
        spin[0] = () -> { if (!stop[0]) loop.execute(spin[0]); };
        loop.execute(spin[0]);
        Loop.delay(1, TimeUnit.MILLISECONDS).thenRun(() -> {
            stop[0] = true;
            System.out.println("timer ran beside a spinning task");
        });
    }
}
