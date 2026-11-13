package lazy.msg;

import app.test.demo.v1.Demo;
import app.test.watch.v1.WatchListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import lazy.async.Loop;

/**
 * Callback channels (docs/msg.md section 5): a client hands a call a listener, the service sends
 * events on the remote one it receives, the listener runs them in order on the client's home, and
 * closing the listener's channel from the client is seen by the service.
 */
public class Events {
    static final CountDownLatch SEEN = new CountDownLatch(3);

    static final class Printer implements WatchListener {
        @Override
        public void changed(String key, String value) {
            System.out.println("changed " + key + " = " + value + (Loop.home() == Loop.executor() ? " (on the loop)" : ""));
            SEEN.countDown();
        }

        @Override
        public void gone() {
            System.out.println("gone");
            SEEN.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        Store store = new Store();
        Registration reg = Demo.serve(store);
        Demo demo = Demo.connect();
        Printer printer = new Printer();
        demo.watch("", printer).toCompletableFuture().get();
        demo.put("a", "1").toCompletableFuture().get();
        demo.put("b", "2").toCompletableFuture().get();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Loop.executor().execute(() -> {
            store.stop();
            stopped.complete(null);
        });
        stopped.get();
        SEEN.await();
        CompletableFuture<Void> closed = Peer.of(printer).closed().toCompletableFuture();
        Peer.of(printer).close();
        closed.get();
        demo.put("c", "3").toCompletableFuture().get();
        System.out.println("closed; a later put sends nothing");

        // A call that reaches no service hands its listener's channel to nobody: it is closed.
        WatchListener lost = new WatchListener() {};
        try {
            Demo.connect("app.nobody.demo").watch("", lost).toCompletableFuture().get();
        } catch (java.util.concurrent.ExecutionException e) {
            System.out.println("watch with no service: " + e.getCause().getMessage());
        }
        try {
            Peer.of(lost);
            System.out.println("its channel is still open");
        } catch (IllegalArgumentException e) {
            System.out.println("its channel was closed");
        }
        reg.close();
    }
}
