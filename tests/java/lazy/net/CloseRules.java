// NEEDS: net
// TIMEOUT: 120
package lazy.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * What close promises: queued writes go out first, even when the peer's input was never read, a
 * cut-short readAll fails, readAll has a limit, and a handler that throws costs one connection.
 */
public class CloseRules {
    static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    static String failure(CompletionStage<?> stage) {
        try {
            join(stage);
            return "no failure";
        } catch (CompletionException e) {
            return e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
        }
    }

    public static void main(String[] args) {
        // The peer writes and closes at once with our bytes unread: the last ones still arrive.
        byte[] big = new byte[2 * 1024 * 1024];
        Listener writer = join(Tcp.listen("127.0.0.1", 0, peer -> {
            peer.write(big).thenCompose(v -> peer.close());
        }));
        Connection a = join(Tcp.connect("127.0.0.1", writer.port()));
        join(a.write("never read by anyone".getBytes(UTF_8)));
        System.out.println("all of it " + (join(a.readAll()).length == big.length));
        join(a.close());
        join(writer.close());

        Listener silent = join(Tcp.listen("127.0.0.1", 0, peer -> { }));
        Connection b = join(Tcp.connect("127.0.0.1", silent.port()));
        CompletionStage<byte[]> all = b.readAll();
        CompletionStage<Void> closing = b.close();
        System.out.println("readAll cut short " + failure(all));
        join(closing);
        System.out.println("write after close " + failure(b.write(new byte[1])));
        // A close cancelled to force it: a second one still just completes, later.
        Connection forced = join(Tcp.connect("127.0.0.1", silent.port()));
        forced.close().toCompletableFuture().cancel(true);
        System.out.println("second close " + failure(forced.close()));
        join(silent.close());

        Listener flood = join(Tcp.listen("127.0.0.1", 0, peer -> {
            peer.write(new byte[17 * 1024 * 1024]).thenCompose(v -> peer.close());
        }));
        Connection d = join(Tcp.connect("127.0.0.1", flood.port()));
        System.out.println("over the limit "
                + failure(d.readAll()).replace(d.peer(), "PEER"));
        System.out.println("closed by that " + failure(d.read()));
        join(flood.close());

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                System.out.println("reported " + e.getMessage() + " on " + t.getName()));
        int[] served = new int[1];
        Listener thrower = join(Tcp.listen("127.0.0.1", 0, peer -> {
            if (served[0]++ == 0) throw new RuntimeException("boom");
            peer.write("second".getBytes(UTF_8)).thenCompose(v -> peer.close());
        }));
        Connection e = join(Tcp.connect("127.0.0.1", thrower.port()));
        System.out.println("thrower's connection ended " + join(e.readAll()).length);
        join(e.close());
        Connection f = join(Tcp.connect("127.0.0.1", thrower.port()));
        System.out.println(new String(join(f.readAll()), UTF_8));
        join(f.close());
        join(thrower.close());
    }
}
