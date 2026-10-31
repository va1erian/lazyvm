package java.util.concurrent;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lazy.internal.Turnstile;

/**
 * A result that arrives once, with a list of what waits for it. The logic is in {@link StageStep}
 * and {@link StageReader}; the observable rules, firing order included, in
 * docs/design/concurrent.md.
 *
 * <p>The five public completion methods never call one another or anything else a subclass can
 * override: {@code lazy.internal.LoopFuture} overrides them (docs/async.md), and only the private
 * and package-private helpers below settle a stage.
 */
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {
    public static interface AsynchronousCompletionTask {
    }

    /**
     * Guards every stage's list and every first settlement. One lock for all makes attaching to two
     * sources atomic with no lock order; only one Java thread runs at a time (D5), so little
     * is lost by sharing it, and nothing is ever called out under it.
     */
    static final Object LOCK = new Object();

    volatile StageOutcome outcome;
    /** The list of what waits: see {@link StageLink}. Read and changed under {@link #LOCK}. */
    StageLink newestLink;
    int linkCount;

    public CompletableFuture() {
    }

    /** First settlement only; the caller then fires the list. */
    final boolean settle(StageOutcome o) {
        synchronized (LOCK) {
            if (outcome != null) {
                return false;
            }
            outcome = o;
            return true;
        }
    }

    /**
     * Fires what the list still holds, last attached first, leaving the rest counted. Any
     * completion call comes here, won or not, and so does a released reader, so a thread blocked
     * in a callback does not hold up the others ("reader last").
     *
     * <p>What a firing settles has its list moved here, in front, first attached on top. No frame
     * per stage, and everything still to fire stays where any visitor of this stage finds it.
     */
    final void fireLinks() {
        for (;;) {
            StageLink last;
            synchronized (LOCK) {
                last = newestLink;
                if (last == null) {
                    return;
                }
                last.removeFrom(this);
            }
            try {
                CompletableFuture<?> settled = last.fire();
                if (settled != null && settled != this) {
                    settled.moveLinksOnto(this);
                }
            } catch (StackOverflowError e) {
                synchronized (LOCK) {
                    last.addTo(this);
                }
                throw e;
            }
        }
    }

    private void moveLinksOnto(CompletableFuture<?> root) {
        synchronized (LOCK) {
            for (StageLink link = newestLink; link != null; link = newestLink) {
                link.removeFrom(this);
                link.addTo(root);
            }
        }
    }

    private boolean completeWith(StageOutcome o) {
        boolean won = settle(o);
        fireLinks();
        return won;
    }

    private void obtrudeWith(StageOutcome o) {
        // Under the lock: an attach that saw no outcome must have its entry on the list before this.
        synchronized (LOCK) {
            outcome = o;
        }
        fireLinks();
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return started(StageStep.SUPPLY, supplier, StageStep.defaultExecutor());
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
        return started(StageStep.SUPPLY, supplier, StageStep.namedExecutor(executor));
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return started(StageStep.RUN, runnable, StageStep.defaultExecutor());
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return started(StageStep.RUN, runnable, StageStep.namedExecutor(executor));
    }

    private static <U> CompletableFuture<U> started(int action, Object fn, Executor executor) {
        if (fn == null) {
            throw new NullPointerException();
        }
        CompletableFuture<U> d = new CompletableFuture<>();
        executor.execute(new StageStep(action, fn, null, null, null, false, d));
        return d;
    }

    public static <U> CompletableFuture<U> completedFuture(U value) {
        CompletableFuture<U> d = new CompletableFuture<>();
        d.outcome = StageOutcome.of(value);
        return d;
    }

    public boolean isDone() {
        return outcome != null;
    }

    public T get() throws InterruptedException, ExecutionException {
        StageOutcome o = outcome;
        return reported(o != null ? o : blockingGet(false, 0L));
    }

    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        StageOutcome o = outcome;
        if (o != null) {
            return reported(o);
        }
        o = blockingGet(true, nanos);
        if (o == null) {
            throw new TimeoutException();
        }
        return reported(o);
    }

    /** A pending stage: the flag is looked at even with no time left (docs/design/concurrent.md G5). */
    private StageOutcome blockingGet(boolean timed, long nanos) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        StageReader reader = new StageReader(true, timed, timed ? Turnstile.deadline(nanos) : 0L);
        StageOutcome o = await(reader);
        if (o == null && reader.interrupted) {
            throw new InterruptedException();
        }
        return o;
    }

    public T join() {
        StageOutcome o = outcome;
        if (o == null) {
            o = await(new StageReader(false, false, 0L));
        }
        return joined(o);
    }

    public T getNow(T valueIfAbsent) {
        StageOutcome o = outcome;
        return o == null ? valueIfAbsent : joined(o);
    }

    /** Waits on the list. The outcome, or null if the reader gave up with the stage still pending. */
    private StageOutcome await(StageReader reader) {
        synchronized (LOCK) {
            if (outcome != null) {
                return outcome;
            }
            reader.addTo(this);
        }
        try {
            ForkJoinPool.managedBlock(reader);
        } catch (InterruptedException e) {
            reader.interrupted = true;
        }
        StageOutcome o = outcome;
        if (o == null) {
            synchronized (LOCK) {
                reader.removeFrom(this);
            }
            return null;
        }
        if (reader.interrupted) {
            Thread.currentThread().interrupt();
        }
        fireLinks();
        return o;
    }

    @SuppressWarnings("unchecked")
    private T joined(StageOutcome o) {
        Throwable x = o.failure;
        if (x == null) {
            return (T) o.value;
        }
        if (x instanceof CancellationException) {
            throw (CancellationException) x;
        }
        if (x instanceof CompletionException) {
            throw (CompletionException) x;
        }
        throw new CompletionException(x);
    }

    @SuppressWarnings("unchecked")
    private T reported(StageOutcome o) throws ExecutionException {
        Throwable x = o.failure;
        if (x == null) {
            return (T) o.value;
        }
        if (x instanceof CancellationException) {
            throw (CancellationException) x;
        }
        Throwable cause = x instanceof CompletionException ? x.getCause() : null;
        throw new ExecutionException(cause != null ? cause : x);
    }

    public boolean complete(T value) {
        return completeWith(StageOutcome.of(value));
    }

    public boolean completeExceptionally(Throwable ex) {
        if (ex == null) {
            throw new NullPointerException();
        }
        return completeWith(StageOutcome.failed(ex));
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean won = settle(StageOutcome.failed(new CancellationException()));
        fireLinks();
        StageOutcome o = outcome;
        return won || o.cancelled();
    }

    public void obtrudeValue(T value) {
        obtrudeWith(StageOutcome.of(value));
    }

    public void obtrudeException(Throwable ex) {
        if (ex == null) {
            throw new NullPointerException();
        }
        obtrudeWith(StageOutcome.failed(ex));
    }

    public boolean isCancelled() {
        StageOutcome o = outcome;
        return o != null && o.cancelled();
    }

    public boolean isCompletedExceptionally() {
        StageOutcome o = outcome;
        return o != null && o.failure != null;
    }

    public int getNumberOfDependents() {
        synchronized (LOCK) {
            return linkCount;
        }
    }

    @Override
    public String toString() {
        StageOutcome o = outcome;
        if (o != null) {
            return super.toString() + (o.failure != null ? "[Completed exceptionally]" : "[Completed normally]");
        }
        int n = getNumberOfDependents();
        return super.toString() + (n == 0 ? "[Not completed]" : "[Not completed, " + n + " dependents]");
    }

    public CompletableFuture<T> toCompletableFuture() {
        return this;
    }

    private <U> CompletableFuture<U> after(int action, Object fn, Executor executor) {
        if (fn == null) {
            throw new NullPointerException();
        }
        CompletableFuture<U> d = new CompletableFuture<>();
        new StageStep(action, fn, executor, this, null, false, d).attach();
        return d;
    }

    private <U> CompletableFuture<U> afterTwo(int action, CompletionStage<?> other, boolean either, Object fn,
            Executor executor) {
        if (fn == null || other == null) {
            throw new NullPointerException();
        }
        CompletableFuture<?> second = other.toCompletableFuture();
        if (second == null) {
            throw new NullPointerException();
        }
        CompletableFuture<U> d = new CompletableFuture<>();
        new StageStep(action, fn, executor, this, second, either, d).attach();
        return d;
    }

    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return after(StageStep.APPLY, fn, null);
    }

    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return after(StageStep.APPLY, fn, StageStep.defaultExecutor());
    }

    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return after(StageStep.APPLY, fn, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return after(StageStep.ACCEPT, action, null);
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return after(StageStep.ACCEPT, action, StageStep.defaultExecutor());
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return after(StageStep.ACCEPT, action, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<Void> thenRun(Runnable action) {
        return after(StageStep.RUN, action, null);
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return after(StageStep.RUN, action, StageStep.defaultExecutor());
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return after(StageStep.RUN, action, StageStep.namedExecutor(executor));
    }

    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return afterTwo(StageStep.COMBINE, other, false, fn, null);
    }

    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return afterTwo(StageStep.COMBINE, other, false, fn, StageStep.defaultExecutor());
    }

    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        return afterTwo(StageStep.COMBINE, other, false, fn, StageStep.namedExecutor(executor));
    }

    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return afterTwo(StageStep.ACCEPT_BOTH, other, false, action, null);
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return afterTwo(StageStep.ACCEPT_BOTH, other, false, action, StageStep.defaultExecutor());
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action, Executor executor) {
        return afterTwo(StageStep.ACCEPT_BOTH, other, false, action, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return afterTwo(StageStep.RUN, other, false, action, null);
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return afterTwo(StageStep.RUN, other, false, action, StageStep.defaultExecutor());
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return afterTwo(StageStep.RUN, other, false, action, StageStep.namedExecutor(executor));
    }

    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return afterTwo(StageStep.APPLY, other, true, fn, null);
    }

    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return afterTwo(StageStep.APPLY, other, true, fn, StageStep.defaultExecutor());
    }

    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn,
            Executor executor) {
        return afterTwo(StageStep.APPLY, other, true, fn, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return afterTwo(StageStep.ACCEPT, other, true, action, null);
    }

    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return afterTwo(StageStep.ACCEPT, other, true, action, StageStep.defaultExecutor());
    }

    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action,
            Executor executor) {
        return afterTwo(StageStep.ACCEPT, other, true, action, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return afterTwo(StageStep.RUN, other, true, action, null);
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return afterTwo(StageStep.RUN, other, true, action, StageStep.defaultExecutor());
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return afterTwo(StageStep.RUN, other, true, action, StageStep.namedExecutor(executor));
    }

    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return after(StageStep.COMPOSE, fn, null);
    }

    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return after(StageStep.COMPOSE, fn, StageStep.defaultExecutor());
    }

    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        return after(StageStep.COMPOSE, fn, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return after(StageStep.WHEN, action, null);
    }

    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return after(StageStep.WHEN, action, StageStep.defaultExecutor());
    }

    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return after(StageStep.WHEN, action, StageStep.namedExecutor(executor));
    }

    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return after(StageStep.HANDLE, fn, null);
    }

    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return after(StageStep.HANDLE, fn, StageStep.defaultExecutor());
    }

    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return after(StageStep.HANDLE, fn, StageStep.namedExecutor(executor));
    }

    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return after(StageStep.RECOVER, fn, null);
    }

    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        return cfs.length == 0 ? completedFuture(null) : StageTree.build(cfs, false);
    }

    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        return cfs.length == 0 ? new CompletableFuture<>() : StageTree.build(cfs, true);
    }
}
