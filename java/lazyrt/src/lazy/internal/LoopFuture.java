package lazy.internal;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * The stage of one operation of the event loop. A completion from outside withdraws the
 * operation first, so whoever removes its id from {@link Reactor}'s table settles it, exactly
 * once (docs/async.md, "Inside"). The dispatcher completes it through {@link #settle}.
 */
final class LoopFuture<T> extends CompletableFuture<T> {
    private final long id;
    private final Reactor.Result<T> result;
    private final Runnable beforeEnd;

    /** {@code beforeEnd} runs before the stage ends, however it ends, so handlers see its effect. */
    LoopFuture(long id, Reactor.Result<T> result, Runnable beforeEnd) {
        this.id = id;
        this.result = result;
        this.beforeEnd = beforeEnd;
    }

    long id() {
        return id;
    }

    /** Ends the stage with what the reactor made: a String in {@code payload} names a failure. */
    void settle(long handle, Object payload) {
        ending();
        if (payload instanceof String) {
            fail(new IOException((String) payload));
            return;
        }
        T value;
        try {
            value = result.from(handle, (byte[]) payload);
        } catch (Throwable t) {
            fail(t);
            return;
        }
        super.complete(value);
    }

    void fail(Throwable cause) {
        ending();
        super.completeExceptionally(cause);
    }

    private void ending() {
        if (beforeEnd != null) beforeEnd.run();
    }

    /** From outside: takes the operation away from the reactor; true if this call did. */
    private boolean withdrawn() {
        if (!Reactor.withdraw(id)) return false;
        ending();
        return true;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) return super.cancel(mayInterruptIfRunning);
        if (!withdrawn()) return false;
        try {
            return super.cancel(mayInterruptIfRunning);
        } finally {
            Reactor.finished();
        }
    }

    @Override
    public boolean complete(T value) {
        if (isDone() || !withdrawn()) return false;
        try {
            return super.complete(value);
        } finally {
            Reactor.finished();
        }
    }

    @Override
    public boolean completeExceptionally(Throwable cause) {
        if (cause == null) throw new NullPointerException();
        if (isDone() || !withdrawn()) return false;
        try {
            return super.completeExceptionally(cause);
        } finally {
            Reactor.finished();
        }
    }

    @Override
    public void obtrudeValue(T value) {
        boolean won = withdrawn();
        try {
            super.obtrudeValue(value);
        } finally {
            if (won) Reactor.finished();
        }
    }

    @Override
    public void obtrudeException(Throwable cause) {
        if (cause == null) throw new NullPointerException();
        boolean won = withdrawn();
        try {
            super.obtrudeException(cause);
        } finally {
            if (won) Reactor.finished();
        }
    }
}
