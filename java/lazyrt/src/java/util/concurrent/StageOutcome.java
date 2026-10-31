package java.util.concurrent;

/**
 * How a stage settled. Immutable, so an obtrude swaps one reference and a reader never sees half a
 * change: a value may itself be a {@code Throwable}, which is why the two have separate fields.
 */
final class StageOutcome {
    static final StageOutcome NULL = new StageOutcome(null, null);

    final Object value;
    final Throwable failure;

    private StageOutcome(Object value, Throwable failure) {
        this.value = value;
        this.failure = failure;
    }

    static StageOutcome of(Object value) {
        return value == null ? NULL : new StageOutcome(value, null);
    }

    static StageOutcome failed(Throwable failure) {
        return new StageOutcome(null, failure);
    }

    /** A failure from a function or a source: the callback's next stage sees it wrapped once. */
    static StageOutcome thrown(Throwable t) {
        return failed(t instanceof CompletionException ? t : new CompletionException(t));
    }

    /** What a dependent gets from this source when it just passes the result on. */
    StageOutcome inherited() {
        return failure == null || failure instanceof CompletionException ? this : thrown(failure);
    }

    boolean cancelled() {
        return failure instanceof CancellationException;
    }
}
