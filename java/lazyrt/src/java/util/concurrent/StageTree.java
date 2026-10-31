package java.util.concurrent;

/**
 * {@code allOf} and {@code anyOf} as a balanced tree of two-source steps. A flat list of sources
 * would be simpler, but the tree shows: in the counts when a source is given twice ("allOf a,b,a"),
 * in how far the call got before a null ("allOf a,b,null,c"), and in what an obtrude can still
 * change ("allOf obtrude") (docs/design/concurrent.md, "Two-source dependents").
 */
final class StageTree {
    private StageTree() {
    }

    static <U> CompletableFuture<U> build(CompletableFuture<?>[] cfs, boolean any) {
        if (cfs.length == 1) {
            return step(element(cfs, 0), null, any);
        }
        return node(cfs, 0, cfs.length - 1, any);
    }

    private static <U> CompletableFuture<U> node(CompletableFuture<?>[] cfs, int lo, int hi, boolean any) {
        int mid = (lo + hi) >>> 1;
        CompletableFuture<?> left = lo == mid ? element(cfs, lo) : node(cfs, lo, mid, any);
        CompletableFuture<?> right = mid + 1 == hi ? element(cfs, hi) : node(cfs, mid + 1, hi, any);
        return step(left, right, any);
    }

    private static CompletableFuture<?> element(CompletableFuture<?>[] cfs, int i) {
        CompletableFuture<?> f = cfs[i];
        if (f == null) {
            throw new NullPointerException();
        }
        return f;
    }

    private static <U> CompletableFuture<U> step(CompletableFuture<?> a, CompletableFuture<?> b, boolean any) {
        CompletableFuture<U> d = new CompletableFuture<>();
        new StageStep(any ? StageStep.PASS : StageStep.ALL, null, null, a, b, any && b != null, d).attach();
        return d;
    }
}
