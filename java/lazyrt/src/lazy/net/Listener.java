package lazy.net;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import lazy.async.Loop;
import lazy.internal.Reactor;

/** A listening socket (docs/async.md). It keeps the VM running until it is closed. */
public final class Listener {
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
        return first.thenRunAsync(() -> { }, Loop.executor());
    }

    private synchronized boolean closed() {
        return closing != null;
    }

    /** One accept at a time: on the loop's thread, nothing can complete before it is attached. */
    void acceptNext() {
        Reactor.accept(handle, Tcp::connection).whenComplete((connection, failure) -> {
            if (failure != null) {
                if (!closed()) Reactor.report(failure);
                return;
            }
            if (closed()) {
                connection.close();
                return;
            }
            serve(connection);
            acceptNext();
        });
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
