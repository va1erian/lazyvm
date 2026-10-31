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

    static Runnable task;
    static int threes;
    static int twos;
    static boolean ran;

    /** Frames so wide that a thread's slots run out, not its frames, so its end can be met to the slot. */
    static void wide(int d) {
        long a0 = 1, a1 = 1, a2 = 1, a3 = 1, a4 = 1, a5 = 1, a6 = 1, a7 = 1, a8 = 1, a9 = 1, a10 = 1, a11 = 1, a12 = 1, a13 = 1, a14 = 1, a15 = 1, a16 = 1, a17 = 1, a18 = 1, a19 = 1, a20 = 1, a21 = 1, a22 = 1, a23 = 1, a24 = 1, a25 = 1, a26 = 1, a27 = 1, a28 = 1, a29 = 1, a30 = 1, a31 = 1, a32 = 1, a33 = 1, a34 = 1, a35 = 1, a36 = 1, a37 = 1, a38 = 1, a39 = 1, a40 = 1, a41 = 1, a42 = 1, a43 = 1, a44 = 1, a45 = 1, a46 = 1, a47 = 1, a48 = 1, a49 = 1, a50 = 1, a51 = 1, a52 = 1, a53 = 1, a54 = 1, a55 = 1, a56 = 1, a57 = 1, a58 = 1, a59 = 1, a60 = 1, a61 = 1, a62 = 1, a63 = 1, a64 = 1, a65 = 1, a66 = 1, a67 = 1, a68 = 1, a69 = 1, a70 = 1, a71 = 1, a72 = 1, a73 = 1, a74 = 1, a75 = 1, a76 = 1, a77 = 1, a78 = 1, a79 = 1, a80 = 1, a81 = 1, a82 = 1, a83 = 1, a84 = 1, a85 = 1, a86 = 1, a87 = 1, a88 = 1, a89 = 1, a90 = 1, a91 = 1, a92 = 1, a93 = 1, a94 = 1, a95 = 1, a96 = 1, a97 = 1, a98 = 1, a99 = 1, a100 = 1, a101 = 1, a102 = 1, a103 = 1, a104 = 1, a105 = 1, a106 = 1, a107 = 1, a108 = 1, a109 = 1, a110 = 1, a111 = 1, a112 = 1, a113 = 1, a114 = 1, a115 = 1, a116 = 1, a117 = 1, a118 = 1, a119 = 1, a120 = 1, a121 = 1, a122 = 1, a123 = 1, a124 = 1, a125 = 1, a126 = 1, a127 = 1, a128 = 1, a129 = 1, a130 = 1, a131 = 1, a132 = 1, a133 = 1, a134 = 1, a135 = 1, a136 = 1, a137 = 1, a138 = 1, a139 = 1, a140 = 1, a141 = 1, a142 = 1, a143 = 1, a144 = 1, a145 = 1, a146 = 1, a147 = 1, a148 = 1, a149 = 1;
        try {
            wide(d + 1);
        } catch (StackOverflowError e) {
            if (!ran) {
                ran = true;
                try {
                    next();
                } catch (StackOverflowError x) {
                    // Now the rerun.
                }
            }
            throw e;
        }
        if (a0 == 2) throw new Error();
    }

    static void next() {
        if (threes > 0) {
            threes--;
            three();
        } else if (twos > 0) {
            twos--;
            two();
        } else {
            task.run();
        }
    }

    /** With next, 4 slots. */
    static void three() {
        int x = 0;
        int y = 0;
        next();
    }

    /** With next, 3 slots. */
    static void two() {
        int x = 0;
        next();
    }

    /** A task with no source, run at each count of slots left, then again from the top. */
    static int pendingAfterRerun() throws InterruptedException {
        int pending = 0;
        for (int slots = 0; slots < 120; slots++) {
            int fours = slots / 4;
            while (fours > 0 && (slots - fours * 4) % 3 != 0) fours--;
            List<Runnable> held = new ArrayList<>();
            CompletableFuture<Integer> f = CompletableFuture.supplyAsync(() -> 1, held::add);
            task = held.get(0);
            ran = false;
            threes = fours;
            twos = (slots - fours * 4) / 3;
            Thread t = new Thread(() -> {
                try {
                    wide(0);
                } catch (StackOverflowError e) {
                    // Only the task matters.
                }
            });
            t.start();
            t.join();
            task.run();
            if (!f.isDone()) pending++;
        }
        return pending;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("sourceless tasks pending after a rerun: " + pendingAfterRerun());
        int limit = stackLimit();
        System.out.println("pending after a visit: " + pendingAfterVisit(limit));
        System.out.println("returns lost: " + returnsLost(limit));
    }
}
