package lazy.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lazy.internal.Reactor;

/** No polling: with a far timer set, the reactor does not wake over 2 s of idling. */
public class Idle {
    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> far = Loop.delay(1, TimeUnit.HOURS).toCompletableFuture();
        // Let it see the timers and park.
        Loop.delay(50, TimeUnit.MILLISECONDS).toCompletableFuture().join();
        Thread.sleep(50);
        long before = Reactor.wakes();
        Thread.sleep(2000);
        long wakes = Reactor.wakes() - before;
        System.out.println("at most one wake " + (wakes <= 1));
        if (wakes > 1) System.out.println(wakes + " wakes");
        // A cancel reaches the reactor: the withdrawn timer does not wake it at its deadline.
        CompletableFuture<Void> withdrawn = Loop.delay(300, TimeUnit.MILLISECONDS).toCompletableFuture();
        withdrawn.cancel(true);
        Thread.sleep(50);
        before = Reactor.wakes();
        Thread.sleep(500);
        wakes = Reactor.wakes() - before;
        System.out.println("a withdrawn timer does not wake it " + (wakes <= 1));
        if (wakes > 1) System.out.println(wakes + " wakes");
        before = Reactor.wakes();
        far.cancel(true);
        Loop.delay(1, TimeUnit.MILLISECONDS).toCompletableFuture().join();
        System.out.println("a request wakes it " + (Reactor.wakes() > before));
    }
}
