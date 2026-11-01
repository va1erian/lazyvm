// NEEDS: net
package lazy.net;

import java.util.concurrent.CompletionStage;

/** Failures of the network fail the stage, in our own words; bad arguments throw in the call. */
public class ConnectRefused {
    static <T> void fails(String what, CompletionStage<T> stage, int port) {
        try {
            stage.toCompletableFuture().join();
            System.out.println(what + ": no failure");
        } catch (java.util.concurrent.CompletionException e) {
            Throwable c = e.getCause();
            System.out.println(what + ": " + c.getClass().getName() + ": "
                    + c.getMessage().replace(":" + port, ":PORT"));
        }
    }

    static void throwsNow(String what, Runnable call) {
        try {
            call.run();
            System.out.println(what + ": no exception");
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getSimpleName());
        }
    }

    public static void main(String[] args) {
        Listener listener = Tcp.listen("127.0.0.1", 0, c -> { }).toCompletableFuture().join();
        int port = listener.port();
        listener.close().toCompletableFuture().join();

        CompletionStage<Connection> refused = Tcp.connect("127.0.0.1", port);
        // A failure comes later, on the loop, like any other result.
        System.out.println("failed in the call " + refused.toCompletableFuture().isCompletedExceptionally());
        fails("refused", refused, port);
        fails("unknown", Tcp.connect("nosuch.invalid", 80), port);

        Listener held = Tcp.listen("127.0.0.1", 0, c -> { }).toCompletableFuture().join();
        fails("in use", Tcp.listen("127.0.0.1", held.port(), c -> { }), held.port());
        held.close().toCompletableFuture().join();

        throwsNow("null host", () -> Tcp.connect(null, 1));
        throwsNow("port 0", () -> Tcp.connect("h", 0));
        throwsNow("port 65536", () -> Tcp.connect("h", 65536));
        throwsNow("null listen host", () -> Tcp.listen(null, 1, c -> { }));
        throwsNow("null handler", () -> Tcp.listen("h", 1, null));
        throwsNow("listen port -1", () -> Tcp.listen("h", -1, c -> { }));
    }
}
