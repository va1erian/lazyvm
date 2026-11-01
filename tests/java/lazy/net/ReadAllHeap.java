// NEEDS: net
// VMARGS: -Xmx8m
package lazy.net;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * A body within readAll's limit but past what the heap can ever hold fails its stage and closes
 * its connection. The loop goes on, and the VM exits: a remote peer cannot wedge a small program.
 */
public class ReadAllHeap {
    static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    public static void main(String[] args) {
        byte[] chunk = new byte[1024 * 1024];
        Listener big = join(Tcp.listen("127.0.0.1", 0, peer -> send(peer, chunk, 9)));
        Connection c = join(Tcp.connect("127.0.0.1", big.port()));
        try {
            join(c.readAll());
            System.out.println("no failure");
        } catch (CompletionException e) {
            System.out.println(e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
        }
        System.out.println("read after " + describe(c));
        join(big.close());

        Listener small = join(Tcp.listen("127.0.0.1", 0, peer ->
                peer.write(new byte[3]).thenCompose(v -> peer.close())));
        Connection d = join(Tcp.connect("127.0.0.1", small.port()));
        System.out.println("the loop goes on " + join(d.readAll()).length);
        join(d.close());
        join(small.close());
    }

    static String describe(Connection c) {
        try {
            return "read " + join(c.read()).length;
        } catch (CompletionException e) {
            return e.getCause().getMessage();
        }
    }

    static CompletionStage<Void> send(Connection peer, byte[] chunk, int left) {
        if (left == 0) return peer.close();
        return peer.write(chunk).thenCompose(v -> send(peer, chunk, left - 1));
    }
}
