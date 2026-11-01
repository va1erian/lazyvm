// NEEDS: net
package lazy.net;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.CompletableFuture;
import lazy.async.Loop;

/** The timeout pattern of docs/async.md: a timer that cancels a read, cancelled when it ends. */
public class ReadTimeout {
    static CompletableFuture<byte[]> withTimeout(Connection c, long ms) {
        CompletableFuture<byte[]> read = c.read().toCompletableFuture();
        CompletableFuture<Void> timer = Loop.delay(ms, MILLISECONDS).toCompletableFuture();
        timer.thenRun(() -> read.cancel(true));
        read.whenComplete((bytes, error) -> timer.cancel(true));
        return read;
    }

    public static void main(String[] args) {
        Listener silent = Tcp.listen("127.0.0.1", 0, peer -> { }).toCompletableFuture().join();
        Connection c = Tcp.connect("127.0.0.1", silent.port()).toCompletableFuture().join();
        CompletableFuture<byte[]> read = withTimeout(c, 100);
        read.handle((bytes, error) -> null).join();
        System.out.println("timed out " + read.isCancelled());

        Listener talker = Tcp.listen("127.0.0.1", 0, peer -> peer.write(new byte[3]))
                .toCompletableFuture().join();
        Connection d = Tcp.connect("127.0.0.1", talker.port()).toCompletableFuture().join();
        // An hour's timer must not hold the VM once the read has ended.
        System.out.println("read " + withTimeout(d, 3_600_000).join().length);
        d.close();
        silent.close();
        talker.close();
    }
}
