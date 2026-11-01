package lazy.internal;

import java.util.concurrent.CompletableFuture;

/**
 * The stage of one operation of the event loop. A completion from outside withdraws the
 * operation first, so whoever removes its id from {@link Reactor}'s table settles it, exactly
 * once (docs/async.md, "Inside"). The dispatcher completes it through {@link #settle}.
 */
final class LoopFuture<T> extends CompletableFuture<T> {
    private final long id;

    LoopFuture(long id) {
        this.id = id;
    }

    long id() {
        return id;
    }

    void settle(T value) {
        super.complete(value);
    }

    void fail(Throwable cause) {
        super.completeExceptionally(cause);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) return super.cancel(mayInterruptIfRunning);
        if (!Reactor.withdraw(id)) return false;
        try {
            return super.cancel(mayInterruptIfRunning);
        } finally {
            Reactor.finished();
        }
    }

    @Override
    public boolean complete(T value) {
        if (isDone() || !Reactor.withdraw(id)) return false;
        try {
            return super.complete(value);
        } finally {
            Reactor.finished();
        }
    }

    @Override
    public boolean completeExceptionally(Throwable cause) {
        if (cause == null) throw new NullPointerException();
        if (isDone() || !Reactor.withdraw(id)) return false;
        try {
            return super.completeExceptionally(cause);
        } finally {
            Reactor.finished();
        }
    }

    @Override
    public void obtrudeValue(T value) {
        boolean won = Reactor.withdraw(id);
        try {
            super.obtrudeValue(value);
        } finally {
            if (won) Reactor.finished();
        }
    }

    @Override
    public void obtrudeException(Throwable cause) {
        if (cause == null) throw new NullPointerException();
        boolean won = Reactor.withdraw(id);
        try {
            super.obtrudeException(cause);
        } finally {
            if (won) Reactor.finished();
        }
    }
}
