// NEEDS: not-lazyos
// TIMEOUT: 120
package lazy.net;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 500 clients at once, each echoing a message of its own, on one loop. LazyOS has 64 sockets. */
public class ManyClients {
    static final int CLIENTS = 500;

    static void serve(Connection c) {
        c.read().thenCompose(bytes -> bytes.length == 0
                ? c.close()
                : c.write(bytes).thenRun(() -> serve(c)))
         .exceptionally(error -> { c.close(); return null; });
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
    }
}
