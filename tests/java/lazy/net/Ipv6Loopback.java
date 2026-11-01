// NEEDS: windows
package lazy.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.CompletionStage;

/** IPv6 literals, in errors and in peer(). LazyOS has no AF_INET6 yet. */
public class Ipv6Loopback {
    static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    public static void main(String[] args) {
        Listener listener = join(Tcp.listen("::1", 0, peer ->
                peer.write("six".getBytes(UTF_8)).thenCompose(v -> peer.close())));
        int port = listener.port();
        Connection c = join(Tcp.connect("::1", port));
        System.out.println("peer " + c.peer().equals("[::1]:" + port));
        System.out.println(new String(join(c.readAll()), UTF_8));
        join(c.close());
        join(listener.close());
        try {
            join(Tcp.connect("::1", port));
        } catch (java.util.concurrent.CompletionException e) {
            System.out.println(e.getCause().getMessage().replace(":" + port, ":PORT"));
        }
    }
}
