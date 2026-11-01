// NEEDS: lazyos-net
package lazy.net;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Inside LazyOS: a listener served to the host's client, through QEMU's port forward. The client
 * sends its bytes, half-closes and expects them back; this is the echo of the docs' example.
 */
public class HostInbound {
    static final int PORT = 47773;

    static void serve(Connection c, CompletableFuture<Void> served) {
        c.read().thenCompose(bytes -> bytes.length == 0
                ? c.close().thenRun(() -> served.complete(null))
                : c.write(bytes).thenRun(() -> serve(c, served)))
         .exceptionally(error -> {
             served.completeExceptionally(error);
             c.close();
             return null;
         });
    }

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> served = new CompletableFuture<>();
        Listener listener = Tcp.listen("0.0.0.0", PORT, c -> serve(c, served))
                .toCompletableFuture().join();
        System.out.println("listening on " + listener.port());
        served.get(60, TimeUnit.SECONDS);
        System.out.println("served the host's client");
        listener.close();
    }
}
