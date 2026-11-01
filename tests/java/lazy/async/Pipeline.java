package lazy.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The S6 gate: 1000 chains of timer stages fanned in with allOf, and one chain 1000 long. */
public class Pipeline {
    static CompletableFuture<Integer> chain(int i) {
        return Loop.delay(i % 7, TimeUnit.MILLISECONDS)
                .thenCompose(v -> Loop.delay(i % 3, TimeUnit.MILLISECONDS))
                .thenApply(v -> i)
                .toCompletableFuture();
    }

    static CompletableFuture<Integer> along(int left, int sum) {
        if (left == 0) return CompletableFuture.completedFuture(sum);
        return Loop.delay(0, TimeUnit.MILLISECONDS).toCompletableFuture()
                .thenCompose(v -> along(left - 1, sum + 1));
    }

    public static void main(String[] args) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Integer>[] all = new CompletableFuture[1000];
        for (int i = 0; i < all.length; i++) all[i] = chain(i);
        CompletableFuture.allOf(all).thenRun(() -> {
            long sum = 0;
            for (CompletableFuture<Integer> c : all) sum += c.join();
            System.out.println("sum " + sum);
        }).join();
        System.out.println("long chain " + along(1000, 0).join());
    }
}
