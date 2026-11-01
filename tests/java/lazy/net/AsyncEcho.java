// NEEDS: net
package lazy.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.CompletionStage;

/**
 * A server and client in one process: echo, each next read started inside the last one's handler,
 * the end of the stream, and both ends closing. Also "localhost", which binds both families.
 */
public class AsyncEcho {
    static void serve(Connection c) {
        c.read().thenCompose(bytes -> bytes.length == 0
                ? c.close()
                : c.write(bytes).thenRun(() -> serve(c)))
         .exceptionally(error -> { c.close(); return null; });
    }

    static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }

    static CompletionStage<String> exchange(Connection c, String s) {
        return c.write(bytes(s)).thenCompose(v -> c.read()).thenApply(b -> new String(b, UTF_8));
    }

    static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    static void round(String host) {
        Listener listener = join(Tcp.listen(host, 0, AsyncEcho::serve));
        System.out.println("port chosen " + (listener.port() > 0));
        Connection c = join(Tcp.connect(host, listener.port()));
        System.out.println(join(exchange(c, "one")));
        System.out.println(join(exchange(c, "two")));
        join(c.close());
        System.out.println("closed");
        join(c.close());
        System.out.println("closed again");
        join(listener.close());
        join(listener.close());
    }

    public static void main(String[] args) {
        Listener listener = join(Tcp.listen("127.0.0.1", 0, AsyncEcho::serve));
        Connection c = join(Tcp.connect("127.0.0.1", listener.port()));
        System.out.println("peer " + c.peer().equals("127.0.0.1:" + listener.port()));

        // The handler starts the next read as soon as a read has ended.
        String ping = join(c.write(bytes("ping"))
                .thenCompose(v -> c.read())
                .thenCompose(first -> c.write(bytes("pong"))
                        .thenCompose(v -> c.read())
                        .thenApply(second -> new String(first, UTF_8) + new String(second, UTF_8))));
        System.out.println(ping);

        CompletionStage<byte[]> pending = c.read();
        try {
            c.read();
            System.out.println("no exception");
        } catch (IllegalStateException e) {
            System.out.println("second read " + e.getMessage());
        }
        try {
            c.readAll();
            System.out.println("no exception");
        } catch (IllegalStateException e) {
            System.out.println("readAll beside it " + e.getMessage());
        }
        System.out.println("close ends the read with " + join(c.close().thenCompose(v -> pending)).length);
        join(listener.close());

        round("127.0.0.1");
        round("localhost");

        // A peer that closes: the end of the stream, every time.
        Listener closer = join(Tcp.listen("127.0.0.1", 0, peer ->
                peer.write(bytes("bye")).thenCompose(v -> peer.close())));
        Connection d = join(Tcp.connect("127.0.0.1", closer.port()));
        System.out.println(new String(join(d.readAll()), UTF_8));
        System.out.println(join(d.read()).length + " " + join(d.read()).length);
        join(d.close());
        join(closer.close());
        System.out.println("done");
    }
}
