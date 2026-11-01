// NEEDS: net
// TIMEOUT: 120
package lazy.net;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Writes may overlap, and go out in call order, each with what its array held in the call. It is
 * enough to fill any system's buffers, so most of them wait in the queue. And close tells the peer
 * promptly: it does not sit out its 10 s limit.
 */
public class WriteOrder {
    static final int CHUNK = 1024 * 1024;
    static final int CHUNKS = 128;

    static long received, wrong;

    static CompletionStage<Long> drain(Connection c) {
        return c.read().thenCompose(bytes -> {
            if (bytes.length == 0) return CompletableFuture.completedFuture(received);
            if (bytes[0] != (byte) (received / CHUNK)) wrong++;
            received += bytes.length;
            if (bytes[bytes.length - 1] != (byte) ((received - 1) / CHUNK)) wrong++;
            return drain(c);
        });
    }

    public static void main(String[] args) {
        CompletableFuture<Long> total = new CompletableFuture<>();
        Listener listener = Tcp.listen("127.0.0.1", 0, peer ->
                drain(peer).thenCompose(n -> peer.close().thenApply(v -> n)).thenAccept(total::complete))
                .toCompletableFuture().join();
        Connection c = Tcp.connect("127.0.0.1", listener.port()).toCompletableFuture().join();

        byte[] chunk = new byte[CHUNK];
        CompletableFuture<?>[] writes = new CompletableFuture<?>[CHUNKS];
        for (int i = 0; i < CHUNKS; i++) {
            // The same array each time: a write that did not copy it would send the last fill.
            Arrays.fill(chunk, (byte) i);
            writes[i] = c.write(chunk).toCompletableFuture();
        }
        CompletableFuture.allOf(writes).join();

        long start = System.nanoTime();
        c.close().toCompletableFuture().join();
        System.out.println("close is prompt " + (System.nanoTime() - start < 5_000_000_000L));
        System.out.println("received all " + (total.join() == (long) CHUNK * CHUNKS)
                + ", out of place " + wrong);
        listener.close();
    }
}
