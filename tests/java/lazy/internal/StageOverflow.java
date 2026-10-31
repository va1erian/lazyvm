package lazy.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A firing that runs out of stack gives its entry back, so a visit finishes the work. A hand-written
 * golden: the oracle can lose that entry, and does when its JIT is off (concurrent.md, "What is not
 * pinned"). The end of the stack is found afresh, so no line depends on a frame count.
 */
public class StageOverflow {
    static void recurse(int depth, Runnable r) {
        if (depth == 0) r.run();
        else recurse(depth - 1, r);
    }

    static boolean fits(int depth) {
        try {
            recurse(depth, () -> { });
            return true;
        } catch (StackOverflowError e) {
            return false;
        }
    }

    static int stackLimit() {
        int lo = 100, hi = 1 << 22;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (fits(m)) lo = m; else hi = m;
        }
        return lo;
    }

    static void deep(int k) {
        if (k > 0) deep(k - 1);
    }

    static void completeNear(int depth, CompletableFuture<String> s) {
        try {
            recurse(depth, () -> s.complete("v"));
        } catch (StackOverflowError e) {
            // A visit comes next.
        }
    }

    /** One visit to the source, nothing else, and everything below it has settled. */
    static int pendingAfterVisit(int limit) {
        int worst = 0;
        for (int d = limit - 100; d <= limit + 5; d++) {
            CompletableFuture<String> s = new CompletableFuture<>();
            CompletableFuture<String> mid = s.thenApply(v -> v);
            List<CompletableFuture<?>> stages = new ArrayList<>();
            stages.add(mid);
            for (int i = 0; i < 20; i++) {
                CompletableFuture<String> one = mid.thenApply(v -> v + "1");
                stages.add(one);
                stages.add(one.thenApply(v -> v + "2"));
            }
            completeNear(d, s);
            if (!s.isDone()) continue;
            s.complete("again");
            int pending = 0;
            for (CompletableFuture<?> f : stages) if (!f.isDone()) pending++;
            worst = Math.max(worst, pending);
        }
        return worst;
    }

    /** A function that got to its return has its value on the stage, however much later it is made one. */
    static int returnsLost(int limit) {
        int lost = 0;
        for (int inner = -1; inner < 8; inner++) {
            for (int d = limit - 40; d <= limit + 3; d++) {
                int k = inner;
                boolean[] returning = new boolean[1];
                CompletableFuture<String> s = new CompletableFuture<>();
                CompletableFuture<String> stage = s.thenApply(v -> {
                    if (k >= 0) deep(k);
                    returning[0] = true;
                    return "r";
                });
                completeNear(d, s);
                s.complete("again");
                if (returning[0] && !"r".equals(valueOf(stage))) lost++;
            }
        }
        return lost;
    }

    static String valueOf(CompletableFuture<String> stage) {
        try {
            return stage.getNow("pending");
        } catch (CompletionException e) {
            return e.toString();
        }
    }

    public static void main(String[] args) {
        int limit = stackLimit();
        System.out.println("pending after a visit: " + pendingAfterVisit(limit));
        System.out.println("returns lost: " + returnsLost(limit));
    }
}
