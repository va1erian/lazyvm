// NEEDS: not-lazyos
// TIMEOUT: 120
package lazy.net;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 500 clients at once, each echoing a message of its own, on one loop. LazyOS has 64 sockets.
 *
 * <p>So many connects at once can overflow the kernel's listen queues (Linux under load: SYN
 * retransmits and cookies). It may then complete a second, stale handshake for a client that has
 * already come and gone, and hand the listener a connection nobody will ever write to or close.
 * Its read would keep the loop, and the program, alive for good; the server drops what is still
 * open once every client is done.
 */
public class ManyClients {
    static final int CLIENTS = 500;
    static final Set<Connection> OPEN = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static void serve(Connection c) {
        OPEN.add(c);
        next(c);
    }

    static void next(Connection c) {
        c.read().thenCompose(bytes -> bytes.length == 0
                ? c.close().thenRun(() -> OPEN.remove(c))
                : c.write(bytes).thenRun(() -> next(c)))
         .exceptionally(error -> { c.close(); OPEN.remove(c); return null; });
    }

    static CompletionStage<Integer> client(int port, int i) {
        byte[] message = ("client " + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Tcp.connect("127.0.0.1", port).thenCompose(c ->
                c.write(message)
                 .thenCompose(v -> c.read())
                 .thenCompose(back -> c.close().thenApply(v -> java.util.Arrays.equals(back, message) ? 1 : 0)));
    }

    public static void main(String[] args) {
        Listener listener = Tcp.listen("127.0.0.1", 0, ManyClients::serve).toCompletableFuture().join();
        @SuppressWarnings("unchecked")
        CompletableFuture<Integer>[] all = new CompletableFuture[CLIENTS];
        for (int i = 0; i < CLIENTS; i++) all[i] = client(listener.port(), i).toCompletableFuture();
        CompletableFuture.allOf(all).join();
        int right = 0;
        for (CompletableFuture<Integer> c : all) right += c.join();
        System.out.println(right + " of " + CLIENTS + " echoed");
        listener.close();
        // A cancelled close closes the socket as it stands.
        for (Connection c : OPEN) c.close().toCompletableFuture().cancel(false);
    }
}
