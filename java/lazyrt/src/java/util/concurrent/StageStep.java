package java.util.concurrent;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One dependent stage's work: when its sources allow, compute the {@code target}'s outcome. One
 * class with an action code, rather than one per public method, keeps the library small.
 *
 * <p>Sources are read when the work runs and not when it was triggered, so an obtrude in between
 * is seen ({@code CompletableFutureEdges}: "async reads at run"). All firing rules that decide what
 * can be observed are in docs/design/concurrent.md.
 */
final class StageStep extends StageLink implements Runnable, CompletableFuture.AsynchronousCompletionTask {
    static final int APPLY = 0;
    static final int ACCEPT = 1;
    static final int RUN = 2;
    static final int COMBINE = 3;
    static final int ACCEPT_BOTH = 4;
    static final int COMPOSE = 5;
    static final int WHEN = 6;
    static final int HANDLE = 7;
    static final int RECOVER = 8;
    /** Hands on the input as it is: a pending compose's inner stage, {@code anyOf}. */
    static final int PASS = 9;
    /** Reports the input's failure, or a null value: {@code allOf}. */
    static final int ALL = 10;
    static final int SUPPLY = 11;

    private static final Executor NEW_THREADS = new StageThreads();

    private final int action;
    private final Object fn;
    private final Executor executor;
    /** The receiver, and the other source if there are two; both null for a supply or a run. */
    private final CompletableFuture<?> first;
    private final CompletableFuture<?> second;
    /** With two sources: the first to settle is enough. */
    private final boolean either;
    private final CompletableFuture<?> target;
    /** Set the first time the sources allowed a firing; that one does the work. */
    private boolean fired;
    /** A task has settled its target: an entry still on a list is nothing but a count now. */
    private boolean ended;
    /** The entry on {@code second}'s list. */
    private OtherSide otherSide;

    StageStep(int action, Object fn, Executor executor, CompletableFuture<?> first, CompletableFuture<?> second,
            boolean either, CompletableFuture<?> target) {
        this.action = action;
        this.fn = fn;
        this.executor = executor;
        this.first = first;
        this.second = second;
        this.either = either;
        this.target = target;
    }

    /** Executors: the common pool serves only with two threads or more; below, a thread per task. */
    static Executor defaultExecutor() {
        return ForkJoinPool.getCommonPoolParallelism() >= 2 ? ForkJoinPool.commonPool() : NEW_THREADS;
    }

    /** An executor named by the caller; naming the common pool means the same as naming none. */
    static Executor screen(Executor given) {
        if (given == null) {
            throw new NullPointerException();
        }
        return given == ForkJoinPool.commonPool() ? defaultExecutor() : given;
    }

    /** Joins the lists of the pending sources, or runs at once when the sources allow it. */
    void attach() {
        synchronized (CompletableFuture.LOCK) {
            if (!ready()) {
                addToPendingSources();
                return;
            }
        }
        if (fire() != null && executor != null) {
            // Settled without a task, by a skip or a rejection: as a task's end does, go on with what
            // the sources have left to fire.
            endOnSources();
        }
    }

    private void addToPendingSources() {
        if (first.outcome == null) {
            addTo(first);
        }
        if (second != null && second != first && second.outcome == null) {
            otherSide = new OtherSide();
            otherSide.addTo(second);
        }
    }

    private boolean ready() {
        if (second == null) {
            return first.outcome != null;
        }
        return either ? first.outcome != null || second.outcome != null
                : first.outcome != null && second.outcome != null;
    }

    private boolean firstFiring() {
        synchronized (CompletableFuture.LOCK) {
            if (fired || !ready()) {
                return false;
            }
            fired = true;
            return true;
        }
    }

    /** A settled target's list is gone on with, however it was settled and however often this fires. */
    @Override
    CompletableFuture<?> fire() {
        if (!firstFiring()) {
            return fired && !ended && target.outcome != null ? target : null;
        }
        try {
            if (target.outcome != null) {
                leaveOtherSource();
                return target;
            }
            if (executor == null || skipsExecutor()) {
                boolean settled = settleTarget();
                leaveSources();
                return settled ? target : null;
            }
            return handOver();
        } catch (StackOverflowError e) {
            // Out of stack before the function was reached: compute catches what it throws. The list
            // takes this back, so the next visitor does the work (CompletableFuture.fireLinks).
            fired = false;
            throw e;
        }
    }

    private CompletableFuture<?> handOver() {
        try {
            executor.execute(this);
            return null;
        } catch (Throwable t) {
            boolean settled = target.settle(StageOutcome.thrown(t));
            leaveSources();
            return settled ? target : null;
        }
    }

    /** Executor entry point. An executor that calls it again finds the target settled, as a rule. */
    @Override
    public void run() {
        boolean settled = target.outcome == null && settleTarget();
        // A compose left to its inner stage has not ended yet, as far as its sources are concerned.
        if (first != null && target.outcome != null) {
            ended = true;
            endOnSources();
        }
        if (settled) {
            target.fireLinks();
        }
    }

