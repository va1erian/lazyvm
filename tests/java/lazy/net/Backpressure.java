// NEEDS: net
// VMARGS: -Xmx32m
// TIMEOUT: 120
package lazy.net;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nothing is read until the program asks, so a peer writing 256 MiB, far more than any system's
 * socket buffers take, to a reader that waits has to wait too, and it fits an 8th of that in heap.
 * Then it all arrives, in order.
 */
public class Backpressure {
    static final long TOTAL = 256L * 1024 * 1024;

    static CompletionStage<Void> send(Connection c, AtomicLong sent, byte[] chunk) {
        if (sent.get() >= TOTAL) return c.close();
        return c.write(chunk).thenCompose(v -> {
            sent.addAndGet(chunk.length);
            return send(c, sent, chunk);
        });
    }

    static long received, wrong;

    static CompletionStage<Long> drain(Connection c) {
        return c.read().thenCompose(bytes -> {
            if (bytes.length == 0) return CompletableFuture.completedFuture(received);
            // Each byte holds its offset mod 256; the ends of each read say enough.
            if (bytes[0] != (byte) received) wrong++;
            received += bytes.length;
            if (bytes[bytes.length - 1] != (byte) (received - 1)) wrong++;
            return drain(c);
        });
    }

    public static void main(String[] args) throws Exception {
        AtomicLong sent = new AtomicLong();
        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) i;
        Listener listener = Tcp.listen("127.0.0.1", 0, c -> send(c, sent, chunk))
                .toCompletableFuture().join();
        Connection c = Tcp.connect("127.0.0.1", listener.port()).toCompletableFuture().join();

        // Stalled means unchanged for 2 s, however long the buffers take to fill.
        long seen = -1, steadySince = System.nanoTime(), end = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() - steadySince < 2_000_000_000L && System.nanoTime() < end) {
            Thread.sleep(50);
            long now = sent.get();
            if (now != seen) {
                seen = now;
                steadySince = System.nanoTime();
            }
        }
        System.out.println("the writer waits " + (sent.get() < TOTAL));

        long total = drain(c).toCompletableFuture().join();
        System.out.println("received all " + (total == TOTAL) + ", out of place " + wrong);
        c.close();
        listener.close();
    }
}
