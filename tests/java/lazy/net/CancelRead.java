// NEEDS: net
package lazy.net;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import lazy.async.Loop;
import lazy.internal.Reactor;

/**
 * A cancelled read aborts its connection and the peer sees it end; a cancelled write and a
 * cancelled connect do what they say; the VM exits with nothing pending.
 */
public class CancelRead {
    static String outcome(CompletableFuture<?> stage) {
        try {
            stage.join();
            return "completed";
        } catch (CompletionException e) {
            return e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
        }
    }

    static void pendingIs(int expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Reactor.pending() != expected) {
            if (System.nanoTime() > end) throw new AssertionError("pending " + Reactor.pending());
            Thread.sleep(1);
        }
    }

    public static void main(String[] args) throws Exception {
        CompletableFuture<byte[]> peerSaw = new CompletableFuture<>();
        Listener listener = Tcp.listen("127.0.0.1", 0, peer ->
                peer.readAll().whenComplete((bytes, error) -> {
                    peerSaw.complete(new byte[0]);
                    peer.close();
                })).toCompletableFuture().join();

        Connection c = Tcp.connect("127.0.0.1", listener.port()).toCompletableFuture().join();
        CompletableFuture<byte[]> read = c.read().toCompletableFuture();
        System.out.println("cancel " + read.cancel(true) + " " + read.isCancelled());
        System.out.println("peer saw the end " + (peerSaw.get(30, TimeUnit.SECONDS).length == 0));
        System.out.println("read after " + outcome(c.read().toCompletableFuture()));
        System.out.println("write after " + outcome(c.write(new byte[1]).toCompletableFuture()));
        c.close().toCompletableFuture().join();
        System.out.println("read after close " + outcome(c.read().toCompletableFuture()));

        // With nobody reading, the socket's buffers fill and a write stays in flight. How much
        // they take is the system's business: Windows took 16 MiB in one go.
        Listener silent = Tcp.listen("127.0.0.1", 0, peer -> { }).toCompletableFuture().join();
        Connection w = Tcp.connect("127.0.0.1", silent.port()).toCompletableFuture().join();
        byte[] chunk = new byte[1024 * 1024];
        CompletableFuture<Void> write;
        do {
            write = w.write(chunk).toCompletableFuture();
            Thread.sleep(50);
        } while (write.isDone());
        System.out.println("write pending " + !write.isDone());
        System.out.println("cancel " + write.cancel(true));
        System.out.println("write after " + outcome(w.write(new byte[1]).toCompletableFuture()));

        // On the loop, so the connect cannot finish before the cancel.
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        Loop.executor().execute(() ->
                cancelled.complete(Tcp.connect("127.0.0.1", silent.port()).toCompletableFuture().cancel(true)));
        System.out.println("cancel connect " + cancelled.join());

        // A cancel that loses the race: the connect has finished, its result is on its way, and
        // the loop is held until the cancel. The socket it made is released, and the peer sees it.
        CompletableFuture<byte[]> released = new CompletableFuture<>();
        Listener watcher = Tcp.listen("127.0.0.1", 0, peer ->
                peer.read().whenComplete((bytes, error) -> {
                    released.complete(bytes);
                    peer.close();
                })).toCompletableFuture().join();
        Loop.executor().execute(() -> {
            CompletableFuture<Connection> late = Tcp.connect("127.0.0.1", watcher.port()).toCompletableFuture();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            System.out.println("cancel a finished connect " + late.cancel(true));
        });
        System.out.println("its socket released " + (released.get(30, TimeUnit.SECONDS).length == 0));
        watcher.close().toCompletableFuture().join();

        listener.close();
        silent.close().toCompletableFuture().join();
        pendingIs(0);
        System.out.println("nothing pending");
    }
}
