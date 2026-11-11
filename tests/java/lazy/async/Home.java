package lazy.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lazy.internal.Homes;

/**
 * Loop.home() names the executor that owns the current thread: the loop's on the loop's thread,
 * a registered one (the UI thread's) on its thread, and none elsewhere (docs/siesta-async.md
 * section 4).
 */
public class Home {
    public static void main(String[] args) throws Exception {
        System.out.println("main: " + (Loop.home() == null));
        CompletableFuture<Boolean> onLoop = new CompletableFuture<>();
        Loop.executor().execute(() -> onLoop.complete(Loop.home() == Loop.executor()));
        System.out.println("loop: " + onLoop.get());

        Executor ui = Runnable::run;
        Thread t = new Thread(() -> {
            Homes.register(Thread.currentThread(), ui);
            System.out.println("registered: " + (Loop.home() == ui));
            Homes.register(Thread.currentThread(), null);
            System.out.println("cleared: " + (Loop.home() == null));
        });
        t.start();
        t.join();
        System.out.println("other thread: " + (Loop.home() == null));
    }
}
