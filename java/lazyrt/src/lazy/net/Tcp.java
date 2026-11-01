package lazy.net;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import lazy.internal.Reactor;

/**
 * The front door of async TCP (docs/async.md). Every stage completes on the loop's thread; a
 * failure is an {@code IOException} naming what failed and where.
 */
public final class Tcp {
    private Tcp() {}

    /**
     * Connects to {@code host}, a name or an IPv4 or IPv6 literal, trying each of its addresses in
     * the order the resolver gives them. Each attempt gives up after 30 s.
     */
    public static CompletionStage<Connection> connect(String host, int port) {
        if (host == null) throw new NullPointerException("host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port " + port);
        return Reactor.connect(host, port, Tcp::connection);
    }

    /**
     * Listens on every address of {@code host}, on one port: {@code "localhost"} for this machine
     * only, {@code "0.0.0.0"} for every IPv4 interface, port 0 for a free one. The stage completes
     * before the first {@code onAccept}, which then runs on the loop's thread for each connection,
     * in arrival order, until the listener is closed. An {@code onAccept} that throws is reported
     * to the uncaught exception handler and its connection closed; the listener goes on.
     */
    public static CompletionStage<Listener> listen(String host, int port, Consumer<Connection> onAccept) {
        if (host == null) throw new NullPointerException("host");
        if (onAccept == null) throw new NullPointerException("onAccept");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port " + port);
        return Reactor.listen(host, port, (handle, payload) -> {
            Listener listener = new Listener(handle, Integer.parseInt(text(payload)), onAccept);
            listener.acceptNext();
            return listener;
        });
    }

    static Connection connection(long handle, byte[] peer) {
        return new Connection(handle, text(peer));
    }

    private static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
