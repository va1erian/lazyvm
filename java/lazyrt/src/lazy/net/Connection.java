package lazy.net;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import lazy.async.Loop;
import lazy.internal.Reactor;

/**
 * One TCP stream (docs/async.md). Safe to use from any thread. Nothing is read until the program
 * calls {@link #read}, so a peer that sends faster than the program reads waits. Cancelling a
 * pending read or write closes the connection at once.
 */
public final class Connection {
    private final long handle;
    private final String peer;
    private boolean reading;
    private CompletionStage<Void> closing;

    Connection(long handle, String peer) {
        this.handle = handle;
        this.peer = peer;
    }

    /**
     * The next bytes that arrive, 1 to 64 KiB of them, or an empty array at the end of the stream,
     * every time once it has ended.
     *
     * @throws IllegalStateException if a read is pending
     */
    public CompletionStage<byte[]> read() {
        return reading(false);
    }

    /**
     * Everything up to the end of the stream. Past 16 MiB it closes the connection and fails;
     * bigger bodies are read in a loop with {@link #read}.
     *
     * @throws IllegalStateException if a read is pending
     */
    public CompletionStage<byte[]> readAll() {
        return reading(true);
    }

    private CompletionStage<byte[]> reading(boolean all) {
        synchronized (this) {
            if (closing != null) return closed();
            if (reading) throw new IllegalStateException("a read is pending");
            reading = true;
        }
        try {
            return Reactor.read(handle, all, this::readFinished);
        } catch (RuntimeException | Error t) {
            readFinished();
            throw t;
        }
    }

    private synchronized void readFinished() {
        reading = false;
    }

    /**
     * Completes when all the bytes are handed to the operating system. Writes may overlap; they go
     * out in call order. The array is copied in the call, so the caller may reuse it.
     */
    public CompletionStage<Void> write(byte[] bytes) {
        if (bytes == null) throw new NullPointerException("bytes");
        synchronized (this) {
            if (closing != null) return closed();
        }
        return Reactor.write(handle, bytes);
    }

    /**
     * No more reads or writes start. A pending read completes with the end of the stream, and a
     * pending {@link #readAll} fails, since its body is cut short. Queued writes are sent, then
     * what the peer still sends is discarded until it closes too. After 10 s, or at once if this
     * stage is cancelled, the socket is closed as it stands and writes not yet sent fail. A second
     * close returns a new stage that completes after the first.
     */
    public CompletionStage<Void> close() {
        CompletionStage<Void> first;
        synchronized (this) {
            first = closing;
            if (first == null) {
                closing = Reactor.close(handle);
                return closing;
            }
        }
        return first.thenRunAsync(() -> { }, Loop.executor());
    }

    /** The remote address as {@code host:port} with the numeric host. */
    public String peer() {
        return peer;
    }

    private static <T> CompletionStage<T> closed() {
        return Reactor.failed(new IOException("closed"));
    }
}