    /**
     * Leaves the settled sources, then visits the other source and the receiver in turn. What a
     * settled other source fires still finds an {@code either} step on a pending receiver.
     */
    private void endOnSources() {
        if (either) {
            synchronized (CompletableFuture.LOCK) {
                leaveIfSettled(first);
                leaveIfSettled(second);
            }
        }
        if (second != null) {
            endOn(second);
        }
        endOn(first);
    }

    private void leaveIfSettled(CompletableFuture<?> source) {
        if (source.outcome != null) {
            leave(source);
        }
    }

    private void endOn(CompletableFuture<?> source) {
        if (source.outcome != null) {
            source.fireLinks();
        } else if (either) {
            synchronized (CompletableFuture.LOCK) {
                leave(source);
            }
        }
    }

    /** A step found with its target settled: only the {@code either} steps leave. */
    private void leaveOtherSource() {
        if (either) {
            leaveSources();
        }
    }

    private void leaveSources() {
        if (second != null) {
            synchronized (CompletableFuture.LOCK) {
                leave(first);
                leave(second);
            }
        }
    }

    private void leave(CompletableFuture<?> source) {
        if (!removeFrom(source) && otherSide != null) {
            otherSide.removeFrom(source);
        }
    }

    private boolean settleTarget() {
        StageOutcome o = compute();
        return o != null && target.settle(o);
    }

    /** A failed source of a step that would drop its function; with {@code either} the task finds out. */
    private boolean skipsExecutor() {
        return !either && dropsFunction() && failedInput() != null;
    }

    private boolean dropsFunction() {
        return action != WHEN && action != HANDLE && action != RECOVER;
    }

    private StageOutcome input() {
        return !either || first.outcome != null ? first.outcome : second.outcome;
    }

    private StageOutcome failedInput() {
        StageOutcome x = input();
        if (x.failure != null) {
            return x;
        }
        return !either && second != null && second.outcome.failure != null ? second.outcome : null;
    }

    /** The target's outcome, or null when it is left to a compose's inner stage. */
    private StageOutcome compute() {
        try {
            if (first == null) {
                return StageOutcome.of(start());
            }
            if (dropsFunction()) {
                StageOutcome failed = failedInput();
                if (failed != null) {
                    return failed.inherited();
                }
            }
            return dispatch(input());
        } catch (Throwable t) {
            return StageOutcome.thrown(t);
        }
    }

    private Object start() {
        if (action == SUPPLY) {
            return ((Supplier<?>) fn).get();
        }
        ((Runnable) fn).run();
        return null;
    }

    @SuppressWarnings("unchecked")
    private StageOutcome dispatch(StageOutcome x) {
        switch (action) {
            case APPLY:
                return StageOutcome.of(((Function<Object, Object>) fn).apply(x.value));
            case ACCEPT:
                ((Consumer<Object>) fn).accept(x.value);
                return StageOutcome.NULL;
            case RUN:
                ((Runnable) fn).run();
                return StageOutcome.NULL;
            case COMBINE:
                return StageOutcome.of(((BiFunction<Object, Object, Object>) fn).apply(x.value, second.outcome.value));
            case ACCEPT_BOTH:
                ((BiConsumer<Object, Object>) fn).accept(x.value, second.outcome.value);
                return StageOutcome.NULL;
            case COMPOSE:
                return compose(((Function<Object, CompletionStage<?>>) fn).apply(x.value));
            case WHEN:
                return when(x);
            case HANDLE:
                return StageOutcome.of(((BiFunction<Object, Object, Object>) fn).apply(x.value, x.failure));
            case RECOVER:
                return x.failure == null ? x : StageOutcome.of(((Function<Object, Object>) fn).apply(x.failure));
            case ALL:
                return StageOutcome.NULL;
            default:
                return x;
        }
    }

    @SuppressWarnings("unchecked")
    private StageOutcome when(StageOutcome x) {
        try {
            ((BiConsumer<Object, Object>) fn).accept(x.value, x.failure);
        } catch (Throwable t) {
            if (x.failure == null) {
                return StageOutcome.thrown(t);
            }
        }
        return x.inherited();
    }

    private StageOutcome compose(CompletionStage<?> stage) {
        CompletableFuture<?> inner = stage == null ? null : stage.toCompletableFuture();
        if (inner == null) {
            throw new NullPointerException();
        }
        synchronized (CompletableFuture.LOCK) {
            if (inner.outcome != null) {
                return inner.outcome.inherited();
            }
            new StageStep(PASS, null, null, inner, null, false, target).addTo(inner);
            return null;
        }
    }

    private final class OtherSide extends StageLink {
        @Override
        CompletableFuture<?> fire() {
            return StageStep.this.fire();
        }
    }
}
