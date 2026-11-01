// NEEDS: net
// VMARGS: -Xmx32m
// TIMEOUT: 120
package lazy.net;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nothing is read until the program asks, so a peer writing 8 MiB to a reader that waits has to
 * wait too, and memory stays bounded (the heap here is smaller than what the writes would queue if
 * nothing pushed back, were they not waited for). Then every byte arrives, in order.
 */
public class Backpressure {
    static final long TOTAL = 8L * 1024 * 1024;

    static CompletionStage<Void> send(Connection c, AtomicLong sent, byte[] chunk) {
        if (sent.get() >= TOTAL) return c.close();
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (sent.get() + i);
        return c.write(chunk).thenCompose(v -> {
            sent.addAndGet(chunk.length);
            return send(c, sent, chunk);
        });
    }

    static long received, wrong;

    static CompletionStage<Long> drain(Connection c) {
        return c.read().thenCompose(bytes -> {
            if (bytes.length == 0) return CompletableFuture.completedFuture(received);
            for (byte b : bytes) {
                if (b != (byte) received++) wrong++;
            }
            return drain(c);
        });
    }

    public static void main(String[] args) throws Exception {
        AtomicLong sent = new AtomicLong();
        Listener listener = Tcp.listen("127.0.0.1", 0, c -> send(c, sent, new byte[64 * 1024]))
                .toCompletableFuture().join();
        Connection c = Tcp.connect("127.0.0.1", listener.port()).toCompletableFuture().join();

        Thread.sleep(1000);
        long early = sent.get();
        Thread.sleep(500);
        System.out.println("the writer waits " + (early < TOTAL && sent.get() == early));

        long total = drain(c).toCompletableFuture().join();
        System.out.println("received all " + (total == TOTAL) + ", out of place " + wrong);
        c.close();
        listener.close();
    }
}
