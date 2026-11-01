package lazy.net;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lazy.async.Loop;
import lazy.internal.Reactor;

/** A listening socket (docs/async.md). It keeps the VM running until it is closed. */
public final class Listener {
    private static final long RETRY_MILLIS = 100;

    private final long handle;
    private final int port;
    private final Consumer<Connection> onAccept;
    private CompletionStage<Void> closing;

    Listener(long handle, int port, Consumer<Connection> onAccept) {
        this.handle = handle;
        this.port = port;
        this.onAccept = onAccept;
    }

    /** The bound port, useful after listening on port 0. */
    public int port() {
        return port;
    }

    /** Stops accepting. Connections already handed to the handler stay open. */
    public CompletionStage<Void> close() {
        CompletionStage<Void> first;
        synchronized (this) {
            first = closing;
            if (first == null) {
                closing = Reactor.closeListener(handle);
                return closing;
            }
        }
        return first.handleAsync((v, failure) -> (Void) null, Loop.executor());
    }

    private synchronized boolean closed() {
        return closing != null;
    }

    /** One accept at a time: on the loop's thread, nothing can complete before it is attached. */
    void acceptNext() {
        try {
            Reactor.accept(handle, Tcp::connection).whenComplete(this::accepted);
        } catch (Throwable t) {
            retry(t);
        }
    }

    private void accepted(Connection connection, Throwable failure) {
        if (failure != null) {
            if (!closed()) retry(failure);
            return;
        }
        if (closed()) {
            connection.close();
            return;
        }
        serve(connection);
        acceptNext();
    }

    /** It stays open until it is closed, so whatever went wrong it goes on listening. */
    private void retry(Throwable failure) {
        Reactor.report(failure);
        Loop.delay(RETRY_MILLIS, TimeUnit.MILLISECONDS).thenRun(this::acceptNext);
    }

    private void serve(Connection connection) {
        try {
            onAccept.accept(connection);
        } catch (Throwable t) {
            Reactor.report(t);
            connection.close();
        }
    }
}
